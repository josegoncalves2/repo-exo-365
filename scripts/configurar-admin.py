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
# Conta nomeada criada pelo assistente, alem do super administrador `root`.
# O responsavel indicou "user: saexo/root" — as duas contas, mesma senha.
CONTA_NOMEADA = do_env("EXO_ADMIN_CONTA_NOMEADA", "saexo")

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
        nav = p.chromium.launch(headless=False, slow_mo=400,
                                args=["--no-sandbox", "--disable-dev-shm-usage",
                                      "--window-size=1600,1000", "--window-position=0,0"])
        ctx = nav.new_context(viewport={"width": 1440, "height": 900}, locale="pt-BR",
                              ignore_https_errors=True)
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

        # O assistente do eXo 7.2 tem DUAS partes num formulário só:
        #   1. uma conta nomeada de administrador (username/nome/e-mail/senha);
        #   2. a senha do super administrador `root`, que já existe.
        # Preencher só uma delas deixa o assistente incompleto e ele reaparece.
        # `#adminFirstName` NÃO entra aqui: é readOnly (verificado no DOM com
        # `e.readOnly`), já vem preenchido com `root` e tentar escrever nele faz
        # o Playwright esperar até estourar o tempo — foi o que impediu a
        # primeira tentativa de concluir o assistente.
        preenchimento = {
            "#userNameAccount": CONTA_NOMEADA,
            "#firstNameAccount": "Administrador",
            "#lastNameAccount": "PMO",
            "#emailAccount": f"{CONTA_NOMEADA}@exo.local",
            "#userPasswordAccount": SENHA,
            "#confirmUserPasswordAccount": SENHA,
            "#adminPassword": SENHA,
            "#confirmAdminPassword": SENHA,
        }
        for sel, valor in preenchimento.items():
            try:
                pg.fill(sel, valor, timeout=10_000)
                oculto = "senha" if "assword" in sel.lower() else valor
                print(f"  preenchido {sel} = {oculto}")
            except Exception as e:  # noqa: BLE001
                print(f"  AVISO: nao preencheu {sel}: {str(e)[:80]}")

        pg.screenshot(path=str(CAPTURAS / "admin-02-preenchido.png"))

        try:
            pg.click("#continueButton", timeout=20_000)
            print("  clicado em 'Enviar' (#continueButton)")
        except Exception as e:  # noqa: BLE001
            print(f"  AVISO: nao foi possivel enviar: {str(e)[:100]}")

        try:
            pg.wait_for_load_state("networkidle", timeout=90_000)
        except Exception:
            pass
        time.sleep(4)

        erros = pg.evaluate("""() => [...document.querySelectorAll(
            '.alert,.error,[class*=error],[class*=Error],[class*=alert]')]
            .map(e => e.innerText.trim()).filter(t => t).slice(0, 6)""")
        if erros:
            print(f"  MENSAGENS DE VALIDACAO: {erros}")

        # O assistente tem um segundo passo: a tela "Saudações!" com o botão
        # "Iniciar". Sem clicar nele a configuração NÃO é persistida e o
        # assistente reaparece no próximo acesso — comprovado na 1a tentativa.
        pg.screenshot(path=str(CAPTURAS / "admin-03-saudacoes.png"))
        try:
            b = pg.get_by_role("button", name="Iniciar", exact=False)
            if b.count():
                b.first.click(timeout=20_000)
                print("  clicado em 'Iniciar' (conclusao do assistente)")
                try:
                    pg.wait_for_load_state("networkidle", timeout=90_000)
                except Exception:
                    pass
                time.sleep(5)
            else:
                print("  AVISO: botao 'Iniciar' nao encontrado")
        except Exception as e:  # noqa: BLE001
            print(f"  AVISO: falha ao concluir: {str(e)[:100]}")

        pg.screenshot(path=str(CAPTURAS / "admin-04-final.png"))
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
