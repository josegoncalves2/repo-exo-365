#!/usr/bin/env python3
# ============================================================================
# estrutura-organizacional.py -- cria Secretaria / Divisao / Setor de uma vez.
#
# O QUE ELE RESOLVE
# -----------------
# Montar um nivel da hierarquia a mao exige 4 operacoes em 3 telas diferentes,
# e errar a ordem quebra em silencio. Aqui e' um comando so'.
#
# Para cada nivel novo o script:
#   1. cria o GRUPO na posicao certa da arvore  (/SGCI/DTI/SINID)
#   2. cria o ESPACO (privado/fechado -- so entra quem esta no grupo)
#   3. ANINHA o espaco dentro do espaco do pai
#   4. registra o grupo nos BINDINGS do proprio espaco E DE TODOS OS ANCESTRAIS
#      -- e' este passo que faz quem entra no setor cair tambem na divisao,
#      na secretaria e no lobby. O eXo NAO herda membership de grupo pai,
#      entao sem a cascata a pessoa ficaria so' no nivel mais fundo.
#   5. (opcional) adiciona usuarios ao grupo, de lista ou de CSV
#
# IDEMPOTENTE: rodar duas vezes nao duplica nada. Ele detecta o que ja existe
# e completa apenas o que falta -- inclusive bindings faltando em ancestrais.
#
# EXEMPLOS
#   ./estrutura-organizacional.py --tipo secretaria --nome SGCI
#   ./estrutura-organizacional.py --tipo divisao --nome DTI --pai /SGCI
#   ./estrutura-organizacional.py --tipo setor --nome SINID --pai /SGCI/DTI \
#       --usuarios usuarios.csv
#   ./estrutura-organizacional.py --tipo setor --nome SINID --pai /SGCI/DTI \
#       --usuarios joao.silva,maria.souza --dry-run
#
# ATENCAO -- duas armadilhas medidas nesta instalacao:
#   * conta DESABILITADA entra no grupo mas NAO nos espacos, sem erro algum.
#     O script avisa antes de tentar.
#   * quem ja' estava no grupo ANTES de a cascata existir nao e' reprocessado
#     (o job registra "Bound Users(0)"). Use --revincular nesses casos.
#
# CSV aceito: com ou sem cabecalho; usa a coluna 'username'/'usuario'/'login'
# se houver cabecalho, senao a primeira coluna. Ignora vazios e duplicados.
#
# Credenciais: EXO_URL, EXO_ADMIN_USER, EXO_ADMIN_PASS (ou --url/--user/--pass)
# ============================================================================
import argparse, csv, json, os, re, sys, unicodedata
import urllib.parse, urllib.request, ssl, http.cookiejar, time

TIPOS = ("secretaria", "divisao", "setor")


def slug_grupo(nome):
    """Nome de grupo seguro para virar segmento de caminho.

    O eXo aceita criar um grupo chamado 'Saude e Bem Estar', mas o id vira
    '/Saude e Bem Estar' -- com espacos e acentos dentro de um caminho que
    entra em URL e no id de membership ('member:<user>:<grupo>'). Isso quebra
    de forma dificil de diagnosticar. Aqui o nome e' normalizado:
    acentos removidos, espacos e pontuacao viram '-', tudo em maiuscula.
      'Saude e Bem Estar'                -> 'SAUDE-E-BEM-ESTAR'
      'Divisao de Inovacao Tecnologica'  -> 'DIVISAO-DE-INOVACAO-TECNOLOGICA'
    Quem quiser o nome bonito usa --rotulo, que vai para o espaco.
    """
    sem_acento = unicodedata.normalize("NFKD", nome).encode("ascii", "ignore").decode()
    limpo = re.sub(r"[^A-Za-z0-9]+", "-", sem_acento).strip("-").upper()
    return limpo or "GRUPO"


