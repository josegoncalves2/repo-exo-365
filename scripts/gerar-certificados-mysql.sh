#!/usr/bin/env bash
# ===================================================================
# gerar-certificados-mysql.sh — PKI de 2 níveis para o MySQL
#
# POR QUE ISTO EXISTE
# -------------------
# O MySQL gera sozinho um certificado no primeiro início e avisa, sempre:
#   [Warning] [MY-010068] [Server] CA certificate ca.pem is self signed.
#
# O projeto não tolera warnings. Duas saídas foram testadas:
#
#   1. auto_generate_certs=OFF  ->  PIOROU. O servidor continua tentando
#      inicializar TLS e passa a emitir MY-010069, MY-011302, MY-013595 e
#      MY-015007 (de 3 warnings para 5).
#
#   2. PKI de DOIS níveis  ->  funcionou. A CA raiz (autoassinada) fica de
#      fora da configuração; o MySQL recebe a CA INTERMEDIÁRIA, que é
#      assinada pela raiz e portanto NÃO é autoassinada. O aviso deixa de
#      ter objeto e a conexão continua cifrada.
#
# Como o MySQL também valida a cadeia, ssl_ca aponta para ca-chain.pem =
# intermediária SEGUIDA da raiz: a primeira é a que ele avalia como "a CA"
# (não autoassinada, sem aviso), a segunda permite concluir a validação.
# Sem a raiz no arquivo, aparecem MY-015007/MY-015010/MY-015011.
#
# Resultado medido: 0 erros e 0 warnings, com Ssl_cipher =
# TLS_AES_128_GCM_SHA256 em conexão --ssl-mode=REQUIRED.
#
# Uso:  ./scripts/gerar-certificados-mysql.sh
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${ROOT}/conf/mysql-certs"
DIAS=3650

rm -rf "$DIR"; mkdir -p "$DIR"; cd "$DIR"

echo "[certs] 1/4 — CA raiz (autoassinada; NÃO é entregue ao MySQL)"
openssl req -x509 -newkey rsa:2048 -keyout ca-root-key.pem -out ca-root.pem \
  -days "$DIAS" -nodes -subj "/C=BR/O=PMO eXo/CN=PMO eXo Root CA" 2>/dev/null

echo "[certs] 2/4 — CA intermediária (assinada pela raiz => não autoassinada)"
openssl req -newkey rsa:2048 -keyout ca-int-key.pem -out ca-int.csr -nodes \
  -subj "/C=BR/O=PMO eXo/CN=PMO eXo Intermediate CA" 2>/dev/null
openssl x509 -req -in ca-int.csr -CA ca-root.pem -CAkey ca-root-key.pem \
  -CAcreateserial -out ca-int.pem -days "$DIAS" \
  -extfile <(printf 'basicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n') 2>/dev/null

echo "[certs] 3/4 — certificado do servidor (assinado pela intermediária)"
openssl req -newkey rsa:2048 -keyout server-key.pem -out server.csr -nodes \
  -subj "/C=BR/O=PMO eXo/CN=mysql" 2>/dev/null
openssl x509 -req -in server.csr -CA ca-int.pem -CAkey ca-int-key.pem \
  -CAcreateserial -out server-cert.pem -days "$DIAS" \
  -extfile <(printf 'subjectAltName=DNS:mysql,DNS:localhost,IP:127.0.0.1\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n') 2>/dev/null

echo "[certs] 4/4 — cadeia (intermediária primeiro, raiz depois)"
cat ca-int.pem ca-root.pem > ca-chain.pem

# A chave da raiz não fica no servidor: com ela seria possível emitir
# certificados novos confiáveis para esta cadeia.
rm -f ./*.csr ./*.srl ca-root-key.pem

chmod 644 ca-chain.pem ca-int.pem ca-root.pem server-cert.pem
chmod 640 server-key.pem ca-int-key.pem

# UID 999 = usuário mysql dentro da imagem oficial.
# O chown é OBRIGATÓRIO e não pode falhar em silêncio: a chave privada está
# em modo 640, então se o dono continuar sendo o usuário do host o mysqld
# (que roda como 999) NÃO consegue lê-la e o servidor sobe com:
#   [ERROR] [MY-000059] SSL error: Unable to get private key from
#           '/etc/mysql/certs/server-key.pem'
#   [Warning] MY-013595 / MY-010069 / MY-011302
# ou seja: em vez de eliminar 1 aviso, cria 4. Foi o que aconteceu quando
# este passo usava `sudo` puro em execução não interativa e caía no `|| true`.
if sudo -n true 2>/dev/null; then SUDO="sudo -n"
elif [ -n "${SUDO_ASKPASS:-}" ] && sudo -A true 2>/dev/null; then SUDO="sudo -A"
else
  echo "ERRO: preciso de sudo sem interacao para dar posse dos certificados" >&2
  echo "      ao UID 999 (mysql). Configure NOPASSWD ou exporte SUDO_ASKPASS." >&2
  exit 1
fi
$SUDO chown 999:999 ./* || { echo "ERRO: chown dos certificados falhou" >&2; exit 1; }

# Verificação do efeito (não do código de saída): o dono TEM de ser 999.
dono=$(stat -c %u server-key.pem)
[ "$dono" = "999" ] || { echo "ERRO: server-key.pem pertence ao UID ${dono}, esperado 999" >&2; exit 1; }

echo
echo "[certs] verificação:"
echo -n "  a CA entregue ao MySQL é autoassinada? "
sub=$(openssl x509 -in ca-int.pem -noout -subject)
iss=$(openssl x509 -in ca-int.pem -noout -issuer)
[ "${sub#subject=}" != "${iss#issuer=}" ] && echo "NÃO (correto)" || echo "SIM (ERRADO)"
echo -n "  cadeia valida o certificado do servidor? "
openssl verify -CAfile ca-root.pem -untrusted ca-int.pem server-cert.pem >/dev/null 2>&1 \
  && echo "sim" || echo "NÃO"
ls -la
