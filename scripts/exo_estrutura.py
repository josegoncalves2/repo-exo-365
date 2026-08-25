#!/usr/bin/env python3
# ============================================================================
# exo_estrutura.py -- motor de provisionamento da hierarquia organizacional
# no eXo Platform. Usado pela CLI (estrutura-organizacional.py) e pela
# interface web (estrutura-web.py).
#
# Um NIVEL (secretaria / divisao / setor) e' composto por:
#   1. GRUPO organizacional na posicao certa da arvore   (/SITDS/DIT/ST)
#   2. ESPACO privado/fechado
#   3. ANINHAMENTO do espaco dentro do espaco do pai
#   4. CADEIA DE BINDINGS: o espaco recebe o proprio grupo E o de todos os
#      niveis ACIMA. A visibilidade DESCE -- quem esta na Secretaria enxerga
#      Divisoes e Setores; quem esta no Setor enxerga so' o Setor.
#   5. PERFIL: descricao, avatar e banner
#   6. PESSOAS: gestores (manager no grupo do nivel E no grupo tecnico do
#      espaco) e membros comuns
#
# ---------------------------------------------------------------------------
# ARMADILHAS DA API, TODAS MEDIDAS NESTA INSTALACAO (nao presumidas):
#
# * POST saveGroupsSpaceBindings e' ADD-ONLY. Mandar uma lista menor devolve
#   200 e NAO remove nada; o QueueGroupSpaceBindingJob nem enfileira. Para
#   remover e' preciso DELETE .../removeGroupSpaceBinding/<bindingId>.
#
# * PUT /social/spaces/<id> IGNORA campos em silencio se o corpo nao trouxer
#   'id' e 'displayName'. Enviar so {"description": ...} devolve 200 e nao
#   grava. Com id+displayName, grava. O mesmo vale para avatarId/bannerId.
#   Por isso toda escrita de perfil e' CONFERIDA com um GET depois.
#
# * PUT SUBSTITUI o objeto: o que nao vier no corpo e' zerado. parentSpaceId,
#   visibility e subscription vao sempre junto.
#
# * Avatar/banner: sobe primeiro em POST /portal/upload?uploadId=<uuid>&
#   action=upload (multipart, campo 'file'), depois referencia o uuid em
#   avatarId/bannerId no PUT do espaco.
#
# * 'manager' no grupo organizacional NAO da poder sobre o espaco. Gestor de
#   espaco e' manager no grupo tecnico /spaces/<prettyName>.
#
# * O bulk de memberships e' TUDO-OU-NADA: um usuario repetido derruba o lote
#   inteiro com 400 MEMBERSHIP:ALREADY_EXISTS e ninguem entra.
#
# * Conta DESABILITADA entra no grupo mas NAO no espaco, sem erro nenhum.
#
# * A propagacao de membros para os espacos e' ASSINCRONA: quem ja' estava no
#   grupo so' entra quando o QueueGroupSpaceBindingJob roda (cron 0 0/5).
#
# * DELETE de grupo leva o caminho em QUERY STRING, nao no path. E o eXo
#   recusa apagar grupo que tenha subgrupo: remova de baixo para cima.
# ============================================================================
import csv, io, json, os, re, secrets, ssl, sys, time, unicodedata, uuid
import http.cookiejar, urllib.error, urllib.parse, urllib.request

TIPOS = ("secretaria", "divisao", "setor")
FILHOS = {"secretaria": "divisoes", "divisao": "setores", "setor": None}
TIPO_FILHO = {"secretaria": "divisao", "divisao": "setor"}

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_PATH = os.environ.get("EXO_ESTRUTURA_LOG",
                          os.path.join(RAIZ, "estrutura-organizacional.log"))
# Registro grupo->espaco. Fica FORA da descricao do espaco de proposito: a
# primeira versao gravava uma marca "[grupo:/SITDS]" no proprio texto da
# descricao e ela aparecia para o usuario final na tela do espaco.
REGISTRO_PATH = os.environ.get("EXO_ESTRUTURA_REGISTRO",
                               os.path.join(RAIZ, "conf", "estrutura-registro.json"))


class Cancelado(Exception):
    """Parada pedida pelo operador (botao Parar da interface web)."""


class FalhaEtapa(Exception):
    """Erro de uma etapa; dispara o rollback do que este run criou."""


# ---------------------------------------------------------------------------
# utilidades
# ---------------------------------------------------------------------------
def slug_grupo(nome):
    """Nome de grupo seguro para virar segmento de caminho.

    O eXo aceita criar um grupo 'Saude e Bem Estar', mas o id vira
    '/Saude e Bem Estar' -- com espacos e acentos dentro de um caminho que
    entra em URL e no id de membership ('member:<user>:<grupo>'). Quebra de
    forma dificil de diagnosticar. Aqui: acentos fora, pontuacao vira '-',
    tudo maiusculo. Quem quer o nome bonito usa o rotulo, que vai no espaco.
    """
    sem_acento = unicodedata.normalize("NFKD", nome).encode("ascii", "ignore").decode()
    limpo = re.sub(r"[^A-Za-z0-9]+", "-", sem_acento).strip("-").upper()
    return limpo or "GRUPO"


def login_de(entrada):
    """Login a partir de um NOME de exibicao ou de um login ja pronto.

    'Wilson França' -> 'wilson.franca';  'wilson.franca' -> 'wilson.franca'.
    Idempotente: aplicar de novo num login nao muda nada. Acentos fora, tudo
    minusculo, separadores viram ponto.
    """
    s = unicodedata.normalize("NFKD", str(entrada)).encode("ascii", "ignore").decode()
    s = re.sub(r"[^a-zA-Z0-9]+", ".", s).strip(".").lower()
    return s or "usuario"


def nome_de(entrada):
    """Nome de exibicao a partir da entrada.

    Se veio um login ('edna.marques'), vira 'Edna Marques'. Se ja veio um nome
    ('Edna Marques'), preserva as palavras (so' ajeita a caixa das que vierem
    todas minusculas). Serve para preencher firstName/lastName ao CRIAR a conta.
    """
    base = str(entrada).strip()
    partes = [p for p in re.split(r"[._\s]+", base) if p]
    return " ".join(p if (p[:1].isupper() and p != p.upper()) else p.capitalize()
                    for p in partes) or base


def email_de(login, dominio=None):
    dom = dominio or os.environ.get("EXO_EMAIL_DOMINIO", "olimpia.sp.gov.br")
    return f"{login}@{dom}"


def le_lista(valor):
    """Aceita lista Python, CSV (com/sem cabecalho) ou string com virgulas."""
    if not valor:
        return []
    if isinstance(valor, (list, tuple)):
        return list(dict.fromkeys(str(x).strip() for x in valor if str(x).strip()))
    valor = str(valor)
    # Um caminho que PARECE arquivo mas nao existe e' erro, nao lista de nomes.
    parece_arquivo = valor.endswith(".csv") or "/" in valor or os.sep in valor
    if parece_arquivo and not os.path.isfile(valor):
        raise FalhaEtapa(f"arquivo de usuarios nao encontrado: {valor}")
    if not os.path.isfile(valor):
        return list(dict.fromkeys(x.strip() for x in valor.split(",") if x.strip()))

    CABECALHOS = ("username", "usuario", "usuário", "login", "user",
                  "nome", "name", "cargo", "email", "e-mail", "funcao", "função")
    with open(valor, newline="", encoding="utf-8-sig") as fh:
        texto = fh.read()
    if not texto.strip():
        return []
    linhas = [l for l in texto.splitlines() if l.strip()]
    # Excel em portugues salva com ';'. Escolhe o que mais aparece; ',' desempata.
    cab = linhas[0]
    delim = ";" if cab.count(";") > cab.count(",") else ","
    # Cabecalho decidido pela PRIMEIRA CELULA, nao pelo csv.Sniffer -- o Sniffer
    # erra nos dois sentidos em arquivos curtos (num teste engoliu um usuario).
    primeira = (next(csv.reader([cab], delimiter=delim), [""])[0] or "").strip().lower()
    tem_cab = primeira in CABECALHOS
    achados, corpo, col = [], linhas[1:] if tem_cab else linhas, 0
    if tem_cab:
        titulos = [(c or "").strip().lower()
                   for c in next(csv.reader([cab], delimiter=delim), [])]
        for i, t in enumerate(titulos):
            if t in ("username", "usuario", "usuário", "login", "user"):
                col = i
                break
    for linha in csv.reader(corpo, delimiter=delim):
        if not linha:
            continue
        v = (linha[col] if col < len(linha) else linha[0]).strip()
        if v:
            achados.append(v)
    return list(dict.fromkeys(achados))


