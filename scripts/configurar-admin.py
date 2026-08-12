#!/usr/bin/env python3
"""
configurar-admin.py — conclui a tela de configuração inicial do eXo.

Numa instalação nova o eXo apresenta o assistente "Configuração da conta",
onde o responsável define os dados e a senha do super administrador `root`.
Este script cumpre esse passo pelo NAVEGADOR, exatamente como uma pessoa
faria, e depois COMPROVA o resultado autenticando de verdade.

Não inventa credenciais: usuário e senha vêm de EXO_ADMIN_USER e
EXO_ADMIN_PASS (lidos do .env do projeto).

Uso:  tests/.venv/bin/python scripts/configurar-admin.py [--somente-inspecionar]
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
CAPTURAS = RAIZ / "evidence" / "capturas"
CAPTURAS.mkdir(parents=True, exist_ok=True)


def do_env(chave: str, padrao: str = "") -> str:
    env = RAIZ / ".env"
    if env.exists():
        for linha in env.read_text().splitlines():
            linha = linha.strip()
            if linha.startswith(f"{chave}=") and not linha.startswith("#"):
                return linha.split("=", 1)[1].strip()
    return os.environ.get(chave, padrao)


BASE = f"http://{do_env('EXO_PROXY_VHOST', '192.168.1.59')}"
USUARIO = do_env("EXO_ADMIN_USER", "root")
SENHA = do_env("EXO_ADMIN_PASS")
INSPECIONAR = "--somente-inspecionar" in sys.argv

if not SENHA:
    sys.exit("ERRO: EXO_ADMIN_PASS nao definido no .env")


def descreve_formulario(pagina) -> list[dict]:
    return pagina.evaluate("""() => [...document.querySelectorAll('input,button,select')]
        .map(e => ({tag:e.tagName, type:e.type||'', name:e.name||'',
                    id:e.id||'', ph:e.placeholder||'',
                    txt:(e.innerText||e.value||'').slice(0,40),
                    vis: !!(e.offsetParent)}))
        .filter(e => e.vis)""")


def main() -> int:
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        nav = p.chromium.launch(args=["--no-sandbox"])
        ctx = nav.new_context(viewport={"width": 1440, "height": 900}, locale="pt-BR")
        pg = ctx.new_page()

        pg.goto(f"{BASE}/", wait_until="networkidle", timeout=120_000)
        time.sleep(3)
        print(f"URL apos abrir a raiz: {pg.url}")
        pg.screenshot(path=str(CAPTURAS / "admin-01-inicial.png"))

        campos = descreve_formulario(pg)
        print(f"\nElementos visiveis ({len(campos)}):")
        for c in campos:
            print(f"  {c['tag']:<7} type={c['type']:<10} name={c['name']:<22} "
                  f"id={c['id']:<24} ph={c['ph'][:24]:<24} txt={c['txt'][:24]}")

        if INSPECIONAR:
            ctx.close(); nav.close()
            return 0

        # --- preenche os campos de senha do assistente ---
        senhas = [c for c in campos if c["type"] == "password"]
        textos = [c for c in campos if c["type"] in ("text", "email")]
        print(f"\ncampos de senha: {len(senhas)} | campos de texto: {len(textos)}")

        for c in senhas:
            sel = f"#{c['id']}" if c["id"] else f"input[name='{c['name']}']"
            pg.fill(sel, SENHA)
            print(f"  preenchido {sel} (senha)")

        for c in textos:
            alvo = (c["name"] or c["id"] or c["ph"]).lower()
            valor = None
            if "mail" in alvo:
                valor = f"{USUARIO}@exo.local"
            elif "first" in alvo or "nome" in alvo or "name" in alvo:
                valor = "Administrador"
            elif "last" in alvo or "sobrenome" in alvo:
                valor = "PMO"
            if valor:
                sel = f"#{c['id']}" if c["id"] else f"input[name='{c['name']}']"
                pg.fill(sel, valor)
                print(f"  preenchido {sel} = {valor}")

        pg.screenshot(path=str(CAPTURAS / "admin-02-preenchido.png"))

        enviado = False
        for rotulo in ("Enviar", "Salvar", "Submit", "Save", "Confirmar", "OK"):
            try:
                b = pg.get_by_role("button", name=rotulo, exact=False)
                if b.count():
                    b.first.click(timeout=15_000)
                    print(f"  clicado no botao '{rotulo}'")
                    enviado = True
                    break
            except Exception:
                continue
        if not enviado:
            print("  AVISO: nenhum botao de envio encontrado")

        pg.wait_for_load_state("networkidle", timeout=90_000)
        time.sleep(4)
        pg.screenshot(path=str(CAPTURAS / "admin-03-final.png"))
        print(f"URL final: {pg.url}")
        ctx.close(); nav.close()

    # ---- COMPROVACAO: autenticar de verdade com a senha definida ----
    import requests
    s = requests.Session()
    s.get(f"{BASE}/portal/login", timeout=60)
    s.post(f"{BASE}/portal/login",
           data={"username": USUARIO, "password": SENHA}, timeout=60,
           allow_redirects=True)
    r = s.get(f"{BASE}/rest/v1/social/users/{USUARIO}", timeout=60)
    print(f"\nCOMPROVACAO — GET /rest/v1/social/users/{USUARIO}: HTTP {r.status_code}")
    if r.status_code == 200:
        d = r.json()
        print(f"  autenticado como: {d.get('username')} ({d.get('fullname')})")
        return 0
    print("  FALHA: a conta nao autenticou com a senha definida")
    return 1


if __name__ == "__main__":
    sys.exit(main())
