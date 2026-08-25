#!/usr/bin/env python3
# ============================================================================
# estrutura-web.py -- interface web do provisionamento da hierarquia.
#
#   ./estrutura-web.py                 # http://0.0.0.0:878
#   ./estrutura-web.py --porta 9000 --host 127.0.0.1
#
# Uma tela para montar a arvore (varias secretarias, cada uma com suas
# divisoes e setores, nomenclatura propria em cada nivel), com os botoes
# Executar / Parar / Remover e o log ao vivo do que esta acontecendo.
#
# So' biblioteca padrao -- a stack ja' tem servicos demais para justificar
# mais uma dependencia. Um job por vez, em thread, com parada cooperativa:
# o Parar levanta Cancelado no proximo checkpoint e o rollback desfaz o que
# aquele run tinha criado.
#
# ATENCAO: a senha do administrador e' digitada na tela e fica so' em memoria,
# no processo. Nao e' gravada em disco nem devolvida ao navegador.
# ============================================================================
import argparse, hashlib, html, json, os, re, ssl, sys, threading, time, traceback
import urllib.error, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import exo_estrutura as E


# ---------------------------------------------------------------------------
# PORTAO DE AUTORIZACAO
#
# Sem isto, qualquer um que alcance a maquina abre a pagina, le o log de
# execucao (nomes de grupos, espacos e usuarios), interrompe um provisionamento
# em andamento e ainda usa o POST de executar como oraculo para tentar senha de
# administrador do eXo sem limite. Foi assim que esta interface subiu -- erro
# grave, corrigido aqui.
#
# A identidade vem da PROPRIA PLATAFORMA, nao de uma senha nova: o navegador ja
# chega com o cookie de sessao do portal (mesma origem, /estrutura/ e /portal/
# no mesmo host). O servidor repassa esse cookie ao eXo, descobre quem e', e so
# deixa passar quem esta em /platform/administrators.
#
# Consequencia boa: o provisionamento passa a rodar com a sessao do proprio
# administrador. O servidor nunca ve, guarda nem testa senha -- o formulario de
# senha deixou de existir.
# ---------------------------------------------------------------------------
GRUPO_ADMIN = os.environ.get("EXO_ESTRUTURA_GRUPO_ADMIN", "/platform/administrators")
_cache_auth = {}
_cache_lock = threading.Lock()
# TTL curto de proposito: e' a janela em que um administrador REBAIXADO ainda
# passaria pelo portao. 60s era folgado demais para uma tela que cria e apaga
# grupos. O teto de entradas evita que uma enxurrada de cookies distintos
# faca o cache crescer sem limite.
CACHE_SEG = 15
CACHE_MAX = 256


def _sem_cache(agora):
    for k, (t, _) in list(_cache_auth.items()):
        if agora - t > CACHE_SEG:
            _cache_auth.pop(k, None)
    if len(_cache_auth) > CACHE_MAX:
        for k, _ in sorted(_cache_auth.items(), key=lambda x: x[1][0])[:len(_cache_auth) - CACHE_MAX]:
            _cache_auth.pop(k, None)