def _pessoa(login=None, nome=None, email=None, senha=None):
    """Normaliza uma pessoa: sempre com login, nome e email coerentes.

    Aceita ser chamada com so' o login OU so' o nome -- deriva o que faltar.
    """
    if not login and nome:
        login = login_de(nome)
    login = login_de(login or "")
    nome = (nome or "").strip()
    # Um "nome" que na verdade e' um login (sem espaco, minusculo com pontos)
    # vira nome de exibicao de gente: 'kaua.ferri' -> 'Kaua Ferri'.
    if not nome or (" " not in nome and nome == nome.lower()):
        nome = nome_de(login)
    email = (email or "").strip() or email_de(login)
    return {"login": login, "nome": nome, "email": email,
            "senha": (senha or "").strip() or None}


def ler_pessoas(valor):
    """Como le_lista, mas devolve PESSOAS ({login,nome,email,senha}) e nao so'
    logins -- para poder CRIAR quem nao existe.

    Aceita: lista Python (de strings ou de dicts), string com virgulas, ou
    arquivo CSV com cabecalho (colunas nome/login/email/senha/cargo, em
    qualquer ordem; delimitador ',' ou ';'). Uma celula solta e' tratada como
    NOME de exibicao ('Wilson França') OU login ('wilson.franca') -- os dois
    convergem para o mesmo login.
    """
    if not valor:
        return []
    if isinstance(valor, (list, tuple)):
        fora = []
        for x in valor:
            if isinstance(x, dict):
                fora.append(_pessoa(x.get("login") or x.get("username"),
                                    x.get("nome") or x.get("name"),
                                    x.get("email"), x.get("senha") or x.get("password")))
            elif str(x).strip():
                fora.append(_pessoa(nome=str(x).strip()))
        return _dedup_pessoas(fora)

    valor = str(valor)
    parece_arquivo = valor.endswith(".csv") or "/" in valor or os.sep in valor
    if parece_arquivo and not os.path.isfile(valor):
        raise FalhaEtapa(f"arquivo de usuarios nao encontrado: {valor}")
    if os.path.isfile(valor):
        with open(valor, newline="", encoding="utf-8-sig") as fh:
            return _dedup_pessoas(_pessoas_de_csv(fh.read()))
    # Texto solto: CSV colado (tem quebra de linha ou ';' ou cabecalho) OU uma
    # simples lista separada por virgula. O mesmo parser serve ao arquivo, ao
    # upload da web (conteudo do CSV) e ao CSV colado no CLI.
    if "\n" in valor or ";" in valor or _tem_cabecalho_csv(valor):
        return _dedup_pessoas(_pessoas_de_csv(valor))
    return _dedup_pessoas([_pessoa(nome=x.strip())
                           for x in valor.split(",") if x.strip()])


_CSV_MAPA = {"login": "login", "username": "login", "usuario": "login",
             "usuário": "login", "user": "login",
             "nome": "nome", "name": "nome",
             "email": "email", "e-mail": "email",
             "senha": "senha", "password": "senha"}


def _tem_cabecalho_csv(texto):
    linha = texto.splitlines()[0] if texto.strip() else ""
    delim = ";" if linha.count(";") > linha.count(",") else ","
    titulos = [(c or "").strip().lower() for c in next(csv.reader([linha], delimiter=delim), [])]
    return any(t in _CSV_MAPA for t in titulos)


def _pessoas_de_csv(texto):
    if not texto.strip():
        return []
    linhas = [l for l in texto.splitlines() if l.strip()]
    cab = linhas[0]
    # Excel em portugues salva com ';'. Escolhe o que mais aparece; ',' desempata.
    delim = ";" if cab.count(";") > cab.count(",") else ","
    titulos = [(c or "").strip().lower() for c in next(csv.reader([cab], delimiter=delim), [])]
    tem_cab = any(t in _CSV_MAPA for t in titulos)
    corpo = linhas[1:] if tem_cab else linhas
    cols = {_CSV_MAPA[t]: i for i, t in enumerate(titulos) if t in _CSV_MAPA} if tem_cab else {}
    fora = []
    for linha in csv.reader(corpo, delimiter=delim):
        if not linha or not any(c.strip() for c in linha):
            continue
        def cel(chave):
            i = cols.get(chave)
            return linha[i].strip() if i is not None and i < len(linha) else ""
        if tem_cab:
            fora.append(_pessoa(cel("login"), cel("nome"), cel("email"), cel("senha")))
        else:                                   # sem cabecalho: 1a celula = nome/login
            fora.append(_pessoa(nome=linha[0].strip()))
    return fora


def _dedup_pessoas(pessoas):
    vistos, fora = set(), []
    for p in pessoas:
        if p["login"] and p["login"] not in vistos:
            vistos.add(p["login"])
            fora.append(p)
    return fora


def senha_forte():
    """Senha aleatoria que cumpre a politica do eXo (maiuscula, minuscula,
    digito e simbolo). Usada quando o CSV nao traz senha -- e reportada no log
    para o admin distribuir (nada de senha fixa no codigo)."""
    return "Exo@" + secrets.token_urlsafe(9)


# ---------------------------------------------------------------------------
# registro grupo -> espaco (em disco, invisivel ao usuario final)
# ---------------------------------------------------------------------------
def registro_ler():
    try:
        with open(REGISTRO_PATH, encoding="utf-8") as fh:
            d = json.load(fh)
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def registro_gravar(mapa):
    try:
        os.makedirs(os.path.dirname(REGISTRO_PATH), exist_ok=True)
        tmp = REGISTRO_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(mapa, fh, indent=2, ensure_ascii=False, sort_keys=True)
        os.replace(tmp, REGISTRO_PATH)   # troca atomica: nunca deixa arquivo pela metade
    except Exception as e:
        print(f"ERRO ao gravar registro {REGISTRO_PATH}: {e}", file=sys.stderr)
        raise FalhaEtapa(f"registro indisponível: {e}")


def registro_por(caminho, space_id):
    m = registro_ler()
    if str(m.get(caminho) or "") != str(space_id):
        m[caminho] = str(space_id)
        registro_gravar(m)


def registro_tirar(caminho):
    m = registro_ler()
    if caminho in m:
        m.pop(caminho, None)
        registro_gravar(m)


