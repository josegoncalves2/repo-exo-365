#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Define Portugues (Brasil) como idioma padrao — pelo botao da LINHA 'Idioma'."""
import os, sys
from playwright.sync_api import sync_playwright

BASE  = os.environ.get("EXO_BASE", "https://192.168.1.59")
USER  = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
OUT   = "/opt/projetos/exo/evidence/ao-vivo"

with sync_playwright() as p:
    nav = p.chromium.launch(headless=False, slow_mo=500,
                            args=["--no-sandbox", "--disable-dev-shm-usage",
                                  "--window-size=1600,1000", "--window-position=0,0"])
    pg = nav.new_context(ignore_https_errors=True, locale="pt-BR",
                         viewport={"width": 1600, "height": 900}).new_page()
    pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=90_000)
    pg.wait_for_selector("input[name='username']", timeout=60_000)
    pg.fill("input[name='username']", USER); pg.fill("input[name='password']", SENHA)
    pg.locator("button[type='submit'], input[type='submit']").first.click()
    pg.wait_for_load_state("domcontentloaded", timeout=90_000); pg.wait_for_timeout(3000)
    pg.goto(f"{BASE}/portal/administration/home/general/mainsettings",
            wait_until="domcontentloaded", timeout=90_000)
    pg.wait_for_timeout(6000)

    linha = pg.locator(".v-list-item", has=pg.locator(
        ".v-list-item__title", has_text="Idioma")).first
    assert linha.count(), "nao achei a linha 'Idioma'"
    linha.locator("button").first.click()
    pg.wait_for_timeout(5000)
    pg.screenshot(path=f"{OUT}/idioma-04-gaveta.png")
    corpo = pg.locator("body").inner_text()
    print("--- FIM DA TELA APOS O CLIQUE ---")
    print(corpo[-900:])

    # A gaveta "Idioma padrao" usa RADIO, nao combo. Rola ate' o item e clica.
    item = pg.locator(".v-list-item, label, [role='radio']").filter(
        has_text="Português (Brasil)").first
    if not item.count():
        item = pg.get_by_text("Português (Brasil)", exact=False).first
    assert item.count(), "nao achei 'Português (Brasil)' na lista de idiomas"
    item.scroll_into_view_if_needed(); pg.wait_for_timeout(800)
    item.click(); pg.wait_for_timeout(1500)
    print(">>> Português (Brasil) marcado")
    escolhido = True

    aplicar = pg.get_by_role("button", name="Aplicar", exact=False).first
    assert aplicar.count(), "nao achei o botao Aplicar"
    aplicar.click(); pg.wait_for_timeout(6000)
    print(">>> clicado em 'Aplicar'")

    pg.screenshot(path=f"{OUT}/idioma-05-depois.png")
    pg.wait_for_timeout(2000)
    nav.close()
    sys.exit(0 if escolhido else 2)
