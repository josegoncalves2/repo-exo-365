#!/usr/bin/env bash
# Corrente completa AO VIVO na tela :77 — espera o portal, conclui a conta de
# administrador pelo navegador, e so' entao roda a suite botao por botao.
set -uo pipefail
cd /opt/projetos/exo
set -a; source .env; set +a
export DISPLAY=:77
PY=tests/.venv/bin/python

echo "== 1/3  esperando o portal responder (boot limpo do eXo leva ate' 20 min)"
# NAO e' teste: e' so' o portao de espera. Quem testa e' o Playwright, adiante.
# Uso o healthcheck do proprio container, para nao haver curl nenhum meu no meio.
until [ "$(docker inspect --format '{{.State.Health.Status}}' exo-app 2>/dev/null)" = "healthy" ]; do
  printf '.'
  sleep 10
done
docker start exo-web >/dev/null 2>&1 || true    # o nginx fica em 'Created' quando o up -d e' interrompido
sleep 5
echo; echo "portal respondeu em $(date +%H:%M:%S)"

echo "== 2/3  concluindo a conta de administrador PELO NAVEGADOR (janela visivel)"
$PY scripts/configurar-admin.py
rc=$?
echo "configurar-admin.py -> rc=$rc"

echo "== 3/3  suite E2E ao vivo, botao por botao"
$PY tests/e2e_ao_vivo.py
