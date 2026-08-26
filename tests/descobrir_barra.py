#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Descoberta AO VIVO do DOM real da barra do eXo: chat, apps, administracao."""
import os, sys, json, time
from playwright.sync_api import sync_playwright

BASE = os.environ.get("EXO_BASE", "https://192.168.1.59")
USER = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
OUT = "/opt/projetos/exo/evidence/ao-vivo"
os.makedirs(OUT, exist_ok=True)


def dump(pg, rotulo, seletor="a, button, [role='button']"):
    print(f"\n----- {rotulo} -----", flush=True)
    els = pg.locator(seletor)
    achados = []
    for i in range(min(els.count(), 250)):
        e = els.nth(i)
        try:
            if not e.is_visible():
                continue
            info = e.evaluate("""el => ({
                tag: el.tagName, id: el.id||'', cls: (el.className||'').toString().slice(0,90),
                href: el.getAttribute('href')||'', aria: el.getAttribute('aria-label')||'',
                title: el.getAttribute('title')||'', txt: (el.innerText||'').trim().slice(0,50)
            })""")
        except Exception:
            continue
        if info["txt"] or info["aria"] or info["title"] or info["href"]:
            achados.append(info)
    for a in achados:
        print(f"  {a['tag']:7} id={a['id'][:24]:24} aria={a['aria'][:28]:28} "
              f"txt={a['txt'][:28]:28} href={a['href'][:50]} cls={a['cls'][:40]}", flush=True)
    return achados


with sync_playwright() as p:
    nav = p.chromium.launch(headless=False, slow_mo=350,
                            args=["--no-sandbox", "--disable-dev-shm-usage",
                                  "--window-size=1600,1000", "--window-position=0,0"])
    pg = nav.new_context(ignore_https_errors=True, viewport={"width": 1600, "height": 900},
                         locale="pt-BR").new_page()
    pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=90_000)
    pg.fill("input[name='username']", USER)
    pg.fill("input[name='password']", SENHA)
    pg.locator("button[type='submit'], input[type='submit']").first.click()
    pg.wait_for_load_state("domcontentloaded", timeout=90_000)
    pg.goto(f"{BASE}/portal/dw", wait_until="domcontentloaded", timeout=90_000)
    pg.wait_for_timeout(6000)
    pg.screenshot(path=f"{OUT}/descoberta-home.png")

    # 1. barra superior inteira
    barra = pg.locator("header, .v-toolbar, #UITopBarContainerParent, [class*='topbar' i]").first
    if barra.count():
        html = barra.inner_html()
        open(f"{OUT}/barra.html", "w", encoding="utf-8").write(html)
        print(f"\nHTML da barra salvo ({len(html)} bytes)", flush=True)
    dump(pg, "BARRA SUPERIOR (elementos visiveis no topo)",
         "header a, header button, .v-toolbar a, .v-toolbar button, "
         "[class*='topbar' i] a, [class*='topbar' i] button")

    # 2. icone de aplicativos (grade)
    for sel in ["#AppCenterUserSetup", "[class*='appCenter' i]", "button:has(i.fa-th)",
                "[aria-label*='plicativ' i]", "[aria-label*='pplication' i]"]:
        alvo = pg.locator(sel).first
        if alvo.count() and alvo.is_visible():
            alvo.click(); pg.wait_for_timeout(2500)
            pg.screenshot(path=f"{OUT}/descoberta-apps.png")
            dump(pg, f"CENTRAL DE APLICATIVOS (via {sel})",
                 ".v-menu__content a, .v-menu__content button, [class*='appCenter' i] a")
            pg.keyboard.press("Escape"); pg.wait_for_timeout(1200)
            break

    # 3. engrenagem = administracao
    for sel in ["[aria-label*='dministra' i]", "[aria-label*='onfigura' i]",
                "button:has(i.fa-cog)", "[class*='settings' i] button", "#administrationLink"]:
        alvo = pg.locator(sel).first
        if alvo.count() and alvo.is_visible():
            alvo.click(); pg.wait_for_timeout(3000)
            pg.screenshot(path=f"{OUT}/descoberta-admin.png")
            dump(pg, f"MENU DE ADMINISTRACAO (via {sel})",
                 ".v-menu__content a, .v-menu__content button, .v-navigation-drawer a, "
                 ".drawer a, [class*='menu' i] a")
            break

    # 4. balao de chat
    for sel in ["[aria-label*='hat' i]", "[aria-label*='onversa' i]", "#chat-status",
                "a[href*='chat']", "button:has(i.fa-comments)"]:
        alvo = pg.locator(sel).first
        if alvo.count() and alvo.is_visible():
            print(f"\nCHAT encontrado por: {sel}", flush=True)
            alvo.click(); pg.wait_for_timeout(4000)
            pg.screenshot(path=f"{OUT}/descoberta-chat.png")
            print("  url apos clique:", pg.url, flush=True)
            break
    else:
        print("\nCHAT: nenhum seletor casou", flush=True)

    pg.wait_for_timeout(2000)
    nav.close()
print("\nDESCOBERTA CONCLUIDA")
