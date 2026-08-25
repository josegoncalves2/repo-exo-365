#!/usr/bin/env python3
"""E2E: carregar estrutura existente, ADICIONAR uma divisao e RENOMEAR, pela web."""
import os, sys, time
from playwright.sync_api import sync_playwright
BASE = "https://192.168.1.59"
USER = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
sys.path.insert(0, "/opt/projetos/exo/scripts")
import exo_estrutura as E

NOVA_DIV_SIGLA = "DPS"
NOVA_DIV_NOME = "Divisao de Protecao Social Especial"
NOVO_ROTULO_SADS = "Secretaria de Assistencia e Desenvolvimento Social e Cidadania"


def main():
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        pg = b.new_context(ignore_https_errors=True).new_page()
        pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=120_000)
        pg.fill("input[name='username']", USER); pg.fill("input[name='password']", SENHA)
        pg.press("input[name='password']", "Enter")
        pg.wait_for_load_state("domcontentloaded", timeout=120_000)
        pg.goto(f"{BASE}/estrutura/", wait_until="domcontentloaded", timeout=120_000)

        # 1) a estrutura existente carregou sozinha?
        pg.wait_for_timeout(2500)
        assert "SADS" in pg.content(), "estrutura existente nao carregou"
        assert "JA EXISTE" in pg.content(), "nos existentes nao marcados"
        print("1) estrutura existente carregou na tela (SADS visivel, 'JA EXISTE')")

        # 2) RENOMEAR a secretaria SADS: acha o input do nome de exibicao da 1a
        # secretaria (que e' SADS na ordem do registro) e troca.
        # A 1a secretaria e' index 0. Seu 'Nome que aparece na tela' e' o 2o input
        # de texto do bloco. Usamos o valor atual para localizar.
        alvo = pg.locator("input[value='Secretaria de Assistencia e Desenvolvimento Social']").first
        alvo.fill(NOVO_ROTULO_SADS)
        print("2) renomeei SADS no campo de nome de exibicao")

        # 3) ADICIONAR divisao: clica no "+ Divisao" da 1a secretaria (SADS)
        pg.locator("button:has-text('+ Divisao')").first.click()
        pg.wait_for_timeout(500)
        # a nova divisao e' a ULTIMA divisao editavel (existe:false) -> sigla editavel
        # preenche a nova sigla/rotulo/gestor. A nova divisao tem input de sigla
        # editavel com placeholder 'ex: SITDS' que esteja vazio.
        siglas_vazias = pg.locator("input[placeholder='ex: SITDS']:not([readonly])")
        siglas_vazias.last.fill(NOVA_DIV_SIGLA)
        # o rotulo correspondente (proximo input do mesmo bloco)
        pg.locator("input[placeholder^='ex: Secretaria']").last.fill(NOVA_DIV_NOME)
        # gestor por nome (sera criado)
        pg.locator("input[placeholder='Wilson França, wilson.franca']").last.fill("Solange Ramos")
        print("3) adicionei a divisao nova", NOVA_DIV_SIGLA, "com gestor por nome")

        # 4) Executar
        pg.on("dialog", lambda d: d.accept())
        pg.click("#bExec")
        estado = "?"
        for _ in range(120):
            estado = pg.locator("#estado").inner_text().strip()
            if estado in ("ok", "erro", "parado"): break
            time.sleep(0.5)
        print("4) Executar ->", estado)
        assert estado == "ok", f"esperava ok, veio {estado}"

        # 5) prova no servidor: nova divisao existe sob /SADS e SADS foi renomeada
        exo = E.conectar()
        div = None
        for _ in range(20):
            div = E.espaco_do_grupo(exo, f"/SADS/{NOVA_DIV_SIGLA}", E.espacos(exo))
            if div: break
            time.sleep(1)
        assert div, "nova divisao /SADS/DPS nao foi criada"
        sads = E.espaco_do_grupo(exo, "/SADS", E.espacos(exo))
        print(f"5) servidor: /SADS/{NOVA_DIV_SIGLA} existe (id {div['id']}, '{div.get('displayName')}')")
        print(f"   /SADS displayName agora = '{sads.get('displayName')}'")
        assert "Cidadania" in (sads.get("displayName") or ""), "rename de SADS nao pegou"
        # gestor por nome criado?
        stc, _ = exo._raw("GET", "/portal/rest/v1/users/solange.ramos")
        print(f"   gestor 'Solange Ramos' -> conta solange.ramos criada? HTTP {stc}")
        assert stc == 200, "gestor por nome nao foi criado"

        # 6) limpeza: remove SO a divisao nova e a conta de teste (deixa SADS)
        cls = type("P", (), {"exo": exo, "dry": False, "log": lambda s, m: None,
                             "checa_parada": lambda s: None})()
        E.remover_arvore(cls, {"secretarias": [{"nome": "SADS", "_existente": True,
            "divisoes": [{"nome": NOVA_DIV_SIGLA, "rotulo": NOVA_DIV_NOME}]}]})
        exo._raw("DELETE", "/portal/rest/v1/users/solange.ramos")
        print("6) limpeza: divisao nova removida, conta de teste apagada (SADS mantido)")
        b.close()
        print("\n>>> E2E INCREMENTAL OK: carregou existente, adicionou divisao, renomeou, tudo pela web.")


if __name__ == "__main__":
    if not SENHA: sys.exit("defina EXO_ADMIN_PASS")
    main()