def identificar(cookie):
    """(usuario, admin, motivo). Cookie invalido -> (None, False, motivo)."""
    if not cookie:
        return None, False, "sem sessao do portal"
    chave = hashlib.sha256(cookie.encode()).hexdigest()
    agora = time.time()
    with _cache_lock:
        _sem_cache(agora)
        achado = _cache_auth.get(chave)
        if achado:
            return achado[1]

    url = os.environ.get("EXO_URL", "https://192.168.1.59").rstrip("/")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    op = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx))

    def pega(caminho):
        req = urllib.request.Request(url + caminho, method="GET")
        req.add_header("Cookie", cookie)
        try:
            with op.open(req, timeout=25) as r:
                return r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, ""
        except Exception:
            return 0, ""

    # Quem e'? O portal publica o dono da sessao no proprio HTML; anonimo nao
    # traz o campo. Nao ha endpoint REST de "me" util nesta versao: medido,
    # /v1/social/users/me devolve 401 ate para sessao valida.
    st, corpo = pega("/portal/dw")
    m = re.search(r"""userName\s*[:=]\s*["']([^"']+)["']""", corpo or "")
    usuario = m.group(1) if m else None
    if not usuario:
        r = (None, False, "sessao do portal ausente ou expirada")
        with _cache_lock:
            _cache_auth[chave] = (agora, r)
        return r

    st2, corpo2 = pega(f"/portal/rest/v1/users/{urllib.parse.quote(usuario)}/memberships?limit=200")
    admin = False
    try:
        for e in (json.loads(corpo2 or "{}").get("entities") or []):
            if e.get("groupId") == GRUPO_ADMIN:
                admin = True
                break
    except Exception:
        pass
    r = (usuario, admin, "" if admin else f"'{usuario}' nao esta em {GRUPO_ADMIN}")
    with _cache_lock:
        _cache_auth[chave] = (agora, r)
    return r


class Job:
    """Um provisionamento em andamento. Um por vez."""

    def __init__(self):
        self.lock = threading.Lock()
        self.linhas = []
        self.estado = "ocioso"        # ocioso | rodando | ok | erro | parado
        self.parar = False
        self.thread = None
        self.resumo = None
        self.operador = ""

    def log(self, msg):
        with self.lock:
            for l in str(msg).splitlines() or [""]:
                self.linhas.append(l)

    def desde(self, n):
        with self.lock:
            return self.linhas[n:], len(self.linhas), self.estado, self.resumo

    def rodando(self):
        return self.thread is not None and self.thread.is_alive()

    def iniciar(self, alvo, operador=""):
        """Reserva a vaga e dispara, TUDO sob o mesmo lock.

        Antes o do_POST checava `rodando()` e o `iniciar()` rechecava, os dois
        FORA de um lock comum. Dois POST quase simultaneos passavam pelos dois
        testes antes de qualquer thread comecar a rodar e disparavam DOIS
        provisionamentos concorrentes sobre o mesmo estado global (linhas,
        parar, diario de rollback) -- runs intercalados e rollback confuso.
        """
        with self.lock:
            if self.thread is not None and self.thread.is_alive():
                return False
            self.linhas, self.parar, self.resumo = [], False, None
            self.estado = "rodando"
            self.operador = operador
            self.thread = threading.Thread(target=alvo, daemon=True)
            self.thread.start()
        return True


JOB = Job()


def executar(payload, remover=False, operador=""):
    def alvo():
        try:
            JOB.log(f"operador: {payload.get('_operador')}")
            exo = E.conectar(dry=bool(payload.get("simulacao")),
                             log=JOB.log, cookie=payload.get("_cookie"))
            prov = E.Provisionador(exo, log=JOB.log,
                                   cancelado=lambda: JOB.parar,
                                   dry=bool(payload.get("simulacao")))
            r = (E.remover_arvore(prov, payload) if remover
                 else E.provisionar_arvore(prov, payload))
            JOB.estado = "ok" if r.get("ok") else ("parado" if r.get("parado") else "erro")
            JOB.resumo = r
        except TypeError as e:
            JOB.log(f"ERRO de serializacao no resumo: {e}")
            JOB.estado = "erro"
        except E.Cancelado as e:
            JOB.log(f"PARADO: {e}")
            JOB.estado = "parado"
        except Exception as e:
            JOB.log(f"ERRO: {e}")
            JOB.log(traceback.format_exc(limit=3))
            JOB.estado = "erro"
    return JOB.iniciar(alvo, operador)


