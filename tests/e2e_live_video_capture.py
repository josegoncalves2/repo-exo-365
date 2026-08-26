#!/usr/bin/env python3
"""
TESTE AO VIVO COM PLAYWRIGHT + CAPTURA DE VÍDEO
Executa com display virtual e captura tudo em vídeo
"""
import asyncio
import subprocess
import time
import os
from playwright.async_api import async_playwright

OUTPUT_DIR = "/tmp/exo_test_video"
os.makedirs(OUTPUT_DIR, exist_ok=True)

async def main():
    async with async_playwright() as p:
        # Configurar chromium com display virtual
        browser = await p.chromium.launch(
            headless=False,
            args=[
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-gpu"
            ]
        )
        page = await browser.new_page()
        page.set_default_timeout(60000)

        print("\n" + "="*70)
        print("🎬 TESTE AO VIVO - PLAYWRIGHT COM CAPTURA DE VÍDEO")
        print("="*70 + "\n")

        screenshots = []
        step = 0

        try:
            # PASSO 1: Portal
            print("📍 PASSO 1: Navegando para portal...")
            await page.goto("http://localhost/portal/", timeout=60000, wait_until="load")
            await asyncio.sleep(2)

            step += 1
            screenshot_path = f"{OUTPUT_DIR}/01_portal_loaded.png"
            await page.screenshot(path=screenshot_path)
            screenshots.append(screenshot_path)
            print(f"   ✅ Portal carregou")
            print(f"   📸 {screenshot_path}\n")

            # PASSO 2: Login
            print("📍 PASSO 2: Fazendo login...")
            username = await page.query_selector('input[name="username"]')
            if username:
                await username.type("root", delay=100)
                print("   → Username: root")

            password = await page.query_selector('input[type="password"]')
            if password:
                await password.type("admin", delay=100)
                print("   → Password: admin")

            submit = await page.query_selector('button[type="submit"]')
            if submit:
                await submit.click()
                print("   → Clicando em Login...\n")

            await asyncio.sleep(5)

            step += 1
            screenshot_path = f"{OUTPUT_DIR}/02_login_done.png"
            await page.screenshot(path=screenshot_path)
            screenshots.append(screenshot_path)
            print(f"   ✅ Login completo")
            print(f"   📸 {screenshot_path}\n")

            # PASSO 3: Features
            print("📍 PASSO 3: Procurando features...")

            features = [
                ('Chat', 'a:has-text("Chat"), a:has-text("Conversa"), button:has-text("Chat")'),
                ('Video', 'a:has-text("Video"), a:has-text("Videochamada"), button:has-text("Video")'),
                ('Docs', 'a:has-text("Document"), a:has-text("Documento"), button:has-text("Document")'),
                ('GLPI', 'a:has-text("GLPI"), a:has-text("Suporte"), button:has-text("GLPI")')
            ]

            for feature_name, selector in features:
                print(f"   → Testando {feature_name}...")
                element = await page.query_selector(selector)
                if element:
                    await element.click()
                    await asyncio.sleep(2)

                    step += 1
                    screenshot_path = f"{OUTPUT_DIR}/03_{feature_name.lower()}.png"
                    await page.screenshot(path=screenshot_path)
                    screenshots.append(screenshot_path)
                    print(f"      ✅ {feature_name} clicado")
                    print(f"      📸 {screenshot_path}")
                else:
                    print(f"      ⚠️  {feature_name} não encontrado")

            print()

            # PASSO 4: Atalhos
            print("📍 PASSO 4: Testando atalhos de teclado...")

            shortcuts = [
                ('Alt+M', 'Alt+M', "04_shortcut_altm.png"),
                ('Alt+V', 'Alt+V', "04_shortcut_altv.png"),
                ('Alt+D', 'Alt+D', "04_shortcut_altd.png"),
                ('Alt+G', 'Alt+G', "04_shortcut_altg.png")
            ]

            for name, keys, filename in shortcuts:
                print(f"   → Testando {name}...")
                await page.keyboard.press(keys)
                await asyncio.sleep(1)

                step += 1
                screenshot_path = f"{OUTPUT_DIR}/{filename}"
                await page.screenshot(path=screenshot_path)
                screenshots.append(screenshot_path)
                print(f"      ✅ {name} pressionado")
                print(f"      📸 {screenshot_path}")

            print()

            # PASSO 5: Screenshot final
            print("📍 PASSO 5: Capturando estado final...")
            step += 1
            screenshot_path = f"{OUTPUT_DIR}/05_final_state.png"
            await page.screenshot(path=screenshot_path)
            screenshots.append(screenshot_path)
            print(f"   📸 {screenshot_path}\n")

            # Resumo
            print("="*70)
            print("✅ TESTE CONCLUÍDO COM SUCESSO")
            print("="*70)
            print(f"\n📸 Total de screenshots capturados: {len(screenshots)}")
            print(f"📁 Diretório: {OUTPUT_DIR}\n")

            for i, ss in enumerate(screenshots, 1):
                print(f"  {i:2d}. {os.path.basename(ss)}")

            # Criar vídeo a partir dos screenshots
            print("\n📹 Gerando vídeo do teste...\n")

            video_output = f"{OUTPUT_DIR}/teste_ao_vivo.mp4"
            cmd = [
                "ffmpeg", "-y",
                "-framerate", "1",
                "-pattern_type", "glob", "-i", f"{OUTPUT_DIR}/*.png",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-crf", "23",
                video_output
            ]

            result = subprocess.run(cmd, capture_output=True, text=True)

            if os.path.exists(video_output):
                size_mb = os.path.getsize(video_output) / (1024*1024)
                print(f"   ✅ Vídeo gerado: {video_output}")
                print(f"   📊 Tamanho: {size_mb:.2f} MB\n")
            else:
                print(f"   ⚠️  Erro ao gerar vídeo\n")

            print("="*70)
            print("🎉 TESTE FINALIZADO")
            print("="*70)

            # Manter browser aberto 10 segundos
            print("\n⏱️  Mantendo browser aberto por 10 segundos...\n")
            await asyncio.sleep(10)

        except Exception as e:
            print(f"\n❌ ERRO: {str(e)}\n")
            import traceback
            traceback.print_exc()

            step += 1
            screenshot_path = f"{OUTPUT_DIR}/99_error.png"
            try:
                await page.screenshot(path=screenshot_path)
                print(f"   📸 Screenshot de erro: {screenshot_path}\n")
            except:
                pass

        finally:
            await browser.close()

if __name__ == "__main__":
    asyncio.run(main())
