#!/usr/bin/env bash
#
# Constroi o NUCLEO do DLP proprio (br.pmo.dlp) e roda as provas.
#
# O QUE ESTE SCRIPT E', E O QUE ELE NAO E'
# ----------------------------------------
# E' o portao de COMPILACAO: prova que o motor de deteccao continua correto
# antes de o binario entrar na imagem. Se uma asseveracao falhar, o script sai
# com codigo 1 e o build da imagem ABORTA -- em vez de embarcar um DLP que so'
# vai errar em producao, onde o erro custa documento na quarentena errada.
#
# NAO e' aceite de funcionalidade. Nenhuma linha aqui prova que a tela do
# portal funciona, que o upload e' interceptado ou que o administrador ve o
# achado. Isso e' verificacao de uso real, com navegador, e esta' descrita em
# LEIA-ME.md. Confundir as duas coisas e' como o antimalware desta instalacao
# passou meses "instalado" sem motor nenhum atras.
#
# Nao depende de rede, de Maven e de nenhum jar externo: so' javac e java.
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAIDA="${1:-${AQUI}/alvo}"
CLASSES="${SAIDA}/classes"
CLASSES_PROVA="${SAIDA}/classes-prova"
JAR="${SAIDA}/pmo-dlp-nucleo.jar"

echo "==> nucleo DLP: compilando"
rm -rf "${CLASSES}" "${CLASSES_PROVA}"
mkdir -p "${CLASSES}" "${CLASSES_PROVA}"

# -Xlint:all -Werror: aviso do compilador vira erro. Codigo que trata dado
# pessoal nao passa com "so' um aviso".
javac -Xlint:all -Werror \
      -encoding UTF-8 \
      -d "${CLASSES}" \
      $(find "${AQUI}/src" -name '*.java')

echo "==> nucleo DLP: compilando as provas"
javac -Xlint:all -Werror \
      -encoding UTF-8 \
      -classpath "${CLASSES}" \
      -d "${CLASSES_PROVA}" \
      $(find "${AQUI}/prova" -name '*.java')

echo "==> nucleo DLP: rodando as provas"
java -Dfile.encoding=UTF-8 \
     -classpath "${CLASSES}:${CLASSES_PROVA}" \
     br.pmo.dlp.Provas

echo "==> nucleo DLP: empacotando ${JAR}"
jar --create --file "${JAR}" -C "${CLASSES}" .

echo
echo "OK  ${JAR}"
echo "    $(find "${AQUI}/src" -name '*.java' | wc -l) fontes, $(jar --list --file "${JAR}" | grep -c '\.class$') classes"
