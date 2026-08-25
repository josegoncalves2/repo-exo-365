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


# ---------------------------------------------------------------------------
# A PAGINA
#
# Vive em scripts/portal.html, nao numa string aqui dentro. Motivo pratico: a
# versao anterior eram ~350 linhas de HTML/CSS/JS dentro de uma string Python
# -- sem realce, sem lint, sem formatador -- e foi assim que virou uma tela que
# mostrava caixinhas vazias e nao dizia nada de util sobre o que existia.
#
# Le do disco a cada request, com cache invalidado por mtime: corrigir a tela
# e' salvar o arquivo e dar F5, sem reiniciar o servico. Leitura de disco e'
# permitida; e' a ESCRITA que esta bloqueada pelo systemd.
# ---------------------------------------------------------------------------
PORTAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "portal.html")
_pagina_cache = {"mtime": None, "txt": ""}


def pagina_html():
    try:
        mt = os.path.getmtime(PORTAL)
    except OSError:
        raise FileNotFoundError(f"pagina nao encontrada: {PORTAL}")
    if _pagina_cache["mtime"] != mt:
        with open(PORTAL, encoding="utf-8") as fh:
            _pagina_cache["txt"] = fh.read()
        _pagina_cache["mtime"] = mt
    return _pagina_cache["txt"]


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
            try:
                bruto = pagina_html()
            except FileNotFoundError as e:
                return self._envia(500, f"<pre>{html.escape(str(e))}</pre>",
                                   "text/html; charset=utf-8")
            pagina = bruto.replace(
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
        if self.path.startswith("/api/modelo.csv"):
            # Modelo de importacao. Gerado em memoria a partir do proprio leitor
            # (E.modelo_csv le _CSV_MAPA), entao nao ha arquivo de exemplo em
            # disco para envelhecer -- nem escrita em disco, que aqui e' proibida.
            corpo = ("\ufeff" + E.modelo_csv()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/csv; charset=utf-8")
            self.send_header("Content-Disposition",
                             'attachment; filename="modelo-membros.csv"')
            self.send_header("Content-Length", str(len(corpo)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            return self.wfile.write(corpo)
        if self.path.startswith("/api/nivel"):
            # Quem esta dentro de UM nivel. Fica fora de /api/arvore de
            # proposito: sao 2 chamadas por nivel, e a tela so' precisa disso
            # para o nivel que o operador abriu.
            q = urllib_parse_qs(self.path)
            caminho = (q.get("caminho") or "").strip()
            espaco = (q.get("espaco") or "").strip()
            # Por ID de espaco atende TODO espaco do eXo, inclusive os que nao
            # tem grupo da estrutura (o Lobby) -- que a tela agora mostra porque
            # eles existem em SOC_SPACES e sao pais de niveis nossos.
            if not espaco.isdigit() and not caminho.startswith("/"):
                return self._envia(400, json.dumps({"erro": "informe espaco=<id> ou caminho=/GRUPO"}))
            try:
                exo = E.conectar(cookie=self.headers.get("Cookie"))
                d = (E.detalhe_espaco(exo, espaco) if espaco.isdigit()
                     else E.detalhe_nivel(exo, caminho))
                return self._envia(200, json.dumps(d, default=str))
            except Exception as e:
                return self._envia(200, json.dumps({"existe": False, "erro": str(e)}))
        if self.path.startswith("/api/pessoas"):
            # Sugestao a partir do diretorio REAL. Sem isso o operador digita de
            # cabeca e um erro de digitacao vira conta nova em silencio.
            q = urllib_parse_qs(self.path)
            try:
                exo = E.conectar(cookie=self.headers.get("Cookie"))
                return self._envia(200, json.dumps(
                    {"pessoas": E.buscar_pessoas(exo, q.get("q", ""))}, default=str))
            except Exception as e:
                return self._envia(200, json.dumps({"pessoas": [], "erro": str(e)}))
        if self.path.startswith("/api/arvore"):
            # a estrutura JA existente, para a tela carregar e permitir
            # acrescentar filho / renomear sem remontar tudo.
            try:
                exo = E.conectar(cookie=self.headers.get("Cookie"))
                arvore = E.arvore_atual(exo)
                # PESSOAS DISTINTAS, nao a soma das contagens de cada nivel.
                # Somar dava 10 numa organizacao de 5 contas: pela cascata, quem
                # esta na Secretaria tambem esta na Divisao e no Setor, e era
                # contado tres vezes. Numero que nao existe em lugar nenhum do
                # banco -- e a tela inteira perde a credibilidade por causa dele.
                # DOIS escopos, porque sao duas perguntas diferentes:
                #   'pessoas'      -> quem esta nos niveis do ORGANOGRAMA
                #   'pessoas_todas'-> quem esta em qualquer espaco listado
                # Um numero so' misturava as duas: o indicador dizia 13 (com o
                # Lobby dentro) para uma organizacao cujos niveis somam 5.
                do_organograma, de_todos = set(), set()

                def anda(no):
                    sid = (no.get("espaco") or {}).get("id")
                    if sid:
                        quem = E.membros_do_espaco(exo, sid)
                        de_todos.update(quem)
                        if no.get("daEstrutura"):
                            do_organograma.update(quem)
                    for f in (no.get("filhos") or []):
                        anda(f)

                for raiz in arvore:
                    anda(raiz)
                return self._envia(200, json.dumps(
                    {"arvore": arvore, "pessoas": sorted(do_organograma),
                     "pessoas_todas": sorted(de_todos)}, default=str))
            except Exception as e:
                return self._envia(200, json.dumps({"arvore": [], "erro": str(e)}))
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
