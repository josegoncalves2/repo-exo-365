#!/usr/bin/env bash
# ===================================================================
# gerar-segredos.sh — cria o .env de uma nova instalação.
#
# O .env NÃO é versionado: contém senhas de banco, segredo de registro
# do Matrix e chaves JWT. Este script o gera do zero, com segredos
# aleatórios, a partir de .env.example.
#
# Uso:  ./scripts/gerar-segredos.sh [IP_OU_HOSTNAME]
#       (padrão: 192.168.1.59)
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VHOST="${1:-192.168.1.59}"

if [[ -f .env ]]; then
  echo "ERRO: .env já existe. Remova-o antes, ou edite-o à mão." >&2
  echo "      (um .env sobrescrito invalida o banco existente: as senhas" >&2
  echo "       gravadas no MySQL e no Synapse deixariam de conferir)" >&2
  exit 1
fi

umask 077
gen(){ openssl rand -hex 32; }

# ATENÇÃO ao comprimento do segredo JWT do Matrix: a biblioteca JJWT usada
# pelo eXo escolhe o algoritmo pelo TAMANHO da chave — 32 bytes → HS256,
# 64 bytes → HS512. `openssl rand -hex 32` produz uma string de 64
# caracteres, portanto 64 bytes, portanto HS512. O homeserver.yaml precisa
# declarar o MESMO algoritmo, ou o Synapse responde
# "403 JWT validation failed: unsupported_algorithm".
sed \
  -e "s|__EXO_PROXY_VHOST__|${VHOST}|g" \
  -e "s|__MATRIX_SERVER_NAME__|${VHOST}|g" \
  -e "s|__EXO_DB_PASSWORD__|$(gen)|" \
  -e "s|__MYSQL_ROOT_PASSWORD__|$(gen)|" \
  -e "s|__ONLYOFFICE_JWT_SECRET__|$(gen)|" \
  -e "s|__ONLYOFFICE_SECURE_LINK_SECRET__|$(gen)|" \
  -e "s|__EXO_REWARDS_WALLET_ADMIN_KEY__|$(gen)|" \
  -e "s|__MATRIX_DB_PASSWORD__|$(gen)|" \
  -e "s|__MATRIX_REGISTRATION_SHARED_SECRET__|$(gen)|" \
  -e "s|__MATRIX_MACAROON_SECRET__|$(gen)|" \
  -e "s|__MATRIX_FORM_SECRET__|$(gen)|" \
  -e "s|__MATRIX_JWT_SECRET__|$(gen)|" \
  -e "s|__MATRIX_EXO_PASSWORD__|$(openssl rand -base64 18 | tr -d '/+=')|" \
  .env.example > .env
chmod 600 .env

cp -n conf/exo.properties.example conf/exo.properties
echo "Gerados:"
echo "  .env                  (chmod 600, segredos aleatórios)"
echo "  conf/exo.properties   (cópia do exemplo; o bloco Matrix é escrito"
echo "                         por scripts/setup-matrix.sh)"
echo
echo "Host configurado: ${VHOST}"
echo "Próximo passo:    ./scripts/subir-seguro.sh"
