#!/usr/bin/env python3
"""
T-09-DLP — o console de DLP, provado como o operador o usa.

POR QUE ESTE ARQUIVO FOI REESCRITO EM 2026-09-01. A versao anterior ancorava
TODAS as asseveracoes de navegador em `.pmo-dlp`, `.pmo-aba`, `.pmo-cartao` —
classes do CSS que o proprio console imprimia. Um teste que procura a marca que
o codigo sob teste acabou de escrever prova que o codigo e' consistente consigo
mesmo, e nada mais. O T-09.1 "passava" por isso, com a tela quebrada.

As tres regras que esta versao segue:

1. **Ancorar no que a PLATAFORMA possui**, nao no que este codigo escreve:
   `.v-tab`, `.v-tab--active`, `.v-window-item--active`, `.v-data-table`. Se o
   console deixar de usar os componentes do portal, o teste cai — que e'
   exatamente o que se quer detectar.

2. **Chegar pelo MENU, com clique**, e nao por URL montada no teste. Uma URL
   digitada pelo teste prova que a pagina existe; ela nao prova que o operador
   consegue CHEGAR nela. As duas coisas foram confundidas antes.

3. **Exigir que a aba MUDE O CONTEUDO.** Destaque de aba nao e' prova: a versao
   anterior do console pintava a aba clicada e servia o mesmo painel embaixo.
   Aqui o texto do painel ativo e' comparado entre as abas, e abas que servem o
   mesmo conteudo REPROVAM.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import Recorder, Result  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
CAPTURAS = RAIZ / "evidence" / "capturas"
BASE = os.environ.get("EXO_BASE", "https://192.168.1.59")
USUARIO = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
MAILPIT = os.environ.get("MAILPIT_BASE", "http://192.168.1.59:8025")
RUN_ID = os.environ.get("RUN_ID", time.strftime("%Y%m%d-%H%M%S"))

# CPF de exemplo publico usado em documentacao de validacao. Nao pertence a
# pessoa alguma. O documento NAO contem a palavra "CPF" -- e' esse o ponto.
CPF_EXEMPLO = "529.982.247-25"


def dlp(caminho: str, metodo: str = "GET", corpo: str = "") -> dict:
    """Le o servico pela rede interna do compose, so' para CONFERIR o efeito."""
    cmd = ["docker", "compose", "exec", "-T", "dlp", "python3", "-c", f'''
import json, os, urllib.request
cab = {{"Content-Type": "application/json"}}
tk = os.environ.get("DLP_TOKEN", "")
if tk:
    cab["X-DLP-Token"] = tk
r = urllib.request.Request("http://127.0.0.1:8480{caminho}", method="{metodo}",
                           data={corpo!r}.encode() or None, headers=cab)
print(urllib.request.urlopen(r, timeout=30).read().decode())
''']
    p = subprocess.run(cmd, capture_output=True, text=True, cwd=RAIZ, timeout=120)
    if p.returncode:
        raise RuntimeError(p.stderr[-400:])
    return json.loads(p.stdout.strip().splitlines()[-1])


def mailpit(caminho: str) -> dict:
    with urllib.request.urlopen(f"{MAILPIT}{caminho}", timeout=20) as r:
        return json.loads(r.read().decode())


def _entrar(pagina) -> None:
    pagina.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=60000)
    pagina.fill("input[name='username']", USUARIO)
    pagina.fill("input[name='password']", SENHA)
    pagina.click("button[type='submit'], input[type='submit']")
    pagina.wait_for_load_state("domcontentloaded", timeout=60000)



# O item de menu e' o NATIVO `security/quarantine`, agora rotulado
# "Prevencao de Perda de Dados". O console nao tem mais pagina nem no' proprios.
ADMIN = f"{BASE}/portal/administration/home"
ROTULO_MENU = "Preven\u00e7\u00e3o de Perda de Dados"
ROTULO_ANTIGO = "Prote\u00e7\u00e3o de dados (DLP)"

# As onze secoes, na ordem em que o console as declara.
ABAS = ["Painel", "Incidentes", "Revis\u00e3o", "Quarentena", "Pol\u00edtica",
        "\u00cdndices", "Dicion\u00e1rios", "Descoberta", "Avisos", "Agentes",
        "Auditoria"]


def _captura(pagina, nome: str) -> str:
    CAPTURAS.mkdir(parents=True, exist_ok=True)
    caminho = CAPTURAS / f"T-09-{nome}-{RUN_ID}.png"
    pagina.screenshot(path=str(caminho), full_page=True)
    return str(caminho.relative_to(RAIZ))


