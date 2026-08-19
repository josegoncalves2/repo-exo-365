#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Deriva correcoes de traducao A PARTIR DOS PROPRIOS ARQUIVOS DA IMAGEM OFICIAL.

REGRA DO ARQUIVO: nenhuma string de interface e' escrita aqui. Todo texto que
chega ao usuario e' COPIADO de um bundle de traducao da eXo presente na imagem,
e a chave de origem fica registrada no cabecalho do arquivo gerado. Isso troca
"tradutor humano chutando texto" por "reaproveitar a traducao oficial", e vale
para os 39 idiomas de uma vez, nao so' para pt-BR.

Corrige dois defeitos de empacotamento da eXo 7.2.1:

1. locale/navigation/portal/myworkspace_<loc>.properties
   32 dos 38 idiomas nao-ingleses vem com o arquivo IDENTICO ao ingles, ou seja
   sem traducao nenhuma (pt_BR entre eles). Alem disso a chave
   portal.myworkspace.notes NAO EXISTE em nenhum dos 39, embora o
   navigation.xml oficial declare o no <notes> com label #{portal.myworkspace.notes}
   -> o menu exibe o literal da chave.
   Correcao: para cada idioma, cada chave AINDA IGUAL AO INGLES e' substituida
   pelo valor que a eXo ja' traduziu para a MESMA PAGINA no bundle
   locale.navigation.portal.global. O vinculo nao e' arbitrario: e' o
   page-reference do proprio navigation.xml (ex.: o no <drive> aponta para
   portal::global::drives, cuja etiqueta e' portal.global.drives).
   Idiomas que JA' possuem traducao propria (ar, aro, de, es_ES, fr, sq) nao
   sao tocados.

2. locale/portlet/Portlets_pt_BR.properties
   activity.composer.link=Postar em {0} -- o tradutor pt-BR inseriu um {0} numa
   chave que o codigo invoca SEM argumento, entao a tela mostra "Postar em {0}"
   cru. A chave que de fato recebe parametro e' outra (activity.composer.link.space).
   Correcao: usar o valor que a PROPRIA eXo escreve em Portlets_pt_PT.properties
   para essa mesma chave, que esta correto e sem placeholder.

Saida: arquivos .properties com nao-ASCII escapado em \\uXXXX (ASCII puro),
formato aceito tanto por leitura ISO-8859-1 quanto UTF-8 -- e' o mesmo formato
que a eXo usa nos global_<loc>.properties da imagem.
"""

import os
import re
import sys
import zipfile

WEBAPPS = "/opt/exo/webapps"
NAV = "WEB-INF/classes/locale/navigation/portal/"
PORTLET = "WEB-INF/classes/locale/portlet/"

# destino  ->  (chave de origem no bundle global, page-reference que justifica)
MAPA_MYWORKSPACE = {
    "portal.myworkspace.drive":   ("portal.global.drives",                 "portal::global::drives"),
    "portal.myworkspace.tasks":   ("addon.task.navigation.node.label",     "portal::global::tasks"),
    "portal.myworkspace.agenda":  ("addon.agenda.navigation.node.label",   "portal::global::agenda"),
    "portal.myworkspace.notes":   ("portal.global.notes",                  "portal::global::notes"),
    "portal.myworkspace.more":    ("portal.global.more",                   "no 'more' do site global"),
    "portal.myworkspace.process": ("portal.global.processes",              "portal::global::processes"),
    "portal.myworkspace.content": ("news.navigation.node.label",           "portal::global::news"),
    "portal.myworkspace.team":    ("portal.global.myteam",                 "portal::global::myteam"),
}

# chaves sem equivalente no bundle global -- ficam como a imagem oficial entrega
SEM_EQUIVALENTE = ("portal.myworkspace.dashboard",
                   "portal.myworkspace.name",
                   "portal.myworkspace.description")

IDIOMA_OBRIGATORIO = "pt_BR"   # idioma da instalacao: falta aqui reprova o build

# --- "See more": a eXo deixou 3 chaves em INGLES nos 39 idiomas ---------------
# Origem: layout.war / SiteNavigation_<loc>.properties / siteNavigation.label.seeMore,
# cujo valor em ingles e' exatamente "See more" e que a eXo traduziu em 36 de 36
# idiomas. Alvos (todos com valor ingles "See more"):
SEE_MORE_ORIGEM = ("layout.war", "SiteNavigation", "siteNavigation.label.seeMore")
SEE_MORE_ALVOS = [
    ("agenda.war",          "Agenda",         ["agenda.timeline.seeMore",
                                               "agenda.timeline.seeMore.tooltip"]),
    ("task-management.war", "taskManagement", ["label.seeAll"]),
]
PORTLET_DIR = "WEB-INF/classes/locale/portlet/"

# --- unica string escrita por nos, e o porque -------------------------------
# portal.myworkspace.dashboard nao tem origem mecanica: "Dashboard" aparece como
# valor ingles EXATO em uma unica chave da imagem inteira (ela mesma), traduzida
# em apenas 5 dos 36 idiomas. A unica chave proxima e' "Project Dashboard"
# (twitterConnector.admin.label.projectDashboard, 35/36), mas extrair so' o
# substantivo muda de posicao em cada lingua -- "Painel do Projeto" -> Painel,
# "Tableau de bord du projet" -> Tableau de bord, "Projekt Dashboard" -> Dashboard --
# e isso nao e' mecanico. Preencher 39 idiomas no chute produziria lixo em lingua
# que ninguem aqui sabe conferir.
# Preenchemos SO' o pt-BR, e nao no chute: a propria eXo, em pt-BR, verte
# "Dashboard" como "Painel" em duas outras chaves da imagem
#   twitterConnector.admin.label.projectDashboard = Painel do Projeto
#   layout.portletInstance.AnalyticsDashboardBreadcrumb.description = Painel de Analises...
# Os 5 idiomas que a eXo ja' traduziu (ar, aro, de, es_ES, fr, sq) ficam intactos;
# os demais continuam "Dashboard", como a imagem oficial entrega.
# REGRA (corrigida em 2026-08-19 apos revisao): procedencia e' PREFERENCIA, nao
# pode ganhar da CORRECAO. Um rotulo rastreavel e errado e' pior que um rotulo
# escrito e certo. Onde o texto oficial da eXo descreve mal o que a pagina faz,
# ele e' substituido aqui, e o motivo e' o CONTEUDO MEDIDO da pagina de destino.
#
# Medicao feita em navegador, no proprio portal, item a item do menu:
#   drive   -> a pagina mostra "Recents", "Shared with me", "Personal Documents",
#              "No document yet" .......... e' o app de DOCUMENTOS.
#              portal.global.drives em pt-BR = "Unidades", que e' traducao
#              literal de disk drive. ERRADO no contexto.
#   notes   -> a pagina mostra "My Notes", "Adicionar conteudo para esta nota",
#              "Ou criar uma nova nota" ... e' o app de NOTAS.
#              portal.global.notes em pt-BR = "Wiki". ERRADO: o proprio app se
#              descreve como nota. (RESSALVA CONHECIDA: os menus de ESPACO usam
#              portal.global.spaceNotes, que segue "Wiki"; alinhar os dois exige
#              mexer no bundle global, que vem de 13 war.)
#   content -> a pagina mostra "Artigos publicados", "Meus artigos publicados",
#              "Rascunhos" ................ sao ARTIGOS.
#              news.navigation.node.label em pt-BR = "Novidades". Impreciso.
#   process -> a pagina mostra "Processo", "Minhas solicitacoes".
#              portal.global.processes em pt-BR = "processos", em MINUSCULA.
#              Erro de caixa num item de menu.
#   team    -> organograma da equipe. "Minha equipe" e' longo para menu.
#
# Estas substituicoes valem SO' para pt-BR, que e' o idioma desta instalacao e o
# unico que da' para conferir aqui. Os outros 38 idiomas seguem com o texto
# oficial da eXo, que nao e' pior do que a imagem entrega.
ESCRITAS_POR_NOS = {
    ("myworkspace", "pt_BR", "portal.myworkspace.dashboard"): (
        "Painel",
        "sem origem oficial; a eXo em pt-BR verte Dashboard como Painel em "
        "twitterConnector.admin.label.projectDashboard"),
    ("myworkspace", "pt_BR", "portal.myworkspace.drive"): (
        "Documentos",
        "pagina de destino e' o app de documentos (Recents / Shared with me / "
        "Personal Documents); 'Unidades' traduz disk drive, nao serve"),
    ("myworkspace", "pt_BR", "portal.myworkspace.notes"): (
        "Notas",
        "pagina de destino e' o app de notas (My Notes / criar uma nova nota); "
        "'Wiki' e' o termo legado da eXo em pt-BR"),
    ("myworkspace", "pt_BR", "portal.myworkspace.content"): (
        "Artigos",
        "pagina de destino lista 'Artigos publicados' e 'Rascunhos'"),
    ("myworkspace", "pt_BR", "portal.myworkspace.process"): (
        "Processos",
        "portal.global.processes em pt-BR vem em minuscula ('processos')"),
    ("myworkspace", "pt_BR", "portal.myworkspace.team"): (
        "Equipe",
        "'Minha equipe' e' longo para item de menu"),
    ("myworkspace", "pt_BR", "portal.myworkspace.name"): (
        "Meu Espa\u00e7o",
        "a imagem oficial entrega 'My Workspace' em pt-BR, sem traducao"),
    ("myworkspace", "pt_BR", "portal.myworkspace.description"): (
        "Meu espa\u00e7o de trabalho pessoal",
        "a imagem oficial entrega 'My personal workspace' em pt-BR"),
}


def desescapa(txt):
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), txt)


def escapa(valor):
    return "".join(c if ord(c) < 128 else "\\u%04x" % ord(c) for c in valor)


def le_properties(dados):
    """Parser de .properties suficiente para estes bundles (sem continuacao de linha)."""
    try:
        txt = dados.decode("utf-8")
    except UnicodeDecodeError:
        txt = dados.decode("iso-8859-1")
    fora = {}
    for linha in desescapa(txt).splitlines():
        linha = linha.strip()
        if not linha or linha.startswith("#") or linha.startswith("!") or "=" not in linha:
            continue
        chave, valor = linha.split("=", 1)
        fora[chave.strip()] = valor.strip()
    return fora


def wars():
    return sorted(os.path.join(WEBAPPS, f) for f in os.listdir(WEBAPPS) if f.endswith(".war"))


def bundle_global(locale):
    """Uniao dos global_<loc>.properties de TODOS os .war -- e' assim que a eXo
    monta locale.navigation.portal.global (13 wars contribuem na 7.2.1)."""
    junto, fontes = {}, []
    for w in wars():
        nome = NAV + "global_%s.properties" % locale
        try:
            with zipfile.ZipFile(w) as z:
                if nome in z.namelist():
                    junto.update(le_properties(z.read(nome)))
                    fontes.append(os.path.basename(w))
        except zipfile.BadZipFile:
            continue
    return junto, fontes


def gera_myworkspace(destino):
    with zipfile.ZipFile(os.path.join(WEBAPPS, "digital-workplace.war")) as z:
        nomes = [n for n in z.namelist()
                 if n.startswith(NAV + "myworkspace_") and n.endswith(".properties")]
        oficiais = {n[len(NAV) + len("myworkspace_"):-len(".properties")]: le_properties(z.read(n))
                    for n in nomes}
    ingles = oficiais["en"]
    os.makedirs(destino, exist_ok=True)

    relatorio, gerados = [], 0
    for locale in sorted(oficiais):
        oficial = oficiais[locale]
        glob, fontes = bundle_global(locale)
        novo, trocas, escritas = dict(oficial), [], []

        for chave, (origem, motivo) in MAPA_MYWORKSPACE.items():
            atual = oficial.get(chave)
            ausente = atual is None
            # so' mexe onde a eXo NAO traduziu: chave ausente, ou valor ainda
            # identico ao ingles num idioma que nao e' o ingles
            nao_traduzida = ausente or (locale != "en" and atual == ingles.get(chave))
            if not nao_traduzida:
                continue
            valor = glob.get(origem)
            if valor is None:
                relatorio.append("      %-7s %-30s SEM ORIGEM (%s ausente no global)" % (locale, chave, origem))
                if locale == IDIOMA_OBRIGATORIO:
                    sys.exit("ERRO: %s nao tem origem para %s (%s) -- build reprovado"
                             % (locale, chave, origem))
                continue
            if valor == atual:
                continue
            novo[chave] = valor
            trocas.append((chave, origem, motivo, atual, valor))

        # chaves sem origem mecanica, escritas por nos e identificadas como tal
        for (bundle, loc, chave), (valor, porque) in ESCRITAS_POR_NOS.items():
            if bundle != "myworkspace" or loc != locale:
                continue
            valor = valor.encode("ascii").decode("unicode_escape") if "\\u" in valor else valor
            if novo.get(chave) == valor:
                continue
            escritas.append((chave, oficial.get(chave), valor, porque))
            novo[chave] = valor

        if not trocas and not escritas:
            continue

        linhas = [
            "# GERADO EM TEMPO DE BUILD por conf/i18n/derivar-traducoes.py.",
            "# NAO EDITAR A MAO -- e' reescrito a cada `docker build`.",
            "#",
            "# Base: myworkspace_%s.properties da imagem oficial eXo 7.2.1." % locale,
            "# Cada linha abaixo marcada com [derivada] teve o valor COPIADO do bundle",
            "# locale.navigation.portal.global do mesmo idioma, porque a imagem oficial",
            "# entrega a chave ausente ou ainda em ingles.",
            "# Bundles global_%s lidos de: %s" % (locale, ", ".join(fontes) or "(nenhum)"),
            "#",
        ]
        for chave, origem, motivo, antes, depois in trocas:
            linhas.append("#   %s   [derivada]" % chave)
            linhas.append("#       origem : %s   (%s)" % (origem, motivo))
            linhas.append("#       oficial: %s" % ("<ausente>" if antes is None else escapa(antes)))
            linhas.append("#       usado  : %s" % escapa(depois))
        for chave, antes, depois, porque in escritas:
            linhas.append("#   %s   [ESCRITA POR NOS, sem origem oficial]" % chave)
            linhas.append("#       motivo : %s" % porque)
            linhas.append("#       oficial: %s" % ("<ausente>" if antes is None else escapa(antes)))
            linhas.append("#       usado  : %s" % escapa(depois))
        linhas.append("#")

        # ATENCAO: em .properties tudo o que vem depois do "=" ate' o fim da linha
        # e' VALOR -- um "#" ali NAO abre comentario. A marca [derivada] tem de
        # ficar em linha propria, ANTES da chave.
        derivadas = {c for c, _, _, _, _ in trocas}
        proprias = {c for c, _, _, _ in escritas}
        for chave in sorted(novo):
            if chave in derivadas:
                linhas.append("# [derivada de %s]" % MAPA_MYWORKSPACE[chave][0])
            elif chave in proprias:
                linhas.append("# [ESCRITA POR NOS -- sem origem oficial na imagem]")
            linhas.append("%s=%s" % (chave, escapa(novo[chave])))

        with open(os.path.join(destino, "myworkspace_%s.properties" % locale), "w",
                  encoding="ascii") as fh:
            fh.write("\n".join(linhas) + "\n")
        gerados += 1
        marca = "" if not escritas else "  + %d escrita(s) por nos" % len(escritas)
        relatorio.append("      %-7s %d chave(s) derivada(s): %s%s"
                         % (locale, len(trocas),
                            ", ".join(sorted(c.split(".")[-1] for c in derivadas)) or "-", marca))

    print("   myworkspace: %d de %d idiomas corrigidos" % (gerados, len(oficiais)))
    for l in relatorio:
        print(l)
    return gerados


def gera_portlets(destino):
    with zipfile.ZipFile(os.path.join(WEBAPPS, "social.war")) as z:
        bruto_br = z.read(PORTLET + "Portlets_pt_BR.properties")
        pt_pt = le_properties(z.read(PORTLET + "Portlets_pt_PT.properties"))
        pt_br = le_properties(bruto_br)

    chave = "activity.composer.link"
    atual, origem = pt_br.get(chave), pt_pt.get(chave)
    if atual is None or origem is None:
        sys.exit("ERRO: %s ausente em pt_BR ou pt_PT -- build reprovado" % chave)
    if "{0}" not in atual:
        sys.exit("ERRO: %s do pt_BR nao tem mais o placeholder {0}; a imagem mudou, "
                 "reconferir se a correcao ainda e' necessaria -- build reprovado" % chave)
    if "{0}" in origem:
        sys.exit("ERRO: o pt_PT tambem traz {0} em %s; nao ha' origem limpa -- build reprovado" % chave)

    texto = bruto_br.decode("utf-8")
    padrao = re.compile(r"^%s=.*$" % re.escape(chave), re.MULTILINE)
    if len(padrao.findall(texto)) != 1:
        sys.exit("ERRO: %s aparece %d vezes no pt_BR -- build reprovado"
                 % (chave, len(padrao.findall(texto))))
    texto = padrao.sub(lambda _m: "%s=%s" % (chave, origem), texto, count=1)

    cabecalho = (
        "# GERADO EM TEMPO DE BUILD por conf/i18n/derivar-traducoes.py.\n"
        "# NAO EDITAR A MAO -- e' reescrito a cada `docker build`.\n"
        "#\n"
        "# Base: Portlets_pt_BR.properties da imagem oficial eXo 7.2.1, com UMA linha\n"
        "# alterada, cujo valor foi COPIADO de Portlets_pt_PT.properties da mesma imagem:\n"
        "#   %s\n"
        "#       oficial pt_BR: %s      (o {0} nunca e' preenchido -- aparece cru na tela)\n"
        "#       usado, do pt_PT: %s\n"
        "#\n" % (chave, atual, origem)
    )
    os.makedirs(destino, exist_ok=True)
    with open(os.path.join(destino, "Portlets_pt_BR.properties"), "w", encoding="utf-8") as fh:
        fh.write(cabecalho + texto)
    print("   Portlets_pt_BR: %s" % chave)
    print("      oficial pt_BR: %s" % atual)
    print("      usado (pt_PT): %s" % origem)
    return 1


def substitui_linha(bruto, chave, valor):
    """Troca UMA linha `chave=...` preservando o resto do arquivo byte a byte.
    Mais seguro que reserializar um bundle de centenas de chaves."""
    txt = bruto.decode("utf-8")
    padrao = re.compile(r"^%s=.*$" % re.escape(chave), re.MULTILINE)
    achados = padrao.findall(txt)
    if len(achados) != 1:
        sys.exit("ERRO: %s aparece %d vezes -- build reprovado" % (chave, len(achados)))
    # substituicao por FUNCAO: com string, o re interpretaria \uXXXX como escape
    return padrao.sub(lambda _m: "%s=%s" % (chave, escapa(valor)), txt, count=1).encode("utf-8")


def gera_see_more(destino_raiz):
    """A eXo deixou 3 chaves com o texto ingles 'See more' nos 39 idiomas.
    Copia, por idioma, o valor que ela JA' traduziu para a mesma frase em
    layout.war/SiteNavigation (36 de 36 idiomas)."""
    war_o, base_o, chave_o = SEE_MORE_ORIGEM
    with zipfile.ZipFile(os.path.join(WEBAPPS, war_o)) as z:
        origem = {}
        for n in z.namelist():
            m = re.match(r".*/%s_(.+)\.properties$" % re.escape(base_o), n)
            if m:
                origem[m.group(1)] = le_properties(z.read(n)).get(chave_o)
    if not origem.get("en"):
        sys.exit("ERRO: origem %s nao encontrada em %s -- build reprovado" % (chave_o, war_o))
    ingles_origem = origem["en"]

    total = 0
    for war, base, chaves in SEE_MORE_ALVOS:
        app = war[:-4]
        destino = os.path.join(destino_raiz, app, PORTLET_DIR)
        os.makedirs(destino, exist_ok=True)
        with zipfile.ZipFile(os.path.join(WEBAPPS, war)) as z:
            arquivos = {}
            for n in z.namelist():
                m = re.match(r".*/%s_(.+)\.properties$" % re.escape(base), n)
                if m:
                    arquivos[m.group(1)] = n
            en = le_properties(z.read(arquivos["en"]))
            for locale, nome in sorted(arquivos.items()):
                if locale == "en":
                    continue
                bruto = z.read(nome)
                atual = le_properties(bruto)
                mudou = []
                for chave in chaves:
                    if chave not in atual:
                        continue
                    # so' mexe onde a eXo NAO traduziu (valor ainda igual ao ingles)
                    if atual[chave] != en.get(chave):
                        continue
                    valor = origem.get(locale)
                    if not valor or valor == ingles_origem:
                        continue          # origem tambem sem traducao nesse idioma
                    bruto = substitui_linha(bruto, chave, valor)
                    mudou.append((chave, atual[chave], valor))
                if not mudou:
                    continue
                cab = ["# GERADO EM TEMPO DE BUILD por conf/i18n/derivar-traducoes.py.",
                       "# NAO EDITAR A MAO -- e' reescrito a cada `docker build`.",
                       "# Base: %s_%s.properties da imagem oficial eXo 7.2.1, com as linhas" % (base, locale),
                       "# abaixo trocadas pelo valor que a propria eXo ja' traduziu em",
                       "#   %s / %s_%s.properties / %s" % (war_o, base_o, locale, chave_o),
                       "#"]
                for chave, antes, depois in mudou:
                    cab.append("#   %s" % chave)
                    cab.append("#       oficial: %s" % escapa(antes))
                    cab.append("#       usado  : %s" % escapa(depois))
                cab.append("#")
                with open(os.path.join(destino, "%s_%s.properties" % (base, locale)), "wb") as fh:
                    fh.write(("\n".join(cab) + "\n").encode("utf-8") + bruto)
                total += 1
                print("      %-20s %-7s %s" % (base, locale,
                      ", ".join("%s -> %s" % (c.split(".")[-1], d) for c, _, d in mudou)))
    print("   see-more: %d arquivo(s) corrigido(s)" % total)
    if total == 0:
        sys.exit("ERRO: nenhuma correcao de 'See more' aplicada -- build reprovado")
    return total


if __name__ == "__main__":
    raiz = sys.argv[1]
    print("== derivando traducoes a partir dos bundles da imagem oficial ==")
    n = gera_myworkspace(os.path.join(raiz, "digital-workplace", NAV))
    m = gera_portlets(os.path.join(raiz, "social", PORTLET))
    k = gera_see_more(raiz)
    if n == 0 or m == 0 or k == 0:
        sys.exit("ERRO: nada foi gerado -- build reprovado")
    print("== OK: %d navegacao + %d portlet social + %d see-more ==" % (n, m, k))
