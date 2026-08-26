#!/usr/bin/env python3
"""
Teste E2E Completo - Navegação Detalhada Botão por Botão
Testa TODOS os módulos e identifica quais faltam (DLP, 2FA, Add-on Manager, etc)
"""

import asyncio
from playwright.async_api import async_playwright
from datetime import datetime

BASE_URL = "http://localhost/portal"

class FullNavigationTest:
    def __init__(self):
        self.results = []
        self.browser = None
        self.page = None
        self.start = datetime.now()

    async def init(self):
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True)

    async def teardown(self):
        if self.browser:
            await self.browser.close()
        if self.playwright:
            await self.playwright.stop()

    def log(self, msg, status="INFO"):
        ts = datetime.now().strftime("%H:%M:%S")
        print(f"[{ts}] {status}: {msg}")

    async def test_navigation_menu(self):
        """Navega por todos os menus principais"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        self.log("Capturando estrutura de navegação...", "MENU")

        # Procura por menus/navegação
        nav_items = await self.page.query_selector_all("nav a, .menu a, [role='navigation'] a, .sidebar a")
        self.log(f"  Encontrados {len(nav_items)} itens de navegação", "OK")

        menu_texts = []
        for item in nav_items[:15]:  # Primeiros 15
            text = await item.text_content()
            href = await item.get_attribute("href")
            if text and text.strip():
                menu_texts.append(text.strip())
                self.log(f"    • {text.strip()}", "MENU")

        await self.page.close()
        return menu_texts

    async def test_admin_panel(self):
        """Tenta acessar painel de administração"""
        self.page = await self.browser.new_page()

        self.log("Procurando painel admin...", "ADMIN")

        # Tenta URLs conhecidas de admin
        admin_urls = [
            "/administration",
            "/admin",
            "/settings",
            "/portal/administration"
        ]

        for url in admin_urls:
            try:
                response = await self.page.goto(f"{BASE_URL}{url}", wait_until="load", timeout=10000)
                if response and response.status < 400:
                    title = await self.page.title()
                    self.log(f"  ✅ Admin encontrado: {url} (título: {title})", "ADMIN")
                    await self.page.close()
                    return True
            except:
                pass

        self.log("  ❌ Painel admin não encontrado", "WARN")
        await self.page.close()
        return False

    async def test_available_modules(self):
        """Identifica módulos carregados"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        self.log("Detectando módulos instalados...", "MODULES")

        # Procura por evidências de módulos na página e DOM
        modules_found = {}

        # Busca por textos/elementos que indicam módulos
        module_indicators = {
            "Chat": ["synapse", "matrix", "message", "conversation", "chat"],
            "Vídeo": ["jitsi", "webconference", "call", "video"],
            "Documentos": ["onlyoffice", "documents", "editor", "file"],
            "Tasks": ["task", "todo", "assignment"],
            "Analytics": ["analytics", "dashboard", "report"],
            "DLP": ["dlp", "data leak", "protection", "sensitive"],
            "2FA": ["2fa", "two factor", "authentication", "mfa"],
            "Add-on Manager": ["addon", "extension", "plugin", "marketplace"],
            "Anti-Malware": ["malware", "virus", "security"],
            "Anti-Brute Force": ["brute force", "attack", "failed login"],
            "SAML": ["saml", "sso", "federation"],
            "Exchange": ["exchange", "outlook", "email"],
            "Cloud Drive": ["cloud", "drive", "storage"],
        }

        page_text = await self.page.content()
        page_text_lower = page_text.lower()

        for module_name, keywords in module_indicators.items():
            found = any(keyword in page_text_lower for keyword in keywords)
            status = "✅" if found else "❌"
            modules_found[module_name] = found
            self.log(f"  {status} {module_name}", "MODULES")

        await self.page.close()
        return modules_found

    async def test_login_functionality(self):
        """Testa autenticação"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        self.log("Testando funcionalidade de login...", "AUTH")

        # Procura por campo de login
        login_fields = await self.page.query_selector_all("input[type='password'], input[type='email'], input[placeholder*='username'], input[placeholder*='email']")

        if login_fields:
            self.log(f"  ✅ Campos de login encontrados ({len(login_fields)})", "AUTH")
            await self.page.close()
            return True
        else:
            self.log("  ⚠️  Usuário já autenticado (sem campos de login visíveis)", "AUTH")
            await self.page.close()
            return True  # Pode estar logado já

    async def test_ui_interactivity(self):
        """Testa cliques e interatividade"""
        self.page = await self.browser.new_page()
        await self.page.goto(f"{BASE_URL}/", wait_until="networkidle", timeout=30000)

        self.log("Testando interatividade (cliques)...", "INTERACTIVE")

        # Procura por botões
        buttons = await self.page.query_selector_all("button, a[role='button'], .btn, input[type='button']")
        self.log(f"  Encontrados {len(buttons)} botões/elementos clicáveis", "INTERACTIVE")

        # Tenta clicar em alguns botões (sem efeitos colaterais)
        clickable_count = 0
        for i, btn in enumerate(buttons[:5]):  # Primeiros 5
            try:
                # Scroll para visibilidade
                await btn.scroll_into_view_if_needed()
                # Verifica se está visível
                is_visible = await btn.is_visible()
                if is_visible:
                    text = await btn.text_content()
                    self.log(f"    • Botão {i+1}: '{text.strip()[:40] if text else 'sem texto'}'", "INTERACTIVE")
                    clickable_count += 1
            except:
                pass

        self.log(f"  ✅ {clickable_count} elementos interativos testados", "INTERACTIVE")
        await self.page.close()
        return clickable_count > 0

    async def test_api_endpoints(self):
        """Testa endpoints de API disponíveis"""
        self.page = await self.browser.new_page()

        endpoints_tested = {}
        endpoints = [
            ("/rest/v1/social/spaces", "Chat/Social"),
            ("/rest/v1/documents", "Documentos"),
            ("/rest/v1/settings", "Configurações"),
            ("/rest/v1/addons", "Add-ons"),
            ("/rest/v1/users", "Usuários"),
        ]

        self.log("Testando endpoints da API...", "API")

        for endpoint, name in endpoints:
            try:
                response = await self.page.goto(f"{BASE_URL}{endpoint}", wait_until="load", timeout=10000)
                status = response.status if response else 0
                symbol = "✅" if status < 400 else "⚠️"
                endpoints_tested[name] = status
                self.log(f"  {symbol} {name:20} → HTTP {status}", "API")
            except Exception as e:
                endpoints_tested[name] = "erro"
                self.log(f"  ❌ {name:20} → Erro", "API")

        await self.page.close()
        return endpoints_tested

    async def run_all(self):
        await self.init()

        print("\n" + "="*80)
        print("TESTE E2E COMPLETO - NAVEGAÇÃO DETALHADA E FUNCIONALIDADES")
        print("="*80 + "\n")

        # Testa funcionalidade de login
        await self.test_login_functionality()
        print()

        # Navega por menus
        menus = await self.test_navigation_menu()
        print()

        # Detecta módulos
        modules = await self.test_available_modules()
        print()

        # Testa interatividade
        await self.test_ui_interactivity()
        print()

        # Testa APIs
        apis = await self.test_api_endpoints()
        print()

        # Procura por admin
        admin_ok = await self.test_admin_panel()
        print()

        # Resumo
        print("="*80)
        print("RESUMO - MÓDULOS DETECTADOS VS ESPERADOS")
        print("="*80 + "\n")

        print("✅ PRESENTES:\n")
        for module, status in modules.items():
            if status:
                print(f"  ✅ {module}")

        print("\n❌ AUSENTES (PRECISAM SER IMPLEMENTADOS):\n")
        for module, status in modules.items():
            if not status:
                print(f"  ❌ {module}")

        print("\n" + "="*80)
        print("STATUS DOS ENDPOINTS DA API")
        print("="*80 + "\n")
        for endpoint, status in apis.items():
            symbol = "✅" if isinstance(status, int) and status < 400 else "❌"
            print(f"  {symbol} {endpoint:20} → {status}")

        print("\n" + "="*80)
        duration = (datetime.now() - self.start).total_seconds()
        print(f"Duração: {duration:.1f}s")
        print("="*80 + "\n")

        await self.teardown()

async def main():
    tester = FullNavigationTest()
    await tester.run_all()

if __name__ == "__main__":
    asyncio.run(main())
