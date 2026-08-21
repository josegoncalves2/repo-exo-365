#!/usr/bin/env bash
# ===================================================================
# preparar-testes.sh — monta o ambiente da suíte de testes.
#
# O tests/.venv NÃO é versionado (157 MB de binários, incluindo o
# Chromium do Playwright). Este script o reconstrói.
#
# A abordagem B dos testes exige um navegador REAL: os fluxos de
# usuário final são executados clicando e digitando de verdade, não
# por chamadas de API.
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "1/3 — criando o ambiente virtual"
python3 -m venv tests/.venv
tests/.venv/bin/pip -q install --upgrade pip
tests/.venv/bin/pip -q install playwright requests pyjwt

echo "2/3 — dependências de sistema do Chromium (requer sudo)"
sudo tests/.venv/bin/playwright install-deps chromium

echo "3/3 — binário do Chromium"
tests/.venv/bin/playwright install chromium

echo
echo "Verificando com um carregamento de página real:"
tests/.venv/bin/python - <<'PY'
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch(args=["--no-sandbox"])
    pg = b.new_page(); pg.set_content("<h1 id=x>navegador operacional</h1>")
    print("  DOM lido:", pg.inner_text("#x"), "| versao:", b.version)
    b.close()
PY
echo
echo "Pronto. Execute a suíte com:  ./tests/run_all.sh"
