#!/bin/bash

# Script para ABRIR BROWSER INTERATIVO no seu computador
# Execute este script num ambiente COM display gráfico (Windows/Mac/Linux com GUI)

set -e

echo "======================================================================"
echo "TESTE E2E INTERATIVO - BROWSER VISÍVEL"
echo "======================================================================"
echo ""
echo "Este script abrirá o eXo Platform no Playwright (browser real)"
echo "Você poderá clicar em botões, navegar e testar funcionalidades"
echo ""
echo "URL: http://localhost/portal"
echo ""

# Detectar SO
OS="Linux"
if [[ "$OSTYPE" == "darwin"* ]]; then
    OS="MacOS"
elif [[ "$OSTYPE" == "msys" ]] || [[ "$OSTYPE" == "cygwin" ]]; then
    OS="Windows"
fi

echo "Sistema detectado: $OS"
echo ""

# Criar script Python que abre o browser
python3 << 'PYTHON_SCRIPT'
import asyncio
from playwright.async_api import async_playwright
import time
import sys

async def interactive_browser_test():
    """Abre browser interativo para testes manuais"""

    playwright = await async_playwright().start()
    browser = await playwright.chromium.launch(headless=False)

    page = await browser.new_page(
        viewport={"width": 1920, "height": 1080}
    )

    print("\n" + "="*70)
    print("🌐 BROWSER ABERTO NA SUA TELA")
    print("="*70)
    print("\n✅ Navegando para o eXo Platform...")
    print("   URL: http://localhost/portal\n")

    try:
        await page.goto("http://localhost/portal", wait_until="networkidle", timeout=60000)

        title = await page.title()
        print(f"✅ Portal carregado: '{title}'")
        print("\n📋 Você pode agora:")
        print("   • Clicar em qualquer botão")
        print("   • Navegar pelos menus")
        print("   • Testar formulários")
        print("   • Explorar funcionalidades")
        print("   • Verificar módulos instalados")
        print("\n⏰ Browser permanecerá aberto por 10 minutos")
        print("   (Você pode testar manualmente quanto tempo quiser)\n")

        # Aguarda 10 minutos
        for i in range(120):
            await page.wait_for_timeout(5000)
            remaining_min = (120 - (i + 1)) // 12
            if (i + 1) % 12 == 0:
                print(f"⏳ Ainda aberto... ({remaining_min} min restantes)")

        print("\n✅ Teste manual completado!")

    except Exception as e:
        print(f"\n❌ Erro: {e}")
        print("   Verifique se o eXo está rodando em http://localhost/portal")
        sys.exit(1)
    finally:
        print("\n🔄 Fechando browser...")
        await browser.close()
        await playwright.stop()
        print("✅ Finalizado!\n")

if __name__ == "__main__":
    try:
        asyncio.run(interactive_browser_test())
    except KeyboardInterrupt:
        print("\n⚠️  Teste interrompido pelo usuário")
PYTHON_SCRIPT

echo ""
echo "======================================================================"
echo "Teste interativo finalizado"
echo "======================================================================"
