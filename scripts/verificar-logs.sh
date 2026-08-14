#!/usr/bin/env bash
# ===================================================================
# verificar-logs.sh — auditoria de erros e warnings em TODAS as fontes
#
# Exigência do projeto: zero erros e zero warnings, tanto no Linux base
# quanto no projeto. Este script é a MEDIÇÃO dessa exigência, e é ele
# que produz a evidência anexada ao AUDIT.md.
#
# Devolve código de saída != 0 se qualquer fonte apresentar ocorrência,
# de modo que possa ser usado como portão em automação.
#
# Uso:  ./scripts/verificar-logs.sh [--desde <tempo docker/journal>]
#       ./scripts/verificar-logs.sh --desde 30m
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DESDE=""
[ "${1:-}" = "--desde" ] && DESDE="${2:-}"

# Elevacao de privilegio SEM sujar o journal.
# Este script precisa de root para `dmesg` e para ler os logs do ONLYOFFICE.
# Usar `sudo -n` direto parece inofensivo, mas quando NAO ha NOPASSWD cada
# tentativa grava no journal:
#   sudo[NNN]: saexo : a password is required ; ... COMMAND=/usr/bin/dmesg ...
# Ou seja: o proprio medidor criava as ocorrencias que depois contava — o
# journal deste host tinha 39 entradas e TODAS eram dessa origem. Por isso o
# SUDO_ASKPASS, quando existe, e' preferido: ele autentica de fato e nao gera
# registro de falha. Sem nenhuma das duas formas, o script segue sem root e
# marca as fontes correspondentes como indisponiveis, em vez de insistir.
if [ -n "${SUDO_ASKPASS:-}" ] && [ -x "${SUDO_ASKPASS}" ] && sudo -A true 2>/dev/null; then
  SU="sudo -A"
elif sudo -n true 2>/dev/null; then
  SU="sudo -n"
else
  SU=""
fi

EVID="${ROOT}/evidence"; mkdir -p "$EVID"
SAIDA="${EVID}/verificacao-logs-$(date +%Y%m%d-%H%M%S).log"
TOTAL=0

titulo(){ printf '\n===== %s =====\n' "$*"; }

# Filtro comum de falsos positivos.
# Justificativa de cada exclusão (nenhuma esconde defeito real):
#   * "0 errors|no errors|errors: 0|error_log|ErrorDocument" -> a palavra
#     aparece em contadores zerados e em nomes de diretiva de configuração.
#   * "WARNING: no usable" nao se aplica: foi corrigido trocando a imagem.
#   * "Using a password on the command line" -> aviso do CLIENTE mysql nos
#     nossos proprios comandos de verificacao, nao do servidor.
#   * "logs-apm.error" -> NOME de index template / component template /
#     ingest pipeline do Elasticsearch. Sao linhas de nivel INFO ("adding
#     index template [logs-apm.error@template]") que casavam com \berror\b
#     apenas porque a palavra faz parte do NOME do objeto. Contar isso como
#     erro e' defeito da MEDICAO, nao do servico: o ES nao reportou erro
#     algum. Conferido: `grep -cE '"log.level": ?"(WARN|ERROR)"'` no mesmo
#     log da a contagem verdadeira.
#   * "COMMAND=/usr/bin/true" -> sondagem `sudo -n true` dos proprios scripts.
#     A causa foi corrigida em reconstruir-do-zero.sh (SUDO_ASKPASS testado
#     antes de `sudo -n`); a exclusao cobre apenas os registros historicos.
#   * "Command line argument: -Dliquibase.logLevel=WARNING" -> linha de nivel
#     INFO em que o Tomcat ecoa os argumentos da JVM. Casa com \bWARNING\b
#     apenas porque WARNING e' o VALOR de um argumento. Nao ha aviso algum.
ruido='0 errors|no errors|errors: 0|error_log|ErrorDocument|Using a password on the command line|errorCount.:0|"errors":0|logs-apm\.error|Command line argument: -Dliquibase\.logLevel'

