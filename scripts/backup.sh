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

# --- 1. Dump lógico do banco (consistente, restaurável em qualquer versão)
if [[ -n "$MYSQL_CT" ]] && docker exec "$MYSQL_CT" mysqladmin ping -h 127.0.0.1 \
       -uroot -pmy-super-secret-pw --silent >/dev/null 2>&1; then
  echo "[1/2] dump do banco (com --single-transaction, sem travar a aplicação)"
  docker exec "$MYSQL_CT" mysqldump -uroot -pmy-super-secret-pw \
      --single-transaction --routines --triggers --events \
      --default-character-set=utf8mb4 exo 2>/dev/null \
    | gzip > "${DEST}/exo-banco-${TS}.sql.gz"
  echo "      -> exo-banco-${TS}.sql.gz ($(du -h "${DEST}/exo-banco-${TS}.sql.gz" | cut -f1))"
else
  echo "[1/2] MySQL não está no ar — pulando o dump lógico."
  echo "      (a cópia dos arquivos abaixo ainda serve, desde que a stack esteja PARADA)"
fi

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
