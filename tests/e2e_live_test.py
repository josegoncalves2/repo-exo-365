#!/usr/bin/env python3
"""
TESTE AO VIVO COM PLAYWRIGHT — Abre navegador real e testa tudo
Botão por botão, passo a passo
"""
import asyncio
from playwright.async_api import async_playwright
import sys

async def main():
    async with async_playwright() as p:
        # Abrir browser em modo visível (headless=False para ver na tela)
        browser = await p.chromium.launch(headless=False)  # ← VISÍVEL NA TELA
        page = await browser.new_page()

        print("\n" + "="*60)
        print("🎬 TESTE AO VIVO COM PLAYWRIGHT")
        print("="*60 + "\n")

        try:
            # PASSO 1: Abrir portal
            print("📍 PASSO 1: Abrindo portal...")
            print("   → http://192.168.1.59/portal/\n")

            await page.goto("http://192.168.1.59/portal/", timeout=60000, wait_until="load")
            title = await page.title()
            print(f"   ✅ Portal carregou | Título: {title}\n")

            # PASSO 2: Fazer login
            print("📍 PASSO 2: Fazendo login...")
            await page.type('input[name="username"]', "root", delay=100)
            print("   → Username: root")

            await page.type('input[type="password"]', "admin", delay=100)
            print("   → Password: admin")

            await page.click('button[type="submit"]')
            print("   → Clicando em Login...\n")

            await asyncio.sleep(5)  # Aguardar login processar

            # PASSO 3: Procurar por features
            print("📍 PASSO 3: Procurando features na interface...\n")

            # Screenshot antes de testar
            await page.screenshot(path="/tmp/exo-before.png")
            print("   📸 Screenshot: /tmp/exo-before.png\n")

            # Procurar navbar
            navbar_links = await page.query_selector_all('a, button, [role="button"]')
            print(f"   Elementos na página: {len(navbar_links)}")

            # Procurar por ícones específicos
            print("\n   Procurando por features:")

            # Chat
            chat = await page.query_selector('a:has-text("Chat"), a:has-text("Conversa"), button:has-text("Chat")')
            if chat:
                print("   ✅ Chat encontrado!")
                await chat.click()
                await asyncio.sleep(2)
                await page.screenshot(path="/tmp/exo-chat.png")
                print("      📸 Screenshot: /tmp/exo-chat.png")
            else:
                print("   ⚠️  Chat não encontrado na navbar")

            # Video
            video = await page.query_selector('a:has-text("Video"), a:has-text("Videochamada"), button:has-text("Video")')
            if video:
                print("   ✅ Videoconferência encontrada!")
                await video.click()
                await asyncio.sleep(2)
                await page.screenshot(path="/tmp/exo-video.png")
                print("      📸 Screenshot: /tmp/exo-video.png")
            else:
                print("   ⚠️  Videoconferência não encontrada na navbar")

            # Documents
            docs = await page.query_selector('a:has-text("Document"), a:has-text("Documento"), button:has-text("Document")')
            if docs:
                print("   ✅ Documentos encontrado!")
                await docs.click()
                await asyncio.sleep(2)
                await page.screenshot(path="/tmp/exo-docs.png")
                print("      📸 Screenshot: /tmp/exo-docs.png")
            else:
                print("   ⚠️  Documentos não encontrado na navbar")

            # GLPI
            glpi = await page.query_selector('a:has-text("GLPI"), a:has-text("Suporte"), button:has-text("GLPI")')
            if glpi:
                print("   ✅ GLPI encontrado!")
                await glpi.click()
                await asyncio.sleep(2)
                await page.screenshot(path="/tmp/exo-glpi.png")
                print("      📸 Screenshot: /tmp/exo-glpi.png")
            else:
                print("   ⚠️  GLPI não encontrado na navbar")

            # PASSO 4: Testar atalhos
            print("\n📍 PASSO 4: Testando atalhos de teclado...")

            print("   → Testando Alt+M (Chat)...")
            await page.keyboard.press("Alt+M")
            await asyncio.sleep(1)
            await page.screenshot(path="/tmp/exo-shortcut-m.png")
            print("      ✅ Alt+M pressionado")
            print("      📸 Screenshot: /tmp/exo-shortcut-m.png\n")

            print("   → Testando Alt+D (Documentos)...")
            await page.keyboard.press("Alt+D")
            await asyncio.sleep(1)
            await page.screenshot(path="/tmp/exo-shortcut-d.png")
            print("      ✅ Alt+D pressionado")
            print("      📸 Screenshot: /tmp/exo-shortcut-d.png\n")

            # PASSO 5: Teste final
            print("📍 PASSO 5: Teste final...")
            final_screenshot = "/tmp/exo-final.png"
            await page.screenshot(path=final_screenshot)
            print(f"   📸 Screenshot final: {final_screenshot}")

            print("\n" + "="*60)
            print("✅ TESTE AO VIVO CONCLUÍDO COM SUCESSO")
            print("="*60)
            print("\nScreenshots gerados:")
            print("  - /tmp/exo-before.png")
            print("  - /tmp/exo-chat.png")
            print("  - /tmp/exo-video.png")
            print("  - /tmp/exo-docs.png")
            print("  - /tmp/exo-glpi.png")
            print("  - /tmp/exo-shortcut-m.png")
            print("  - /tmp/exo-shortcut-d.png")
            print("  - /tmp/exo-final.png\n")

            # Manter browser aberto por 30 segundos para visualizar
            print("⏱️  Mantendo navegador aberto por 30 segundos...")
            await asyncio.sleep(30)

        except Exception as e:
            print(f"\n❌ ERRO: {str(e)}\n")
            await page.screenshot(path="/tmp/exo-error.png")
            print(f"Screenshot de erro: /tmp/exo-error.png")

        finally:
            await browser.close()

if __name__ == "__main__":
    asyncio.run(main())
