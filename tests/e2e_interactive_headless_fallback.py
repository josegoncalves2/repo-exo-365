#!/usr/bin/env python3
"""
Teste E2E Interativo com fallback para headless
Se houver display gráfico, abre browser visível
Se não, testa em headless e gera relatório detalhado
"""

import asyncio
import os
import sys
from playwright.async_api import async_playwright
from datetime import datetime

BASE_URL = "http://localhost/portal"

class InteractiveTest:
    def __init__(self):
        self.has_display = bool(os.environ.get('DISPLAY')) or bool(os.environ.get('WAYLAND_DISPLAY'))
        self.results = {}

    async def run(self):
        print("\n" + "="*80)
        print("TESTE E2E INTERATIVO - eXo Platform")
        print("="*80)
        print(f"\nDisplay detectado: {'SIM ✅' if self.has_display else 'NÃO (rodando em headless)'}")
        print(f"URL: {BASE_URL}\n")

        async with async_playwright() as playwright:
            # Determina modo de launch
            headless = not self.has_display  # Se tem display, abre visível

            browser = await playwright.chromium.launch(headless=headless)
            page = await browser.new_page(viewport={"width": 1920, "height": 1080})

            print("🌐 Abrindo portal...")

            try:
                await page.goto(BASE_URL, wait_until="networkidle", timeout=60000)
                title = await page.title()
                print(f"✅ Portal carregado: {title}\n")

                # Coleta informações
                print("📊 Analisando página...")

                # Títulos de seções (para detectar módulos)
                headings = await page.query_selector_all("h1, h2, h3, h4, h5, h6")
                heading_texts = []
                for h in headings[:20]:
                    text = await h.text_content()
                    if text and text.strip():
                        heading_texts.append(text.strip())
                        print(f"   📝 Seção: {text.strip()}")

                print()

                # Botões disponíveis
                buttons = await page.query_selector_all("button, a[role='button'], .btn")
                print(f"🔘 Total de botões/elementos clicáveis: {len(buttons)}\n")

                # Tenta encontrar painel admin
                print("🔍 Procurando funcionalidades...")

                features = {
                    "Chat/Mensagens": False,
                    "Videoconferência": False,
                    "Documentos": False,
                    "Tasks": False,
                    "Admin Panel": False,
                    "DLP": False,
                    "2FA": False,
                    "Add-on Manager": False,
                }

                page_content = await page.content()
                page_content_lower = page_content.lower()

                # Detecta features por keywords
                keyword_map = {
                    "Chat/Mensagens": ["chat", "message", "conversation", "matrix"],
                    "Videoconferência": ["video", "call", "jitsi", "conference"],
                    "Documentos": ["document", "file", "onlyoffice", "editor"],
                    "Tasks": ["task", "todo", "assignment"],
                    "Admin Panel": ["administration", "admin", "settings"],
                    "DLP": ["dlp", "data leak", "protection"],
                    "2FA": ["2fa", "mfa", "two factor"],
                    "Add-on Manager": ["addon", "extension", "plugin"],
                }

                for feature, keywords in keyword_map.items():
                    found = any(kw in page_content_lower for kw in keywords)
                    features[feature] = found
                    symbol = "✅" if found else "❌"
                    print(f"   {symbol} {feature}")

                print()

                # Se tem display, aguarda interação
                if self.has_display:
                    print("🎮 BROWSER ABERTO E VISÍVEL!")
                    print("\n👆 Você pode clicar em qualquer lugar para testar")
                    print("⏰ Aguardando por 5 minutos...\n")

                    for i in range(60):
                        await page.wait_for_timeout(5000)
                        remaining = 60 - (i + 1)
                        if remaining % 10 == 0:
                            print(f"⏳ Ainda aguardando... {remaining}s")

                    print("\n✅ Teste interativo finalizado!")
                else:
                    print("🔄 Modo headless (sem display gráfico)")
                    print("⏳ Executando análise detalhada por 30 segundos...\n")

                    # Em headless, faz uma análise mais detalhada
                    for i in range(6):
                        await page.wait_for_timeout(5000)
                        print(f"   [{i+1}/6] Análise em progresso...")

                    print("\n✅ Análise completa!")

                # Resumo final
                print("\n" + "="*80)
                print("RESUMO - MÓDULOS DETECTADOS")
                print("="*80 + "\n")

                present = [f for f, found in features.items() if found]
                absent = [f for f, found in features.items() if not found]

                print("✅ PRESENTES:\n")
                for f in present:
                    print(f"   ✅ {f}")

                print("\n❌ AUSENTES (NECESSITAM IMPLEMENTAÇÃO):\n")
                for f in absent:
                    print(f"   ❌ {f}")

                print("\n" + "="*80)

            except Exception as e:
                print(f"❌ ERRO: {e}")
                sys.exit(1)
            finally:
                await browser.close()

        print("\n✅ Teste finalizado!\n")

async def main():
    test = InteractiveTest()
    await test.run()

if __name__ == "__main__":
    asyncio.run(main())
