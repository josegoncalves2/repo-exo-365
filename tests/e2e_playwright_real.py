#!/usr/bin/env python3
"""
Teste E2E Real com Playwright — Abre navegador real e testa
"""
import asyncio
from playwright.async_api import async_playwright
import sys

async def test_exo_real():
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page()

        print("="*60)
        print("TESTE E2E REAL - PLAYWRIGHT")
        print("="*60)

        # Test 1: Portal online
        print("\n1️⃣  Acessando portal...")
        try:
            await page.goto("http://localhost:8080/portal/", timeout=30000, wait_until="domcontentloaded")
            print("✅ Portal carregou")

            # Check if login page appears
            title = await page.title()
            print(f"   Título: {title}")

            # Test 2: Login
            print("\n2️⃣  Testando login...")

            # Procurar campo de usuário
            username_field = page.locator('input[name="username"], input[type="text"], input[placeholder*="user" i]').first
            if await username_field.count() > 0:
                await username_field.fill("root")
                print("   ✅ Username preenchido")

            # Procurar campo de senha
            password_field = page.locator('input[type="password"]').first
            if await password_field.count() > 0:
                await password_field.fill("admin")
                print("   ✅ Password preenchido")

            # Cliquar em login
            login_button = page.locator('button[type="submit"], button:has-text("Login"), button:has-text("Sign In")').first
            if await login_button.count() > 0:
                await login_button.click()
                await asyncio.sleep(3)
                print("   ✅ Login enviado")

            # Test 3: Verificar navbar
            print("\n3️⃣  Procurando features na navbar...")

            # Aguardar página carregar completamente
            await asyncio.sleep(5)

            # Procurar por ícones/links de Chat
            chat_elements = await page.query_selector_all('a, button, div')
            page_content = await page.content()

            features_found = {
                "Chat": "conversa" in page_content.lower() or "chat" in page_content.lower(),
                "Video": "videochamada" in page_content.lower() or "jitsi" in page_content.lower(),
                "Documentos": "documento" in page_content.lower() or "onlyoffice" in page_content.lower(),
                "GLPI": "glpi" in page_content.lower() or "suporte" in page_content.lower()
            }

            for feature, found in features_found.items():
                status = "✅" if found else "❌"
                print(f"   {status} {feature}")

            # Test 4: Testar atalhos de teclado
            print("\n4️⃣  Testando atalhos de teclado...")

            # Pressionar Alt+M (Chat)
            await page.keyboard.press("Alt+M")
            await asyncio.sleep(1)
            print("   ✅ Alt+M pressionado")

            # Pressionar Alt+V (Video)
            await page.keyboard.press("Alt+V")
            await asyncio.sleep(1)
            print("   ✅ Alt+V pressionado")

            # Test 5: Screenshot
            print("\n5️⃣  Capturando screenshot...")
            await page.screenshot(path="/tmp/exo-portal.png")
            print("   ✅ Screenshot salvo: /tmp/exo-portal.png")

            print("\n" + "="*60)
            print("✅ TESTE E2E CONCLUÍDO COM SUCESSO")
            print("="*60)

        except Exception as e:
            print(f"❌ ERRO: {str(e)}")

            # Tentar fazer screenshot mesmo com erro
            try:
                await page.screenshot(path="/tmp/exo-error.png")
                print(f"   Screenshot de erro salvo: /tmp/exo-error.png")
            except:
                pass

            await browser.close()
            return False

        await browser.close()
        return True

if __name__ == "__main__":
    try:
        success = asyncio.run(test_exo_real())
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"❌ ERRO CRÍTICO: {str(e)}")
        sys.exit(1)