def _abrir_pelo_menu(pagina) -> str:
    """Chega ao console COMO O OPERADOR CHEGA: clicando no menu lateral.

    Devolve a URL a que o clique levou. Nenhuma URL e' montada aqui.
    """
    pagina.goto(ADMIN, wait_until="domcontentloaded", timeout=60000)
    pagina.wait_for_load_state("networkidle", timeout=60000)

    # O galho "Seguranca" pode estar recolhido.
    galho = pagina.locator("text=/^Seguran\u00e7a$|^Security$/").first
    if galho.count() and galho.is_visible():
        galho.click()
        pagina.wait_for_timeout(700)

    item = pagina.locator(f"text={ROTULO_MENU}").first
    item.wait_for(state="visible", timeout=30000)
    item.click()
    pagina.wait_for_load_state("networkidle", timeout=60000)
    pagina.wait_for_selector(".v-tab", timeout=45000)
    return pagina.url


def _painel_ativo(pagina) -> str:
    """Texto do painel da aba ATIVA. E' o que o operador esta' vendo."""
    alvo = pagina.locator(".v-window-item--active").first
    if not alvo.count():
        return ""
    return " ".join((alvo.inner_text() or "").split())


def b_chega_pelo_menu(rec: Recorder) -> None:
    """O console vive no menu que ja' existia, com o nome novo, e sem duplicata."""
    r = Result("T-09.1", "Console abre pelo menu Seguranca, renomeado e sem no' duplicado",
               "B-usuario")
    t0, passos = time.time(), []
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            nav = pw.chromium.launch(args=["--ignore-certificate-errors"])
            ctx = nav.new_context(ignore_https_errors=True, viewport={"width": 1500, "height": 1000})
            pagina = ctx.new_page()
            _entrar(pagina)
            url = _abrir_pelo_menu(pagina)
            passos.append(f"clique no menu levou a: {url}")

            # O no' proprio, nao autorizado, nao pode existir mais.
            antigo = pagina.locator(f"text={ROTULO_ANTIGO}").count()
            passos.append(f"itens de menu '{ROTULO_ANTIGO}': {antigo} (esperado 0)")

            # O rotulo nativo foi renomeado.
            quarentena_sozinha = pagina.locator(
                "a:text-is('Quarentena'), span:text-is('Quarentena')").count()
            passos.append(f"itens de menu 'Quarentena' isolados: {quarentena_sozinha}")

            abas = pagina.locator(".v-tab").count()
            passos.append(f"abas .v-tab renderizadas: {abas} (esperado {len(ABAS)})")

            no_lugar_certo = "/security/quarantine" in url
            passos.append(f"URL e' a pagina NATIVA security/quarantine: {no_lugar_certo}")

            r.proof = _captura(pagina, "01-menu")
            r.passed = (antigo == 0 and abas == len(ABAS) and no_lugar_certo)
            ctx.close()
            nav.close()
        r.steps = list(passos)
        r.detail = passos[-1]
    except Exception as e:                                  # noqa: BLE001
        r.passed = False
        r.detail = f"{type(e).__name__}: {e}"
        r.steps = list(passos) + [r.detail]
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def b_abas_trocam_conteudo(rec: Recorder) -> None:
    """A asseveracao que o console anterior nunca passou.

    Nao basta a aba ficar destacada. O PAINEL embaixo tem de mudar. O defeito de
    2026-09-01 era exatamente este: onze abas, um unico painel.
    """
    r = Result("T-09.2", "As onze abas trocam o conteudo, e nao so' o destaque",
               "B-usuario")
    t0, passos = time.time(), []
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            nav = pw.chromium.launch(args=["--ignore-certificate-errors"])
            ctx = nav.new_context(ignore_https_errors=True, viewport={"width": 1500, "height": 1000})
            pagina = ctx.new_page()
            _entrar(pagina)
            _abrir_pelo_menu(pagina)

            visto: dict[str, str] = {}
            falhas: list[str] = []
            for rotulo in ABAS:
                aba = pagina.locator(f".v-tab:text-is('{rotulo}')").first
                if not aba.count():
                    falhas.append(f"{rotulo}: aba nao existe")
                    continue
                aba.click()
                # A troca e' no cliente; a aba ativa e' marcada pela PLATAFORMA.
                pagina.wait_for_selector(f".v-tab--active:text-is('{rotulo}')", timeout=20000)
                pagina.wait_for_timeout(1200)   # a secao busca os proprios dados
                texto = _painel_ativo(pagina)
                if not texto:
                    falhas.append(f"{rotulo}: painel ativo vazio")
                visto[rotulo] = texto

            # Duas abas servindo EXATAMENTE o mesmo painel e' o defeito antigo.
            iguais = []
            rotulos = list(visto)
            for i, a in enumerate(rotulos):
                for b in rotulos[i + 1:]:
                    if visto[a] and visto[a] == visto[b]:
                        iguais.append(f"{a} == {b}")
            passos.append(f"abas visitadas: {len(visto)}/{len(ABAS)}")
            passos.append(f"paineis identicos entre si: {iguais or 'nenhum'}")
            if falhas:
                passos.append("falhas: " + "; ".join(falhas))

            r.proof = _captura(pagina, "02-abas")
            r.passed = (len(visto) == len(ABAS) and not iguais and not falhas)
            r.steps = list(passos) + [f"{k}: {v[:90]}" for k, v in visto.items()]
            r.detail = passos[-1]
            ctx.close()
            nav.close()
    except Exception as e:                                  # noqa: BLE001
        r.passed = False
        r.detail = f"{type(e).__name__}: {e}"
        r.steps = list(passos) + [r.detail]
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def b_e_portlet_e_nao_webpagina(rec: Recorder) -> None:
    """Anti-regressao do defeito que originou esta reescrita.

    O console anterior imprimia o proprio documento HTML com 80 linhas de
    <style> e paleta propria. Aqui se exige o contrario: nenhuma folha de estilo
    propria dentro do portlet, e os componentes vindos do skin da plataforma.
    """
    r = Result("T-09.3", "A tela usa os componentes da plataforma, sem CSS propria",
               "B-usuario")
    t0, passos = time.time(), []
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            nav = pw.chromium.launch(args=["--ignore-certificate-errors"])
            ctx = nav.new_context(ignore_https_errors=True, viewport={"width": 1500, "height": 1000})
            pagina = ctx.new_page()
            _entrar(pagina)
            _abrir_pelo_menu(pagina)

            # 1. Nenhum <style> embutido no portlet.
            estilos = pagina.evaluate(
                "() => { const r = document.querySelector('#consoleDlp');"
                " return r ? r.querySelectorAll('style').length : -1; }")
            passos.append(f"<style> dentro do portlet: {estilos} (esperado 0)")

            # 2. As classes do console antigo nao podem reaparecer.
            legado = pagina.evaluate(
                "() => document.querySelectorAll("
                "'[class*=\"pmo-aba\"],[class*=\"pmo-cartao\"],[class*=\"pmo-dlp\"]').length")
            passos.append(f"elementos com classe do console antigo: {legado} (esperado 0)")

            # 3. Componentes da plataforma presentes.
            comps = pagina.evaluate(
                "() => ({tabs: document.querySelectorAll('.v-tabs').length,"
                " app: document.querySelectorAll('#consoleDlp.v-application').length"
                "  + document.querySelectorAll('.v-application #consoleDlp,"
                " #consoleDlp .v-window').length})")
            passos.append(f"componentes Vuetify: {comps}")

            # 4. A folha do portlet e' servida pelo agregador de skin do portal,
            #    e nao embutida: tem de aparecer entre os stylesheets da pagina.
            skin = pagina.evaluate(
                "() => [...document.styleSheets].map(s => s.href || '')"
                ".filter(h => h.includes('Stylesheet') || h.includes('skin')).length")
            passos.append(f"folhas de skin da plataforma carregadas: {skin}")

            r.proof = _captura(pagina, "03-padrao")
            r.passed = (estilos == 0 and legado == 0 and comps["tabs"] > 0 and skin > 0)
            r.steps = list(passos)
            r.detail = passos[0]
            ctx.close()
            nav.close()
    except Exception as e:                                  # noqa: BLE001
        r.passed = False
        r.detail = f"{type(e).__name__}: {e}"
        r.steps = list(passos) + [r.detail]
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def b_acao_real_pela_tela(rec: Recorder) -> None:
    """Uma acao de escrita, feita com teclado e mouse, conferida no servico."""
    r = Result("T-09.4", "Dicionario cadastrado na tela chega ao servico", "B-usuario")
    t0, passos = time.time(), []
    nome = f"teste-console-{RUN_ID}"
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            nav = pw.chromium.launch(args=["--ignore-certificate-errors"])
            ctx = nav.new_context(ignore_https_errors=True, viewport={"width": 1500, "height": 1000})
            pagina = ctx.new_page()
            _entrar(pagina)
            _abrir_pelo_menu(pagina)
            pagina.locator(".v-tab:text-is('Dicion\u00e1rios')").first.click()
            pagina.wait_for_selector(".v-tab--active:text-is('Dicion\u00e1rios')", timeout=20000)
            pagina.wait_for_timeout(1200)

            painel = pagina.locator(".v-window-item--active").first
            painel.locator("input[type='text']").first.fill(nome)
            painel.locator("textarea").first.fill("palavra-secreta-a, palavra-secreta-b")
            painel.locator("button:has-text('Gravar')").first.click()
            pagina.wait_for_timeout(2500)
            passos.append(f"dicionario {nome!r} submetido pela tela")

            # Conferencia pelo SERVICO: prova o efeito, nao a aparencia.
            # /dicionarios devolve {"cadastrados": {nome: entrada}, "em_uso": {...}}
            # — um MAPA indexado pelo nome, e nao uma lista. A versao anterior
            # deste teste procurava "itens" e por isso lia zero dicionarios
            # mesmo quando havia.
            dicionarios = dlp("/dicionarios")
            cadastrados = dicionarios.get("cadastrados", {})
            chegou = nome in cadastrados
            passos.append(f"o servico tem {len(cadastrados)} dicionario(s); "
                          f"{nome!r} presente: {chegou}")
            if chegou:
                termos = cadastrados[nome].get("termos", [])
                passos.append(f"termos gravados: {termos}")
                # O autor tem de ser quem o PORTAL autenticou, e nao o que o
                # navegador alegou: o carimbo e' feito no servidor.
                trilha = dlp("/auditoria?limite=25").get("itens", [])
                marca = [t for t in trilha
                         if t.get("acao") == "DICIONARIO_GRAVADO" and t.get("alvo") == nome]
                autor = marca[0].get("autor") if marca else None
                passos.append(f"autor registrado na trilha: {autor!r} "
                              f"(esperado {USUARIO!r})")
                chegou = chegou and autor == USUARIO

            r.proof = _captura(pagina, "04-dicionario")
            r.passed = chegou
            r.steps = list(passos)
            r.detail = passos[-1]
            ctx.close()
            nav.close()
    except Exception as e:                                  # noqa: BLE001
        r.passed = False
        r.detail = f"{type(e).__name__}: {e}"
        r.steps = list(passos) + [r.detail]
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_notificacao_sai(rec: Recorder) -> None:
    """NOTIFICAR_ADMIN deixa de ser nome numa lista: o e-mail chega ao relay."""
    r = Result("T-09.5", "Aviso de DLP e' entregue de verdade no servidor de mail",
               "A-maquina")
    t0, passos = time.time(), []
    try:
        fila = dlp("/notificacoes?limite=20")
        passos.append(f"fila: {fila['resumo']}")
        enviadas = fila["resumo"].get("ENVIADA", 0)
        assunto = ""
        for item in fila["itens"]:
            if item["estado"] == "ENVIADA":
                assunto = item["assunto"]
                break
        passos.append(f"assunto do ultimo aviso enviado: {assunto!r}")

        caixa = mailpit("/api/v1/messages?limit=50")
        recebidos = [m for m in caixa.get("messages", [])
                     if m.get("Subject", "").startswith("[DLP]")]
        passos.append(f"mensagens [DLP] no Mailpit: {len(recebidos)}")
        if recebidos:
            passos.append(f"ultima: {recebidos[0].get('Subject')!r} para "
                          f"{[t.get('Address') for t in recebidos[0].get('To', [])]}")
            # O aviso NAO pode carregar o valor sensivel.
            corpo = mailpit(f"/api/v1/message/{recebidos[0]['ID']}")
            texto = corpo.get("Text", "")
            vazou = CPF_EXEMPLO in texto
            passos.append(f"CPF dentro do aviso: {'SIM (defeito)' if vazou else 'nao'}")
        else:
            vazou = False
        r.passed = enviadas > 0 and len(recebidos) > 0 and not vazou
        r.steps = list(passos)
        r.detail = passos[-1] if passos else ""
        r.proof = " | ".join(passos)
    except Exception as e:                                  # noqa: BLE001
        r.passed = False
        r.detail = f"{type(e).__name__}: {e}"
        r.steps = list(passos) + [r.detail]
        r.proof = r.detail
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("test_09_dlp_console")
    b_chega_pelo_menu(rec)
    b_abas_trocam_conteudo(rec)
    b_e_portlet_e_nao_webpagina(rec)
    b_acao_real_pela_tela(rec)
    a_notificacao_sai(rec)
    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
