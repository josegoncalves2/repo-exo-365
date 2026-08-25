#!/usr/bin/env python3
"""E2E da UI refatorada: criar secretaria, +divisao, renomear, remover -- por
IDs de campo unicos (nada de seletores ambiguos). Estrutura descartavel; NAO
toca em SITDS/SADS."""
import os, sys, time
from playwright.sync_api import sync_playwright
BASE="https://192.168.1.59"
USER=os.environ.get("EXO_ADMIN_USER","root"); SENHA=os.environ.get("EXO_ADMIN_PASS","")
sys.path.insert(0,"/opt/projetos/exo/scripts"); import exo_estrutura as E
SEC="ZZE2E"; DIV="ZZDIV"

def espera_estado(pg):
    for _ in range(120):
        s=pg.locator("#pill").inner_text().strip()
        if s in ("ok","erro","parado"):return s
        time.sleep(0.5)
    return "?"

def main():
    with sync_playwright() as p:
        b=p.chromium.launch(args=["--no-sandbox","--disable-dev-shm-usage"])
        pg=b.new_context(ignore_https_errors=True).new_page()
        pg.goto(f"{BASE}/portal/login",wait_until="domcontentloaded",timeout=120_000)
        pg.fill("input[name='username']",USER);pg.fill("input[name='password']",SENHA)
        pg.press("input[name='password']","Enter");pg.wait_for_load_state("domcontentloaded",timeout=120_000)
        pg.goto(f"{BASE}/estrutura/",wait_until="domcontentloaded",timeout=120_000)
        pg.wait_for_timeout(2500)
        exo=E.conectar()

        # 1) a arvore existente aparece, e SITDS esta la intacto
        assert "SITDS" in pg.content(),"arvore nao carregou"
        print("1) arvore carregou (SITDS visivel, intacto)")

        # 2) + Secretaria (throwaway), por IDs unicos do editor
        pg.click("button:has-text('+ Secretaria')")
        pg.fill("#f_sigla",SEC); pg.fill("#f_rotulo","Secretaria E2E Descartavel")
        pg.fill("#f_descricao","estrutura de teste"); pg.fill("#f_gestores","Teste Um")
        pg.click("#bExec"); assert espera_estado(pg)=="ok","criar sec falhou"
        pg.wait_for_timeout(1500)
        assert E.espaco_do_grupo(exo,f"/{SEC}",E.espacos(exo)),"secretaria nao criada"
        print("2) secretaria nova criada:",SEC)

        # 3) + Divisao na secretaria nova (botao dentro do no dela)
        # abre o editor de nova divisao via a acao do no SEC
        pg.evaluate(f"abrir('novoDiv', JSON.stringify(['{SEC}']))")
        pg.fill("#f_sigla",DIV); pg.fill("#f_rotulo","Divisao E2E")
        pg.click("#bExec"); assert espera_estado(pg)=="ok","criar div falhou"
        pg.wait_for_timeout(1500)
        assert E.espaco_do_grupo(exo,f"/{SEC}/{DIV}",E.espacos(exo)),"divisao nao criada"
        print("3) divisao adicionada a secretaria existente:",f"/{SEC}/{DIV}")

        # 4) renomear a secretaria nova
        pg.evaluate(f"abrir('renomear', JSON.stringify(['{SEC}']))")
        pg.fill("#f_rotulo","Secretaria E2E Renomeada")
        pg.click("#bExec"); assert espera_estado(pg)=="ok","rename falhou"
        pg.wait_for_timeout(1500)
        sec=E.espaco_do_grupo(exo,f"/{SEC}",E.espacos(exo))
        assert "Renomeada" in (sec.get("displayName") or ""),"rename nao pegou"
        print("4) renomeada:",sec.get("displayName"))

        # 5) remover a secretaria nova inteira (via modal)
        pg.evaluate(f"pedirRemover(JSON.stringify(['{SEC}']))")
        pg.click("#modal-ok"); assert espera_estado(pg)=="ok","remover falhou"
        pg.wait_for_timeout(2000)
        sumiu=False
        for _ in range(15):
            if f"/{SEC}" not in E.grupos_existentes(exo):sumiu=True;break
            time.sleep(1)
        assert sumiu,"secretaria nao removida"
        print("5) secretaria descartavel removida por completo")

        # 6) SITDS continua intacto (36/36 e' checado fora); aqui so' confirmo o nome
        st=E.espaco_do_grupo(exo,"/SITDS/DIT/ST",E.espacos(exo))
        assert st and st.get("displayName")=="Setor de Tecnologia","SITDS foi tocado!"
        print("6) SITDS intacto (ST = 'Setor de Tecnologia')")
        b.close()
        print("\n>>> E2E UI OK: criar, +divisao, renomear, remover -- por acoes pontuais, sem tocar no resto.")

if __name__=="__main__":
    if not SENHA:sys.exit("defina EXO_ADMIN_PASS")
    main()
