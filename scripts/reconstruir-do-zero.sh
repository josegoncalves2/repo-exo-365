#!/usr/bin/env bash
# ===================================================================
# reconstruir-do-zero.sh — instalação limpa, com log SEM erros e SEM warnings
#
# POR QUE PRÉ-INICIALIZAR OS BANCOS
# ---------------------------------
# Os entrypoints oficiais do MySQL e do PostgreSQL criam o diretório de
# dados no primeiro início, e essa criação emite mensagens que ficam para
# sempre no log do container de produção:
#
#   MySQL      [Warning] [MY-010453] root@localhost is created with an
#              empty password ! ... --initialize-insecure
#   PostgreSQL FATAL: the database system is shutting down
#              (o servidor temporário do initdb sendo encerrado)
#
# Nenhuma das duas é defeito, mas ambas são ruído permanente. Medido: com
# o diretório de dados JÁ criado, o mesmo container reinicia com
# 0 erros e 0 warnings.
#
# Este script então cria os diretórios de dados em containers DESCARTÁVEIS,
# cujo log é jogado fora, e só depois sobe a stack de produção — que nasce
# com o log limpo.
#
# Uso:  ./scripts/reconstruir-do-zero.sh
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
set -a; source .env; set +a

log(){ printf '\n\033[1m[reconstrucao] %s\033[0m\n' "$*"; }
falha(){ printf '\n[reconstrucao] FALHA: %s\n' "$*" >&2; exit 1; }
livre(){ free -m | awk '/Mem:/{print $7}'; }

# Elevação de privilégio em execução NÃO interativa.
# Sem isto o script falha em silêncio: o `sudo` sem terminal responde
# "a terminal is required to read the password", o `rm -rf data` não apaga
# nada e a "reconstrucao do zero" na verdade reaproveita o estado antigo —
# exatamente o que aconteceu na primeira tentativa.
if sudo -n true 2>/dev/null; then
  SUDO="sudo -n"
elif [ -n "${SUDO_ASKPASS:-}" ] && [ -x "${SUDO_ASKPASS}" ] && sudo -A true 2>/dev/null; then
  SUDO="sudo -A"
else
  echo "ERRO: este script precisa de sudo sem interacao." >&2
  echo "  Configure NOPASSWD para este usuario, ou exporte SUDO_ASKPASS" >&2
  echo "  apontando para um executavel que imprima a senha. Ex.:" >&2
  echo "    printf '#!/bin/sh\\nprintf %%s \"SUA_SENHA\"\\n' > /tmp/ap.sh" >&2
  echo "    chmod 700 /tmp/ap.sh && export SUDO_ASKPASS=/tmp/ap.sh" >&2
  exit 1
fi
echo "[reconstrucao] elevacao de privilegio: ${SUDO}"

# -------------------------------------------------------------------
log "1/8 — parando a stack e removendo os containers"
docker compose down --remove-orphans 2>/dev/null   # SEM -v: ver AUDIT [024]
docker rm -f exo-app exo-web exo-mysql exo-es onlyoffice exo-mailpit \
             exo-synapse exo-synapse-db 2>/dev/null | tr '\n' ' '; echo

# -------------------------------------------------------------------
log "2/8 — apagando o estado anterior (reconstrucao do zero)"
$SUDO rm -rf data
mkdir -p data/mysql data/mysql-run data/elasticsearch data/exo data/exo-codec \
         data/exo-logs data/onlyoffice/data data/onlyoffice/log \
         data/onlyoffice/cache data/mailpit data/synapse data/synapse-db
# UIDs conferidos dentro de cada imagem
$SUDO chown -R 999:999   data/mysql data/mysql-run data/synapse-db
$SUDO chown -R 1000:0    data/elasticsearch
$SUDO chown -R 999:1001  data/exo data/exo-codec data/exo-logs
$SUDO chown -R 104:107   data/onlyoffice
$SUDO chmod 750          data/mysql-run          # evita o aviso MY-011810
$SUDO chmod 777          data/synapse data/mailpit
echo "  arvore ./data recriada"

# -------------------------------------------------------------------
log "3/8 — certificados do MySQL (PKI de 2 niveis)"
./scripts/gerar-certificados-mysql.sh >/dev/null 2>&1 || falha "geracao de certificados"
echo "  conf/mysql-certs/ regenerado"

# -------------------------------------------------------------------
log "4/8 — PRE-INICIALIZACAO do MySQL (container descartavel)"
docker run -d --name exo-mysql-init \
  -e MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD}" \
  -e MYSQL_DATABASE="${EXO_DB_NAME}" \
  -e MYSQL_USER="${EXO_DB_USER}" \
  -e MYSQL_PASSWORD="${EXO_DB_PASSWORD}" \
  -v "${ROOT}/data/mysql:/var/lib/mysql" \
  -v "${ROOT}/data/mysql-run:/var/lib/mysql-run" \
  -v "${ROOT}/conf/mysql.cnf:/etc/mysql/conf.d/exo.cnf:ro" \
  -v "${ROOT}/conf/mysql-certs:/etc/mysql/certs:ro" \
  "${MYSQL_IMAGE}" --mysql-native-password=OFF >/dev/null 2>&1 \
  || falha "nao foi possivel iniciar o MySQL de inicializacao"
for i in $(seq 1 60); do
  docker exec exo-mysql-init mysqladmin ping -h 127.0.0.1 -u root \
      -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1 && break
  sleep 5
done
docker exec exo-mysql-init mysqladmin ping -h 127.0.0.1 -u root \
    -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1 \
  || { docker logs exo-mysql-init 2>&1 | tail -20; falha "MySQL nao inicializou"; }
