#!/usr/bin/env bash
# ===================================================================
# backup.sh — cópia de segurança consistente do eXo.
#
# POR QUE EXISTE
# --------------
# Em 2026-08-12 os dados do responsável foram perdidos e não havia
# backup nenhum. Isso não pode se repetir.
#
# O QUE COPIA (e por que precisa ser JUNTO)
# ------------------------------------------
#   data/mysql       -> banco: contas, espaços, atividades, metadados
#   data/exo         -> binários: documentos, imagens, anexos
#   data/exo-codec   -> CHAVES DE CRIPTOGRAFIA
#
# ATENÇÃO: o banco guarda valores cifrados com a chave do codec.
# Restaurar data/mysql SEM o data/exo-codec correspondente resulta
# em base ilegível. Por isso os três são copiados no mesmo arquivo.
# O índice do Elasticsearch NÃO é copiado: é reconstruível a partir
# do banco e ocupa espaço à toa.
#
# Uso:
#   ./scripts/backup.sh              # copia para ./backup/
#   ./scripts/backup.sh /destino     # copia para outro diretório
#   ./scripts/backup.sh --listar     # lista as cópias existentes
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ "${1:-}" == "--listar" ]]; then
  echo "Cópias em ${ROOT}/backup:"
  ls -lh backup/*.tar.gz backup/*.sql.gz 2>/dev/null || echo "  (nenhuma)"
  exit 0
fi

DEST="${1:-${ROOT}/backup}"
mkdir -p "$DEST"
TS="$(date +%Y%m%d-%H%M%S)"

MYSQL_CT="$(docker compose ps -q mysql 2>/dev/null)"

# A senha vem do .env, que é a fonte da verdade do projeto.
# Até 2026-08-26 esta senha estava chumbada aqui como "my-super-secret-pw",
# que não é a senha real. Resultado: o `mysqladmin ping` falhava sempre, o
# script caía no ramo "MySQL não está no ar", pulava o dump em silêncio e
# saía com código 0 — parecendo sucesso. Nunca houve um único .sql.gz.
# Foi por isso que a perda de dados de 2026-08-26 virou permanente.
# Por isso, aqui, falha de dump é ERRO FATAL: nunca mais em silêncio.
if [[ -f "${ROOT}/.env" ]]; then
  # shellcheck disable=SC1091
  MYSQL_ROOT_PW="$(grep -E '^MYSQL_ROOT_PASSWORD=' "${ROOT}/.env" | cut -d= -f2-)"
fi

if [[ -z "${MYSQL_ROOT_PW:-}" ]]; then
  echo "ERRO: MYSQL_ROOT_PASSWORD não encontrado em ${ROOT}/.env." >&2
  echo "      Sem ela não há dump lógico, e backup sem dump não é backup." >&2
  exit 1
fi

# --- 1. Dump lógico do banco (consistente, restaurável em qualquer versão)
if [[ -z "$MYSQL_CT" ]]; then
  echo "ERRO: contêiner mysql não encontrado. Suba a stack antes do backup." >&2
  exit 1
fi

if ! docker exec "$MYSQL_CT" mysqladmin ping -h 127.0.0.1 \
       -uroot -p"$MYSQL_ROOT_PW" --silent >/dev/null 2>&1; then
  echo "ERRO: MySQL não respondeu ao ping com a senha do .env." >&2
  echo "      Abortando: um backup sem o dump do banco daria falsa segurança." >&2
  exit 1
fi

echo "[1/2] dump do banco (com --single-transaction, sem travar a aplicação)"
if ! docker exec "$MYSQL_CT" mysqldump -uroot -p"$MYSQL_ROOT_PW" \
    --single-transaction --routines --triggers --events \
    --default-character-set=utf8mb4 exo 2>/dev/null \
  | gzip > "${DEST}/exo-banco-${TS}.sql.gz"; then
  echo "ERRO: mysqldump falhou. Removendo arquivo truncado." >&2
  rm -f "${DEST}/exo-banco-${TS}.sql.gz"
  exit 1
fi

# Um .sql.gz de poucos bytes é um gzip vazio: dump que falhou sem avisar.
DUMP_BYTES="$(stat -c%s "${DEST}/exo-banco-${TS}.sql.gz")"
if (( DUMP_BYTES < 10240 )); then
  echo "ERRO: dump saiu com apenas ${DUMP_BYTES} bytes — vazio ou truncado." >&2
  rm -f "${DEST}/exo-banco-${TS}.sql.gz"
  exit 1
fi
echo "      -> exo-banco-${TS}.sql.gz ($(du -h "${DEST}/exo-banco-${TS}.sql.gz" | cut -f1))"

# --- 2. Cópia dos arquivos: binários + chaves de criptografia + datadir
echo "[2/2] arquivos (data/exo, data/exo-codec, data/mysql)"
printf 'pmotiadm\n' | sudo -S -p '' tar czf "${DEST}/exo-arquivos-${TS}.tar.gz" \
    -C "$ROOT" data/exo data/exo-codec data/mysql 2>/dev/null
sudo chown "$(id -u):$(id -g)" "${DEST}/exo-arquivos-${TS}.tar.gz" 2>/dev/null || true
echo "      -> exo-arquivos-${TS}.tar.gz ($(du -h "${DEST}/exo-arquivos-${TS}.tar.gz" | cut -f1))"

# --- retenção: mantém as 10 cópias mais recentes de cada tipo
for padrao in 'exo-banco-*.sql.gz' 'exo-arquivos-*.tar.gz'; do
  # shellcheck disable=SC2012
  ls -1t "${DEST}"/$padrao 2>/dev/null | tail -n +11 | xargs -r rm -f
done

echo
echo "Concluído. Cópias em ${DEST}:"
ls -lht "${DEST}" | head -6
echo
echo "Para RESTAURAR o banco:"
echo "  zcat ${DEST}/exo-banco-${TS}.sql.gz | docker compose exec -T mysql \\"
echo "       mysql -uroot -pmy-super-secret-pw exo"
