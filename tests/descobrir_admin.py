#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Descoberta AO VIVO do centro de administracao real: /portal/administration."""
import os
from playwright.sync_api import sync_playwright

BASE = os.environ.get("EXO_BASE", "https://192.168.1.59")
USER = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
OUT = "/opt/projetos/exo/evidence/ao-vivo"
os.makedirs(OUT, exist_ok=True)

with sync_playwright() as p:
    nav = p.chromium.launch(headless=False, slow_mo=300,
                            args=["--no-sandbox", "--disable-dev-shm-usage",
                                  "--window-size=1600,1000", "--window-position=0,0"])
    pg = nav.new_context(ignore_https_errors=True, viewport={"width": 1600, "height": 900},
                         locale="pt-BR").new_page()
    for tentativa in range(10):
        pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=90_000)
        pg.wait_for_timeout(2000)
        if pg.locator("input[name='username']").count():
            break
        print("  login ainda nao renderizou, tentativa", tentativa + 1)
    pg.fill("input[name='username']", USER); pg.fill("input[name='password']", SENHA)
    pg.locator("button[type='submit'], input[type='submit']").first.click()
    pg.wait_for_load_state("domcontentloaded", timeout=90_000)

    pg.goto(f"{BASE}/portal/administration", wait_until="domcontentloaded", timeout=90_000)
    pg.wait_for_timeout(7000)
    pg.screenshot(path=f"{OUT}/administracao.png", full_page=True)
    print("URL:", pg.url)
    print("TITULO:", pg.title())
    print("\n===== TEXTO DA PAGINA =====")
    print(pg.locator("body").inner_text()[:4000])
    print("\n===== LINKS =====")
    els = pg.locator("a[href]")
    for i in range(min(els.count(), 200)):
        e = els.nth(i)
        try:
            if not e.is_visible():
                continue
            h = e.get_attribute("href") or ""
            t = (e.inner_text() or "").strip().replace("\n", " ")[:45]
            a = e.get_attribute("aria-label") or ""
        except Exception:
            continue
        if h and (t or a):
            print(f"  {t:45} | {a[:35]:35} | {h}")
    nav.close()