# ---------------------------------------------------------------------------
# cliente REST
# ---------------------------------------------------------------------------
class Exo:
    """Cliente do eXo com sessao de formulario (nao ha token de API)."""

    def __init__(self, url, user=None, senha=None, dry=False, log=None, cookie=None):
        """Duas formas de autenticar.

        cookie  -- reaproveita a SESSAO DE QUEM CHAMOU (a interface web). O
                   provisionamento passa a agir com a identidade e os poderes
                   reais daquele administrador, e o servidor nunca ve, guarda
                   nem testa senha nenhuma.
        user/senha -- login de formulario, para uso em linha de comando.
        """
        self.url, self.dry = url.rstrip("/"), dry
        self.log = log or (lambda m: None)
        self.cookie = cookie
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE          # CA interna do projeto
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.HTTPSHandler(context=ctx))
        if cookie:
            st, _ = self._raw("GET", "/portal/rest/v1/groups?limit=1")
            if st != 200:
                raise FalhaEtapa(
                    f"a sessao recebida nao tem acesso administrativo (status {st})")
        else:
            self._login(user, senha)

    def _raw(self, metodo, caminho, corpo=None, tipo="application/json"):
        u = caminho if caminho.startswith("http") else f"{self.url}{caminho}"
        dados = None
        if corpo is not None:
            if isinstance(corpo, (bytes, bytearray)):
                dados = bytes(corpo)              # multipart de avatar/banner
            elif isinstance(corpo, str):
                dados = corpo.encode()
            else:
                dados = json.dumps(corpo).encode()
        req = urllib.request.Request(u, data=dados, method=metodo)
        if dados:
            req.add_header("Content-Type", tipo)
        if self.cookie:
            req.add_header("Cookie", self.cookie)
        try:
            with self.op.open(req, timeout=90) as r:
                return r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")
        except Exception as e:
            return 0, str(e)

    def _login(self, user, senha):
        self._raw("GET", "/portal/login")
        corpo = urllib.parse.urlencode({"username": user, "password": senha})
        st, _ = self._raw("POST", "/portal/login?op=signin", corpo,
                          "application/x-www-form-urlencoded")
        st2, _ = self._raw("GET", "/portal/rest/v1/groups?limit=1")
        if st2 != 200:
            raise FalhaEtapa(
                f"login falhou para '{user}' (status {st}/{st2}). Confira usuario/senha "
                f"e se a conta e' administradora.")

    def get(self, c):
        st, t = self._raw("GET", c)
        if st >= 400:
            raise FalhaEtapa(f"GET {c} falhou: HTTP {st} {t[:100]}")
        try:
            return st, json.loads(t)
        except json.JSONDecodeError as e:
            raise FalhaEtapa(f"GET {c} JSON invalido: {e}")

    def escreve(self, metodo, caminho, corpo=None, rotulo=""):
        if self.dry:
            self.log(f"    [simulacao] {metodo} {caminho}")
            return 200, {}
        st, t = self._raw(metodo, caminho, corpo)
        if st >= 400:
            self.log(f"    ! {rotulo or caminho} devolveu {st}: {t[:150]}")
        # Sucesso sem corpo (tipico de DELETE 200/204) NAO e' erro: o eXo
        # confirma a remocao de binding devolvendo corpo vazio. Tratar isso
        # como {} evita quebrar o rollback/remocao num json.loads("").
        if not t.strip():
            if st >= 400:
                raise FalhaEtapa(f"{metodo} {caminho} HTTP {st}")
            return st, {}
        try:
            return st, json.loads(t)
        except json.JSONDecodeError as e:
            if st >= 400:
                raise FalhaEtapa(f"{metodo} {caminho} HTTP {st}")
            raise FalhaEtapa(f"{metodo} {caminho} JSON invalido: {e}")

    # -- upload de imagem ---------------------------------------------------
    def subir_imagem(self, dados, nome="imagem.png", tipo="image/png"):
        """Sobe no servico de upload e devolve o uploadId a referenciar no PUT."""
        if self.dry:
            return "<simulacao>"
        uid = uuid.uuid4().hex
        b = "----exo" + uuid.uuid4().hex
        corpo = (f'--{b}\r\nContent-Disposition: form-data; name="file"; '
                 f'filename="{nome}"\r\nContent-Type: {tipo}\r\n\r\n').encode() \
            + bytes(dados) + f"\r\n--{b}--\r\n".encode()
        st, _ = self._raw("POST", f"/portal/upload?uploadId={uid}&action=upload",
                          corpo, f"multipart/form-data; boundary={b}")
        return uid if st < 400 else None


# ---------------------------------------------------------------------------
# consultas
# ---------------------------------------------------------------------------
def paginar(exo, base, chave, passo=100):
    """Percorre TODAS as paginas de um endpoint da API v1, sem truncar.

    Um limit fixo (era 500/200) mente por omissao: acima do teto o item some
    e o chamador conclui que nao existe -- e entao CRIA um duplicado. Aqui a
    parada e' guiada pelo campo 'size' (total real que a API informa), nao pelo
    tamanho da pagina: medido, esta instalacao devolve paginas SUB-preenchidas
    (offset=0&limit=2 traz 1 item de 52), entao "pagina menor que o passo" NAO
    significa fim. offset e' indice absoluto, entao andamos de 'passo' em 'passo'
    ate cobrir 'size'.
    """
    sep = "&" if "?" in base else "?"
    itens, offset, total, giros = [], 0, None, 0
    while True:
        # Limite da pagina limitado ao que resta: este servidor devolve HTTP
        # 500 ('Try to get more than ... can retrieve') quando offset+limit
        # passa do que existe -- inclusive porque o 'size' as vezes conta 1 a
        # mais do que da' para listar. Cobrir so' o que resta evita provocar o
        # 500 de proposito.
        lim = passo
        if total is not None and total >= 0:
            if offset >= total:
                break
            lim = max(1, min(passo, total - offset))
        # _raw, nao get: get() LEVANTA em HTTP>=400 e um 500 de paginacao
        # abortaria o provisionamento/remocao inteiro. Aqui um erro so' encerra
        # a coleta com o que ja' se tem -- nunca derruba o run.
        st, t = exo._raw("GET", f"{base}{sep}offset={offset}&limit={lim}")
        if st >= 400:
            break
        try:
            d = json.loads(t) if t.strip() else {}
        except json.JSONDecodeError:
            break
        if isinstance(d, list):
            itens.extend(d)
            break
        if not isinstance(d, dict):
            break
        lote = d.get(chave, []) or []
        itens.extend(lote)
        if total is None:
            total = d.get("size")
        offset += lim
        giros += 1
        if not lote:                  # pagina vazia = fim de verdade
            break
        if total is not None and total >= 0 and offset >= total:
            break
        if giros > 100000:            # trava contra loop infinito
            break
    return itens


def grupos_existentes(exo):
    return {g.get("id") for g in paginar(exo, "/portal/rest/v1/groups", "entities")}


def espacos(exo):
    return {s.get("id"): s
            for s in paginar(exo, "/portal/rest/v1/social/spaces", "spaces")}


def _itens_binding(d):
    if isinstance(d, list):
        return d
    return (d.get("groupSpaceBindings") or d.get("spaceGroupBindings")
            or d.get("entities") or [])


def bindings_detalhados(exo, space_id):
    """[(bindingId, grupo)] -- o id e' necessario para REMOVER."""
    st, d = exo.get(f"/portal/rest/v1/social/spaceGroupBindings/{space_id}")
    if st != 200 or not isinstance(d, (list, dict)):
        return []
    fora = []
    for b in _itens_binding(d):
        if not isinstance(b, dict):
            continue
        g = b.get("group") or b.get("groupName") or b.get("groupId")
        if g and b.get("id") is not None:
            fora.append((str(b["id"]), g))
    return fora


def bindings_do_espaco(exo, space_id):
    return [g for _, g in bindings_detalhados(exo, space_id)]


def remover_binding(exo, binding_id, rotulo=""):
    """Caminho descoberto por sondagem (os outros dois devolvem 405 e 404)."""
    return exo.escreve(
        "DELETE",
        f"/portal/rest/v1/social/spaceGroupBindings/removeGroupSpaceBinding/{binding_id}",
        None, rotulo or f"remover binding {binding_id}")


def membros_do_grupo(exo, grupo):
    """Quem esta no grupo organizacional, qualquer tipo de membership.

    ATENCAO ao endpoint: /v1/users?group=<g> parece servir e NAO serve --
    medido, ele IGNORA o filtro e devolve os 17 usuarios da plataforma. O que
    responde de verdade e' /v1/groups/memberships?groupId=<g>.
    """
    q = urllib.parse.quote(grupo, safe="")
    fora = set()
    for e in paginar(exo, f"/portal/rest/v1/groups/memberships?groupId={q}", "entities"):
        if not isinstance(e, dict):
            continue
        u = e.get("userName") or e.get("username")
        if isinstance(u, dict):
            u = u.get("userName") or u.get("username")
        if u:
            fora.add(str(u))
    return fora


def membros_do_espaco(exo, space_id):
    fora = set()
    for e in paginar(exo, f"/portal/rest/v1/social/spaces/{space_id}/users", "users"):
        n = e.get("username") or e.get("userName") or e.get("remoteId") or e.get("id")
        if n:
            fora.add(str(n).split("/")[-1])
    return fora