class Exo:
    """Cliente REST do eXo com sessao de formulario (nao ha token de API)."""

    def __init__(self, url, user, senha, dry=False):
        self.url, self.dry = url.rstrip("/"), dry
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE          # CA interna do projeto
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.HTTPSHandler(context=ctx))
        self._login(user, senha)

    def _raw(self, metodo, caminho, corpo=None, tipo="application/json"):
        u = caminho if caminho.startswith("http") else f"{self.url}{caminho}"
        dados = None
        if corpo is not None:
            dados = corpo.encode() if isinstance(corpo, str) else json.dumps(corpo).encode()
        req = urllib.request.Request(u, data=dados, method=metodo)
        if dados:
            req.add_header("Content-Type", tipo)
        try:
            with self.op.open(req, timeout=60) as r:
                return r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    def _login(self, user, senha):
        self._raw("GET", "/portal/login")
        corpo = urllib.parse.urlencode({"username": user, "password": senha})
        st, _ = self._raw("POST", "/portal/login?op=signin", corpo,
                          "application/x-www-form-urlencoded")
        st2, _ = self._raw("GET", "/portal/rest/v1/groups?limit=1")
        if st2 != 200:
            sys.exit(f"ERRO: login falhou para '{user}' (status {st}/{st2}). "
                     f"Confira EXO_ADMIN_USER/EXO_ADMIN_PASS e se a conta e' administradora.")

    def get(self, c):
        st, t = self._raw("GET", c)
        try:
            return st, json.loads(t)
        except Exception:
            return st, t

    def escreve(self, metodo, caminho, corpo=None, rotulo=""):
        if self.dry:
            print(f"    [dry-run] {metodo} {caminho}")
            return 200, {}
        st, t = self._raw(metodo, caminho, corpo)
        if st >= 400:
            print(f"    ! {rotulo or caminho} devolveu {st}: {t[:150]}")
        try:
            return st, json.loads(t)
        except Exception:
            return st, t


def grupos_existentes(exo):
    st, d = exo.get("/portal/rest/v1/groups?limit=500")
    return {g.get("id") for g in (d.get("entities", []) if isinstance(d, dict) else [])}


def espacos(exo):
    """{groupId -> espaco} de todos os espacos visiveis."""
    st, d = exo.get("/portal/rest/v1/social/spaces?limit=500")
    fora = {}
    for s in (d.get("spaces", []) if isinstance(d, dict) else []):
        fora[s.get("groupId")] = s
    return fora


def bindings_do_espaco(exo, space_id):
    st, d = exo.get(f"/portal/rest/v1/social/spaceGroupBindings/{space_id}")
    if st != 200 or not isinstance(d, (list, dict)):
        return []
    # A chave da resposta e' "groupSpaceBindings" (conferido na API, nao
    # presumido). Errar o nome aqui e' PERIGOSO: a funcao devolveria vazio e
    # o passo 4 sobrescreveria os vinculos existentes do espaco -- no Lobby
    # isso apagaria o /platform/users e tiraria todo mundo de la.
    itens = d if isinstance(d, list) else (
        d.get("groupSpaceBindings") or d.get("spaceGroupBindings") or d.get("entities") or [])
    return [b.get("group") or b.get("groupName") or b.get("groupId")
            for b in itens if isinstance(b, dict)]


def le_usuarios(valor):
    """Aceita CSV (com/sem cabecalho) ou lista separada por virgula."""
    if not valor:
        return []
    # Um caminho que PARECE arquivo (tem barra ou termina em .csv) mas nao
    # existe e' erro, nao lista de usuarios. Antes o script seguia calado e
    # ninguem era adicionado.
    parece_arquivo = valor.endswith(".csv") or "/" in valor or os.sep in valor
    if parece_arquivo and not os.path.isfile(valor):
        sys.exit(f"ERRO: arquivo de usuarios nao encontrado: {valor}")
    if os.path.isfile(valor):
        # Rotulos que identificam uma linha de cabecalho.
        CABECALHOS = ("username", "usuario", "usuário", "login", "user",
                      "nome", "name", "cargo", "email", "e-mail", "funcao", "função")
        with open(valor, newline="", encoding="utf-8-sig") as fh:
            texto = fh.read()
        if not texto.strip():
            return []
        linhas = [l for l in texto.splitlines() if l.strip()]

        # Delimitador: Excel em portugues salva com ';'. Escolhe o que mais
        # aparece na primeira linha; ',' desempata.
        cab = linhas[0]
        delim = ";" if cab.count(";") > cab.count(",") else ","

        # Cabecalho: decide pela PRIMEIRA CELULA, nao pelo csv.Sniffer.
        # O Sniffer erra nos dois sentidos em arquivos curtos -- num teste ele
        # deixou de ver 'username,cargo' como cabecalho e, noutro, tratou uma
        # lista pura de logins como se a primeira linha fosse titulo,
        # engolindo um usuario em silencio.
        primeira = (next(csv.reader([cab], delimiter=delim), [""])[0] or "").strip().lower()
        tem_cab = primeira in CABECALHOS

        achados, corpo = [], linhas[1:] if tem_cab else linhas
        col = 0
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
    return list(dict.fromkeys(x.strip() for x in valor.split(",") if x.strip()))


