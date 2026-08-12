#!/usr/bin/env bash
# ===================================================================
# subir-ordenado.sh — inicialização da stack na ordem correta.
#
# É este script que o systemd executa no boot (unidade exo.service).
#
# POR QUE NÃO USAR `restart: unless-stopped`
# ------------------------------------------
# 1. PICO DE MEMÓRIA. Com política de reinício, o Docker sobe os 8
#    containers SIMULTANEAMENTE quando o daemon inicia. O pico de boot
#    somado é o que derrubou esta VM três vezes pelo OOM do hipervisor
#    (AUDIT [022], [025], [029]) — não o consumo em regime.
#
# 2. LOG SUJO. O nginx subiria junto com o Tomcat e responderia a
#    qualquer requisição com 502, registrando
#      [error] connect() failed (111: Connection refused) ... upstream
#    durante os 10-20 min do boot do eXo. O `depends_on: service_healthy`
#    do compose só é respeitado por `docker compose up`, NÃO pela política
#    de reinício do daemon.
#
# Aqui os serviços sobem um a um, cada um esperando o anterior ficar
# saudável, e o proxy só entra em cena quando existe o que servir.
#
# Uso:  ./scripts/subir-ordenado.sh          (ou: systemctl start exo)
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PISO_MB=800           # aborta se a RAM disponível cair abaixo disto
livre(){ free -m | awk '/Mem:/{print $7}'; }
log(){ printf '[exo] %s\n' "$*"; }

sobe(){
  local svc="$1" espera="${2:-300}" n fim st hs l
  l=$(livre)
  if [ "$l" -lt "$PISO_MB" ]; then
    log "ABORTANDO: RAM disponível ${l}MB abaixo do piso ${PISO_MB}MB"
    docker compose stop >/dev/null 2>&1
    exit 1
  fi
  log "subindo ${svc} (RAM livre: ${l}MB)"
  docker compose up -d --no-deps "$svc" >/dev/null 2>&1 || {
    log "FALHA ao iniciar ${svc}"; docker compose logs --tail 20 "$svc"; exit 1; }

  n=$(docker compose ps -q "$svc" | head -1)
  fim=$((SECONDS + espera))
  while [ $SECONDS -lt $fim ]; do
    st=$(docker inspect "$n" --format '{{.State.Status}}' 2>/dev/null)
    hs=$(docker inspect "$n" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}sem{{end}}' 2>/dev/null)
    [ "$st" = "exited" ] && { log "${svc} PAROU"; docker compose logs --tail 25 "$svc"; exit 1; }
    [ "$hs" = "healthy" ] && { log "${svc} saudável"; return 0; }
    [ "$hs" = "sem" ] && { sleep 5; log "${svc} iniciado"; return 0; }
    sleep 10
  done
  log "${svc} não ficou saudável em ${espera}s"
  return 1
}

log "início da subida ordenada — RAM livre: $(livre)MB"
sobe mailpit      90
sobe mysql        420
sobe es           300
sobe synapse-db   180
sobe synapse      300
sobe onlyoffice   420
# O eXo é o último dos pesados e o que mais demora (Tomcat + 48 webapps).
sobe exo          1500
# O proxy só depois: assim nenhuma requisição encontra o backend fora do ar.
sobe web          120
log "stack no ar — RAM livre: $(livre)MB"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
