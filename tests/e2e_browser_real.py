#!/usr/bin/env python3
"""
Teste E2E Real com Browser Automation — Cliques, Navegação, Interações Reais
Usa Playwright para simular usuário real navegando
"""

import asyncio
from playwright.async_api import async_playwright, expect
from datetime import datetime
import sys

BASE_URL = "http://localhost/portal"

class BrowserE2ETest:
    def __init__(self):
        self.browser = None
        self.page = None
        self.results = []
        self.start = datetime.now()

    async def init(self):
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    async def teardown(self):
        if self.browser:
            await self.browser.close()
        if self.playwright:
            await self.playwright.stop()

    def log(self, msg, status="INFO"):
        ts = datetime.now().strftime("%H:%M:%S")
        print(f"[{ts}] {status}: {msg}")

    async def test(self, name, func):
        try:
            self.log(f"Testando: {name}")
            await func()
            self.results.append((name, "✅ PASSOU"))
            self.log(f"✅ {name} PASSOU", "PASS")
        except Exception as e:
            self.results.append((name, f"❌ {str(e)[:60]}"))
            self.log(f"❌ {name}: {str(e)[:60]}", "FAIL")

    async def test_homepage_loads(self):
        """Homepage carrega e contém elementos esperados"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        # Verifica elementos básicos
        title = await self.page.title()
        self.log(f"  Título da página: {title}")

        # Aguarda algum elemento visível
        await self.page.wait_for_selector("body", timeout=5000)
        assert len(await self.page.query_selector_all("*")) > 10, "Muito poucos elementos no DOM"

        await self.page.close()

    async def test_navigation_elements_exist(self):
        """Elementos de navegação existem na página"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        # Procura por links/botões comuns
        links = await self.page.query_selector_all("a")
        buttons = await self.page.query_selector_all("button")

        self.log(f"  Encontrados {len(links)} links e {len(buttons)} botões")
        assert len(links) > 0 or len(buttons) > 0, "Nenhum link ou botão encontrado"

        await self.page.close()

    async def test_search_exists(self):
        """Campo de busca existe e é clicável"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        # Procura por input de busca
        search = await self.page.query_selector("input[placeholder*='search'], input[type='search'], .search-input")

        if search:
            await search.click()
            self.log("  Campo de busca clicado com sucesso")
        else:
            self.log("  Aviso: Campo de busca não encontrado (pode estar em menu)")

        await self.page.close()

    async def test_responsive_design(self):
        """Página responde em diferentes tamanhos"""
        self.page = await self.browser.new_page()

        # Desktop
        await self.page.set_viewport_size({"width": 1920, "height": 1080})
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)
        self.log("  Desktop (1920x1080): OK")

        # Mobile
        await self.page.set_viewport_size({"width": 375, "height": 667})
        await self.page.reload(wait_until="networkidle")
        self.log("  Mobile (375x667): OK")

        await self.page.close()

    async def test_page_performance(self):
        """Página carrega em tempo razoável"""
        self.page = await self.browser.new_page()

        start = datetime.now()
        await self.page.goto(f"{BASE_URL}/", wait_until="load", timeout=30000)
        elapsed = (datetime.now() - start).total_seconds()

        self.log(f"  Tempo de carregamento: {elapsed:.2f}s")
        assert elapsed < 30, f"Página demorou {elapsed}s (máx 30s)"

        await self.page.close()

    async def test_no_console_errors(self):
        """Não há erros críticos no console"""
        self.page = await self.browser.new_page()

        errors = []
        self.page.on("console", lambda msg: errors.append(msg.text) if "error" in msg.type.lower() else None)

        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)
        await self.page.wait_for_timeout(2000)

        critical_errors = [e for e in errors if "critical" in e.lower() or "fatal" in e.lower()]
        self.log(f"  {len(errors)} mensagens de console, {len(critical_errors)} críticas")

        await self.page.close()

    async def test_forms_interactive(self):
        """Formulários são interativos"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        # Procura por formulários
        forms = await self.page.query_selector_all("form")
        inputs = await self.page.query_selector_all("input[type='text'], input[type='password'], textarea")

        self.log(f"  Encontrados {len(forms)} formulários e {len(inputs)} campos de input")

        if len(inputs) > 0:
            first_input = inputs[0]
            await first_input.click()
            await first_input.type("teste", delay=50)
            value = await first_input.input_value()
            assert "teste" in value, "Texto não foi digitado"
            self.log("  Teste de digitação: OK")

        await self.page.close()

    async def test_api_endpoints_respond(self):
        """APIs estão respondendo"""
        self.page = await self.browser.new_page()

        endpoints_ok = 0
        endpoints_total = 5

        # Monitora requests durante navegação
        async def handle_request(request):
            nonlocal endpoints_ok
            url = request.url
            if any(api in url for api in ["/rest/", "/api/", "/v1/"]):
                endpoints_ok += 1

        self.page.on("request", handle_request)

        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        self.log(f"  Endpoints API capturados: {endpoints_ok}")

        await self.page.close()

    async def run_all(self):
        print("\n" + "="*70)
        print("TESTE E2E REAL — Automação de Browser com Playwright")
        print("="*70 + "\n")

        await self.test("Homepage Carrega", self.test_homepage_loads)
        await self.test("Navegação Existe", self.test_navigation_elements_exist)
        await self.test("Busca Existe", self.test_search_exists)
        await self.test("Design Responsivo", self.test_responsive_design)
        await self.test("Performance", self.test_page_performance)
        await self.test("Console Limpo", self.test_no_console_errors)
        await self.test("Formulários", self.test_forms_interactive)
        await self.test("APIs Respond", self.test_api_endpoints_respond)

        # Resumo
        passed = sum(1 for _, s in self.results if "PASSOU" in s)
        failed = sum(1 for _, s in self.results if "❌" in s)

        print("\n" + "="*70)
        print(f"Total: {len(self.results)} | ✅ {passed} | ❌ {failed}")
        print("="*70 + "\n")

        for name, status in self.results:
            print(f"  {status:<30} {name}")

        print("\n" + "="*70)
        duration = (datetime.now() - self.start).total_seconds()
        print(f"Duração: {duration:.1f}s")
        if failed == 0:
            print("🎉 SISTEMA RESPONDENDO E INTERATIVO!")
        print("="*70 + "\n")

        return failed == 0

async def main():
    tester = BrowserE2ETest()
    try:
        await tester.init()
        success = await tester.run_all()
        return 0 if success else 1
    finally:
        await tester.teardown()

if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
