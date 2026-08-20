#!/usr/bin/env python3
"""
prova-login-admin.py — PROVA de que o login administrativo funciona de verdade.

Nao e' teste de fumaca. Faz o fluxo humano completo num navegador real
(Chromium via Playwright): abre a tela de login, digita usuario e senha,
envia, e so entao verifica se o portal AUTENTICADO renderizou.

Verifica tambem, na mesma sessao, os dois defeitos que ja quebraram esta
instalacao, porque ambos so aparecem depois do login:

  * a webapp digital-workplace tem de estar realmente implantada — se um
    bind mount voltar a criar /opt/exo/webapps/digital-workplace/, o portal
    responde HTTP 200 com corpo VAZIO (ver AUDIT [075]);
  * o menu de "Meu Espaco" nao pode exibir a chave crua
    "#portal.myworkspace.notes" (defeito [049]).

Uso:  tests/.venv/bin/python scripts/prova-login-admin.py
Saida: evidence/capturas/prova-login-*.png  +  codigo de saida 0/1
"""
from __future__ import annotations

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
    return padrao


BASE = f"http://{do_env('EXO_PROXY_VHOST', '192.168.1.59')}"
USUARIO = do_env("EXO_ADMIN_USER", "root")
SENHA = do_env("EXO_ADMIN_PASS")

falhas: list[str] = []


def checa(condicao: bool, descricao: str, detalhe: str = "") -> None:
    if condicao:
        print(f"[PASSOU] {descricao}")
    else:
        print(f"[FALHOU] {descricao}" + (f" — {detalhe}" if detalhe else ""))
        falhas.append(descricao)


def main() -> int:
    from playwright.sync_api import sync_playwright

    if not SENHA:
        sys.exit("ERRO: EXO_ADMIN_PASS nao definido no .env")

    with sync_playwright() as p:
        nav = p.chromium.launch(args=["--no-sandbox"])
        ctx = nav.new_context(viewport={"width": 1440, "height": 900}, locale="pt-BR")
        pg = ctx.new_page()

        # ---- 1. tela de login ----
        pg.goto(f"{BASE}/portal/login", wait_until="networkidle", timeout=120_000)
        pg.screenshot(path=str(CAPTURAS / "prova-login-01-tela.png"))
        checa(pg.locator('input[name="username"]').count() > 0,
              "a tela de login renderiza o campo de usuario")

        # ---- 2. login humano ----
        pg.fill('input[name="username"]', USUARIO)
        pg.fill('input[name="password"]', SENHA)
        pg.press('input[name="password"]', "Enter")
        try:
            pg.wait_for_load_state("networkidle", timeout=120_000)
        except Exception:
            pass
        time.sleep(6)
        print(f"URL apos o login: {pg.url}")

        # ---- 3. o portal autenticado renderizou? ----
        checa("/portal/login" not in pg.url,
              "o login redirecionou para fora da tela de login", pg.url)

        html = pg.content()
        checa(len(html) > 5000,
              "o portal autenticado devolveu conteudo real (>5000 bytes)",
              f"{len(html)} bytes")

        # A barra de navegacao do digital-workplace so existe se a webapp
        # estiver de fato implantada. E' a prova direta da correcao de [075].
        itens = pg.evaluate(
            """() => [...document.querySelectorAll('nav a, header a, .v-tab, [role=tab]')]
                   .map(e => (e.innerText||'').trim())
                   .filter(t => t && t.length < 30)"""
        )
        print(f"Itens de navegacao encontrados: {itens[:15]}")
        checa(len(itens) > 0, "a navegacao do portal foi renderizada")

        # ---- 4. defeito [049]: chave crua no menu ----
        checa("portal.myworkspace.notes" not in html,
              "o menu NAO exibe a chave crua #portal.myworkspace.notes")
        checa("Postar em {0}" not in html,
              "o compositor NAO exibe o literal 'Postar em {0}'")

        pg.screenshot(path=str(CAPTURAS / "prova-login-02-autenticado.png"),
                      full_page=False)
        print(f"captura: evidence/capturas/prova-login-02-autenticado.png")

        # ---- 5. identidade confirmada pela propria aplicacao ----
        r = pg.evaluate(
            """async (u) => {
                 const resp = await fetch('/rest/v1/social/users/' + u,
                                          {credentials:'same-origin'});
                 return {st: resp.status, body: (await resp.text()).slice(0, 300)};
               }""", USUARIO)
        print(f"GET /rest/v1/social/users/{USUARIO} -> HTTP {r['st']}")
        checa(r["st"] == 200,
              f"a sessao autenticada consulta a API como {USUARIO}", str(r["st"]))

        ctx.close()
        nav.close()

    print()
    if falhas:
        print(f"RESULTADO: {len(falhas)} verificacao(oes) FALHARAM: {falhas}")
        return 1
    print("RESULTADO: login administrativo comprovado — todas as verificacoes passaram")
    return 0


if __name__ == "__main__":
    sys.exit(main())
