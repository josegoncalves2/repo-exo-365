#!/usr/bin/env python3
"""
TESTE AO VIVO — PASSO 0 (wizard de primeiro boot) + BOTAO POR BOTAO

Contexto: o banco MySQL foi zerado, entao o eXo voltou ao estado de instalacao
nova e intercepta QUALQUER acesso com o Account Setup
(form action="/portal/accountSetupAction"). Enquanto esse form nao for enviado,
root/admin e rejeitado com "Usuario ou senha invalidos" — nao adianta testar
navbar, atalho, nada.

Este script:
  1. detecta se o wizard esta na tela e o preenche (idempotente: se ja passou,
     pula direto pro login);
  2. faz login root/admin;
  3. testa botao por botao as features e os atalhos, com screenshot em cada passo.

Uso no rig ao vivo (o display que o usuario esta vendo):
    DISPLAY=:101 python3 tests/e2e_setup_wizard_e_botoes.py
ou, com display proprio:
    xvfb-run -a -s "-screen 0 1920x1080x24" python3 tests/e2e_setup_wizard_e_botoes.py
"""
import asyncio
import os
from playwright.async_api import async_playwright

BASE = os.environ.get("EXO_BASE_URL", "http://localhost")
OUTPUT_DIR = os.environ.get("EXO_SHOTS", "/tmp/exo_wizard_test")

ENV_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".env")


def _do_env(chave):
    """Le uma chave do .env — que e a fonte da verdade das credenciais do projeto.

    Nunca embutir senha literal aqui: a senha do super administrador da intranet
    nao pode virar 'admin' so para conveniencia de teste.
    """
    try:
        with open(ENV_FILE) as fh:
            for linha in fh:
                if linha.startswith(f"{chave}="):
                    return linha.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


ADMIN_USER = os.environ.get("EXO_ADMIN_USER") or _do_env("EXO_ADMIN_USER") or "root"
ADMIN_PASSWORD = os.environ.get("EXO_ADMIN_PASSWORD") or _do_env("EXO_ADMIN_PASS")

if not ADMIN_PASSWORD:
    raise SystemExit(
        f"EXO_ADMIN_PASS nao encontrado em {ENV_FILE} e EXO_ADMIN_PASSWORD nao definido. "
        "Abortando em vez de tentar uma senha adivinhada."
    )

os.makedirs(OUTPUT_DIR, exist_ok=True)

shots = []


async def shot(page, name):
    path = f"{OUTPUT_DIR}/{len(shots):02d}_{name}.png"
    await page.screenshot(path=path)
    shots.append(path)
    print(f"      📸 {path}")


async def concluir_wizard(page):
    """Retorna True se o wizard foi preenchido agora, False se ja estava concluido."""
    form = await page.query_selector('form[action="/portal/accountSetupAction"]')
    if not form:
        print("   ℹ️  Wizard nao esta na tela (setup ja concluido)")
        return False

    print("   ⚠️  WIZARD DE PRIMEIRO BOOT detectado — preenchendo")
    await shot(page, "wizard_antes")

    campos = [
        ('input[name="username"]', "admin.olimpia"),
        ('input[name="firstNameAccount"]', "Administrador"),
        ('input[name="lastNameAccount"]', "Olimpia"),
        ('input[name="emailAccount"]', "admin@olimpia.sp.gov.br"),
        ('input[name="password"]', ADMIN_PASSWORD),
        ('input[name="confirmUserPasswordAccount"]', ADMIN_PASSWORD),
        # adminFirstName e readonly ("root") — so as senhas do root sao editaveis
        ('input[name="adminPassword"]', ADMIN_PASSWORD),
        ('input[name="confirmAdminPassword"]', ADMIN_PASSWORD),
    ]
    for seletor, valor in campos:
        el = await page.query_selector(seletor)
        if el:
            await el.fill(valor)
            mostrado = "********" if "assword" in seletor else valor
            print(f"      → {seletor} = {mostrado}")
        else:
            print(f"      ⚠️  campo ausente: {seletor}")

    await shot(page, "wizard_preenchido")

    submit = await page.query_selector(
        'form[action="/portal/accountSetupAction"] button[type="submit"], '
        'form[action="/portal/accountSetupAction"] input[type="submit"]'
    )
    if submit:
        await submit.click()
        print("      → submit do wizard clicado")
    else:
        await page.evaluate('document.forms["tcForm"].submit()')
        print("      → submit do wizard via JS (botao nao encontrado)")

    await page.wait_for_load_state("load")
    await asyncio.sleep(5)
    await shot(page, "wizard_depois")
    return True