def espaco_do_grupo(exo, group_id, mapa=None):
    """Espaco DONO de um nivel. Tres criterios, do mais firme ao mais fraco.

    1. REGISTRO em disco (grupo -> spaceId). Exato.
    2. CADEIA DE BINDINGS: com a cascata descendente, o espaco de um nivel
       carrega a cadeia inteira de cima ate ele --
         Secretaria [/SITDS] | Divisao [/SITDS, /SITDS/DIT] |
         Setor [/SITDS, /SITDS/DIT, /SITDS/DIT/ST]
       logo o dono de G e' aquele cujo vinculo MAIS FUNDO e' exatamente G.
       Espacos que misturam vinculos de fora da arvore (o Lobby carrega
       /platform/users) sao descartados: foi o Lobby que, num criterio antigo
       por "menos vinculos", roubou o aninhamento da Divisao.
    3. NOME igual ao ultimo segmento do grupo. So' serve quando nao se usou
       rotulo -- com rotulo o grupo e' /SITDS e o espaco "Secretaria de...".
    """
    mapa = mapa if mapa is not None else espacos(exo)

    sid = registro_ler().get(group_id)
    if sid and str(sid) in mapa:
        esp = dict(mapa[str(sid)]); esp["_por"] = "registro"
        return esp

    raiz_org = "/" + group_id.strip("/").split("/")[0]

    def da_arvore(g):
        return g == raiz_org or (g or "").startswith(raiz_org + "/")

    candidatos = []
    for esp in mapa.values():
        b = [g for g in bindings_do_espaco(exo, esp.get("id")) if g]
        if group_id not in b or not all(da_arvore(g) for g in b):
            continue
        if max(b, key=lambda g: g.count("/")) == group_id:
            candidatos.append((len(b), esp))
    if candidatos:
        candidatos.sort(key=lambda x: x[0])
        esp = dict(candidatos[0][1]); esp["_por"] = "cadeia"
        return esp

    alvo = group_id.strip("/").split("/")[-1].strip().lower()
    for esp in mapa.values():
        if alvo in ((esp.get("prettyName") or "").strip().lower(),
                    (esp.get("displayName") or "").strip().lower()):
            esp = dict(esp); esp["_por"] = "nome"
            return esp
    return None


