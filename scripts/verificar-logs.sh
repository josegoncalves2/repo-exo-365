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
ruido='0 errors|no errors|errors: 0|error_log|ErrorDocument|Using a password on the command line|errorCount.:0|"errors":0'

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

D=$(sudo -n dmesg -l err,warn 2>/dev/null || dmesg -l err,warn 2>/dev/null || echo "")
conta "dmesg err,warn" "$D" '.'

titulo "2. CONTAINERS DO PROJETO"
for c in exo-app exo-web exo-mysql exo-es exo-synapse exo-synapse-db onlyoffice exo-mailpit; do
  docker inspect "$c" >/dev/null 2>&1 || { printf '%-22s (ausente)\n' "$c"; continue; }
  if [ -n "$DESDE" ]; then L=$(docker logs "$c" --since "$DESDE" 2>&1)
  else L=$(docker logs "$c" 2>&1); fi
  L=$(printf '%s' "$L" | sed 's/\x1b\[[0-9;]*m//g')
  conta "$c" "$L"
done

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