async def fazer_login(page):
    user = await page.query_selector('input[name="username"]')
    pwd = await page.query_selector('input[type="password"]')
    if not (user and pwd):
        print("   ℹ️  Sem form de login — provavelmente ja autenticado")
        return

    await user.fill("")
    await user.type(ADMIN_USER, delay=80)
    await pwd.fill("")
    await pwd.type(ADMIN_PASSWORD, delay=80)
    print(f"   → {ADMIN_USER} / ******** (senha do .env)")

    btn = await page.query_selector('button[type="submit"], input[type="submit"]')
    if btn:
        await btn.click()
    await page.wait_for_load_state("load")
    await asyncio.sleep(5)
    await shot(page, "login_resultado")

    corpo = (await page.content()).lower()
    if "inválidos" in corpo or "invalidos" in corpo or "conexão falhou" in corpo:
        raise RuntimeError("LOGIN REJEITADO — credenciais root ainda invalidas")
    print("   ✅ Login aceito")


async def testar_botoes(page):
    features = [
        ("Chat", 'a:has-text("Chat"), a:has-text("Conversa"), button:has-text("Chat"), [data-testid*="chat"]'),
        ("Videochamada", 'a:has-text("Video"), a:has-text("Videochamada"), button:has-text("Video")'),
        ("Documentos", 'a:has-text("Document"), a:has-text("Documento"), button:has-text("Document")'),
        ("Suporte/GLPI", 'a:has-text("GLPI"), a:has-text("Suporte"), button:has-text("GLPI")'),
        ("Notas", 'a:has-text("Notes"), a:has-text("Notas")'),
        ("Tarefas", 'a:has-text("Task"), a:has-text("Tarefa")'),
        ("Agenda", 'a:has-text("Agenda"), a:has-text("Calend")'),
        ("Apps", 'a:has-text("Aplica"), a:has-text("App Center"), button:has-text("Apps")'),
    ]
    resultados = {}
    for nome, seletor in features:
        print(f"   → {nome}")
        el = await page.query_selector(seletor)
        if el:
            try:
                await el.click()
                await asyncio.sleep(3)
                await shot(page, f"feature_{nome.split('/')[0].lower()}")
                resultados[nome] = f"OK — abriu {page.url}"
                print(f"      ✅ clicado → {page.url}")
            except Exception as e:
                resultados[nome] = f"FALHOU ao clicar: {e}"
                print(f"      ❌ falhou ao clicar: {e}")
            await page.goto(f"{BASE}/portal/", wait_until="load")
            await asyncio.sleep(2)
        else:
            resultados[nome] = "NAO ENCONTRADO na interface"
            print("      ⚠️  nao encontrado")
    return resultados


async def testar_atalhos(page):
    atalhos = ["Alt+M", "Alt+V", "Alt+D", "Alt+G", "Control+K"]
    for a in atalhos:
        print(f"   → {a}")
        await page.keyboard.press(a)
        await asyncio.sleep(2)
        await shot(page, f"atalho_{a.replace('+', '').lower()}")
        await page.keyboard.press("Escape")
        await asyncio.sleep(1)


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=False,
            args=["--no-sandbox", "--disable-gpu", "--start-maximized",
                  "--disable-blink-features=AutomationControlled"],
        )
        page = await browser.new_page(viewport={"width": 1920, "height": 1080})
        page.set_default_timeout(60000)

        print("\n" + "=" * 70)
        print("🎬 TESTE AO VIVO — WIZARD + BOTAO POR BOTAO")
        print(f"   alvo: {BASE}/portal/")
        print("=" * 70 + "\n")

        resultados = {}
        try:
            print("📍 PASSO 1: abrindo o portal")
            await page.goto(f"{BASE}/portal/", timeout=90000, wait_until="load")
            await asyncio.sleep(3)
            await shot(page, "portal_inicial")
            print(f"   título: {await page.title()}\n")

            print("📍 PASSO 2: wizard de primeiro boot")
            fez_wizard = await concluir_wizard(page)
            if fez_wizard:
                await page.goto(f"{BASE}/portal/login", wait_until="load")
                await asyncio.sleep(3)
            print()

            print("📍 PASSO 3: login")
            await fazer_login(page)
            print()

            print("📍 PASSO 4: features, botao por botao")
            resultados = await testar_botoes(page)
            print()

            print("📍 PASSO 5: atalhos de teclado")
            await testar_atalhos(page)
            print()

            await shot(page, "estado_final")

        except Exception as e:
            print(f"\n❌ ERRO: {e}\n")
            import traceback
            traceback.print_exc()
            try:
                await shot(page, "erro")
            except Exception:
                pass

        print("=" * 70)
        print("RESULTADO POR FEATURE")
        print("=" * 70)
        for nome, r in resultados.items():
            print(f"  {nome:<16} {r}")
        print(f"\n📁 {len(shots)} screenshots em {OUTPUT_DIR}\n")

        print("⏱️  navegador aberto por 60s para inspecao visual")
        await asyncio.sleep(60)
        await browser.close()


if __name__ == "__main__":
    asyncio.run(main())