# ---------------------------------------------------------------------------
# provisionamento com diario e rollback
# ---------------------------------------------------------------------------
class Provisionador:
    """Executa a arvore inteira, anotando cada criacao num DIARIO.

    Se qualquer etapa falhar, o diario e' desfeito na ordem inversa. Desfaz
    SO' o que este run criou -- grupo, espaco ou vinculo que ja' existia antes
    permanece intocado. Sem isso, um erro no meio de uma arvore de 30 setores
    deixava niveis pela metade que nunca sincronizam ninguem.
    """

    def __init__(self, exo, log=None, cancelado=None, dry=False):
        self.exo = exo
        self.dry = dry
        self._log = log or (lambda m: None)
        self._cancelado = cancelado or (lambda: False)
        self.diario = []          # [(descricao, funcao_desfazer)]
        # Em simulacao nada e' criado de verdade, entao o nivel de baixo
        # acusaria "grupo pai nao existe" e a arvore inteira falharia sem
        # motivo. Estes conjuntos fazem a simulacao lembrar do que "criou".
        self.grupos_simulados = set()
        self.espacos_simulados = {}
        self.criados = {"grupos": [], "espacos": [], "bindings": [],
                        "memberships": [], "niveis": [], "usuarios": []}
        # credenciais das contas CRIADAS neste run, para o admin distribuir
        # (a senha so' aparece uma vez, aqui; nao fica gravada em lugar nenhum).
        self.credenciais = []

    # -- infra --------------------------------------------------------------
    def log(self, msg, tela=True):
        if tela:
            self._log(msg)
        try:
            with open(LOG_PATH, "a", encoding="utf-8") as fh:
                fh.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg.strip()}\n")
        except Exception:
            pass

    def checa_parada(self):
        if self._cancelado():
            raise Cancelado("parada pedida pelo operador")

    def anota(self, descricao, desfazer):
        self.diario.append((descricao, desfazer))

    def rollback(self, motivo=""):
        if not self.diario:
            self.log(f" ROLLBACK: nada a desfazer ({motivo})")
            return
        self.log(f" ROLLBACK ({motivo}): desfazendo {len(self.diario)} acao(oes) "
                 f"deste run, da ultima para a primeira")
        for descricao, desfazer in reversed(self.diario):
            try:
                desfazer()
                self.log(f"   desfeito: {descricao}")
            except Exception as e:
                self.log(f"   FALHOU ao desfazer {descricao}: {e}")
        self.diario = []

    # -- pessoas ------------------------------------------------------------
    def _existe_usuario(self, login):
        """(existe, habilitado) -- medido no store de ORGANIZACAO, nao no social.

        POR QUE org e nao social: as memberships de grupo/espaco usam o store
        de organizacao (/portal/rest/v1/users). O store social diverge dele: um
        DELETE de usuario tira do org mas deixa a identidade social 'deleted'
        (fantasma) respondendo 200. Se eu checasse o social, veria 'existe' e
        pularia a criacao -- e o passo de membership falharia com USER:NOT_FOUND
        (o bug medido). O org e' a autoridade para o que vem depois.
        """
        st, t = self.exo._raw(
            "GET", f"/portal/rest/v1/users/{urllib.parse.quote(login)}")
        try:
            d = json.loads(t) if t.strip() else {}
        except json.JSONDecodeError:
            d = {}
        existe = st == 200 and isinstance(d, dict) and bool(d.get("userName"))
        habil = existe and str(d.get("enabled")).lower() not in ("false", "0")
        return existe, habil

    def _criar_usuario(self, p):
        """Cria a conta de uma pessoa {login,nome,email,senha} e ANOTA para
        rollback. firstName exige >=3 chars na API; garanto isso. Idempotente:
        so' chega aqui quem NAO existe."""
        # A API so' aceita letras, espaco, '-' e '\'' em Nome/Sobrenome (digito
        # ou '.' -> HTTP 400). Saneia mantendo acento (França e' valido).
        def so_nome(s):
            return re.sub(r"[^A-Za-zÀ-ſ '\-]+", " ", s).strip()
        limpo = so_nome(p["nome"]) or so_nome(nome_de(p["login"]))
        partes = limpo.split()
        first = partes[0] if partes else "Usuario"
        last = " ".join(partes[1:]) or first
        if len(first) < 3:                     # API: 'Nome' entre 3 e 255 chars
            first = (limpo if len(limpo) >= 3 else (first + "aa")[:3])
        senha = p["senha"] or senha_forte()
        corpo = {"userName": p["login"], "firstName": first, "lastName": last,
                 "email": p["email"], "password": senha, "enabled": True}
        if self.dry:
            self.log(f"      [simulacao] criaria usuario {p['login']} "
                     f"({first} {last}, {p['email']})")
            return
        st, resp = self.exo.escreve("POST", "/portal/rest/v1/users", corpo,
                                    f"criar usuario {p['login']}")
        if st >= 400:
            # Fantasma: um login excluido antes deixa a identidade SOCIAL orfa
            # (o DELETE do eXo tira do org mas nao do social). Recriar por cima
            # dela devolve 500 no SocialUserEventListener. Mensagem clara e'
            # melhor que o 500 cru -- e o rollback deixa tudo limpo.
            if st >= 500 and "SocialUser" in str(resp):
                raise FalhaEtapa(
                    f"o login '{p['login']}' colide com uma identidade social "
                    f"orfa de uma exclusao anterior (limitacao do eXo ao recriar "
                    f"um usuario ja' excluido). Use outro login, ou peca ao admin "
                    f"para purgar a identidade. Nada foi mantido.")
            raise FalhaEtapa(f"criar usuario {p['login']}: HTTP {st} {str(resp)[:140]}")
        self.criados["usuarios"].append(p["login"])
        self.credenciais.append((p["login"], senha))
        lg = urllib.parse.quote(p["login"], safe="")
        self.anota(f"usuario {p['login']}",
                   lambda lg=lg: self.exo.escreve(
                       "DELETE", f"/portal/rest/v1/users/{lg}"))
        self.log(f"      usuario CRIADO: {p['login']} ({first} {last}, {p['email']})")

    def _garantir_pessoas(self, entrada, papel):
        """Resolve a entrada (nomes/logins/CSV) em logins existentes, CRIANDO
        quem nao existe -- e nao mais bloqueando.

        O prompt lista pessoas a serem 'inseridas OU criadas': 'Wilson França'
        vira a conta wilson.franca se ainda nao houver. Contas criadas entram
        no rollback; se qualquer passo adiante falhar, elas somem junto.
        Devolve a lista de logins para os passos de membership.
        """
        pessoas = ler_pessoas(entrada)
        if not pessoas:
            return []
        logins, desabilitados = [], []
        for p in pessoas:
            existe, habil = self._existe_usuario(p["login"])
            if not existe:
                self._criar_usuario(p)
                habil = True                   # nasce habilitado
            if not habil:
                desabilitados.append(p["login"])
            logins.append(p["login"])
        if desabilitados:
            self.log(f"    AVISO: {len(desabilitados)} {papel}(es) com conta "
                     f"DESABILITADA: {', '.join(desabilitados[:8])} -- entram no grupo "
                     f"mas NAO nos espacos ate serem habilitados")
        return logins

    def _tem_membership(self, user, grupo, tipo):
        base = f"/portal/rest/v1/users/{urllib.parse.quote(user)}/memberships"
        return any(e.get("groupId") == grupo and e.get("membershipType") == tipo
                   for e in paginar(self.exo, base, "entities"))

    def _add_memberships(self, grupo, users, tipo, rotulo):
        """Adiciona em lote, pulando quem ja' tem. Anota para rollback."""
        if not users:
            return
        repetidos = [] if self.dry else [u for u in users
                                         if self._tem_membership(u, grupo, tipo)]
        pendentes = [u for u in users if u not in repetidos]
        if repetidos:
            self.log(f"    {len(repetidos)} ja era(m) {tipo} em {grupo} "
                     f"(ignorado): {', '.join(repetidos[:8])}")
        if not pendentes:
            return
        lote = [{"groupId": grupo, "membershipType": tipo, "userName": u}
                for u in pendentes]
        st, resp = self.exo.escreve(
            "POST", "/portal/rest/v1/groups/memberships/bulk?membershipId=",
            lote, f"{rotulo} em {grupo}")
        if st >= 400:
            raise FalhaEtapa(f"{rotulo}: bulk devolveu {st}: {str(resp)[:160]}")
        self.log(f"    {rotulo}: {len(pendentes)} em {grupo} -> "
                 f"{', '.join(pendentes[:8])}")
        for u in pendentes:
            self.criados["memberships"].append(f"{tipo}:{u}:{grupo}")
            mid = urllib.parse.quote(f"{tipo}:{u}:{grupo}", safe="")
            self.anota(f"membership {tipo} {u} em {grupo}",
                       lambda mid=mid: self.exo.escreve(
                           "DELETE",
                           f"/portal/rest/v1/groups/memberships?membershipId={mid}"))

    # -- perfil do espaco ---------------------------------------------------
    @staticmethod
    def _imagem(v):
        """Aceita bytes (vem da web, em base64 decodificado) ou caminho de
        arquivo (vem do JSON/CLI). Caminho que nao existe e' erro, nao um
        nome de arquivo qualquer -- seguir calado deixaria o espaco sem
        imagem sem ninguem saber por que."""
        if not v:
            return None
        if isinstance(v, (bytes, bytearray)):
            return bytes(v)
        caminho = str(v)
        if not os.path.isfile(caminho):
            raise FalhaEtapa(f"imagem nao encontrada: {caminho}")
        with open(caminho, "rb") as fh:
            return fh.read()

    def aplicar_perfil(self, space_id, esp, rotulo, descricao=None,
                       avatar=None, banner=None):
        """Grava descricao/avatar/banner e CONFERE que pegou.

        O PUT so' persiste se o corpo trouxer 'id' E 'displayName' -- sem eles
        devolve 200 e ignora o resto, silenciosamente. E como o PUT SUBSTITUI
        o objeto, visibilidade, inscricao e pai vao junto ou sao zerados.
        """
        if self.dry:
            self.log(f" 5. perfil   [simulacao] descricao/avatar/banner de '{rotulo}'")
            return
        # O PUT SUBSTITUI o objeto: todo campo ausente do corpo e' ZERADO no
        # servidor. Por isso a descricao viva vai SEMPRE no corpo -- atualizar
        # so' a imagem (descricao=None) nao pode mais apagar a descricao (era o
        # defeito: 'descricao foi perdida durante a execucao'). O alvo desejado
        # vence quando informado; senao preserva-se o que ja' esta la'.
        desc_atual = esp.get("description") or ""
        desc_alvo = descricao if descricao is not None else desc_atual
        # RENAME: o rotulo desejado VENCE o displayName atual. Antes era o
        # contrario ('esp.get(displayName) or rotulo'), entao renomear um nivel
        # existente nao pegava -- reexecutar com um rotulo novo dizia 'perfil
        # integro' e nao mudava nada. Trocar a SIGLA (id do grupo) e' outra
        # coisa: o eXo nao renomeia grupo, so' criando outro.
        nome_atual = esp.get("displayName") or ""
        nome_alvo = (rotulo or nome_atual).strip()
        corpo = {"id": str(space_id),
                 "displayName": nome_alvo,
                 "visibility": esp.get("visibility") or "private",
                 "subscription": esp.get("subscription") or "closed",
                 "description": desc_alvo}
        if esp.get("parentSpaceId"):
            corpo["parentSpaceId"] = str(esp.get("parentSpaceId"))

        mudou = []
        if nome_alvo and nome_alvo != nome_atual:
            mudou.append("nome")
        if desc_alvo != desc_atual:
            mudou.append("descricao")
        for campo, dados, chave in (("avatar", self._imagem(avatar), "avatarId"),
                                    ("banner", self._imagem(banner), "bannerId")):
            if not dados:
                continue
            uid = self.exo.subir_imagem(dados, f"{campo}.png")
            if not uid:
                self.log(f" 5. perfil   AVISO: upload de {campo} falhou; segue sem ele")
                continue
            corpo[chave] = uid
            mudou.append(campo)
        if not mudou:
            return

        antes = (esp.get("bannerUrl") or "") + (esp.get("avatarUrl") or "")
        self.exo.escreve("PUT", f"/portal/rest/v1/social/spaces/{space_id}",
                         corpo, "perfil do espaco")
        st, det = self.exo.get(f"/portal/rest/v1/social/spaces/{space_id}")
        if st != 200 or not isinstance(det, dict):
            self.log(" 5. perfil   AVISO: nao consegui conferir a gravacao")
            return
        ok = []
        if "descricao" in mudou:
            ok.append("descricao" if (det.get("description") or "") == desc_alvo
                      else "descricao NAO GRAVOU")
        elif (det.get("description") or "") != desc_alvo:
            # a descricao foi junto no corpo justamente para NAO se perder;
            # se ainda assim divergir, e' sinal de problema -- nao esconder.
            ok.append("descricao PRESERVADA NAO BATEU")
        if "avatarId" in corpo or "bannerId" in corpo:
            depois = (det.get("bannerUrl") or "") + (det.get("avatarUrl") or "")
            ok.append("imagens" if depois != antes else "imagens NAO GRAVARAM")
        self.log(f" 5. perfil   '{rotulo}': {', '.join(ok)}")

    # -- um nivel -----------------------------------------------------------
    def nivel(self, tipo, nome, pai="", rotulo="", descricao=None,
              gestores=None, usuarios=None, avatar=None, banner=None,
              lobby=""):
        self.checa_parada()
        nome_bruto = str(nome).strip().strip("/")
        if not nome_bruto:
            raise FalhaEtapa(f"{tipo}: nome vazio")
        grupo = slug_grupo(nome_bruto)
        rotulo = (rotulo or nome_bruto).strip()
        pai = ("/" + pai.strip("/")) if pai else ""
        caminho = f"{pai}/{grupo}" if pai else f"/{grupo}"
        if grupo != nome_bruto:
            self.log(f"   nome do grupo normalizado: '{nome_bruto}' -> '{grupo}'")
        self.log(f"\n== {tipo.upper()}: {rotulo}  ->  grupo {caminho}")

        # 1) grupo ----------------------------------------------------------
        existentes = grupos_existentes(self.exo) | self.grupos_simulados
        if pai and pai not in existentes:
            raise FalhaEtapa(f"grupo pai '{pai}' nao existe -- crie o nivel de cima antes")
        if caminho in existentes:
            self.log(f" 1. grupo    ja existe: {caminho}")
        else:
            corpo = {"groupName": grupo, "label": rotulo,
                     "description": f"{tipo} {rotulo}"}
            if pai:
                corpo["parentId"] = pai
            st, resp = self.exo.escreve("POST", "/portal/rest/v1/groups", corpo,
                                        "criar grupo")
            if st >= 400 and not self.dry:
                raise FalhaEtapa(f"criar grupo {caminho}: {st} {str(resp)[:140]}")
            self.log(f" 1. grupo    criado: {caminho}")
            self.grupos_simulados.add(caminho)
            self.criados["grupos"].append(caminho)
            gq = urllib.parse.quote(caminho, safe="")
            self.anota(f"grupo {caminho}",
                       lambda gq=gq: self.exo.escreve(
                           "DELETE", f"/portal/rest/v1/groups?groupId={gq}"))

        self.checa_parada()

        # 2) espaco ---------------------------------------------------------
        mapa = espacos(self.exo)
        esp = espaco_do_grupo(self.exo, caminho, mapa)
        if esp is None:
            esp = next((s for s in mapa.values()
                        if (s.get("displayName") or "").strip().lower()
                        == rotulo.lower()), None)
        if esp:
            self.log(f" 2. espaco   ja existe: {esp.get('displayName')} "
                     f"(id {esp.get('id')})")
        else:
            st, esp = self.exo.escreve(
                "POST", "/portal/rest/v1/social/spaces",
                {"displayName": rotulo, "description": descricao or f"{tipo} {rotulo}",
                 "visibility": "private", "subscription": "closed",
                 "templateId": 3}, "criar espaco")
            if self.dry:
                # id ficticio porem UNICO: com "<simulacao>" para todos, o
                # passo 3 achava que o filho era o proprio pai e nunca aninhava.
                esp = {"id": f"sim-{len(self.espacos_simulados) + 1}",
                       "displayName": rotulo, "groupId": f"/spaces/sim_{grupo.lower()}"}
                self.espacos_simulados[caminho] = esp
            elif st >= 400 or not isinstance(esp, dict) or not esp.get("id"):
                raise FalhaEtapa(f"criar espaco '{rotulo}': {st}")
            self.log(f" 2. espaco   criado: {rotulo} (id {esp.get('id')})")
            self.criados["espacos"].append(str(esp.get("id")))
            sid_novo = esp.get("id")
            self.anota(f"espaco {rotulo} (id {sid_novo})",
                       lambda sid=sid_novo: self.exo.escreve(
                           "DELETE", f"/portal/rest/v1/social/spaces/{sid}"))
        space_id = esp.get("id")
        if not self.dry:
            registro_por(caminho, space_id)
            self.anota(f"registro {caminho}", lambda c=caminho: registro_tirar(c))

        self.checa_parada()

        # 3) aninhamento ----------------------------------------------------
        if pai:
            pai_esp = self.espacos_simulados.get(pai) if self.dry else None
            pai_esp = pai_esp or espaco_do_grupo(self.exo, pai)
            if not pai_esp:
                raise FalhaEtapa(
                    f"nao achei o espaco dono de '{pai}'. O nivel de cima tem espaco "
                    f"proprio? Crie-o antes.")
        else:
            pai_esp = (mapa.get(lobby) if lobby else
                       next((s for s in mapa.values() if not s.get("parentSpaceId")), None))

        if pai_esp and str(pai_esp.get("id")) != str(space_id):
            if str(esp.get("parentSpaceId") or "") == str(pai_esp.get("id")):
                self.log(f" 3. aninhar  ja esta dentro de '{pai_esp.get('displayName')}'")
            else:
                # O PUT SUBSTITUI o objeto: sem 'description' no corpo, este
                # aninhamento ZERARIA a descricao do nivel (defeito da mesma
                # classe do passo 5, so' que aqui e mais cedo). Por isso a
                # descricao viva vai junto: a desejada quando informada, senao
                # a que o espaco ja' tem, senao o padrao do tipo. Fecha o furo
                # em TODOS os PUT que substituem o espaco, nao so' no perfil.
                desc_ninho = (descricao if descricao is not None
                              else (esp.get("description") or f"{tipo} {rotulo}"))
                self.exo.escreve("PUT", f"/portal/rest/v1/social/spaces/{space_id}",
                                 {"id": str(space_id),
                                  "displayName": esp.get("displayName") or rotulo,
                                  "parentSpaceId": str(pai_esp.get("id")),
                                  "visibility": "private",
                                  "subscription": "closed",
                                  "description": desc_ninho}, "aninhar")
                self.log(f" 3. aninhar  dentro de '{pai_esp.get('displayName')}' "
                         f"(id {pai_esp.get('id')})")
        else:
            self.log(" 3. aninhar  nivel raiz: nada a fazer")

        self.checa_parada()

        # 4) cadeia de bindings (DESCENDENTE) -------------------------------
        partes = caminho.strip("/").split("/")
        cadeia = ["/" + "/".join(partes[:i + 1]) for i in range(len(partes))]
        raiz_org = cadeia[0]

        def da_arvore(g):
            return g == raiz_org or (g or "").startswith(raiz_org + "/")

        detalhes = [] if self.dry else bindings_detalhados(self.exo, space_id)
        atuais = [g for _, g in detalhes]
        # Vinculos de FORA desta arvore (/platform/users, grupos de projeto) sao
        # intocaveis -- mexer neles ja' tirou 14 pessoas do Lobby numa execucao.
        externos = [g for g in atuais if not da_arvore(g)]
        desejados = sorted(set(externos + cadeia))
        sobrando = [g for g in atuais if g not in desejados]

        if sorted(set(atuais)) == desejados:
            self.log(f" 4. cadeia   ja sincroniza: {', '.join(cadeia)}")
        elif not desejados:
            self.log(f" 4. cadeia   ABORTADO: lista desejada vazia, nao apago "
                     f"{len(atuais)} vinculos")
        else:
            novos = [g for g in cadeia if g not in atuais]
            self.exo.escreve(
                "POST",
                f"/portal/rest/v1/social/spaceGroupBindings/saveGroupsSpaceBindings/{space_id}",
                desejados, f"cadeia de '{rotulo}'")
            self.log(f" 4. cadeia   '{rotulo}' <- {', '.join(cadeia)}")
            for g in novos:
                self.criados["bindings"].append(f"{space_id}:{g}")
                self.anota(f"vinculo {g} em {rotulo}",
                           lambda sid=space_id, g=g: self._desfaz_binding(sid, g))
            # remocao real: o POST acima e' add-only e nunca tira nada
            for bid, g in detalhes:
                if g in sobrando:
                    st_r, _ = remover_binding(self.exo, bid, f"retirar {g}")
                    if st_r < 400:
                        self.log(f" 4. cadeia   RETIRADO {g} (vinha da cascata antiga)")

        self.checa_parada()

        # 5) perfil ---------------------------------------------------------
        st_e, det = self.exo.get(f"/portal/rest/v1/social/spaces/{space_id}") \
            if not self.dry else (200, esp)
        self.aplicar_perfil(space_id, det if isinstance(det, dict) else esp,
                            rotulo, descricao, avatar, banner)

        self.checa_parada()

        # 6) pessoas --------------------------------------------------------
        gestores = self._garantir_pessoas(gestores, "gestor")
        usuarios = self._garantir_pessoas(usuarios, "usuario")
        if gestores:
            self._add_memberships(caminho, gestores, "manager", " 6. gestores")
            # Gestor do ESPACO e' outra coisa: manager no grupo tecnico
            # /spaces/<prettyName>. Sem isto o secretario e' membro comum e nao
            # administra a propria secretaria.
            grupo_espaco = (det.get("groupId") if isinstance(det, dict) else None) \
                or esp.get("groupId")
            if self.dry and not grupo_espaco:
                grupo_espaco = f"/spaces/sim_{grupo.lower()}"
            if grupo_espaco:
                self._add_memberships(grupo_espaco, gestores, "manager",
                                      " 6. gestor do espaco")
            else:
                self.log(" 6. AVISO: nao descobri o grupo tecnico do espaco; "
                         "os gestores ficam sem poder de administrar")
        if usuarios:
            self._add_memberships(caminho, usuarios, "member", " 6. membros")
        if not gestores and not usuarios:
            self.log(" 6. pessoas  nenhuma informada")

        self.criados["niveis"].append({"tipo": tipo, "grupo": caminho,
                                       "espaco": str(space_id), "rotulo": rotulo})
        # NAO devolver os bytes das imagens aqui. Este dicionario vira o
        # "resumo" do job e e' serializado em JSON no /api/log -- com bytes
        # dentro, o endpoint estourava com "Object of type bytes is not JSON
        # serializable" e a interface ficava CEGA logo depois de um
        # provisionamento bem-sucedido com avatar/banner. So' os sinalizadores.
        return {"grupo": caminho, "espaco": str(space_id), "rotulo": rotulo,
                "descricao": descricao,
                "tem_avatar": bool(avatar), "tem_banner": bool(banner)}

    def _desfaz_binding(self, space_id, grupo):
        for bid, g in bindings_detalhados(self.exo, space_id):
            if g == grupo:
                remover_binding(self.exo, bid)


