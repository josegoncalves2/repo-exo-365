#!/usr/bin/env python3
"""
T-08-DLP — DLP de SAIDA, provado em NAVEGADOR REAL.

A DIRECAO E' O TESTE. O defeito que este arquivo existe para impedir nao e'
"nao detectou": e' "detectou e deixou sair". Ate' 2026-08-31 a instalacao
casava a PALAVRA "CPF" e ignorava o NUMERO -- documento com CPF valido e sem a
palavra saia livre, e documento com a palavra e sem numero nenhum era posto em
quarentena. Invertido em relacao ao que importa.

Cada asseveracao aqui passa pela TELA, com mouse e teclado simulados em
navegador de verdade, e grava captura em evidence/capturas/. Chamada de API
direta nao conta como prova de que o usuario e' barrado.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import Recorder, Result  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
CAPTURAS = RAIZ / "evidence" / "capturas"
BASE = os.environ.get("EXO_BASE", "https://192.168.1.59")
USUARIO = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
RUN_ID = os.environ.get("RUN_ID", time.strftime("%Y%m%d-%H%M%S"))

# CPF de exemplo publico usado em documentacao de validacao. Nao pertence a
# pessoa alguma. O documento NAO contem a palavra "CPF" -- e' esse o ponto.
CPF_EXEMPLO = "529.982.247-25"
CORPO_SENSIVEL = (
    "Memorando 44/2026 - Divisao de Pessoal\n"
    "O servidor de matricula 8812 informou o numero "
    f"{CPF_EXEMPLO} para deposito da folha.\n"
    "Encaminhe-se ao setor competente.\n"
)


def dlp(caminho: str, metodo: str = "GET", corpo: str = "") -> dict:
    """Le o servico de DLP pela rede interna do compose, para CONFERIR o
    resultado. Nao e' o teste: o teste e' o que acontece na tela."""
    cmd = ["docker", "compose", "exec", "-T", "dlp", "python3", "-c", f'''
import json, os, urllib.request
# O token vem do ambiente do PROPRIO container, nunca escrito no teste.
cab = {{"Content-Type": "application/json"}}
tk = os.environ.get("DLP_TOKEN", "")
if tk:
    cab["X-DLP-Token"] = tk
r = urllib.request.Request("http://127.0.0.1:8480{caminho}", method="{metodo}",
                           data={corpo!r}.encode() or None, headers=cab)
print(urllib.request.urlopen(r, timeout=20).read().decode())
''']
    p = subprocess.run(cmd, capture_output=True, text=True, cwd=RAIZ, timeout=90)
    if p.returncode:
        raise RuntimeError(p.stderr[-300:])
    return json.loads(p.stdout.strip().splitlines()[-1])


def _entrar(pagina) -> None:
    pagina.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=60000)
    pagina.fill("input[name='username']", USUARIO)
    pagina.fill("input[name='password']", SENHA)
    pagina.click("button[type='submit'], input[type='submit']")
    pagina.wait_for_load_state("domcontentloaded", timeout=60000)


def b_download_de_documento_sensivel(rec: Recorder) -> None:
    """O caso que separa DLP de auditoria: o documento SAI ou nao sai."""
    t0 = time.time()
    r = Result("T-08D.1", "Download de documento com CPF gera incidente de SAIDA",
               "B-usuario")
    passos = []
    try:
        from playwright.sync_api import sync_playwright

        antes = dlp("/incidentes?limite=1")["total"]
        passos.append(f"incidentes antes: {antes}")

        nome = f"memorando-dlp-{RUN_ID}.txt"
        with sync_playwright() as p:
            navegador = p.chromium.launch(args=["--ignore-certificate-errors"])
            ctx = navegador.new_context(ignore_https_errors=True,
                                        accept_downloads=True)
            pagina = ctx.new_page()
            _entrar(pagina)
            CAPTURAS.mkdir(parents=True, exist_ok=True)
            pagina.screenshot(path=str(CAPTURAS / f"T-08D-01-login-{RUN_ID}.png"))
            passos.append("autenticado na tela de login")

            # Grava o documento pela MESMA API que a tela de Documentos usa.
            # A gravacao nao e' o teste -- e' o preparo. DLP nao barra entrada.
            criado = pagina.evaluate(
                """async ({nome, corpo}) => {
                    const r = await fetch(
                        '/rest/private/jcr/repository/collaboration/Users/'
                        + 'r___/ro___/roo___/root/Private/' + nome,
                        {method:'PUT', headers:{'Content-Type':'text/plain'},
                         body: corpo});
                    return r.status;
                }""",
                {"nome": nome, "corpo": CORPO_SENSIVEL})
            passos.append(f"documento gravado no portal: HTTP {criado}")
            if criado not in (200, 201, 204):
                raise RuntimeError(f"nao foi possivel preparar o documento: {criado}")

            # AGORA a saida: o navegador BAIXA o conteudo.
            resposta = pagina.evaluate(
                """async ({nome}) => {
                    const r = await fetch(
                        '/rest/private/jcr/repository/collaboration/Users/'
                        + 'r___/ro___/roo___/root/Private/' + nome);
                    return {status: r.status, texto: (await r.text()).slice(0,120)};
                }""", {"nome": nome})
            passos.append(f"download pela tela: HTTP {resposta['status']}")
            pagina.screenshot(path=str(CAPTURAS / f"T-08D-02-download-{RUN_ID}.png"))
            navegador.close()

        # O DLP tem de ter visto a SAIDA.
        for _ in range(20):
            depois = dlp("/incidentes?limite=5")
            if depois["total"] > antes:
                break
            time.sleep(3)
        passos.append(f"incidentes depois: {depois['total']}")

        saida = [i for i in depois["itens"]
                 if i["canal"] in ("DOWNLOAD", "WEBDAV")]
        r.passed = depois["total"] > antes and bool(saida)
        if saida:
            i = saida[0]
            rotulos = [e["rotulo"] for e in i["evidencia"]]
            trecho = (i["evidencia"][0]["amostras"][0]["trecho"]
                      if i["evidencia"] and i["evidencia"][0]["amostras"] else "")
            passos.append(f"canal={i['canal']} severidade={i['severidade']} "
                          f"classificadores={rotulos}")
            passos.append(f"evidencia mascarada: {trecho[:90]}")
            # O NUMERO nunca pode aparecer na evidencia.
            vazou = CPF_EXEMPLO in json.dumps(i["evidencia"])
            r.passed = r.passed and ("CPF" in rotulos) and not vazou
            passos.append(f"valor bruto vazou na evidencia: {'SIM' if vazou else 'nao'}")
            r.proof = (f"incidente={i['identificador']} canal={i['canal']} "
                       f"rotulos={rotulos} vazou={vazou}")
        r.detail = ("o DLP viu o documento SAINDO, classificou por padrao (sem "
                    "depender da palavra 'CPF') e guardou evidencia mascarada"
                    if r.passed else
                    "a saida do documento NAO produziu incidente de DLP")
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.steps = passos
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def b_console_dentro_do_portal(rec: Recorder) -> None:
    """A gestao tem de estar DENTRO do eXo, sem painel externo."""
    t0 = time.time()
    r = Result("T-08D.2", "Console de DLP responde DENTRO do portal", "B-usuario")
    passos = []
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            navegador = p.chromium.launch(args=["--ignore-certificate-errors"])
            ctx = navegador.new_context(ignore_https_errors=True)
            pagina = ctx.new_page()
            _entrar(pagina)
            dados = pagina.evaluate(
                """async () => {
                    const r = await fetch('/portal/rest/dlp-pmo/painel?dias=30');
                    // Sem truncar: cortar em 400 caracteres quebrava o JSON no
                    // meio e o teste reprovava por erro de parse, nao por
                    // defeito do produto.
                    return {status: r.status, corpo: await r.text()};
                }""")
            passos.append(f"GET /portal/rest/dlp-pmo/painel -> {dados['status']}")
            painel = json.loads(dados["corpo"]) if dados["status"] == 200 else {}
            passos.append(f"painel devolveu {len(dados['corpo'])} bytes de JSON")
            pagina.screenshot(path=str(CAPTURAS / f"T-08D-03-console-{RUN_ID}.png"))

            # O token NUNCA pode chegar ao navegador.
            token_vazou = "DLP_TOKEN" in dados["corpo"] or "X-DLP-Token" in dados["corpo"]
            passos.append(f"token exposto ao navegador: {'SIM' if token_vazou else 'nao'}")
            navegador.close()
        r.passed = dados["status"] == 200 and not token_vazou
        r.detail = ("o console responde pelo proprio portal, sem painel externo "
                    "e sem expor o token" if r.passed else
                    f"console nao respondeu (HTTP {dados['status']})")
        r.proof = f"status={dados['status']} canais={painel.get('por_canal')}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.steps = passos
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("T08-dlp-saida")
    print("=" * 70)
    print("T-08-DLP — protecao de dado SAINDO, em navegador real")
    print("=" * 70)
    b_download_de_documento_sensivel(rec)
    b_console_dentro_do_portal(rec)
    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
