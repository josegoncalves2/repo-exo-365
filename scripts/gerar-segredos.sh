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
# Segredos do addon oficial eXo Jitsi: precisam ser IDENTICOS no .env e no
# conf/exo.properties. Por isso sao gerados aqui, em variaveis, e nao inline
# no sed -- inline cada ocorrencia geraria um valor diferente e o portal
# levaria 401 do microservico jitsi-call.
JITSI_JWT_APP_SECRET_V="$(gen)"
JITSI_EXO_JWT_SECRET_V="$(gen)"
JITSI_INTERNAL_SECRET_V="$(gen)"

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
  -e "s|__JICOFO_COMPONENT_SECRET__|$(gen)|" \
  -e "s|__JICOFO_AUTH_PASSWORD__|$(openssl rand -hex 24)|" \
  -e "s|__JVB_AUTH_PASSWORD__|$(openssl rand -hex 24)|" \
  -e "s|__EXO_ADMIN_PASS__|$(openssl rand -base64 18 | tr -d '/+=')|" \
  -e "s|__JITSI_JWT_APP_SECRET__|${JITSI_JWT_APP_SECRET_V}|" \
  -e "s|__JITSI_EXO_JWT_SECRET__|${JITSI_EXO_JWT_SECRET_V}|" \
  -e "s|__JITSI_INTERNAL_SECRET__|${JITSI_INTERNAL_SECRET_V}|" \
  -e "s|__JITSI_PUBLIC_URL__|https://${VHOST}:8443|g" \
  -e "s|__JITSI_JVB_ADVERTISE_IP__|${VHOST}|g" \
  .env.example > .env
chmod 600 .env

# exo.properties recebe os MESMOS segredos do Jitsi que foram para o .env.
# (antes era um cp cru, que deixava os placeholders __...__ literais no
# arquivo e derrubava a ligacao com 401)
if [ ! -f conf/exo.properties ]; then
  sed \
    -e "s|__JITSI_INTERNAL_SECRET__|${JITSI_INTERNAL_SECRET_V}|" \
    -e "s|__JITSI_EXO_JWT_SECRET__|${JITSI_EXO_JWT_SECRET_V}|" \
    conf/exo.properties.example > conf/exo.properties
  chmod 600 conf/exo.properties
fi

# Portao: nenhum placeholder pode sobreviver nos dois arquivos gerados.
if grep -vE '^\s*#' .env conf/exo.properties | grep -qE '__[A-Z0-9_]+__'; then
  echo "ERRO: placeholder nao substituido:" >&2
  grep -nE '__[A-Z0-9_]+__' .env conf/exo.properties | grep -vE ':[0-9]+:\s*#' >&2
  exit 1
fi

# Portao: os pares que precisam bater entre .env e exo.properties.
for par in "JITSI_EXO_JWT_SECRET:webconferencing.jitsi.external.secret" \
           "JITSI_INTERNAL_SECRET:webconferencing.jitsi.internal.secret"; do
  v_env="$(grep "^${par%%:*}=" .env | cut -d= -f2-)"
  v_pro="$(grep "^${par##*:}=" conf/exo.properties | cut -d= -f2-)"
  if [ "$v_env" != "$v_pro" ] || [ -z "$v_env" ]; then
    echo "ERRO: ${par%%:*} != ${par##*:}" >&2
    exit 1
  fi
done
echo "Gerados:"
echo "  .env                  (chmod 600, segredos aleatórios)"
echo "  conf/exo.properties   (cópia do exemplo; o bloco Matrix é escrito"
echo "                         por scripts/setup-matrix.sh)"
echo
echo "Host configurado: ${VHOST}"
echo "Próximo passo:    ./scripts/subir-seguro.sh"