# ---------------------------------------------------------------------------
# arvore inteira
# ---------------------------------------------------------------------------
def _filhos(no, tipo):
    chave = FILHOS.get(tipo)
    return no.get(chave) or [] if chave else []


def contar_niveis(payload):
    total = 0
    for sec in payload.get("secretarias") or []:
        total += 0 if sec.get("_existente") else 1
        for div in _filhos(sec, "secretaria"):
            total += 0 if div.get("_existente") else 1
            total += len(_filhos(div, "divisao"))
    return total


def checar_colisao_slug(payload):
    """Barra dois nomes distintos que colapsam no mesmo slug de grupo.

    slug_grupo tira acentos, troca pontuacao por '-' e sobe pra maiuscula:
    'Setor 1', 'Setor-1' e 'Setor.1' viram todos SETOR-1. Sem esta trava, o
    segundo irmao reaproveitava silenciosamente o grupo/espaco do primeiro
    (os passos 1 e 2 sao idempotentes por caminho) -- corrupcao invisivel, e
    o oposto do que o pedido exige ('nomenclaturas proprias' para cada nivel).
    Detectado ANTES de criar qualquer coisa: nenhuma escrita parcial.
    """
    vistos = {}                         # caminho -> nome de origem
    def visita(no, pai):
        nome = str(no.get("nome") or "").strip()
        if not nome or no.get("_existente"):
            return
        caminho = (pai + "/" if pai else "/") + slug_grupo(nome)
        anterior = vistos.get(caminho)
        if anterior is not None and anterior != nome:
            raise FalhaEtapa(
                f"colisao de nome: '{anterior}' e '{nome}' geram o MESMO grupo "
                f"'{caminho}'. De' nomes que nao colapsem (acento, espaco e "
                f"pontuacao viram '-' e a caixa e' ignorada). Nada foi criado.")
        vistos[caminho] = nome
        for div in _filhos(no, "secretaria"):
            visita(div, caminho)
        for st_ in _filhos(no, "divisao"):
            visita(st_, caminho)
    for sec in payload.get("secretarias") or []:
        visita(sec, "")