PAGINA = r"""<!doctype html>
<meta charset="utf-8"><title>Estrutura organizacional - eXo</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
:root{--bg:#f4f6f8;--card:#fff;--linha:#dfe3e8;--txt:#1f2733;--suave:#5b6672;
      --azul:#1565c0;--verde:#2e7d32;--vermelho:#c62828;--amarelo:#f9a825}
*{box-sizing:border-box}
body{margin:0;font:14px/1.45 system-ui,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--txt)}
header{background:var(--amarelo);padding:14px 20px;font-weight:700;font-size:16px}
main{max-width:1180px;margin:18px auto;padding:0 16px;display:grid;
     grid-template-columns:1fr 460px;gap:16px}
@media(max-width:980px){main{grid-template-columns:1fr}}
.card{background:var(--card);border:1px solid var(--linha);border-radius:8px;padding:16px;margin-bottom:16px}
h2{margin:0 0 12px;font-size:15px}
h3{margin:0 0 8px;font-size:13px;color:var(--suave);text-transform:uppercase;letter-spacing:.4px}
label{display:block;font-size:12px;color:var(--suave);margin:8px 0 3px}
input,textarea,select{width:100%;padding:7px 9px;border:1px solid var(--linha);
  border-radius:5px;font:inherit;background:#fff;color:var(--txt)}
textarea{min-height:52px;resize:vertical}
.linha{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.nivel{border-left:3px solid var(--azul);padding:10px 12px;margin:10px 0;background:#fafbfc;border-radius:0 6px 6px 0}
.nivel.div{border-left-color:var(--verde);margin-left:18px}
.nivel.set{border-left-color:var(--amarelo);margin-left:36px}
.topo{display:flex;align-items:center;justify-content:space-between;gap:8px}
.tag{font-size:11px;font-weight:700;text-transform:uppercase;color:var(--suave)}
button{font:inherit;padding:7px 14px;border-radius:5px;border:1px solid var(--linha);
  background:#fff;cursor:pointer}
button:hover{background:#f0f2f4}
button.p{background:var(--azul);color:#fff;border-color:var(--azul)}
button.perigo{background:var(--vermelho);color:#fff;border-color:var(--vermelho)}
button.mini{padding:3px 9px;font-size:12px}
button:disabled{opacity:.45;cursor:not-allowed}
.acoes{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}
#log{background:#10151b;color:#d4dae1;font:12px/1.5 ui-monospace,Menlo,Consolas,monospace;
  padding:12px;border-radius:6px;height:460px;overflow:auto;white-space:pre-wrap}
#estado{font-size:12px;padding:3px 10px;border-radius:20px;background:#eceff1;color:var(--suave)}
#estado.rodando{background:#e3f2fd;color:var(--azul)}
#estado.ok{background:#e8f5e9;color:var(--verde)}
#estado.erro{background:#ffebee;color:var(--vermelho)}
#estado.parado{background:#fff8e1;color:#8d6e00}
.aviso{font-size:12px;color:var(--suave);margin-top:8px}
</style>
<header>Estrutura organizacional &mdash; eXo Platform</header>
<main>
<div>
  <div class="card">
    <h2>Acesso</h2>
    <p class="aviso" style="margin:0">
      Voce esta autenticado como <b>__USUARIO__</b>, administrador da plataforma.
      A execucao usa a sua propria sessao do portal &mdash; nao ha senha a digitar
      aqui, e nada e' gravado em disco. Servidor: <code>__EXO_URL__</code>.
    </p>
    <label style="display:flex;align-items:center;gap:7px;margin-top:12px">
      <input type="checkbox" id="simulacao" style="width:auto">
      <span>Simulacao (mostra o que faria, sem gravar nada)</span>
    </label>
  </div>

  <div class="card">
    <div class="topo"><h2 style="margin:0">Hierarquia</h2>
      <button class="mini" onclick="addSec()">+ Secretaria</button></div>
    <div id="arvore"></div>
    <div class="acoes">
      <button class="p" id="bExec" onclick="acao(false)">Executar</button>
      <button id="bParar" onclick="parar()" disabled>Parar</button>
      <button class="perigo" id="bRem" onclick="acao(true)">Remover</button>
      <button onclick="baixarJson()">Baixar JSON</button>
    </div>
    <div class="aviso">Remover tira as pessoas dos espacos e apaga grupos e espacos.
      Qualquer erro durante a execucao desfaz automaticamente o que aquele run criou.</div>
  </div>
</div>

<div>
  <div class="card">
    <div class="topo"><h2 style="margin:0">Execucao</h2><span id="estado">ocioso</span></div>
    <div id="humano" style="display:none;padding:10px 12px;border-radius:8px;
         margin-bottom:8px;font-weight:600"></div>
    <div id="log"></div>
  </div>
</div>
</main>
<script>
let arv = [];
const vazio = () => ({nome:"",rotulo:"",descricao:"",gestores:"",usuarios:"",
                      avatar:null,banner:null,divisoes:[],setores:[]});
function addSec(){arv.push(vazio());pinta()}
function addDiv(i){arv[i].divisoes.push(vazio());pinta()}
function addSet(i,j){arv[i].divisoes[j].setores.push(vazio());pinta()}
function delSec(i){arv.splice(i,1);pinta()}
function delDiv(i,j){arv[i].divisoes.splice(j,1);pinta()}
function delSet(i,j,k){arv[i].divisoes[j].setores.splice(k,1);pinta()}

function campos(no,cam){
  return `
  <div class="linha">
    <div><label>Sigla curta <b style="color:var(--vermelho)">*obrigatorio</b></label>
      <input value="${esc(no.nome)}" oninput="set('${cam}','nome',this.value)"
             placeholder="ex: SITDS"
             title="Codigo curto e unico do nivel (vira o identificador do grupo). Ex.: SITDS, DIT, ST">
      <small style="color:var(--suave)">codigo curto e unico (vira o grupo). ex.: SITDS</small></div>
    <div><label>Nome que aparece na tela</label>
      <input value="${esc(no.rotulo)}" oninput="set('${cam}','rotulo',this.value)"
             placeholder="ex: Secretaria de Inovacao...">
      <small style="color:var(--suave)">titulo por extenso do espaco</small></div>
  </div>
  <label>Descricao do espaco (perfil)</label>
  <textarea oninput="set('${cam}','descricao',this.value)"
            placeholder="Aparece na tela do espaco">${esc(no.descricao)}</textarea>
  <div class="linha">
    <div><label>Gestores (login, virgula)</label>
      <input value="${esc(no.gestores)}" oninput="set('${cam}','gestores',this.value)"
             placeholder="wilson.franca"
             title="Use o LOGIN, nao o nome. Ex.: wilson.franca (nao 'Wilson Franca')">
      <small style="color:var(--suave)">o LOGIN, nao o nome. ex.: wilson.franca</small></div>
    <div><label>Membros (login, virgula)</label>
      <input value="${esc(no.usuarios)}" oninput="set('${cam}','usuarios',this.value)"
             placeholder="kaua.ferri">
      <small style="color:var(--suave)">o LOGIN, nao o nome. ex.: kaua.ferri</small></div>
  </div>
  <div class="linha">
    <div><label>Avatar (imagem)</label>
      <input type="file" accept="image/*" onchange="img('${cam}','avatar',this)"></div>
    <div><label>Banner (imagem)</label>
      <input type="file" accept="image/*" onchange="img('${cam}','banner',this)"></div>
  </div>`;
}
const esc = s => String(s||"").replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function no(cam){let o=arv;for(const p of cam.split(".")){o=o[p==+p?+p:p]}return o}
function set(cam,k,v){no(cam)[k]=v}
function img(cam,k,el){
  const f=el.files[0]; if(!f){no(cam)[k]=null;return}
  const r=new FileReader();
  r.onload=()=>{no(cam)[k]=r.result.split(",")[1]};
  r.readAsDataURL(f);
}
function pinta(){
  document.getElementById("arvore").innerHTML = arv.map((s,i)=>`
    <div class="nivel">
      <div class="topo"><span class="tag">Secretaria ${i+1}</span>
        <span><button class="mini" onclick="addDiv(${i})">+ Divisao</button>
        <button class="mini" onclick="delSec(${i})">remover</button></span></div>
      ${campos(s,`${i}`)}
      ${s.divisoes.map((d,j)=>`
        <div class="nivel div">
          <div class="topo"><span class="tag">Divisao ${j+1}</span>
            <span><button class="mini" onclick="addSet(${i},${j})">+ Setor</button>
            <button class="mini" onclick="delDiv(${i},${j})">remover</button></span></div>
          ${campos(d,`${i}.divisoes.${j}`)}
          ${d.setores.map((t,k)=>`
            <div class="nivel set">
              <div class="topo"><span class="tag">Setor ${k+1}</span>
                <button class="mini" onclick="delSet(${i},${j},${k})">remover</button></div>
              ${campos(t,`${i}.divisoes.${j}.setores.${k}`)}
            </div>`).join("")}
        </div>`).join("")}
    </div>`).join("") || '<p class="aviso">Nenhuma secretaria. Clique em "+ Secretaria".</p>';
}
function limpa(n){
  const o={nome:n.nome,rotulo:n.rotulo,descricao:n.descricao,
           gestores:n.gestores,usuarios:n.usuarios};
  if(n.avatar) o.avatar=n.avatar;
  if(n.banner) o.banner=n.banner;
  if(n.divisoes&&n.divisoes.length) o.divisoes=n.divisoes.map(limpa);
  if(n.setores&&n.setores.length) o.setores=n.setores.map(limpa);
  return o;
}
function corpo(){
  return {simulacao:document.getElementById("simulacao").checked,
          secretarias:arv.map(limpa)};
}
const val = id => document.getElementById(id).value;
function baixarJson(){
  const c=corpo();
  const b=new Blob([JSON.stringify(c,null,2)],{type:"application/json"});
  const a=document.createElement("a");
  a.href=URL.createObjectURL(b); a.download="estrutura.json"; a.click();
}
async function acao(remover){
  if(!arv.length){alert("Monte ao menos uma secretaria.");return}
  if(remover && !confirm("Remover apaga grupos e espacos e tira as pessoas. Confirma?"))return;
  const r=await fetch(remover?"api/remover":"api/executar",
    {method:"POST",headers:{"Content-Type":"application/json","X-Estrutura":"1"},
     body:JSON.stringify(corpo())});
  const j=await r.json();
  if(!j.ok){alert(j.erro||"nao foi possivel iniciar");return}
  n=0; document.getElementById("log").textContent="";
}
async function parar(){await fetch("api/parar",{method:"POST",headers:{"X-Estrutura":"1"}})}
let n=0;
async function poll(){
  try{
    const r=await fetch("api/log?desde="+n);
    const j=await r.json();
    if(j.linhas.length){
      const el=document.getElementById("log");
      el.textContent += j.linhas.join("\n")+"\n";
      el.scrollTop=el.scrollHeight;
    }
    n=j.total;
    const e=document.getElementById("estado");
    e.textContent=j.estado; e.className=j.estado;
    const r_=j.estado==="rodando";
    document.getElementById("bExec").disabled=r_;
    document.getElementById("bRem").disabled=r_;
    document.getElementById("bParar").disabled=!r_;
    // Mensagem HUMANA: o leigo nao deve precisar ler REST cru para saber
    // se deu certo. Cores e texto claros por estado.
    const h=document.getElementById("humano");
    const M={
      rodando:["Trabalhando... aguarde.","#e3f2fd","#0d47a1"],
      ok:["Tudo pronto! A estrutura foi gravada com sucesso.","#e8f5e9","#1b5e20"],
      erro:["Algo falhou — e NADA foi deixado pela metade: o que este run criou foi desfeito automaticamente. Veja o detalhe abaixo.","#ffebee","#b71c1c"],
      parado:["Parado a seu pedido — o que este run tinha criado foi desfeito.","#fff8e1","#8d6e00"]
    };
    if(M[j.estado]){h.style.display="block";h.textContent=M[j.estado][0];
      h.style.background=M[j.estado][1];h.style.color=M[j.estado][2];}
    else{h.style.display="none";}
  }catch(e){}
  setTimeout(poll,900);
}
addSec(); poll();
</script>
"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass                                    # sem ruido no terminal

    def _envia(self, codigo, corpo, tipo="application/json; charset=utf-8"):
        b = corpo.encode() if isinstance(corpo, str) else corpo
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(b)

    def _json_recebido(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 40 * 1024 * 1024:                # imagens vem em base64
            raise ValueError("corpo grande demais")
        return json.loads(self.rfile.read(n) or b"{}")

    def _autorizado(self):
        """Devolve o usuario ou None (ja tendo respondido 401/403)."""
        usuario, admin, motivo = identificar(self.headers.get("Cookie"))
        if not usuario:
            self._envia(401, json.dumps(
                {"erro": "entre no portal primeiro", "detalhe": motivo}))
            return None
        if not admin:
            self._envia(403, json.dumps(
                {"erro": "acesso restrito a administradores da plataforma",
                 "detalhe": motivo}))
            return None
        return usuario

    def _redir(self, destino):
        self.send_response(302)
        self.send_header("Location", destino)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        # /saude e' o unico ponto sem sessao: o healthcheck do container roda
        # de dentro, sem navegador. Nao revela nada alem de estar de pe.
        if self.path == "/saude":
            return self._envia(200, "ok", "text/plain; charset=utf-8")

        # A PAGINA (navegador) e os /api/* (JS) sao tratados diferente quando
        # nao ha sessao. Um /api/* devolve 401 JSON, que o JS entende. Mas a
        # PAGINA nao pode devolver JSON cru para um humano: um usuario deslogado
        # que abre /estrutura/ tem de ser LEVADO ao login do portal e voltar --
        # senao a tela "nao funciona", so' aparece um blob de erro.
        if self.path in ("/", "/index.html"):
            usuario, admin, motivo = identificar(self.headers.get("Cookie"))
            if not usuario:
                # manda para o login do portal; o eXo volta para ca' via
                # initialURI depois que a sessao e' criada.
                return self._redir("/portal/login?initialURI=%2Festrutura%2F")
            if not admin:
                # logado, mas sem poder de admin: pagina HTML clara (nao JSON),
                # para o humano entender por que nao entra.
                aviso = ("<!doctype html><meta charset=utf-8>"
                         "<title>Acesso restrito</title>"
                         "<body style='font:15px system-ui;max-width:640px;margin:60px auto;"
                         "padding:0 20px;color:#1f2733'>"
                         "<h2>Acesso restrito</h2>"
                         f"<p>Voce entrou como <b>{html.escape(usuario)}</b>, mas esta tela "
                         "e' exclusiva de administradores da plataforma "
                         "(<code>/platform/administrators</code>).</p>"
                         "<p>Pe&ccedil;a a um administrador ou entre com uma conta "
                         "administrativa.</p></body>")
                return self._envia(403, aviso, "text/html; charset=utf-8")
            pagina = PAGINA.replace(
                "__EXO_URL__",
                html.escape(os.environ.get("EXO_URL", "https://192.168.1.59"), quote=True)
            ).replace("__USUARIO__", html.escape(usuario))
            return self._envia(200, pagina, "text/html; charset=utf-8")

        # dados (JS): exige sessao, responde 401/403 JSON
        if not self._autorizado():
            return
        if self.path.startswith("/api/log"):
            q = urllib_parse_qs(self.path)
            linhas, total, estado, resumo = JOB.desde(int(q.get("desde", "0") or 0))
            # default=str: cinto de seguranca. Um unico valor nao
            # serializavel no resumo derrubava ESTE endpoint, que e' o unico
            # canal da interface -- a tela ficava sem log e sem estado, dando
            # a impressao de que o provisionamento tinha travado.
            return self._envia(200, json.dumps(
                {"linhas": linhas, "total": total, "estado": estado, "resumo": resumo},
                default=str))
        return self._envia(404, json.dumps({"erro": "nao encontrado"}))

    def do_POST(self):
        # CSRF: o portao acima confia no cookie de sessao, e cookie o navegador
        # manda sozinho. Sem esta trava, bastava induzir um administrador
        # logado a abrir uma pagina qualquer para ela disparar /api/remover em
        # nome dele. Cabecalho customizado nao pode ser enviado por formulario
        # cross-site nem por fetch simples: exigiria preflight CORS, que este
        # servidor nao responde.
        if self.headers.get("X-Estrutura") != "1":
            return self._envia(403, json.dumps(
                {"erro": "requisicao sem o cabecalho da interface (protecao CSRF)"}))
        usuario = self._autorizado()
        if not usuario:
            return
        try:
            if self.path == "/api/parar":
                JOB.parar = True
                dono = JOB.operador or "?"
                # Qualquer administrador pode parar -- mas fica registrado
                # quem parou o trabalho de quem. Parada anonima num sistema com
                # varios administradores e' o tipo de coisa que ninguem
                # consegue explicar depois.
                JOB.log(f"... parada pedida por '{usuario}'"
                        + (f" (trabalho iniciado por '{dono}')" if dono != usuario else "")
                        + "; encerrando no proximo passo seguro")
                return self._envia(200, json.dumps({"ok": True}))
            if self.path in ("/api/executar", "/api/remover"):
                payload = self._json_recebido()
                decodifica_imagens(payload)
                if JOB.rodando():
                    return self._envia(409, json.dumps(
                        {"ok": False, "erro": "ja ha um trabalho em andamento"}))
                # A sessao do chamador vai junto: o provisionamento age com a
                # identidade e os poderes reais daquele administrador.
                payload["_cookie"] = self.headers.get("Cookie")
                payload["_operador"] = usuario
                ok = executar(payload, remover=(self.path == "/api/remover"),
                              operador=usuario)
                if not ok:
                    return self._envia(409, json.dumps(
                        {"ok": False, "erro": "ja ha um trabalho em andamento"}))
                return self._envia(200, json.dumps({"ok": ok}))
            return self._envia(404, json.dumps({"erro": "nao encontrado"}))
        except Exception as e:
            return self._envia(400, json.dumps({"ok": False, "erro": str(e)}))


def urllib_parse_qs(caminho):
    import urllib.parse as up
    return {k: v[0] for k, v in up.parse_qs(up.urlparse(caminho).query).items()}


def decodifica_imagens(no):
    """O navegador manda avatar/banner em base64; o motor quer bytes."""
    import base64
    if isinstance(no, dict):
        for k in ("avatar", "banner"):
            v = no.get(k)
            if isinstance(v, str) and v:
                try:
                    no[k] = base64.b64decode(v)
                except Exception:
                    no[k] = None
        for v in no.values():
            decodifica_imagens(v)
    elif isinstance(no, list):
        for v in no:
            decodifica_imagens(v)


def main():
    p = argparse.ArgumentParser(description="Interface web da estrutura organizacional")
    p.add_argument("--porta", type=int, default=int(os.environ.get("EXO_ESTRUTURA_PORTA", 878)))
    p.add_argument("--host", default=os.environ.get("EXO_ESTRUTURA_HOST", "0.0.0.0"))
    a = p.parse_args()
    srv = ThreadingHTTPServer((a.host, a.porta), Handler)
    print(f"Interface em http://{a.host}:{a.porta}/   (Ctrl+C para encerrar)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nencerrado.")


if __name__ == "__main__":
    main()
