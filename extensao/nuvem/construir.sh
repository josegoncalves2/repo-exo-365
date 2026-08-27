#!/usr/bin/env bash
# ===================================================================
# construir.sh — constroi a extensao Cloud Drive Nextcloud/WebDAV.
#
# Sao DOIS estagios, e a ordem importa:
#
#   1. NUCLEO (br.pmo.nuvem) — JDK puro, zero dependencia externa. Compila e
#      se PROVA no host, com javac e java e mais nada (o teste de HTTP usa
#      com.sun.net.httpserver, que vive no proprio JDK). E' o portao: se uma
#      asseveracao falhar, o script sai com codigo 1 e NADA e' empacotado.
#
#   2. ADAPTADOR (br.pmo.nuvem.exo) — precisa das classes da plataforma.
#      Compila DENTRO da imagem, contra o /opt/exo/lib REAL. O host tem JDK 17
#      e os jars da plataforma sao Java 21: o javac do host nem le.
#
# O container de compilacao e' DESCARTAVEL e tem memoria limitada: nao encosta
# no exo-app.
#
# ISTO NAO E' ACEITE DE FUNCIONALIDADE. Nenhuma linha aqui prova que a tela do
# portal funciona. O aceite e' humano, com mouse e teclado.
#
# Saida: extensao/nuvem/target/nuvem.jar
# ===================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGEM="${EXO_IMAGE:-exo-pmo:7.2.1-addons2}"
SAIDA="${AQUI}/target"
CLASSES_NUCLEO="${SAIDA}/classes-nucleo"
CLASSES_PROVA="${SAIDA}/classes-prova"

mkdir -p "${SAIDA}"

# -------------------------------------------------------------------
# 0. PORTAO DE XML — mal formado nao pode atravessar em silencio.
# -------------------------------------------------------------------
echo "==> conferindo o XML de registro"
python3 - "${AQUI}/conf/configuration.xml" <<'PY'
import sys, xml.dom.minidom
caminho = sys.argv[1]
try:
    xml.dom.minidom.parse(caminho)
except Exception as e:
    print(f"XML INVALIDO em {caminho}: {e}", file=sys.stderr)
    sys.exit(1)
print("    XML bem formado")
PY

# -------------------------------------------------------------------
# 1. NUCLEO + PROVAS (no host, JDK puro)
# -------------------------------------------------------------------
echo "==> nucleo nuvem: compilando"
rm -rf "${CLASSES_NUCLEO}" "${CLASSES_PROVA}"
mkdir -p "${CLASSES_NUCLEO}" "${CLASSES_PROVA}"

javac -Xlint:all -Werror \
      -encoding UTF-8 \
      -d "${CLASSES_NUCLEO}" \
      $(find "${AQUI}/src/br/pmo/nuvem" -maxdepth 1 -name '*.java')

echo "==> nucleo nuvem: compilando as provas"
javac -Xlint:all -Werror \
      -encoding UTF-8 \
      -classpath "${CLASSES_NUCLEO}" \
      -d "${CLASSES_PROVA}" \
      $(find "${AQUI}/prova" -name '*.java')

echo "==> nucleo nuvem: rodando as provas"
# Sem -e/|| true: se sair != 0, o `set -e` aborta e NADA e' empacotado.
java -Dfile.encoding=UTF-8 \
     -classpath "${CLASSES_NUCLEO}:${CLASSES_PROVA}" \
     br.pmo.nuvem.Provas

# -------------------------------------------------------------------
# 2. ADAPTADOR (dentro da imagem, contra o classpath real)
# -------------------------------------------------------------------
echo "==> adaptador eXo: compilando dentro de ${IMAGEM}"
docker run --rm --user root --memory=768m \
  -v "${AQUI}:/ext" \
  -w /ext \
  "${IMAGEM}" \
  sh -euc '
    CP=$(find /opt/exo/lib -name "*.jar" | tr "\n" ":")
    echo "    $(find /opt/exo/lib -name "*.jar" | wc -l) jars no classpath"

    rm -rf /tmp/classes && mkdir -p /tmp/classes

    # Aqui NAO se usa -Werror: as APIs internas da eXo geram avisos de
    # depreciacao que nao dependem deste codigo. O portao de qualidade fica
    # no nucleo, que e codigo nosso.
    javac -encoding UTF-8 \
          -classpath "$CP" \
          -d /tmp/classes \
          $(find src -name "*.java")

    mkdir -p /tmp/classes/conf/portal
    cp conf/configuration.xml /tmp/classes/conf/portal/configuration.xml
    cd /tmp/classes && jar --create --file /ext/target/nuvem.jar .
    chmod 0644 /ext/target/nuvem.jar
  '

# -------------------------------------------------------------------
# 3. CONFERENCIA DO ARTEFATO
# -------------------------------------------------------------------
echo "==> conferindo o artefato"
for exigido in \
  "conf/portal/configuration.xml" \
  "br/pmo/nuvem/exo/NextcloudDriveConnector.class" \
  "br/pmo/nuvem/WebDavCliente.class" \
  "br/pmo/nuvem/CaminhoNuvem.class"
do
  if ! docker run --rm -v "${SAIDA}:/t:ro" "${IMAGEM}" \
        sh -c "unzip -l /t/nuvem.jar 2>/dev/null | grep -q '${exigido}'"; then
    echo "ERRO: ${exigido} ausente do jar" >&2
    exit 1
  fi
  echo "    ok ${exigido}"
done

echo "==> pronto: ${SAIDA}/nuvem.jar"
ls -l "${SAIDA}/nuvem.jar"
