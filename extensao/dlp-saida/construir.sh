#!/usr/bin/env bash
# ===================================================================
# construir.sh — extensao de SAIDA do DLP.
#
# DOIS ESTAGIOS, pelo mesmo motivo das demais extensoes deste projeto:
#
#   1. NUCLEO (br.pmo.dlpsaida) — o cliente HTTP e o analisador de JSON nao
#      dependem de NADA da plataforma. Compilam e se PROVAM no host, com javac
#      e java. E' o portao: se uma asseveracao falhar, nada e' empacotado.
#
#   2. ADAPTADOR (br.pmo.dlpsaida.exo) — o filtro e o REST precisam das classes
#      do portal. Compilam DENTRO da imagem, contra o /opt/exo/lib REAL. Isso
#      elimina "compilou contra uma versao e rodou contra outra", que so'
#      aparece em producao como NoSuchMethodError.
#
# Saida: extensao/dlp-saida/target/dlp-saida.jar
# ===================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALVO="${AQUI}/target"
CLASSES_NUCLEO="${ALVO}/classes-nucleo"
CLASSES_PROVA="${ALVO}/classes-prova"
CLASSES_ADAPTADOR="${ALVO}/classes-adaptador"
IMAGEM="$(grep -E '^EXO_IMAGE=' "${AQUI}/../../.env" | cut -d= -f2)"
: "${IMAGEM:?EXO_IMAGE ausente no .env}"

# -------------------------------------------------------------------
# 1. NUCLEO + PROVAS (no host, JDK puro)
# -------------------------------------------------------------------
echo "==> nucleo de dlp-saida: compilando"
rm -rf "${CLASSES_NUCLEO}" "${CLASSES_PROVA}" "${CLASSES_ADAPTADOR}"
mkdir -p "${CLASSES_NUCLEO}" "${CLASSES_PROVA}" "${CLASSES_ADAPTADOR}"

# -Xlint:all -Werror: aviso do compilador vira erro. Codigo que decide se dado
# pessoal sai da prefeitura nao tem direito a "e' so' um aviso".
javac -Xlint:all -Werror -encoding UTF-8 \
      -d "${CLASSES_NUCLEO}" \
      $(find "${AQUI}/src/br/pmo/dlpsaida" -maxdepth 1 -name '*.java')

echo "==> nucleo de dlp-saida: compilando as provas"
javac -Xlint:all -Werror -encoding UTF-8 \
      -classpath "${CLASSES_NUCLEO}" \
      -d "${CLASSES_PROVA}" \
      $(find "${AQUI}/prova" -name '*.java')

echo "==> nucleo de dlp-saida: rodando as provas"
java -Dfile.encoding=UTF-8 \
     -classpath "${CLASSES_NUCLEO}:${CLASSES_PROVA}" \
     br.pmo.dlpsaida.Provas

# -------------------------------------------------------------------
# 2. ADAPTADOR (dentro da imagem, contra o classpath real)
# -------------------------------------------------------------------
echo "==> adaptador eXo: compilando dentro de ${IMAGEM}"
# Compila e EMPACOTA dentro do container, escrevendo em /tmp e so' depois
# copiando o jar para o volume. O container roda como o usuario `exo`, que nao
# e' dono do diretorio do host: escrever as classes direto no volume falhava
# com "could not create parent directories". Mesmo padrao das demais
# extensoes deste projeto.
mkdir -p "${ALVO}"
# --user com o uid/gid do host: o `exo` da imagem e' 999:1001 e nao consegue
# escrever num diretorio do host que pertence ao operador. Mesmo padrao das
# demais extensoes. Sem isto o `jar` falha com AccessDeniedException no fim,
# depois de compilar tudo -- o pior momento para descobrir.
docker run --rm --user "$(id -u):$(id -g)" --memory=768m \
  -v "${AQUI}/src:/fonte:ro" \
  -v "${AQUI}/conf:/conf:ro" \
  -v "${CLASSES_NUCLEO}:/nucleo:ro" \
  -v "${ALVO}:/ext-target" \
  --entrypoint /bin/bash \
  "${IMAGEM}" -c '
    set -euo pipefail
    CP="$(find /opt/exo/lib -name "*.jar" | tr "\n" ":")"
    echo "    $(find /opt/exo/lib -name "*.jar" | wc -l) jars no classpath"
    rm -rf /tmp/montagem && mkdir -p /tmp/montagem/conf/portal
    cp -r /nucleo/. /tmp/montagem/
    # Aqui NAO se usa -Werror, pela mesma razao das demais extensoes deste
    # projeto: o classpath real traz manifesto citando jar inexistente
    # ("bad path element") e APIs depreciadas da propria plataforma -- 204
    # avisos que nao dependem deste codigo. Falhar por eles esconderia os que
    # importam. O portao de qualidade fica no NUCLEO, que e codigo nosso e
    # compila com -Xlint:all -Werror no estagio 1.
    javac -encoding UTF-8 \
          -classpath "/nucleo:${CP}" \
          -d /tmp/montagem \
          $(find /fonte/br/pmo/dlpsaida/exo -name "*.java")
    cp /conf/configuration.xml /tmp/montagem/conf/portal/configuration.xml
    cd /tmp/montagem && jar --create --file /ext-target/dlp-saida.jar .
    chmod 0644 /ext-target/dlp-saida.jar
  '

# -------------------------------------------------------------------
# 3. EMPACOTAMENTO
# -------------------------------------------------------------------
echo "==> conferindo o XML embarcado"
# O kernel do eXo aborta o arranque se o XML tiver "--" dentro de comentario.
# Ja' aconteceu neste projeto (2026-08-27) e derrubou o portal inteiro.
python3 - "${AQUI}/conf/configuration.xml" <<'PY'
import sys, xml.dom.minidom
xml.dom.minidom.parse(sys.argv[1])
print("    ok configuration.xml bem formado")
PY

echo "==> conferindo o artefato"
# Conferencia com python3 e nao `unzip`: o host nao tem unzip instalado, e um
# portao que depende de binario ausente falha por falta de ferramenta em vez de
# por defeito -- que e' pior do que nao ter portao, porque parece portao.
python3 - "${ALVO}/dlp-saida.jar" <<'PY'
import sys, zipfile
esperados = ["conf/portal/configuration.xml",
             "br/pmo/dlpsaida/ClienteDlp.class",
             "br/pmo/dlpsaida/exo/FiltroSaidaDlp.class",
             "br/pmo/dlpsaida/exo/ConsoleDlpRest.class"]
nomes = set(zipfile.ZipFile(sys.argv[1]).namelist())
faltando = [e for e in esperados if e not in nomes]
for e in esperados:
    print(("    ok " if e in nomes else "    FALTA ") + e)
if faltando:
    sys.exit(1)
print(f"    {len(nomes)} entradas no jar")
PY

echo "==> pronto: ${ALVO}/dlp-saida.jar"
ls -la "${ALVO}/dlp-saida.jar"