docker stop exo-mysql-init >/dev/null 2>&1
docker rm   exo-mysql-init >/dev/null 2>&1   # o log de inicializacao vai junto
echo "  datadir do MySQL criado; container de inicializacao descartado"

# -------------------------------------------------------------------
log "5/8 — PRE-INICIALIZACAO do PostgreSQL (container descartavel)"
docker run -d --name exo-pg-init \
  -e POSTGRES_DB=synapse -e POSTGRES_USER=synapse \
  -e POSTGRES_PASSWORD="${MATRIX_DB_PASSWORD}" \
  -e POSTGRES_HOST_AUTH_METHOD=scram-sha-256 \
  -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --lc-collate=C --lc-ctype=C --auth-local=scram-sha-256 --auth-host=scram-sha-256" \
  -v "${ROOT}/data/synapse-db:/var/lib/postgresql/data" \
  "${SYNAPSE_DB_IMAGE}" >/dev/null 2>&1 \
  || falha "nao foi possivel iniciar o PostgreSQL de inicializacao"
for i in $(seq 1 40); do
  docker exec exo-pg-init pg_isready -U synapse -d synapse >/dev/null 2>&1 && break
  sleep 3
done
docker exec exo-pg-init pg_isready -U synapse -d synapse >/dev/null 2>&1 \
  || { docker logs exo-pg-init 2>&1 | tail -20; falha "PostgreSQL nao inicializou"; }
docker stop exo-pg-init >/dev/null 2>&1
docker rm   exo-pg-init >/dev/null 2>&1
echo "  datadir do PostgreSQL criado; container de inicializacao descartado"

# -------------------------------------------------------------------
log "6/8 — subindo a camada de dados (um servico por vez)"
sobe(){
  local svc="$1" espera="${2:-300}" n fim st hs
  printf '  >> %-12s (RAM livre: %sMB)\n' "$svc" "$(livre)"
  docker compose up -d --no-deps "$svc" >/dev/null 2>&1 \
    || { docker compose logs --tail 25 "$svc"; falha "$svc nao subiu"; }
  n=$(docker compose ps -q "$svc" | head -1); fim=$((SECONDS + espera))
  while [ $SECONDS -lt $fim ]; do
    st=$(docker inspect "$n" --format '{{.State.Status}}' 2>/dev/null)
    hs=$(docker inspect "$n" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}sem{{end}}' 2>/dev/null)
    [ "$st" = "exited" ] && { docker compose logs --tail 25 "$svc"; falha "$svc parou"; }
    [ "$hs" = "healthy" ] && { printf '     healthy em %ss\n' "$SECONDS"; return 0; }
    [ "$hs" = "sem" ] && { sleep 5; return 0; }
    sleep 10
  done
  printf '     ainda iniciando apos %ss\n' "$espera"
}
sobe mailpit 90
sobe mysql 420
sobe es 300
sobe synapse-db 180

# -------------------------------------------------------------------
log "7/8 — chat Matrix (gera homeserver.yaml, sobe Synapse, cria usuario)"
./scripts/setup-matrix.sh >/dev/null 2>&1 || falha "provisionamento do Matrix"
echo "  Synapse provisionado"

# -------------------------------------------------------------------
log "8/8 — ONLYOFFICE, eXo e proxy"
sobe onlyoffice 420
docker compose up -d --no-deps exo >/dev/null 2>&1 || falha "eXo nao subiu"
echo "  eXo iniciado — primeiro boot leva de 10 a 20 min"
for i in $(seq 1 150); do
  hs=$(docker inspect exo-app --format '{{.State.Health.Status}}' 2>/dev/null)
  [ "$hs" = "healthy" ] && { echo "  eXo healthy apos $((i*20))s"; break; }
  [ "$(docker inspect exo-app --format '{{.State.Status}}' 2>/dev/null)" = "exited" ] \
    && { docker compose logs --tail 40 exo; falha "o eXo parou"; }
  sleep 20
done
# -------------------------------------------------------------------
# O PRIMEIRO boot do eXo cria o schema pelo Liquibase, e essa criação emite
# ~78 WARN que ficam para sempre no log deste container:
#   * "NATIONAL/NCHAR/NVARCHAR implies the character set UTF8MB3, which will
#      be replaced by UTF8MB4 in a future release"  (avisos do servidor MySQL
#      sobre o DDL que o próprio eXo gera)
#   * "Due to mysql SQL limitations, modifyDataType will lose primary key…"
#   * "CreateSequenceStatement is not supported on mysql"  (Liquibase)
# Nenhum é defeito e nenhum é evitável por configuração: o DDL vem dos
# changelogs do produto. Mas eles só ocorrem na CRIAÇÃO do schema — com o
# banco já migrado, o mesmo container reinicia sem nenhum deles.
# Recriar o container aqui faz o log de produção nascer limpo, pelo mesmo
# motivo que os bancos são pré-inicializados nos passos 4 e 5.
log "8b/8 — recriando o eXo para que o log de producao nasca limpo"
docker compose up -d --force-recreate --no-deps exo >/dev/null 2>&1 \
  || falha "nao foi possivel recriar o eXo"
for i in $(seq 1 90); do
  hs=$(docker inspect exo-app --format '{{.State.Health.Status}}' 2>/dev/null)
  [ "$hs" = "healthy" ] && { echo "  eXo healthy (2a subida) apos $((i*20))s"; break; }
  [ "$(docker inspect exo-app --format '{{.State.Status}}' 2>/dev/null)" = "exited" ] \
    && { docker compose logs --tail 40 exo; falha "o eXo parou na recriacao"; }
  sleep 20
done

docker compose up -d web >/dev/null 2>&1 || falha "proxy nao subiu"

echo
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
printf '\nRAM: %sMB livres\n' "$(livre)"
echo "Reconstrucao concluida. Verifique os logs com ./scripts/verificar-logs.sh"