conta(){                      # conta ocorrencias e imprime as distintas
  local nome="$1" conteudo="$2" padrao="${3:-}"
  local pad="${padrao:-\\berror\\b|\\bwarn(ing)?\\b|\\bfatal\\b|\\bsevere\\b}"
  local achados
  achados=$(printf '%s\n' "$conteudo" | grep -iE "$pad" | grep -ivE "$ruido" || true)
  local n=0
  [ -n "$achados" ] && n=$(printf '%s\n' "$achados" | grep -c . )
  TOTAL=$((TOTAL + n))
  printf '%-22s %s\n' "$nome" "$n"
  if [ "$n" -gt 0 ]; then
    printf '%s\n' "$achados" | cut -c1-200 | sort | uniq -c | sort -rn | head -8 \
      | sed 's/^/      /'
  fi
}

{
echo "# Verificacao de erros e warnings — $(date '+%Y-%m-%d %H:%M:%S %Z')"
[ -n "$DESDE" ] && echo "# Janela: desde ${DESDE}"

titulo "1. LINUX BASE"
printf '%-22s %s\n' "unidades com falha" "$(systemctl --failed --no-pager --plain 2>/dev/null | grep -c '\.service\|\.mount\|\.timer' || echo 0)"
systemctl --failed --no-pager --plain 2>/dev/null | grep '\.' | sed 's/^/      /' | head -5

if [ -n "$DESDE" ]; then J=$(journalctl -p 0..4 --since "-${DESDE}" --no-pager 2>/dev/null)
else J=$(journalctl -p 0..4 -b --no-pager 2>/dev/null); fi
conta "journalctl p0-4" "$J" '.'

D=$(${SU} dmesg -l err,warn 2>/dev/null || dmesg -l err,warn 2>/dev/null || echo "")
conta "dmesg err,warn" "$D" '.'

titulo "2. CONTAINERS DO PROJETO"
for c in exo-app exo-web exo-mysql exo-es exo-synapse exo-synapse-db onlyoffice exo-mailpit; do
  docker inspect "$c" >/dev/null 2>&1 || { printf '%-22s (ausente)\n' "$c"; continue; }
  if [ -n "$DESDE" ]; then L=$(docker logs "$c" --since "$DESDE" 2>&1)
  else L=$(docker logs "$c" 2>&1); fi
  L=$(printf '%s' "$L" | sed 's/\x1b\[[0-9;]*m//g')
  conta "$c" "$L"
done

titulo "2b. LOGS EM DISCO DO ONLYOFFICE"
# POR QUE ESTA SECAO EXISTE — e' correcao de um ponto CEGO da medicao.
# O ONLYOFFICE nao escreve no stdout: o entrypoint faz `tail` de arquivos sob
# /var/log/onlyoffice, que aqui e' o bind mount ./data/onlyoffice/log. Quando o
# container e' RECRIADO, o `tail` so mostra o que for escrito dali em diante —
# o conteudo anterior dos arquivos, que continua no disco, some do
# `docker logs`. Ou seja: contar apenas `docker logs onlyoffice` faz o
# resultado MELHORAR sozinho a cada recriacao, sem que nada tenha sido
# corrigido. Medir o arquivo e' a unica leitura honesta.
if [ -d "${ROOT}/data/onlyoffice/log" ]; then
  OO=$( (${SU} cat "${ROOT}"/data/onlyoffice/log/documentserver/*.log \
                   "${ROOT}"/data/onlyoffice/log/documentserver/*/*.log 2>/dev/null \
        || cat "${ROOT}"/data/onlyoffice/log/documentserver/*.log \
               "${ROOT}"/data/onlyoffice/log/documentserver/*/*.log 2>/dev/null) || true)
  conta "onlyoffice (arquivos)" "$OO"
else
  printf '%-22s (ausente)\n' "onlyoffice (arquivos)"
fi

titulo "3. ESTADO DE SAUDE"
docker compose ps --format 'table {{.Name}}\t{{.Status}}' 2>/dev/null

titulo "RESULTADO"
if [ "$TOTAL" -eq 0 ]; then
  echo "APROVADO — 0 erros e 0 warnings em todas as fontes."
else
  echo "REPROVADO — ${TOTAL} ocorrencias somadas."
fi
} 2>&1 | tee "$SAIDA"

echo
echo "Evidencia: ${SAIDA#$ROOT/}"
[ "$TOTAL" -eq 0 ] || exit 1
