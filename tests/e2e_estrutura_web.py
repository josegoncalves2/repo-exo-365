#!/usr/bin/env python3
"""Teste end-to-end da interface web /estrutura/ com Chromium real (Playwright).

Fluxo do usuario de verdade: login no portal -> abre /estrutura/ -> preenche o
formulario -> clica Executar -> espera o banner humano de sucesso -> confere no
servidor -> clica Remover para limpar. Usa um GESTOR que EXISTE (wilson.franca).
"""
import os, sys, time
from playwright.sync_api import sync_playwright

BASE = "https://192.168.1.59"
USER = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
SIGLA = "E2EWEB"
ROTULO = "Secretaria Teste E2E (web)"
# Gestor por NOME de exibicao que NAO existe: o script deve CRIAR a conta.
GESTOR_NOME = "Regina Aparecida Nogueira"
GESTOR = "regina.aparecida.nogueira"          # login que o script vai derivar/criar
SHOT = "/opt/projetos/exo/evidence"


def main():
    os.makedirs(SHOT, exist_ok=True)
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        ctx = b.new_context(ignore_https_errors=True)
        pg = ctx.new_page()

        # 1) login no portal
        pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=120_000)
        pg.fill("input[name='username']", USER)
        pg.fill("input[name='password']", SENHA)
        pg.press("input[name='password']", "Enter")
        pg.wait_for_load_state("domcontentloaded", timeout=120_000)
        print("1) login enviado")

        # 2) abre a interface (deslogado redirecionaria ao login; logado abre)
        pg.goto(f"{BASE}/estrutura/", wait_until="domcontentloaded", timeout=120_000)
        assert "Estrutura organizacional" in pg.content(), "pagina nao carregou"
        assert "autenticado como" in pg.content(), "nao mostra o operador"
        print("2) /estrutura/ abriu como", USER)

        # 3) preenche a PRIMEIRA secretaria (ja existe uma vazia no load)
        pg.fill("input[placeholder='ex: SITDS']", SIGLA)
        pg.fill("input[placeholder^='ex: Secretaria']", ROTULO)
        pg.fill("textarea[placeholder='Aparece na tela do espaco']",
                "Espaco criado pelo teste end-to-end automatizado (Chromium).")
        pg.fill("input[placeholder='Wilson França, wilson.franca']", GESTOR_NOME)
        pg.screenshot(path=f"{SHOT}/e2e_1_preenchido.png")
        print("3) formulario preenchido (gestor por NOME, sera criado:", GESTOR_NOME, ")")

        # garante que a conta NAO existe antes (senao o teste nao prova criacao)
        sys.path.insert(0, "/opt/projetos/exo/scripts")
        import exo_estrutura as E
        exo0 = E.conectar()
        st0, _ = exo0._raw("GET", f"/portal/rest/v1/social/users/{GESTOR}")
        assert st0 != 200, f"a conta {GESTOR} ja existia; o teste nao provaria criacao"
        print(f"   (confirmado: {GESTOR} nao existe antes do run)")

        # 4) EXECUTAR e esperar o banner humano de sucesso
        pg.click("#bExec")
        estado = "?"
        for _ in range(120):                       # ate 60s
            estado = pg.locator("#estado").inner_text().strip()
            if estado in ("ok", "erro", "parado"):
                break
            time.sleep(0.5)
        banner = pg.locator("#humano").inner_text().strip()
        pg.screenshot(path=f"{SHOT}/e2e_2_executado.png")
        print(f"4) EXECUTAR -> estado={estado!r} | banner={banner!r}")
        assert estado == "ok", f"esperava ok, veio {estado}"
        assert "Tudo pronto" in banner, "banner de sucesso nao apareceu"

        # 5) confere no servidor, independente da UI. As listagens do eXo
        # propagam a escrita com um pequeno atraso, entao conferimos com
        # algumas tentativas (a UI ja confirmou 'ok'; isto e' so a prova
        # independente, e nao deve falhar por corrida de indexacao).
        exo = E.conectar()
        # 5a) a CONTA foi criada?
        criada = False
        for _ in range(20):
            stc, _t = exo._raw("GET", f"/portal/rest/v1/social/users/{GESTOR}")
            if stc == 200:
                criada = True
                break
            time.sleep(1)
        assert criada, f"a conta {GESTOR} nao foi criada"
        print(f"5a) conta CRIADA no servidor: {GESTOR}")
        esp = None
        for _ in range(20):                        # ate ~20s
            esp = E.espaco_do_grupo(exo, f"/{SIGLA}", E.espacos(exo))
            if esp:
                break
            time.sleep(1)
        assert esp, "espaco nao existe no servidor (alem do atraso)"
        membros = set()
        for _ in range(20):
            membros = E.membros_do_espaco(exo, esp["id"])
            if GESTOR in membros:
                break
            time.sleep(1)
        print(f"5) servidor confirma: espaco id={esp['id']} desc={len(esp.get('description') or '')}car "
              f"membros={sorted(membros)}")
        assert GESTOR in membros, "gestor nao entrou no espaco"

        # 6) REMOVER pela propria UI e esperar o banner
        pg.on("dialog", lambda d: d.accept())      # confirma o prompt de remocao
        pg.click("#bRem")
        estado = "?"
        for _ in range(120):
            estado = pg.locator("#estado").inner_text().strip()
            if estado in ("ok", "erro", "parado"):
                break
            time.sleep(0.5)
        pg.screenshot(path=f"{SHOT}/e2e_3_removido.png")
        print(f"6) REMOVER -> estado={estado!r}")

        # 7) confirma que sumiu de verdade. A remocao do GRUPO no eXo propaga
        # para a listagem com um pequeno atraso; por isso conferimos com
        # algumas tentativas em vez de uma leitura unica (era falso-negativo do
        # teste, nao do produto -- o log ja registra 'grupo apagado').
        sumiu = False
        for _ in range(20):                        # ate ~20s
            grupos = E.grupos_existentes(exo)
            esp2 = E.espaco_do_grupo(exo, f"/{SIGLA}", E.espacos(exo))
            if f"/{SIGLA}" not in grupos and not esp2:
                sumiu = True
                break
            time.sleep(1)
        assert sumiu, "grupo/espaco ainda presentes apos remover (alem do atraso)"
        print(f"7) limpeza confirmada: /{SIGLA} sumiu do servidor (grupo e espaco)")

        # 8) apaga a CONTA de teste criada (remover a estrutura NAO apaga contas
        # -- de proposito: um usuario pode pertencer a outras estruturas).
        exo._raw("DELETE", f"/portal/rest/v1/users/{GESTOR}")
        stf, _ = exo._raw("GET", f"/portal/rest/v1/social/users/{GESTOR}")
        print(f"8) conta de teste {GESTOR} removida (GET agora -> {stf})")

        ctx.close(); b.close()
        print("\n>>> E2E OK: criou pela web, confirmou no servidor, removeu pela web, limpou.")


if __name__ == "__main__":
    if not SENHA:
        sys.exit("defina EXO_ADMIN_PASS")
    main()