def ancestrais(caminho_grupo, mapa_espacos, exo):
    """Espacos de todos os niveis acima, do mais proximo ao lobby."""
    fora, partes = [], caminho_grupo.strip("/").split("/")
    for i in range(len(partes) - 1, 0, -1):
        gid = "/" + "/".join(partes[:i])
        esp = espaco_por_grupo_organizacional(exo, gid, mapa_espacos)
        if esp:
            fora.append(esp)
    return fora


def espaco_por_grupo_organizacional(exo, group_id, mapa=None):
    """Espaco DONO de um nivel da hierarquia.

    ATENCAO: nao basta 'ter o grupo nos bindings'. Pela cascata, o grupo do
    setor aparece nos bindings do setor, da divisao, da secretaria E do lobby
    -- procurar assim devolve o primeiro da iteracao, que costuma ser o lobby
    (bug real observado: espaco_por_grupo_organizacional('/SEMED') devolvia
    'Lobby Prefeitura' e a divisao acabava aninhada no lugar errado).

    O dono e' identificado pelo NOME: o espaco de /SEMED/DEINF chama-se DEINF.
    Casa por prettyName (normalizado pelo proprio eXo) e, como reforco, por
    displayName. So' se nao houver nome igual e' que cai no criterio de
    binding -- e ai' escolhendo o espaco com MENOS vinculos, que e' o mais
    especifico (o lobby e' sempre o que tem mais).
    """
    alvo = group_id.strip("/").split("/")[-1].strip().lower()
    mapa = mapa if mapa is not None else espacos(exo)
    for esp in mapa.values():
        pretty = (esp.get("prettyName") or "").strip().lower()
        display = (esp.get("displayName") or "").strip().lower()
        if alvo == pretty or alvo == display:
            esp = dict(esp); esp["_casou_por"] = "nome"
            return esp
    candidatos = []
    for esp in mapa.values():
        b = bindings_do_espaco(exo, esp.get("id"))
        if group_id in b:
            candidatos.append((len(b), esp))
    if candidatos:
        candidatos.sort(key=lambda x: x[0])
        esp = dict(candidatos[0][1]); esp["_casou_por"] = "binding"
        return esp
    return None