def provisionar_arvore(prov, payload):
    """Percorre secretarias > divisoes > setores, de cima para baixo.

    A ordem importa: o passo 3 precisa do espaco do pai ja' existindo, e o
    passo 4 monta a cadeia com os grupos de cima. Qualquer excecao sobe e
    dispara o rollback do run inteiro -- meia arvore e' pior que nenhuma.
    """
    feitos = []

    def caminho_de(no, pai):
        """Nivel marcado _existente e' so' o endereco do pai -- a CLI usa isso
        quando se pede um setor solto e a secretaria/divisao ja' existem. Nao
        pode ser reprovisionado: viria sem rotulo e sobrescreveria o perfil."""
        return f"{pai}/{slug_grupo(str(no.get('nome') or ''))}" if pai \
            else "/" + slug_grupo(str(no.get("nome") or ""))

    try:
        checar_colisao_slug(payload)     # antes de qualquer escrita
        for sec in payload.get("secretarias") or []:
            if sec.get("_existente"):
                gs = caminho_de(sec, "")
            else:
                r = prov.nivel("secretaria", sec.get("nome"), "",
                               sec.get("rotulo"), sec.get("descricao"),
                               sec.get("gestores"), sec.get("usuarios"),
                               sec.get("avatar"), sec.get("banner"),
                               payload.get("lobby", ""))
                feitos.append(r)
                gs = r["grupo"]
            for div in _filhos(sec, "secretaria"):
                if div.get("_existente"):
                    gd = caminho_de(div, gs)
                else:
                    rd = prov.nivel("divisao", div.get("nome"), gs,
                                    div.get("rotulo"), div.get("descricao"),
                                    div.get("gestores"), div.get("usuarios"),
                                    div.get("avatar"), div.get("banner"))
                    feitos.append(rd)
                    gd = rd["grupo"]
                for st_ in _filhos(div, "divisao"):
                    feitos.append(prov.nivel(
                        "setor", st_.get("nome"), gd,
                        st_.get("rotulo"), st_.get("descricao"),
                        st_.get("gestores"), st_.get("usuarios"),
                        st_.get("avatar"), st_.get("banner")))
        # PERFIL POR ULTIMO, e conferido.
        # Motivo medido: com o perfil aplicado dentro do nivel, a descricao da
        # DIVISAO voltava vazia no fim da execucao -- gravava (a conferencia do
        # passo 5 passava) e algo disparado pelos niveis seguintes re-salvava o
        # espaco por cima. O nivel do meio era o unico afetado: a Secretaria
        # sobrevivia e o Setor, por ser o ultimo, nunca chegava a ser tocado.
        # Em vez de caçar qual listener do eXo re-salva o espaco, o perfil
        # passa a ser a ultima escrita e e' reconferido aqui.
        # Rede de seguranca, agora redundante: desde que o PUT de perfil passou
        # a carregar SEMPRE a descricao viva (aplicar_perfil), o passo 5 nao
        # apaga mais a descricao, entao nao ha o que "regravar". Fica como
        # conferencia final -- e nunca em simulacao, onde os ids sao ficticios
        # e o GET real devolveria 401/500 e dispararia um rollback FALSO.
        if feitos and not prov.dry:
            prov.log("\n== conferindo perfis (ultima escrita) ==")
            for f in feitos:
                prov.checa_parada()
                st, det = prov.exo.get(
                    f"/portal/rest/v1/social/spaces/{f['espaco']}")
                if st != 200 or not isinstance(det, dict):
                    continue
                falta_desc = (f.get("descricao") is not None
                              and (det.get("description") or "") != f["descricao"])
                if falta_desc:
                    prov.log(f"   '{f['rotulo']}': descricao divergente, regravando")
                    prov.aplicar_perfil(f["espaco"], det, f["rotulo"],
                                        f["descricao"], None, None)
                else:
                    prov.log(f"   '{f['rotulo']}': perfil integro")

        # PROPAGACAO IMEDIATA.
        #
        # O vinculo de grupo sozinho nao poe ninguem no espaco na hora: quem JA'
        # estava no grupo antes do vinculo existir so' entra quando o
        # QueueGroupSpaceBindingJob roda -- cron de 5 em 5 minutos. Isso atinge
        # justamente o caso normal desta arvore: quando o espaco do Setor e'
        # criado e recebe /SITDS na cadeia, o secretario ja' esta em /SITDS ha'
        # segundos, entao ele NAO aparece no Setor e a conferencia logo apos a
        # execucao acusa gente faltando.
        #
        # Aqui a entrada e' forcada nivel por nivel, com o mesmo endpoint que a
        # UI usa para adicionar alguem a um espaco. O job continua rodando
        # depois como rede de seguranca; deixou de ser o unico caminho.
        if feitos:
            prov.log("\n== propagando membros da cadeia (sem esperar o cron) ==")
            for f in feitos:
                prov.checa_parada()
                partes = f["grupo"].strip("/").split("/")
                cadeia = ["/" + "/".join(partes[:i + 1]) for i in range(len(partes))]
                # Em simulacao o grupo NAO foi criado de verdade; consultar seus
                # membros faria GET num grupo inexistente -> 404 -> excecao ->
                # rollback FALSO (o "erro assustador" que a simulacao mostrava
                # para qualquer nivel novo). A simulacao para aqui, sem tocar a
                # rede: nada foi criado, entao nada ha para propagar.
                if prov.dry:
                    prov.log(f"   '{f['rotulo']}': (simulacao) propagaria a cadeia "
                             f"{' <- '.join(cadeia)} apos a criacao real")
                    continue
                querem = set()
                for g in cadeia:
                    querem |= membros_do_grupo(prov.exo, g)
                ja = membros_do_espaco(prov.exo, f["espaco"])
                faltam = sorted(querem - ja)
                if not faltam:
                    prov.log(f"   '{f['rotulo']}': todos os {len(querem)} da cadeia "
                             f"ja estao dentro")
                    continue
                entraram = []
                for u in faltam:
                    st_m, resp_m = prov.exo.escreve(
                        "POST", "/portal/rest/v1/social/spacesMemberships",
                        {"space": str(f["espaco"]), "user": u, "role": "member"},
                        f"entrar {u} em {f['rotulo']}")
                    if st_m < 400:
                        entraram.append(u)
                        # Journaliza para o rollback: esta pessoa pode ter sido
                        # posta num espaco PRE-EXISTENTE; se um passo adiante
                        # falhar, ela precisa sair junto. O id da associacao vem
                        # na resposta; sem ele, desfaz por user:space.
                        mid = (resp_m.get("id") if isinstance(resp_m, dict) else None) \
                            or f"{u}:{f['espaco']}"
                        midq = urllib.parse.quote(str(mid), safe="")
                        prov.anota(
                            f"membro {u} no espaco {f['rotulo']}",
                            lambda midq=midq: prov.exo.escreve(
                                "DELETE",
                                f"/portal/rest/v1/social/spacesMemberships/{midq}"))
                prov.log(f"   '{f['rotulo']}': {len(entraram)} entraram agora "
                         f"-> {', '.join(entraram[:8])}")
                if len(entraram) < len(faltam):
                    prov.log(f"   '{f['rotulo']}': {len(faltam) - len(entraram)} NAO "
                             f"entraram; o job dos 5 min ainda pode resolver")

        if prov.credenciais:
            prov.log("\n== contas CRIADAS neste run (guarde as senhas -- so' "
                     "aparecem aqui) ==")
            for login, senha in prov.credenciais:
                prov.log(f"   {login}  |  senha: {senha}")
        prov.log(f"\nOK -- {len(feitos)} nivel(is) provisionado(s).")
        return {"ok": True, "niveis": feitos,
                "credenciais": [{"login": l, "senha": s} for l, s in prov.credenciais]}
    except Cancelado as e:
        prov.log(f"\nPARADO: {e}")
        prov.rollback("parada pedida")
        return {"ok": False, "parado": True, "erro": str(e), "niveis": feitos}
    except Exception as e:
        prov.log(f"\nERRO: {e}")
        prov.rollback(f"erro: {e}")
        return {"ok": False, "erro": str(e), "niveis": feitos}


