#!/usr/bin/env bash
# ===================================================================
# subir-seguro.sh — sobe a stack SEM produzir pico de memória.
#
# Por que existe:
#   O hipervisor Proxmox está sem RAM física sobrando e as VMs não têm
#   balloon driver. Nessa condição, toda página que o guest TOCA fica
#   retida no host e nunca é devolvida. Portanto o que derruba o host
#   não é o consumo médio da stack — é o PICO instantâneo durante o
#   boot, quando todos os serviços inicializam ao mesmo tempo.
#
#   Este script sobe um serviço por vez, espera cada um estabilizar e
#   ABORTA se a RAM livre cair abaixo do piso, em vez de seguir até a
#   VM ser morta pelo hipervisor.
#
# Uso:  ./scripts/subir-seguro.sh
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PISO_MB=1200          # aborta se a RAM disponivel ficar abaixo disto
ESTABILIZA=20         # segundos de acomodacao apos cada servico

livre(){ free -m | awk '/Mem:/{print $7}'; }
usado(){ free -m | awk '/Mem:/{print $3}'; }

barra(){ printf '%s\n' "-------------------------------------------------------------------"; }

checa_piso(){
  local l; l=$(livre)
  if [ "$l" -lt "$PISO_MB" ]; then
    echo
    echo "!!! ABORTANDO: RAM disponivel ${l}MB, abaixo do piso de ${PISO_MB}MB."
    echo "!!! Parando a stack para NAO derrubar a VM."
    docker compose stop
    echo "!!! Stack parada. Nenhum dado perdido."
    exit 1
  fi
}

sobe(){
  local svc="$1" espera="${2:-300}"
  barra
  printf '>> %-14s   RAM antes: %sMB livre\n' "$svc" "$(livre)"
  checa_piso
  docker compose up -d --no-deps "$svc" >/dev/null 2>&1 || {
    echo "   FALHA ao iniciar $svc"; docker compose logs --tail 20 "$svc"; exit 1; }

  # aguarda healthy quando o servico define healthcheck
  local nome fim
  nome=$(docker compose ps -q "$svc" | head -1)
  fim=$((SECONDS + espera))
  while [ $SECONDS -lt $fim ]; do
    local st hs
    st=$(docker inspect "$nome" --format '{{.State.Status}}' 2>/dev/null)
    hs=$(docker inspect "$nome" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}sem{{end}}' 2>/dev/null)
    [ "$st" = "exited" ] && { echo "   $svc PAROU"; docker compose logs --tail 25 "$svc"; exit 1; }
    [ "$hs" = "healthy" ] && break
    [ "$hs" = "sem" ] && { sleep 5; break; }
    checa_piso
    sleep 10
  done
  sleep "$ESTABILIZA"
  printf '   %-14s pronto.  RAM: %sMB livre / %sMB em uso\n' "$svc" "$(livre)" "$(usado)"
}

echo "==================================================================="
echo " SUBIDA SEGURA — um servico por vez, com trava de memoria"
echo " Piso de aborto: ${PISO_MB}MB livres"
echo " RAM inicial:    $(livre)MB livres de $(free -m | awk '/Mem:/{print $2}')MB"
echo "==================================================================="

# Ordem deliberada: os leves e as dependencias primeiro; o eXo por ultimo,
# porque e o que mais aloca e so faz sentido com tudo o mais ja estavel.
sobe mailpit      60
sobe mysql        300
sobe es           300
sobe synapse-db   180
sobe synapse      300
sobe onlyoffice   400
sobe exo          60     # nao espera ficar healthy: o boot leva ~20 min
sobe web          90

barra
echo " Todos os servicos iniciados."
echo " RAM: $(livre)MB livres / $(usado)MB em uso"
echo
echo " O eXo ainda esta iniciando (primeiro boot ~15-20 min)."
echo " Acompanhe com:  ./scripts/guarda-memoria.sh   (em outro terminal)"
barra
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
