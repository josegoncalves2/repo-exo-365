#!/usr/bin/env bash
# ===================================================================
# construir.sh — console de DLP (portlet no padrao da plataforma).
#
# O QUE MUDOU EM 2026-09-01. Este script compilava Java: o console era um
# GenericPortlet que montava o proprio HTML. Nao e' mais. O WAR agora nao tem
# NENHUMA classe — a classe do portlet vem da plataforma
# (GenericDispatchedViewPortlet), a tela e' Vue e o acesso ao servico passa
# pelo ConsoleDlpRest, que vive no WAR dlp-saida. E' a mesma forma do add-on
# nativo /opt/exo/webapps/dlp.
#
# Some, portanto, o estagio que compilava dentro da imagem, e com ele a
# dependencia de Docker para construir. Em troca entram QUATRO portoes que
# rodam no host, em segundos, e que precisam TODOS passar para o WAR existir:
#
#   1. SINTAXE      — node --check no modulo. Nao ha' npm neste host, entao
#                     nao ha' empacotador; o arquivo e' servido como esta'.
#                     Um erro de sintaxe deixaria a tela em branco sem uma
#                     linha no log do servidor.
#   2. IDIOMAS      — idiomas.py gera os bundles a partir das chaves REALMENTE
#                     usadas no JS e falha se alguma ficou sem traducao. Um
#                     rotulo faltante aparece como a propria chave na tela.
#   3. ROTAS        — conferir_rotas.js CARREGA o modulo no Node e chama cada
#                     metodo, anotando toda URL que o codigo monta em
#                     execucao; depois compara com as anotacoes @Path do
#                     recurso JAX-RS. Pega secao apontando para rota que
#                     ninguem atende — o defeito que existiu neste arquivo.
#   4. XML          — bem formado E sem "--" dentro de comentario. O kernel da
#                     eXo aborta o arranque com "--" em comentario; ja'
#                     derrubou este portal em 2026-08-27 e de novo hoje.
#
# NENHUM DESTES PORTOES PROVA QUE A TELA FUNCIONA. Eles provam que ela e'
# carregavel, traduzida, ligada a rotas existentes e que o portal sobe. Quem
# prova funcionamento e' tests/test_09_dlp_console.py, no navegador.
#
# Saida: extensao/dlp-console/target/pmo-dlp-console.war
# ===================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALVO="${AQUI}/target"
REST="${AQUI}/../dlp-saida/src/br/pmo/dlpsaida/exo/ConsoleDlpRest.java"

if [[ ! -f "${REST}" ]]; then
  echo "ERRO: ${REST} nao existe." >&2
  echo "      O console nao fala com o servico de DLP diretamente: quem fala e'" >&2
  echo "      o ConsoleDlpRest, no WAR dlp-saida. Sem ele nao ha' o que conferir." >&2
  exit 2
fi

# -------------------------------------------------------------------
# 1. Sintaxe do modulo
# -------------------------------------------------------------------
echo "==> 1/4 sintaxe do modulo"
node --check "${AQUI}/web/js/consoleDlp.bundle.js"
echo "    ok  web/js/consoleDlp.bundle.js"

# -------------------------------------------------------------------
# 2. Idiomas
# -------------------------------------------------------------------
echo "==> 2/4 idiomas"
python3 "${AQUI}/idiomas.py" "${AQUI}"

# -------------------------------------------------------------------
# 3. Contrato tela <-> REST
# -------------------------------------------------------------------
echo "==> 3/4 rotas"
node "${AQUI}/conferir_rotas.js" "${AQUI}" "${REST}" | tail -2

# -------------------------------------------------------------------
# 4. XML
# -------------------------------------------------------------------
echo "==> 4/4 XML"
python3 - "${AQUI}/web" <<'PY'
import pathlib, sys, xml.dom.minidom
raiz = pathlib.Path(sys.argv[1])
falhou = False
for arquivo in sorted(raiz.rglob("*.xml")):
    xml.dom.minidom.parse(str(arquivo))
    texto = arquivo.read_text(encoding="utf-8")
    # "--" dentro de comentario aborta o kernel da eXo no arranque.
    ini = 0
    while True:
        a = texto.find("<!--", ini)
        if a < 0:
            break
        b = texto.find("-->", a + 4)
        if b < 0:
            print("    ERRO %s: comentario nao fechado" % arquivo.name)
            falhou = True
            break
        if "--" in texto[a + 4:b]:
            print("    ERRO %s: '--' dentro de comentario" % arquivo.name)
            falhou = True
        ini = b + 3
    print("    ok  %s" % arquivo.relative_to(raiz))
if falhou:
    raise SystemExit(1)
PY

# -------------------------------------------------------------------
# 5. Empacotamento
# -------------------------------------------------------------------
echo "==> empacotando"
mkdir -p "${ALVO}"
rm -f "${ALVO}/pmo-dlp-console.war"
python3 - "${AQUI}/web" "${ALVO}/pmo-dlp-console.war" <<'PY'
import pathlib, sys, zipfile
raiz, destino = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
with zipfile.ZipFile(destino, "w", zipfile.ZIP_DEFLATED) as z:
    for arquivo in sorted(raiz.rglob("*")):
        if arquivo.is_file():
            z.write(arquivo, str(arquivo.relative_to(raiz)))
print("    %s" % destino)
PY

echo "==> conferindo o artefato"
python3 - "${ALVO}/pmo-dlp-console.war" <<'PY'
import sys, zipfile
esperados = [
    "META-INF/exo-conf/configuration.xml",
    "WEB-INF/web.xml",
    "WEB-INF/portlet.xml",
    "WEB-INF/gatein-resources.xml",
    "WEB-INF/conf/configuration.xml",
    "WEB-INF/conf/pmo-dlp-console/portal-configuration.xml",
    "WEB-INF/conf/pmo-dlp-console/sites/portal/administration/pages.xml",
    "WEB-INF/classes/locale/navigation/portal/administration_pt_BR.properties",
    "WEB-INF/classes/locale/portlet/dlpconsole/Console_pt_BR.properties",
    "html/consoleDlp.html",
    "js/consoleDlp.bundle.js",
    "skin/css/console-dlp.css",
]
with zipfile.ZipFile(sys.argv[1]) as z:
    dentro = set(z.namelist())
faltam = [e for e in esperados if e not in dentro]
if faltam:
    print("ERRO: faltam no WAR: %s" % ", ".join(faltam))
    raise SystemExit(1)
# O no' de menu proprio foi retirado; se voltar, e' regressao.
if any(n.endswith("administration/navigation.xml") for n in dentro):
    print("ERRO: navigation.xml voltou ao WAR. O no' de menu proprio foi")
    print("      retirado de proposito: o console vive no menu que ja' existe.")
    raise SystemExit(1)
# Um .class aqui significa que o portlet voltou a desenhar a propria pagina.
classes = [n for n in dentro if n.endswith(".class")]
if classes:
    print("ERRO: o WAR voltou a ter classe Java: %s" % ", ".join(classes[:5]))
    raise SystemExit(1)
print("    %d itens, nenhuma classe Java, sem no' de menu proprio" % len(dentro))
PY

echo
echo "PRONTO: ${ALVO}/pmo-dlp-console.war"
