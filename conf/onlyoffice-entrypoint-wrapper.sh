#!/bin/bash
# Wrapper para o entrypoint do ONLYOFFICE DocumentServer
# Garante que as CAs internas (Root + Intermediate) sejam carregadas
# no bundle do sistema ANTES do DocService iniciar.
# Resolve: SELF_SIGNED_CERT_IN_CHAIN ao baixar/enviar docs via HTTPS.
set -e

# 1. Atualiza o bundle de CAs do sistema com as CAs internas do projeto
update-ca-certificates 2>&1 | grep -v 'skipping\|rehash: warning'

# 2. Aplica NODE_EXTRA_CA_CERTS nos supervisors (docservice e converter)
#    O binario pkg do ONLYOFFICE 9.4 NAO respeita NODE_OPTIONS (--use-openssl-ca
#    e ignorado), mas respeita NODE_EXTRA_CA_CERTS quando o valor nao tem aspas.
for f in /etc/supervisor/conf.d/ds-docservice.conf /etc/supervisor/conf.d/ds-converter.conf; do
  if grep -q 'NODE_EXTRA_CA_CERTS' "$f" 2>/dev/null; then
    sed -i 's|NODE_EXTRA_CA_CERTS=[^,]*|NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt|' "$f"
  else
    sed -i 's|APPLICATION_NAME=[^,]*|APPLICATION_NAME=onlyoffice,NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt|' "$f"
  fi
done

exec /app/ds/run-document-server.sh "$@"