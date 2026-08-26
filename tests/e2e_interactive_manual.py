#!/usr/bin/env python3
"""
Teste E2E Interativo - Browser Visível para Testes Manuais
Abre browser real (não headless) para você testar botão por botão
"""

import asyncio
from playwright.async_api import async_playwright
import time

async def run_interactive_test():
    playwright = await async_playwright().start()
    browser = await playwright.chromium.launch(headless=False, args=[])  # headless=False = visível

    page = await browser.new_page()

    print("\n" + "="*70)
    print("TESTE E2E INTERATIVO - BROWSER ABERTO E VISÍVEL")
    print("="*70)
    print("\n✅ Browser está ABERTO na sua tela")
    print("✅ Navegando para: http://localhost/portal")
    print("\nVocê pode:")
    print("  - Clicar em qualquer lugar na página")
    print("  - Navegar pelos menus")
    print("  - Testar formulários")
    print("  - Verificar funcionalidades")
    print("\nScript aguardando por 5 minutos...")
    print("(Browser permanecerá aberto para testes manuais)\n")

    try:
        await page.goto("http://localhost/portal", wait_until="networkidle", timeout=60000)

        title = await page.title()
        print(f"✅ Página carregada: {title}\n")

        # Aguarda 5 minutos para você testar manualmente
        for i in range(60):
            await page.wait_for_timeout(5000)
            remaining = 60 - (i + 1)
            if remaining % 10 == 0 or remaining <= 5:
                print(f"⏳ Aguardando teste manual... {remaining}s restantes")

        print("\n✅ Teste manual completado!")

    except Exception as e:
        print(f"❌ Erro: {e}")
    finally:
        # Browser permanece aberto para você interagir
        print("\n⚠️  Browser permanecerá aberto por mais 2 minutos para inspeção final...")
        await asyncio.sleep(120)
        await browser.close()
        await playwright.stop()

if __name__ == "__main__":
    asyncio.run(run_interactive_test())
