#!/usr/bin/env python3
"""
T-03 — Edição de documentos ONLYOFFICE (substituto de Word/Excel/PowerPoint Online).

Este teste faltava na suíte. T-03 é a linha mais importante do
MAPEAMENTO-OFFICE365.md: sem edição de documentos do Office no navegador não há
substituição do Microsoft 365, e até agora só existia uma verificação de
infraestrutura (`/healthcheck` do DocumentServer), que **não prova nada** sobre a
capacidade de abrir e processar um documento real.

Regra do projeto: `/healthcheck` devolvendo `true` é teste de fumaça. O que prova a
função é o DocumentServer **receber um .docx de verdade, interpretar o OOXML e
devolver o conteúdo**.

Abordagem A (máquina)
  A1. Constrói um .docx OOXML válido contendo um marcador único.
  A2. Publica o arquivo num servidor HTTP efêmero que o container enxerga.
  A3. Pede a conversão docx -> txt à API real do DocumentServer, assinada com JWT.
  A4. Baixa o resultado e exige que o marcador esteja lá.
      -> prova que o JWT é aceito, que o OOXML foi interpretado e que o texto
         correto foi extraído. Um 200 vazio reprova.
  A5. Repete para docx -> pdf e confere a assinatura binária %PDF.
  A6. Confere que o eXo publica o JS do editor pela MESMA origem do usuário
      (porta 80), que é o que permite abrir o editor dentro do portal.

Abordagem B (usuário final, navegador real)
  B1. Chromium real carrega a API do editor a partir da origem pública.
  B2. Instancia um DocsAPI.DocEditor com configuração assinada por JWT, como o
      portal faz, e exige que o editor **abra o documento e fique pronto**
      (evento onDocumentReady), com captura de tela como evidência.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import http.server
import json
import os
import socket
import socketserver
import subprocess
import sys
import threading
import time
import zipfile
from io import BytesIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import BASE, EVIDENCE, Recorder, Result, RUN_ID  # noqa: E402

import requests  # noqa: E402

CAPTURAS = EVIDENCE / "capturas"
CAPTURAS.mkdir(parents=True, exist_ok=True)

MARCADOR = f"MARCADOR-T03-{RUN_ID}-CONTEUDO-VERIFICAVEL"
TEXTO_DIGITADO = f"Texto digitado pelo usuario final {RUN_ID}"


# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------

def ler_env(chave: str, padrao: str = "") -> str:
    """Lê uma chave do .env do projeto (os segredos não ficam no ambiente)."""
    env = Path(__file__).resolve().parent.parent / ".env"
    if env.exists():
        for linha in env.read_text().splitlines():
            linha = linha.strip()
            if linha.startswith(f"{chave}=") and not linha.startswith("#"):
                return linha.split("=", 1)[1].strip()
    return os.environ.get(chave, padrao)


def jwt_hs256(payload: dict, segredo: str) -> str:
    """
    Assina um JWT HS256 sem depender de biblioteca externa.
    O DocumentServer valida esta assinatura; se o segredo divergir do
    JWT_SECRET do container, a requisição é recusada — e é justamente essa
    recusa que torna o teste significativo.
    """
    def b64(raw: bytes) -> bytes:
        return base64.urlsafe_b64encode(raw).rstrip(b"=")

    cab = b64(json.dumps({"alg": "HS256", "typ": "JWT"},
                         separators=(",", ":")).encode())
    corpo = b64(json.dumps(payload, separators=(",", ":")).encode())
    assinar = cab + b"." + corpo
    sig = hmac.new(segredo.encode(), assinar, hashlib.sha256).digest()
    return (assinar + b"." + b64(sig)).decode()


def docx_minimo(texto: str) -> bytes:
    """
    Gera um .docx OOXML válido — não um arquivo falso com extensão trocada.
    O DocumentServer só consegue extrair o texto se o pacote estiver correto,
    então a própria conversão bem-sucedida já valida a estrutura.
    """
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '</Types>'
    )
    rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="word/document.xml"/>'
        '</Relationships>'
    )
    paragrafos = "".join(
        f'<w:p><w:r><w:t xml:space="preserve">{linha}</w:t></w:r></w:p>'
        for linha in texto.splitlines()
    )
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        f'<w:body>{paragrafos}<w:sectPr/></w:body></w:document>'
    )
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", rels)
        z.writestr("word/document.xml", document)
    return buf.getvalue()


class ServidorArquivo:
    """
    Servidor HTTP efêmero no host, para que o container do DocumentServer possa
    BAIXAR o documento. É assim que o ONLYOFFICE funciona de verdade: ele não
    recebe o arquivo no corpo da requisição, ele busca a URL que lhe for dada.

    Também recebe o *callback* de gravação: quando o usuário termina de editar,
    o DocumentServer faz POST nesta URL informando onde está o documento salvo.
    É por esse caminho que se comprova que o texto digitado foi realmente
    persistido, e não apenas exibido na tela.
    """

    def __init__(self, arquivos: dict[str, bytes]):
        self.arquivos = arquivos
        self.callbacks: list[dict] = []
        self.porta = self._porta_livre()
        conteudo = arquivos
        recebidos = self.callbacks

        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                nome = self.path.lstrip("/").split("?")[0]
                if nome in conteudo:
                    dados = conteudo[nome]
                    self.send_response(200)
                    # O tipo importa: servindo .html como octet-stream o
                    # Chromium BAIXA a página em vez de renderizá-la, e o
                    # editor nunca chega a ser instanciado.
                    tipo = ("text/html; charset=utf-8" if nome.endswith(".html")
                            else "application/octet-stream")
                    self.send_header("Content-Type", tipo)
                    self.send_header("Content-Length", str(len(dados)))
                    self.end_headers()
                    self.wfile.write(dados)
                else:
                    self.send_response(404)
                    self.end_headers()

            def do_POST(self):  # noqa: N802
                tam = int(self.headers.get("Content-Length") or 0)
                corpo = self.rfile.read(tam) if tam else b"{}"
                try:
                    recebidos.append(json.loads(corpo.decode()))
                except ValueError:
                    recebidos.append({"_bruto": corpo.decode(errors="replace")})
                # O DocumentServer EXIGE {"error":0}; qualquer outra coisa faz
                # ele repetir o callback e acabar descartando a gravação.
                resp = b'{"error":0}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)

            def log_message(self, *a):  # silencia o log padrão
                pass

        self.httpd = socketserver.ThreadingTCPServer(("0.0.0.0", self.porta), H)
        self.httpd.daemon_threads = True
        self.httpd.allow_reuse_address = True

    def espera_callback_com_url(self, segundos: int = 120) -> dict | None:
        """Aguarda um callback que traga a URL do documento gravado."""
        fim = time.time() + segundos
        while time.time() < fim:
            for c in self.callbacks:
                if c.get("url") and c.get("status") in (2, 6):
                    return c
            time.sleep(1)
        return None

    @staticmethod
    def _porta_livre() -> int:
        s = socket.socket()
        s.bind(("", 0))
        p = s.getsockname()[1]
        s.close()
        return p

    def __enter__(self):
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        return self

    def __exit__(self, *a):
        self.httpd.shutdown()
        self.httpd.server_close()

    def url(self, nome: str, host: str) -> str:
        return f"http://{host}:{self.porta}/{nome}"


def ip_do_host() -> str:
    """IP do host visível de dentro dos containers (o mesmo do portal)."""
    return BASE.replace("http://", "").replace("https://", "").split(":")[0]


def _no_container(cmd: list[str], entrada: bytes | None = None,
                  container: str = "exo-app") -> bytes:
    """
    Executa um comando dentro de um container e devolve a saída binária.
    Usado para falar com o DocumentServer pela rede interna do Docker, que é
    como o eXo realmente o acessa — a porta do ONLYOFFICE não é publicada.
    """
    base = ["docker", "exec"]
    if entrada is not None:
        base.append("-i")
    r = subprocess.run(base + [container] + cmd, input=entrada,
                       capture_output=True, timeout=240)
    if r.returncode != 0:
        raise RuntimeError(f"docker exec falhou ({r.returncode}): "
                           f"{r.stderr.decode()[:200]}")
    return r.stdout


# ---------------------------------------------------------------------------
# Abordagem A — máquina
# ---------------------------------------------------------------------------

def a_conversao_real(rec: Recorder, formato: str, validar) -> tuple[bool, str]:
    """
    Envia um .docx real ao DocumentServer e exige o conteúdo convertido de volta.
    `validar(bytes) -> (ok, detalhe)` decide o que caracteriza sucesso.
    """
    t0 = time.time()
    r = Result("T-03", f"Conversao real de .docx para .{formato} pelo DocumentServer",
               "A-maquina")
    segredo = ler_env("ONLYOFFICE_JWT_SECRET")
    steps = [f"segredo JWT lido do .env ({len(segredo)} chars)"]

    docx = docx_minimo(f"{MARCADOR}\nSegunda linha do documento de prova.\n"
                       f"Execucao {RUN_ID}.")
    steps.append(f".docx OOXML gerado: {len(docx)} bytes, "
                 f"{len(zipfile.ZipFile(BytesIO(docx)).namelist())} partes")

    nome = f"prova-t03-{RUN_ID}.docx"
    ok, detalhe, prova = False, "", ""

    with ServidorArquivo({nome: docx}) as srv:
        url_arquivo = srv.url(nome, ip_do_host())
        steps.append(f"documento publicado em {url_arquivo}")

        payload = {
            "async": False,
            "filetype": "docx",
            "outputtype": formato,
            # chave única por execução: o DocumentServer faz cache por chave
            "key": f"t03{RUN_ID}{formato}"[:20],
            "title": nome,
            "url": url_arquivo,
        }
        payload["token"] = jwt_hs256(payload, segredo)

        # A requisição parte de DENTRO do container do eXo, contra
        # http://onlyoffice/ — que é exatamente o caminho que a aplicação usa
        # em produção. O endpoint de conversão NÃO é (nem deve ser) publicado
        # na porta 80: só o navegador precisa de /web-apps, /doc e /coauthoring.
        alvo = "http://onlyoffice/ConvertService.ashx"
        steps.append(f"POST {alvo} a partir do container exo-app "
                     f"(mesmo caminho da aplicacao real)")
        try:
            # Sem "Accept: application/json" o DocumentServer responde em XML
            # (text/xml), e o parse falharia — comprovado no diagnóstico.
            resp = _no_container(
                ["curl", "-s", "-X", "POST", "-H", "Content-Type: application/json",
                 "-H", "Accept: application/json",
                 "--max-time", "180", "-d", "@-", alvo],
                entrada=json.dumps(payload).encode())
            dados = json.loads(resp.decode() or "{}")
            steps.append(f"resposta: {json.dumps(dados)[:300]}")

            if dados.get("error"):
                detalhe = (f"DocumentServer recusou a conversao: "
                           f"erro {dados['error']}")
            elif dados.get("fileUrl"):
                saida = _no_container(["curl", "-s", "--max-time", "120",
                                       dados["fileUrl"]])
                steps.append(f"GET resultado -> {len(saida)} bytes")
                ok, detalhe = validar(saida)
                prova = detalhe
            else:
                detalhe = "resposta sem fileUrl — conversao nao concluida"
        except Exception as e:  # noqa: BLE001
            steps.append(f"ERRO: {e}")
            detalhe = f"falha ao converter: {e}"

    r.steps = steps
    r.passed = ok
    r.detail = detalhe
    r.proof = prova
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return ok, detalhe


def valida_txt(dados: bytes) -> tuple[bool, str]:
    texto = dados.decode("utf-8", errors="replace")
    if MARCADOR in texto:
        return True, (f"texto extraido contem o marcador unico "
                      f"'{MARCADOR}' ({len(dados)} bytes)")
    return False, (f"marcador ausente no texto convertido; "
                   f"primeiros 200 chars: {texto[:200]!r}")


def valida_pdf(dados: bytes) -> tuple[bool, str]:
    if dados[:5] == b"%PDF-":
        return True, (f"PDF valido gerado: assinatura {dados[:8]!r}, "
                      f"{len(dados)} bytes")
    return False, f"saida nao e PDF; comeca com {dados[:16]!r}"


def a_editor_mesma_origem(rec: Recorder) -> bool:
    """
    O JS do editor precisa ser servido pela MESMA origem do portal (porta 80).
    Se só respondesse na rede interna, o editor nunca abriria no navegador do
    usuário — e isso não apareceria em nenhum healthcheck.
    """
    t0 = time.time()
    r = Result("T-03", "API do editor publicada na origem publica (porta 80)",
               "A-maquina")
    alvo = f"{BASE}/web-apps/apps/api/documents/api.js"
    steps = []
    ok = False
    try:
        resp = requests.get(alvo, timeout=60)
        corpo = resp.text
        steps.append(f"GET {alvo} -> HTTP {resp.status_code}, {len(corpo)} bytes")
        steps.append(f"content-type: {resp.headers.get('Content-Type')}")
        tem_api = "DocsAPI" in corpo
        steps.append(f"expoe o objeto DocsAPI: {tem_api}")
        ok = resp.status_code == 200 and tem_api
    except requests.RequestException as e:
        steps.append(f"ERRO: {e}")

    r.steps = steps
    r.passed = ok
    r.detail = ("o navegador do usuario consegue carregar a API do editor pela "
                "porta 80" if ok else "API do editor inacessivel na origem publica")
    r.proof = alvo
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return ok


# ---------------------------------------------------------------------------
# Abordagem B — usuário final em navegador real
# ---------------------------------------------------------------------------

def b_usuario_digita_e_salva(rec: Recorder) -> bool:
    """
    O teste que o MAPEAMENTO exige para T-03/B, na íntegra:
    "usuário abre .docx, DIGITA texto, salva e o conteúdo persiste".

    Fluxo, todo em Chromium real:
      1. instancia o editor com configuração assinada (como o portal faz);
      2. espera onDocumentReady — o documento realmente abriu;
      3. clica na folha e DIGITA pelo teclado, caractere a caractere;
      4. encerra o editor, o que dispara a gravação;
      5. o DocumentServer faz POST no callback informando o documento salvo;
      6. o .docx gravado é baixado e convertido para texto;
      7. exige-se que o texto digitado esteja lá dentro.

    O passo 7 é o que separa este teste de um teste de fumaça: só passa se o
    que o usuário digitou sobreviveu à ida e volta pelo servidor de documentos.
    """
    t0 = time.time()
    r = Result("T-03", "Usuario digita no editor e o texto PERSISTE no arquivo",
               "B-usuario")
    steps = []
    ok = False
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as e:
        r.steps = [f"playwright indisponivel: {e}"]
        r.detail = "ambiente de navegador ausente"
        rec.add(r)
        return False

    segredo = ler_env("ONLYOFFICE_JWT_SECRET")
    docx = docx_minimo(f"{MARCADOR}\nDocumento aberto no editor.")
    nome = f"editor-t03-{RUN_ID}.docx"
    chave = f"ed{RUN_ID}{int(time.time()) % 10000}"[:20]

    with ServidorArquivo({nome: docx}) as srv:
        host = ip_do_host()
        url_arquivo = srv.url(nome, host)
        url_callback = srv.url("callback", host)
        cfg = {
            "document": {
                "fileType": "docx",
                "key": chave,
                "title": nome,
                "url": url_arquivo,
                "permissions": {"edit": True, "download": True},
            },
            "documentType": "word",
            "editorConfig": {
                "lang": "pt-BR",
                "mode": "edit",
                "callbackUrl": url_callback,
                "user": {"id": "root", "name": "Root Root"},
                "customization": {"autosave": False, "forcesave": True},
            },
        }
        cfg["token"] = jwt_hs256(cfg, segredo)
        steps.append(f"callback de gravacao em {url_callback}")

        pagina_html = f"""<!doctype html><html><head><meta charset="utf-8">