def main():
    p = argparse.ArgumentParser(
        description="Cria Secretaria/Divisao/Setor com grupo, espaco, aninhamento e cascata de bindings.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--tipo", required=True, choices=TIPOS)
    p.add_argument("--nome", required=True, help="Nome do nivel. Ex: SGCI, DTI, SINID")
    p.add_argument("--pai", default="", help="Caminho do grupo pai. Ex: /SGCI ou /SGCI/DTI")
    p.add_argument("--rotulo", default="", help="Nome de exibicao (padrao: --nome)")
    p.add_argument("--usuarios", default="", help="CSV ou lista separada por virgula")
    p.add_argument("--lobby", default="", help="groupId do espaco raiz (padrao: descobre o sem pai)")
    p.add_argument("--url", default=os.environ.get("EXO_URL", "https://192.168.1.59"))
    p.add_argument("--user", default=os.environ.get("EXO_ADMIN_USER", "root"))
    p.add_argument("--senha", default=os.environ.get("EXO_ADMIN_PASS", ""))
    p.add_argument("--remover", action="store_true",
                   help="DESFAZ o nivel: tira o grupo dos bindings de todos os espacos, "
                        "apaga o espaco e apaga o grupo. Pede confirmacao.")
    p.add_argument("--sim", action="store_true", help="responde sim a confirmacao do --remover")
    p.add_argument("--revincular", action="store_true",
                   help="tira do grupo e poe de volta quem ja' estava, forcando a propagacao "
                        "para os espacos (use quando a pessoa esta no grupo mas nao nos espacos)")
    p.add_argument("--dry-run", action="store_true", help="mostra o que faria, sem gravar")
    a = p.parse_args()

    if a.tipo in ("divisao", "setor") and not a.pai:
        sys.exit(f"ERRO: --tipo {a.tipo} exige --pai (ex: --pai /SGCI)")
    if not a.senha:
        sys.exit("ERRO: informe a senha em EXO_ADMIN_PASS ou --senha")

    nome_bruto = a.nome.strip().strip("/")
    nome = slug_grupo(nome_bruto)
    rotulo = a.rotulo or nome_bruto
    if nome != nome_bruto:
        print(f"   nome do grupo normalizado: '{nome_bruto}' -> '{nome}'")
    pai = ("/" + a.pai.strip("/")) if a.pai else ""
    caminho = f"{pai}/{nome}" if pai else f"/{nome}"

    exo = Exo(a.url, a.user, a.senha, a.dry_run)
    print(f"\n== {a.tipo.upper()}: {rotulo}  ->  grupo {caminho}")

    # ---------------- ROLLBACK / REMOCAO ----------------
    # Desfaz na ordem inversa da criacao. Tirar o grupo dos bindings ANTES de
    # apagar o espaco importa: quem entrou pelo vinculo sai junto, e quem ja'
    # era membro antes (IS_MEMBER_BEFORE=1) permanece -- o eXo nao desfaz o
    # que nao foi ele que fez.
    if a.remover:
        alvo_esp = espaco_por_grupo_organizacional(exo, caminho)
        if alvo_esp and alvo_esp.get("_casou_por") != "nome":
            print(f" -- ATENCAO: nao ha espaco com nome correspondente a '{caminho}'.")
            print(f"    O mais parecido e '{alvo_esp.get('displayName')}', achado por palpite --")
            print( "    ele NAO sera apagado. Sera' apenas retirado o vinculo do grupo.")
        print(f" -- REMOVER {caminho}"
              + (f" e o espaco '{alvo_esp.get('displayName')}' (id {alvo_esp.get('id')})"
                 if alvo_esp and alvo_esp.get("_casou_por") == "nome" else " (bindings apenas)"))
        if not a.sim and not a.dry_run:
            if input("    confirma? isto tira as pessoas dos espacos [digite SIM]: ").strip() != "SIM":
                sys.exit("    cancelado.")
        for esp in espacos(exo).values():
            atuais = bindings_do_espaco(exo, esp.get("id"))
            if caminho in atuais:
                restantes = [g for g in atuais if g != caminho]
                exo.escreve("POST",
                            f"/portal/rest/v1/social/spaceGroupBindings/saveGroupsSpaceBindings/{esp.get('id')}",
                            restantes, "retirar binding")
                print(f"    binding removido de '{esp.get('displayName')}' (restam {len(restantes)})")
        # TRAVA DE SEGURANCA. So apaga o espaco se ele foi identificado pelo
        # NOME. Quando o espaco ja nao existe, a busca cai no criterio de
        # "menos bindings" e devolve um espaco QUALQUER -- numa execucao real
        # devolveu 'Lobby Prefeitura' e o script tentou apaga-lo (o DELETE
        # falhou por acaso, mas os bindings do Lobby ja tinham sido retirados
        # e 14 pessoas ficaram fora do espaco). Palpite nao apaga nada.
        if alvo_esp and alvo_esp.get("_casou_por") == "nome":
            st_e, _ = exo.escreve("DELETE",
                                  f"/portal/rest/v1/social/spaces/{alvo_esp.get('id')}",
                                  None, "apagar espaco")
            if a.dry_run or st_e < 400:
                print(f"    espaco apagado: {alvo_esp.get('displayName')}")
            else:
                print(f"    espaco NAO apagado ({st_e}): {alvo_esp.get('displayName')}")
        elif alvo_esp:
            print(f"    espaco PRESERVADO: '{alvo_esp.get('displayName')}' nao casa pelo nome "
                  f"com '{caminho}'. Nao apago por palpite -- apague pela UI se for o caso.")
        else:
            print("    espaco nao encontrado (ja removido?)")
        # O caminho do grupo vai em QUERY STRING, nao no path -- conferido no
        # JS da propria UI. Com o caminho embutido a API devolve 404 e o
        # grupo fica para tras (aconteceu: espaco e bindings sumiam, o grupo
        # nao). O eXo tambem recusa apagar grupo com filho ("has at least one
        # child group"), entao a remocao vai do nivel mais fundo para cima.
        gq = urllib.parse.quote(caminho, safe="")
        st_g, resp_g = exo.escreve("DELETE", f"/portal/rest/v1/groups?groupId={gq}",
                                   None, "apagar grupo")
        if a.dry_run or st_g < 400:
            print(f"    grupo apagado: {caminho}")
        elif "child group" in str(resp_g):
            print(f"    grupo MANTIDO: {caminho} ainda tem subgrupos. "
                  f"Remova primeiro os niveis de baixo.")
        else:
            print(f"    grupo NAO apagado ({st_g}): {caminho}")
        print("\nOK (removido)." if not a.dry_run else "\nOK (dry-run).")
        return
    if a.dry_run:
        print("   (dry-run: nada sera gravado)")

    # 1) grupo -----------------------------------------------------------
    existentes = grupos_existentes(exo)
    if pai and pai not in existentes:
        sys.exit(f"ERRO: grupo pai '{pai}' nao existe. Crie o nivel de cima primeiro.")
    if caminho in existentes:
        print(f" 1. grupo    ja existe: {caminho}")
    else:
        corpo = {"groupName": nome, "label": rotulo, "description": f"{a.tipo} {rotulo}"}
        if pai:
            corpo["parentId"] = pai
        st, _ = exo.escreve("POST", "/portal/rest/v1/groups", corpo, "criar grupo")
        print(f" 1. grupo    criado: {caminho}" if st < 400 else f" 1. grupo    FALHOU ({st})")

    # 2) espaco ----------------------------------------------------------
    mapa = espacos(exo)
    esp = next((s for s in mapa.values()
                if (s.get("displayName") or "").strip().lower() == rotulo.strip().lower()), None)
    if esp:
        print(f" 2. espaco   ja existe: {esp.get('displayName')} (id {esp.get('id')})")
    else:
        st, esp = exo.escreve("POST", "/portal/rest/v1/social/spaces",
                              {"displayName": rotulo, "description": f"{a.tipo} {rotulo}",
                               "visibility": "private", "subscription": "closed",
                               "templateId": 3}, "criar espaco")
        if a.dry_run:
            esp = {"id": "<novo>"}
        elif st >= 400 or not isinstance(esp, dict) or not esp.get("id"):
            sys.exit(f"ERRO: nao consegui criar o espaco '{rotulo}'")
        print(f" 2. espaco   criado: {rotulo} (id {esp.get('id')})")
    space_id = esp.get("id")

    # 3) aninhamento -----------------------------------------------------
    #    o PUT do eXo SUBSTITUI o objeto: reenviar visibilidade e registro
    #    junto, senao ele zera o que nao vier no corpo.
    pai_esp = None
    if pai:
        pai_esp = espaco_por_grupo_organizacional(exo, pai)
    else:
        alvo = a.lobby or None
        pai_esp = (mapa.get(alvo) if alvo else
                   next((s for s in mapa.values() if not s.get("parentSpaceId")), None))
    if pai_esp and str(pai_esp.get("id")) != str(space_id):
        if str(esp.get("parentSpaceId") or "") == str(pai_esp.get("id")):
            print(f" 3. aninhar  ja esta dentro de '{pai_esp.get('displayName')}'")
        else:
            exo.escreve("PUT", f"/portal/rest/v1/social/spaces/{space_id}",
                        {"parentSpaceId": str(pai_esp.get("id")),
                         "visibility": "private", "subscription": "closed"}, "aninhar")
            print(f" 3. aninhar  dentro de '{pai_esp.get('displayName')}' (id {pai_esp.get('id')})")
    else:
        print(" 3. aninhar  nivel raiz: nada a fazer")

    # 4) cascata de bindings ---------------------------------------------
    #    o grupo novo entra no proprio espaco E em todos os ancestrais.
    alvos = [(space_id, rotulo)]
    for anc in ancestrais(caminho, espacos(exo), exo):
        alvos.append((anc.get("id"), anc.get("displayName")))
    if pai_esp and not pai:
        alvos.append((pai_esp.get("id"), pai_esp.get("displayName")))

    vistos = set()
    for sid, nome_esp in alvos:
        if not sid or sid in vistos:
            continue
        vistos.add(sid)
        atuais = [] if a.dry_run else bindings_do_espaco(exo, sid)
        if caminho in atuais:
            print(f" 4. binding  '{nome_esp}' ja sincroniza {caminho}")
            continue
        novos = sorted(set(atuais + [caminho]))
        if len(novos) < len(atuais) + 1:
            print(f" 4. binding  ABORTADO em '{nome_esp}': a lista nova ({len(novos)}) "
                  f"nao cresceu sobre a atual ({len(atuais)}) -- nao vou sobrescrever")
            continue
        exo.escreve("POST",
                    f"/portal/rest/v1/social/spaceGroupBindings/saveGroupsSpaceBindings/{sid}",
                    novos, f"binding {nome_esp}")
        print(f" 4. binding  '{nome_esp}' <- {caminho}   (total {len(novos)})")

    # 5) usuarios --------------------------------------------------------
    # ORDEM IMPORTA: os bindings (passo 4) tem de existir ANTES de o usuario
    # entrar no grupo. Quem entra depois e' propagado NA HORA pelo listener
    # SpaceBindingMembershipGroupEventListener (medido: 4 espacos em ~12s).
    # Quem ja' estava no grupo quando a cascata ainda nao existia NAO e'
    # reprocessado: o job QueueGroupSpaceBindingJob (cron 0 0/5 * * * ?) trata
    # a criacao do binding, mas registrou "Bound Users(0)" nesses casos.
    # Por isso o --revincular abaixo: tira do grupo e poe de volta, disparando
    # o listener.
    users = le_usuarios(a.usuarios)
    if not users:
        print(" 5. usuarios nenhum informado (use --usuarios lista.csv ou a,b,c)")
    else:
        # CONTA DESABILITADA NAO ENTRA EM ESPACO. O usuario fica no grupo, o
        # POST devolve 204, e nada acontece -- sem erro nenhum. Custou uma
        # investigacao inteira descobrir: vc.semlink/vc.comlink tinham
        # ENABLED=0 e por isso a cascata "nao funcionava" para eles, enquanto
        # tela.binding (ENABLED=1) entrava nos 4 espacos em ~12s.
        inexistentes, desabilitados = [], []
        for u in users:
            st, d = exo.get(f"/portal/rest/v1/social/users/{urllib.parse.quote(u)}")
            if st != 200 or not isinstance(d, dict):
                inexistentes.append(u)
            elif str(d.get("enabled")).lower() in ("false", "0"):
                desabilitados.append(u)
        if inexistentes:
            print(f"    AVISO: {len(inexistentes)} usuario(s) NAO EXISTEM e serao ignorados: "
                  f"{', '.join(inexistentes[:6])}")
            users = [u for u in users if u not in inexistentes]
        if desabilitados:
            print(f"    AVISO: {len(desabilitados)} conta(s) DESABILITADA(S): "
                  f"{', '.join(desabilitados[:6])}")
            print("           elas entram no grupo mas NAO nos espacos. Habilite em")
            print("           Administracao > Organizacao > Usuarios e rode com --revincular.")

        ja_no_grupo = set()
        for u in users:
            st, d = exo.get(f"/portal/rest/v1/users/{urllib.parse.quote(u)}/memberships")
            if st == 200 and isinstance(d, dict):
                if caminho in {e.get("groupId") for e in d.get("entities", [])}:
                    ja_no_grupo.add(u)

        if a.revincular and ja_no_grupo:
            for u in sorted(ja_no_grupo):
                mid = urllib.parse.quote(f"member:{u}:{caminho}", safe="")
                exo.escreve("DELETE",
                            f"/portal/rest/v1/groups/memberships?membershipId={mid}",
                            None, f"remover {u}")
            if not a.dry_run:
                time.sleep(5)
            print(f"    revincular: {len(ja_no_grupo)} usuario(s) removidos para reentrar")
            ja_no_grupo = set()

        novos = [u for u in users if u not in ja_no_grupo]
        if ja_no_grupo:
            print(f" 5. usuarios {len(ja_no_grupo)} ja estavam no grupo (ignorados): "
                  f"{', '.join(sorted(ja_no_grupo)[:6])}")
            print("             se eles nao aparecem nos espacos, rode de novo com --revincular")
        if novos:
            lote = [{"groupId": caminho, "membershipType": "member", "userName": u} for u in novos]
            st, _ = exo.escreve("POST",
                                "/portal/rest/v1/groups/memberships/bulk?membershipId=",
                                lote, "adicionar usuarios")
            if st < 400:
                print(f" 5. usuarios {len(novos)} adicionados a {caminho}: {', '.join(novos[:6])}"
                      + (" ..." if len(novos) > 6 else ""))
                print("             (entram em todos os niveis acima, na hora)")
            else:
                print(f" 5. usuarios FALHOU ({st})")
        elif not ja_no_grupo:
            print(" 5. usuarios nada a fazer")

    print("\nOK." if not a.dry_run else "\nOK (dry-run, nada gravado).")


if __name__ == "__main__":
    main()