# ---------------------------------------------------------------------------
# remocao
# ---------------------------------------------------------------------------
def remover_nivel(prov, caminho, apagar_espaco=True):
    """Desfaz um nivel: tira os vinculos, apaga o espaco e apaga o grupo.

    Ordem importa. Tirar o grupo dos vinculos ANTES de apagar o espaco faz com
    que quem entrou pelo vinculo saia junto; quem ja' era membro por outra via
    (IS_MEMBER_BEFORE=1) permanece -- o eXo nao desfaz o que nao foi ele que fez.
    """
    exo = prov.exo
    prov.checa_parada()
    alvo = espaco_do_grupo(exo, caminho)
    prov.log(f"\n-- REMOVER {caminho}"
             + (f" e o espaco '{alvo.get('displayName')}' (id {alvo.get('id')}, "
                f"identificado por {alvo.get('_por')})" if alvo else " (espaco nao encontrado)"))

    for esp in espacos(exo).values():
        for bid, g in bindings_detalhados(exo, esp.get("id")):
            if g == caminho:
                st_r, _ = remover_binding(exo, bid, f"retirar {g}")
                if st_r < 400:
                    prov.log(f"   vinculo {g} retirado de '{esp.get('displayName')}'")

    # TRAVA: so' apaga o espaco se a identificacao for firme. Por "palpite" o
    # script ja' chegou a apontar para o 'Lobby Prefeitura' -- o DELETE falhou
    # por acaso, mas os vinculos do Lobby ja' tinham saido e 14 pessoas ficaram
    # de fora do espaco.
    FIRMES = ("registro", "cadeia", "nome")
    if alvo and apagar_espaco and alvo.get("_por") in FIRMES:
        st_e, _ = exo.escreve("DELETE",
                              f"/portal/rest/v1/social/spaces/{alvo.get('id')}",
                              None, "apagar espaco")
        if prov.dry or st_e < 400:
            prov.log(f"   espaco apagado: {alvo.get('displayName')}")
        else:
            prov.log(f"   espaco NAO apagado ({st_e}): {alvo.get('displayName')}")
    elif alvo and apagar_espaco:
        prov.log(f"   espaco PRESERVADO: '{alvo.get('displayName')}' so' foi achado "
                 f"por palpite. Nao apago por palpite.")

    # O caminho do grupo vai em QUERY STRING, nao no path -- com ele embutido a
    # API devolve 404 e o grupo fica para tras.
    gq = urllib.parse.quote(caminho, safe="")
    st_g, resp = exo.escreve("DELETE", f"/portal/rest/v1/groups?groupId={gq}",
                             None, "apagar grupo")
    if prov.dry or st_g < 400:
        prov.log(f"   grupo apagado: {caminho}")
        registro_tirar(caminho)
        return "removido"
    if st_g == 404 or "NOT_FOUND" in str(resp).upper():
        # Nao existia. Isso NAO e' remocao -- contar como tal fazia o resumo
        # anunciar "3 nivel(is) removido(s)" depois de tres 404 seguidos, ou
        # seja, dando por feito um trabalho que nao aconteceu.
        prov.log(f"   grupo INEXISTENTE: {caminho} (nada a remover)")
        registro_tirar(caminho)
        return "inexistente"
    if "child group" in str(resp):
        prov.log(f"   grupo MANTIDO: {caminho} ainda tem subgrupos "
                 f"-- remova os niveis de baixo primeiro")
        return "bloqueado"
    prov.log(f"   grupo NAO apagado ({st_g}): {caminho}")
    return "falhou"


def caminhos_da_arvore(payload):
    """Todos os caminhos de grupo do payload, DO MAIS FUNDO PARA O MAIS RASO.

    O eXo recusa apagar grupo que tenha subgrupo, entao a remocao sobe.
    """
    fora = []
    for sec in payload.get("secretarias") or []:
        cs = "/" + slug_grupo(str(sec.get("nome") or ""))
        for div in _filhos(sec, "secretaria"):
            cd = f"{cs}/{slug_grupo(str(div.get('nome') or ''))}"
            for st_ in _filhos(div, "divisao"):
                fora.append(f"{cd}/{slug_grupo(str(st_.get('nome') or ''))}")
            if not div.get("_existente"):
                fora.append(cd)
        if not sec.get("_existente"):
            fora.append(cs)
    return fora


def remover_arvore(prov, payload):
    caminhos = caminhos_da_arvore(payload)
    try:
        contas = {}
        for c in caminhos:
            r = remover_nivel(prov, c) or "removido"
            contas.setdefault(r, []).append(c)
        removidos = contas.get("removido", [])
        # O resumo diz o que REALMENTE aconteceu. Antes anunciava sempre
        # "N nivel(is) removido(s)", mesmo quando nada tinha sido removido.
        partes = [f"{len(removidos)} removido(s)"]
        for chave, rotulo in (("inexistente", "ja nao existia(m)"),
                              ("bloqueado", "com subgrupo, mantido(s)"),
                              ("falhou", "FALHOU")):
            if contas.get(chave):
                partes.append(f"{len(contas[chave])} {rotulo}")
        houve_falha = bool(contas.get("falhou") or contas.get("bloqueado"))
        prov.log(("\nOK -- " if not houve_falha else "\nATENCAO -- ") + ", ".join(partes) + ".")
        return {"ok": not houve_falha, "removidos": removidos,
                "inexistentes": contas.get("inexistente", []),
                "bloqueados": contas.get("bloqueado", []),
                "falhas": contas.get("falhou", [])}
    except Cancelado as e:
        prov.log(f"\nPARADO: {e}")
        return {"ok": False, "parado": True, "erro": str(e)}
    except Exception as e:
        prov.log(f"\nERRO: {e}")
        return {"ok": False, "erro": str(e)}


# ---------------------------------------------------------------------------
# conexao
# ---------------------------------------------------------------------------
def conectar(url=None, user=None, senha=None, dry=False, log=None, cookie=None):
    """A URL vem SEMPRE do ambiente do servidor, nunca do pedido.

    Antes ela vinha no corpo enviado pelo navegador -- ou seja, quem chamasse
    escolhia para qual host o servidor ia se conectar e mandar credenciais.
    Isso e' SSRF: daria para apontar o backend para qualquer maquina alcancavel
    a partir do container.
    """
    url = os.environ.get("EXO_URL") or url or "https://192.168.1.59"
    if cookie:
        return Exo(url, dry=dry, log=log, cookie=cookie)
    user = user or os.environ.get("EXO_ADMIN_USER", "root")
    senha = senha or os.environ.get("EXO_ADMIN_PASS", "")
    if not senha:
        raise FalhaEtapa("informe a senha (EXO_ADMIN_PASS ou --senha)")
    return Exo(url, user, senha, dry, log)
