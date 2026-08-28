#!/usr/bin/env bash
# ===================================================================
# construir.sh — constroi a extensao DLP por padrao.
#
# Sao DOIS estagios, e a ordem importa:
#
#   1. NUCLEO (br.pmo.transferencia) — JDK puro, zero dependencia externa. Compila e se
#      PROVA no host, com javac e java e mais nada. E' o portao: se uma
#      asseveracao falhar, o script sai com codigo 1 e NADA e' empacotado.
#      Motivo: DLP com regressao silenciosa e' pior do que DLP nenhum, porque
#      passa a sensacao de protecao.
#
#   2. ADAPTADOR (br.pmo.transferencia.exo) — precisa das classes da plataforma. Compila
#      DENTRO da imagem, contra o /opt/exo/lib REAL (567 jars, versao exata em
#      execucao). Isso elimina a classe de defeito "compilou contra uma versao
#      e rodou contra outra", que so' aparece em producao como NoSuchMethodError.
#      O host tem JDK 17 e os jars da plataforma sao classes major 65 (Java 21):
#      o javac do host nem consegue LE-LOS.
#
# O container de compilacao e' DESCARTAVEL e tem memoria limitada: nao encosta
# no exo-app, que opera a 92% do seu limite.
#
# ISTO NAO E' ACEITE DE FUNCIONALIDADE. Nenhuma linha aqui prova que a tela do
# portal funciona. O aceite e' humano, com mouse e teclado.
#
# Saida: extensao/transferencia/target/transferencia.jar
# ===================================================================
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGEM="${EXO_IMAGE:-exo-pmo:7.2.1-addons2}"
SAIDA="${AQUI}/target"
CLASSES_NUCLEO="${SAIDA}/classes-nucleo"
CLASSES_PROVA="${SAIDA}/classes-prova"

mkdir -p "${SAIDA}"

# -------------------------------------------------------------------
# 0. PORTAO DE XML — antes de tudo, porque ja' custou um deploy.
#
# Em 2026-08-27 este jar foi embarcado com um "--" dentro de um comentario
# XML. O kernel recusou o arquivo inteiro:
#   "Error Parsing file jar:...!/conf/portal/configuration.xml"
#   "A string '--' nao e' permitida nos comentarios"
# O conector nao registrou, e nada no build acusou. Um XML mal formado NAO
# pode voltar a atravessar este script em silencio.
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
echo "==> nucleo de transferencia: compilando"
rm -rf "${CLASSES_NUCLEO}" "${CLASSES_PROVA}"
mkdir -p "${CLASSES_NUCLEO}" "${CLASSES_PROVA}"

# -Xlint:all -Werror: aviso do compilador vira erro. Codigo que trata dado
# pessoal nao tem direito a "e' so' um aviso".
# DEPENDENCIA DECLARADA: Regra usa br.pmo.mfa.Zona para as condicoes de rede.
# Reaproveitar em vez de copiar a classe evita duas implementacoes de CIDR
# divergindo com o tempo ; e aritmetica de rede duplicada e' onde a copia
# esquecida vira furo. Os dois jars convivem no mesmo classpath do portal.
DEP_ZONA="${AQUI}/../mfa-zona/target/classes-nucleo"
if [ ! -d "${DEP_ZONA}" ]; then
  echo "==> dependencia ausente: construindo o nucleo de mfa-zona antes"
  ( cd "${AQUI}/../mfa-zona" && ./construir.sh >/dev/null )
fi

javac -Xlint:all -Werror \
      -encoding UTF-8 \
      -classpath "${DEP_ZONA}" \
      -d "${CLASSES_NUCLEO}" \
      $(find "${AQUI}/src/br/pmo/transferencia" -maxdepth 1 -name '*.java')

echo "==> nucleo de transferencia: compilando as provas"
javac -Xlint:all -Werror \
      -encoding UTF-8 \
      -classpath "${CLASSES_NUCLEO}:${DEP_ZONA}" \
      -d "${CLASSES_PROVA}" \
      $(find "${AQUI}/prova" -name '*.java')

echo "==> nucleo de transferencia: rodando as provas"
# Sem -e/|| true: se sair != 0, o `set -e` aborta e NADA e' empacotado.
java -Dfile.encoding=UTF-8 \
     -classpath "${CLASSES_NUCLEO}:${CLASSES_PROVA}:${DEP_ZONA}" \
     br.pmo.transferencia.Provas

# -------------------------------------------------------------------
# 2. ADAPTADOR (dentro da imagem, contra o classpath real)
# -------------------------------------------------------------------
echo "==> adaptador eXo: compilando dentro de ${IMAGEM}"
# O container de compilacao sobe da IMAGEM LIMPA e nao enxerga os jars que o
# docker-compose monta em /opt/exo/lib no container de producao. A dependencia
# br.pmo.mfa.Zona precisa, portanto, ser montada aqui explicitamente ; sem
# isso o estagio do adaptador falha com "package br.pmo.mfa does not exist",
# mesmo com o nucleo compilando sem erro no host.
DEP_JAR="${AQUI}/../mfa-zona/target/mfa-zona.jar"
if [ ! -f "${DEP_JAR}" ]; then
  echo "ERRO: dependencia ${DEP_JAR} ausente; construa extensao/mfa-zona antes" >&2
  exit 1
fi

docker run --rm --user "$(id -u):$(id -g)" --memory=768m \
  -v "${AQUI}:/ext" \
  -v "${DEP_JAR}:/dep/mfa-zona.jar:ro" \
  -w /ext \
  "${IMAGEM}" \
  sh -euc '
    CP=$(find /opt/exo/lib -name "*.jar" | tr "\n" ":")/dep/mfa-zona.jar
    echo "    $(find /opt/exo/lib -name "*.jar" | wc -l) jars no classpath"

    rm -rf /tmp/classes && mkdir -p /tmp/classes

    # Aqui NAO se usa -Werror: as APIs internas da eXo geram avisos de
    # depreciacao que nao dependem deste codigo (SearchResult, por exemplo,
    # e deprecated na propria plataforma e ainda assim e o tipo que
    # treatItem exige). Falhar por causa deles esconderia os avisos que
    # importam. O portao de qualidade fica no nucleo, que e codigo nosso.
    javac -encoding UTF-8 \
          -classpath "$CP" \
          -d /tmp/classes \
          $(find src -name "*.java")

    mkdir -p /tmp/classes/conf/portal
    cp conf/configuration.xml /tmp/classes/conf/portal/configuration.xml
    cd /tmp/classes && jar --create --file /ext/target/transferencia.jar .
    chmod 0644 /ext/target/transferencia.jar
  '

# -------------------------------------------------------------------
# 3. CONFERENCIA DO ARTEFATO — o jar tem mesmo o que precisa ter?
# -------------------------------------------------------------------
echo "==> conferindo o artefato"
for exigido in \
  "conf/portal/configuration.xml" \
  "br/pmo/transferencia/exo/FiltroTransferencia.class" \
  "br/pmo/transferencia/Regra.class" \
  "br/pmo/transferencia/PoliticaTransferencia.class"
do
  if ! docker run --rm -v "${SAIDA}:/t:ro" "${IMAGEM}" \
        sh -c "unzip -l /t/transferencia.jar 2>/dev/null | grep -q '${exigido}'"; then
    echo "ERRO: ${exigido} ausente do jar" >&2
    exit 1
  fi
  echo "    ok ${exigido}"
done

echo "==> pronto: ${SAIDA}/transferencia.jar"
ls -l "${SAIDA}/transferencia.jar"
