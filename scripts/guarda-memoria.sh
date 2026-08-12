#!/usr/bin/env bash
# ===================================================================
# guarda-memoria.sh — vigia a RAM e para a stack ANTES do hipervisor
# matar a VM.
#
# Contexto: em 2026-08-11 esta VM foi morta duas vezes pelo OOM killer
# do host Proxmox (15:43 e ~16:20). O OOM foi do HIPERVISOR, não da VM:
# sem balloon driver, toda página tocada pelo guest fica retida no host.
# Não há como impedir isso de dentro da VM — só há como evitar chegar lá.
#
# Este vigia roda continuamente e, se a RAM disponível cruzar o piso,
# para a stack por conta própria. Perder a stack é reversível; perder a
# VM inteira, no meio de uma gravação em banco, não é.
#
# Uso:  ./scripts/guarda-memoria.sh [piso_MB] [intervalo_s]
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PISO="${1:-1000}"
INTERVALO="${2:-15}"
LOG="${ROOT}/evidence/guarda-memoria.log"
mkdir -p "${ROOT}/evidence"

echo "[guarda] vigiando a memoria: piso=${PISO}MB, intervalo=${INTERVALO}s" | tee -a "$LOG"

pico=0
while true; do
  livre=$(free -m | awk '/Mem:/{print $7}')
  usado=$(free -m | awk '/Mem:/{print $3}')
  [ "$usado" -gt "$pico" ] && pico=$usado

  ts=$(date '+%H:%M:%S')
  if [ "$livre" -lt "$PISO" ]; then
    {
      echo "[$ts] ALERTA: RAM disponivel ${livre}MB < piso ${PISO}MB. PARANDO A STACK."
      docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'
    } | tee -a "$LOG"
    docker compose stop 2>&1 | tee -a "$LOG"
    echo "[$ts] stack parada preventivamente. pico de uso observado: ${pico}MB" | tee -a "$LOG"
    exit 1
  fi

  # registra so quando muda de forma relevante, para nao inflar o log
  echo "[$ts] livre=${livre}MB usado=${usado}MB pico=${pico}MB" >> "$LOG"
  sleep "$INTERVALO"
done
