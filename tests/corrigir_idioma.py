#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Devolve o portal ao pt-BR pela tela de Configuracoes gerais — no navegador."""
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
    pg.wait_for_timeout(7000)
    pg.screenshot(path=f"{OUT}/idioma-01-antes.png")
    print("URL:", pg.url)
    print("\n--- TEXTO DA TELA ---")
    print(pg.locator("body").inner_text()[:1500])

    print("\n--- SELETORES / COMBOS ---")
    for sel in ("select", ".v-select", "[role='combobox']", "input[role='combobox']"):
        n = pg.locator(sel).count()
        if n:
            print(f"  {sel}: {n}")
            for i in range(min(n, 8)):
                e = pg.locator(sel).nth(i)
                try:
                    print(f"    [{i}] visivel={e.is_visible()} texto={(e.inner_text() or '')[:60]!r}")
                except Exception:
                    pass
    pg.wait_for_timeout(2000)
    nav.close()