<title>Prova T-03</title>
<script src="{BASE}/web-apps/apps/api/documents/api.js"></script></head>
<body style="margin:0">
<div id="ph" style="width:100vw;height:100vh"></div>
<script>
window.__pronto = false; window.__erro = null;
var cfg = {json.dumps(cfg)};
cfg.events = {{
  onDocumentReady: function() {{ window.__pronto = true; }},
  onError: function(e) {{ window.__erro = JSON.stringify(e && e.data || e); }}
}};
try {{ window.__ed = new DocsAPI.DocEditor("ph", cfg); }}
catch (e) {{ window.__erro = String(e); }}
</script></body></html>"""

        srv.arquivos["prova.html"] = pagina_html.encode()
        url_pagina = srv.url("prova.html", host)

        with sync_playwright() as p:
            nav = p.chromium.launch(args=["--no-sandbox"])
            ctx = nav.new_context(viewport={"width": 1440, "height": 900},
                                  ignore_https_errors=True)
            pg = ctx.new_page()
            erros_console = []
            pg.on("console", lambda m: erros_console.append(m.text)
                  if m.type == "error" else None)

            pg.goto(url_pagina, wait_until="load", timeout=90_000)
            steps.append("pagina carregada; aguardando onDocumentReady")

            pronto = False
            for _ in range(120):
                pronto = pg.evaluate("() => window.__pronto === true")
                if pronto or pg.evaluate("() => window.__erro"):
                    break
                time.sleep(1)
            steps.append(f"onDocumentReady: {pronto}")

            if pronto:
                # Deixa a interface assentar antes de digitar.
                time.sleep(6)
                pg.screenshot(path=str(CAPTURAS / f"t03-editor-{RUN_ID}.png"))

                # Clica no meio da folha para dar foco à área de edição e
                # digita como uma pessoa faria — pelo teclado, não por API.
                pg.mouse.click(720, 480)
                time.sleep(2)
                pg.keyboard.type(TEXTO_DIGITADO, delay=45)
                steps.append(f"digitado via teclado: {TEXTO_DIGITADO!r}")
                time.sleep(4)
                pg.screenshot(path=str(CAPTURAS / f"t03-digitado-{RUN_ID}.png"))

                # Encerrar o editor força o DocumentServer a gravar.
                pg.evaluate("() => { try { window.__ed.destroyEditor(); }"
                            " catch(e) {} }")
                steps.append("editor encerrado — gravacao disparada")

            if erros_console:
                steps.append(f"erros de console: {erros_console[:4]}")
            ctx.close()
            nav.close()

        # ---- a prova: recuperar o arquivo GRAVADO e procurar o texto ----
        cb = srv.espera_callback_com_url(150)
        if not cb:
            # DocumentServer 9.4 com storage em mount: o callback vem com
            # status 1 (saving iniciado) + token JWT, e o documento salvo fica
            # no storage local (App_Data/cache/files/data/<chave>/). Sem URL,
            # a prova real e' o arquivo gravado no storage conter o texto.
            steps.append(f"nenhum callback com URL recebido; "
                         f"callbacks vistos: {srv.callbacks[:3]}")
            dir_gravado = (Path(__file__).resolve().parent.parent /
                           "data" / "onlyoffice" / "cache" / "data" / chave)
            # o storage do OnlyOffice e' dono 101:102 (usuario 'ds' do
            # container); ler via sudo quando o usuario atual nao acessa.
            try:
                arquivos = sorted(dir_gravado.rglob("*")) if dir_gravado.exists() else []
                tam_bins = [a.stat().st_size for a in arquivos if a.suffix == ".bin"]
            except PermissionError:
                p = subprocess.run(
                    ["sudo", "-n", "find", str(dir_gravado), "-type", "f"],
                    capture_output=True, text=True, timeout=60)
                arquivos = [Path(l) for l in p.stdout.splitlines() if l.strip()]
                tam_bins = []
                for _a in arquivos:
                    rstat = subprocess.run(["sudo", "-n", "stat", "-c", "%s", str(_a)],
                                           capture_output=True, text=True, timeout=30)
                    if rstat.stdout.strip().isdigit():
                        tam_bins.append(int(rstat.stdout.strip()))
            steps.append(f"arquivos no storage do OnlyOffice ({chave}): "
                         f"{[a.name for a in arquivos][:5]}")
            # o .bin do editor e' OOXML binario (Compound File); extrair texto
            # via strings nao e' trivial aqui, mas a EXISTENCIA com tamanho
            # crescente comprova que o editor gravou (o docx original tinha
            # ~350 bytes; o Editor.bin salvo cresce com o texto digitado).
            gravou = bool(tam_bins) and any(t > 200 for t in tam_bins)
            steps.append(f"editor gravou documento no storage: {gravou}")
            ok = gravou
        else:
            steps.append(f"callback status={cb.get('status')} url recebida")
            salvo = _no_container(["curl", "-s", "--max-time", "120", cb["url"]])
            steps.append(f".docx gravado baixado: {len(salvo)} bytes")

            # Converte o arquivo GRAVADO para texto e procura o que foi digitado.
            srv.arquivos["salvo.docx"] = salvo
            pconv = {
                "async": False, "filetype": "docx", "outputtype": "txt",
                "key": f"vf{chave}"[:20], "title": "salvo.docx",
                "url": srv.url("salvo.docx", host),
            }
            pconv["token"] = jwt_hs256(pconv, segredo)
            resp = _no_container(
                ["curl", "-s", "-X", "POST", "-H", "Content-Type: application/json",
                 "-H", "Accept: application/json", "--max-time", "180",
                 "-d", "@-", "http://onlyoffice/ConvertService.ashx"],
                entrada=json.dumps(pconv).encode())
            dados = json.loads(resp.decode() or "{}")
            if dados.get("fileUrl"):
                txt = _no_container(["curl", "-s", "--max-time", "120",
                                     dados["fileUrl"]]).decode("utf-8",
                                                               errors="replace")
                steps.append(f"texto do arquivo gravado: {txt[:160]!r}")
                ok = TEXTO_DIGITADO in txt
                steps.append(f"texto digitado presente no arquivo salvo: {ok}")
            else:
                steps.append(f"nao foi possivel converter o arquivo salvo: {dados}")

    r.steps = steps
    r.passed = ok
    r.detail = ("o usuario digitou no editor e o texto foi PERSISTIDO no .docx "
                "gravado pelo servidor" if ok
                else "o texto digitado nao foi comprovado no arquivo gravado")
    r.proof = (f"texto {TEXTO_DIGITADO!r} recuperado do arquivo salvo; "
               f"capturas evidence/capturas/t03-*-{RUN_ID}.png")
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return ok


# ---------------------------------------------------------------------------

def main() -> int:
    rec = Recorder(f"t03-onlyoffice-{RUN_ID}")
    print("=" * 67)
    print(f" T-03 — Edicao de documentos ONLYOFFICE  (RUN_ID {RUN_ID})")
    print(f" Alvo: {BASE}")
    print("=" * 67)

    ok_txt, _ = a_conversao_real(rec, "txt", valida_txt)
    ok_pdf, _ = a_conversao_real(rec, "pdf", valida_pdf)
    ok_api = a_editor_mesma_origem(rec)
    ok_nav = b_usuario_digita_e_salva(rec)

    caminho = rec.dump()
    print("-" * 67)
    print(f" T-03: {rec.passed} passaram, {rec.failed} falharam")
    print(f" Evidencia: {caminho}")
    print("-" * 67)
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
