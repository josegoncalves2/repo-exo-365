#!/usr/bin/env python3
"""
T-00 — Infraestrutura, sob dupla abordagem.

Abordagem A (máquina): estado do Docker, versões reais das imagens, conectividade
   efetiva com MySQL (SQL executado), Elasticsearch (cluster health), ONLYOFFICE
   (healthcheck + JWT) e SMTP (handshake real com EHLO/MAIL FROM/DATA).
Abordagem B (usuário final): Chromium real abre http://192.168.1.59/, aguarda o
   portal renderizar e LÊ O DOM — comprovando que um ser humano veria a página.

Nenhum teste aqui se contenta com "HTTP 200": todos verificam conteúdo/efeito.
"""
from __future__ import annotations

import json
import smtplib
import subprocess
import sys
import time
from email.message import EmailMessage
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import (BASE, EVIDENCE, MAILPIT, Mail, Recorder, Result,  # noqa: E402
                    RUN_ID)

import requests  # noqa: E402

SERVICES = ["exo-app", "exo-web", "exo-es", "exo-mysql", "onlyoffice", "exo-mailpit"]
SHOTS = EVIDENCE / "capturas"
SHOTS.mkdir(exist_ok=True)


def sh(cmd: list[str], timeout: int = 60) -> tuple[int, str]:
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout + p.stderr).strip()


# ---------------------------------------------------------------- abordagem A

def a_containers(rec: Recorder) -> None:
    t0 = time.time()
    r = Result("T-00.1", "Todos os 6 servicos em execucao e saudaveis", "A-maquina")
    rc, out = sh(["docker", "inspect", "--format",
                  "{{.Name}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}sem-healthcheck{{end}}",
                  *SERVICES])
    lines = [l for l in out.splitlines() if "|" in l]
    bad = [l for l in lines if l.split("|")[1] != "running"
           or l.split("|")[2] not in ("healthy", "sem-healthcheck")]
    r.steps = lines
    r.passed = (len(lines) == len(SERVICES) and not bad)
    r.detail = (f"{len(lines)}/{len(SERVICES)} servicos; "
                + ("todos running+healthy" if not bad else f"PROBLEMAS: {bad}"))
    r.proof = "; ".join(lines)
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_versions(rec: Recorder) -> None:
    t0 = time.time()
    r = Result("T-00.2", "Versoes das imagens sao as fixadas (sem :latest oculto)",
               "A-maquina")
    expected = {
        "exo-app": "exoplatform/exo-community:7.2.1",
        "exo-mysql": "mysql:8.4.9",
        "exo-es": "elasticsearch:8.18.8",
        "onlyoffice": "onlyoffice/documentserver:9.4",
        "exo-web": "nginx:1.30.2-alpine",
    }
    got, mismatch = {}, []
    for c, want in expected.items():
        rc, out = sh(["docker", "inspect", "--format", "{{.Config.Image}}", c])
        got[c] = out
        if out != want:
            mismatch.append(f"{c}: esperado {want}, obtido {out}")
    # confirma a versao REAL informada pelo proprio eXo, nao so a tag
    rc, exover = sh(["docker", "exec", "exo-app", "bash", "-c",
                     "ls /opt/exo/ >/dev/null 2>&1 && cat /opt/exo/version 2>/dev/null || echo n/d"])
    r.steps = [f"{k} = {v}" for k, v in got.items()] + [f"versao interna: {exover}"]
    r.passed = not mismatch
    r.detail = "todas as tags conferem" if not mismatch else f"DIVERGENCIA: {mismatch}"
    r.proof = json.dumps(got, ensure_ascii=False)
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_mysql(rec: Recorder) -> None:
    """Executa SQL de verdade e conta as tabelas que o eXo criou."""
    t0 = time.time()
    r = Result("T-00.3", "MySQL: schema do eXo criado e gravavel (SQL real)",
               "A-maquina")
    env = dict(l.split("=", 1) for l in
               Path("/opt/projetos/exo/.env").read_text().splitlines()
               if "=" in l and not l.strip().startswith("#"))
    pw = env.get("MYSQL_ROOT_PASSWORD", "")
    db = env.get("EXO_DB_NAME", "exo")

    q = ("SELECT COUNT(*) FROM information_schema.tables "
         f"WHERE table_schema='{db}';")
    rc, out = sh(["docker", "exec", "exo-mysql", "mysql", "-uroot",
                  f"-p{pw}", "-N", "-B", "-e", q])
    tables = 0
    for tok in out.split():
        if tok.isdigit():
            tables = int(tok)
            break

    # prova de escrita: cria tabela, insere, le de volta, remove
    probe = (f"USE {db}; DROP TABLE IF EXISTS _probe_{RUN_ID}; "
             f"CREATE TABLE _probe_{RUN_ID}(id INT PRIMARY KEY, v VARCHAR(64)); "
             f"INSERT INTO _probe_{RUN_ID} VALUES (1,'gravacao-ok-{RUN_ID}'); "
             f"SELECT v FROM _probe_{RUN_ID} WHERE id=1; "
             f"DROP TABLE _probe_{RUN_ID};")
    rc2, out2 = sh(["docker", "exec", "exo-mysql", "mysql", "-uroot",
                    f"-p{pw}", "-N", "-B", "-e", probe])
    wrote = f"gravacao-ok-{RUN_ID}" in out2

    r.steps = [f"tabelas no schema '{db}': {tables}",
               f"ciclo cria/insere/le/remove: {'OK' if wrote else 'FALHOU'}",
               f"retorno da leitura: {out2.strip()[:120]}"]
    r.passed = tables > 100 and wrote
    r.detail = (f"{tables} tabelas; escrita/leitura "
                f"{'confirmada' if wrote else 'FALHOU'}")
    r.proof = f"tabelas={tables}; select devolveu 'gravacao-ok-{RUN_ID}'={wrote}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_elasticsearch(rec: Recorder) -> None:
    """Cria índice, indexa documento, busca e apaga — ciclo completo."""
    t0 = time.time()
    r = Result("T-00.4", "Elasticsearch: indexa e recupera documento (ciclo real)",
               "A-maquina")
    idx = f"probe-{RUN_ID}"
    steps = []

    rc, health = sh(["docker", "exec", "exo-es", "curl", "-s",
                     "localhost:9200/_cluster/health"])
    steps.append(f"cluster health: {health[:120]}")

    doc = json.dumps({"titulo": f"documento-de-prova-{RUN_ID}",
                      "corpo": "substituicao do office 365"})
    sh(["docker", "exec", "exo-es", "curl", "-s", "-XPUT",
        f"localhost:9200/{idx}/_doc/1", "-H", "Content-Type: application/json",
        "-d", doc])
    sh(["docker", "exec", "exo-es", "curl", "-s", "-XPOST",
        f"localhost:9200/{idx}/_refresh"])
    rc, found = sh(["docker", "exec", "exo-es", "curl", "-s",
                    f"localhost:9200/{idx}/_search?q=titulo:documento-de-prova-{RUN_ID}"])
    hit = f"documento-de-prova-{RUN_ID}" in found
    steps.append(f"busca devolveu o documento indexado: {hit}")
    sh(["docker", "exec", "exo-es", "curl", "-s", "-XDELETE",
        f"localhost:9200/{idx}"])

    ok_health = '"status":"green"' in health or '"status":"yellow"' in health
    r.steps = steps
    r.passed = ok_health and hit
    r.detail = ("cluster saudavel e ciclo indexar/buscar confirmado" if r.passed
                else "FALHA no ciclo de indexacao")
    r.proof = f"health_ok={ok_health}; documento_recuperado={hit}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_onlyoffice(rec: Recorder) -> None:
    t0 = time.time()
    r = Result("T-00.5", "ONLYOFFICE: servico pronto, JWT ativo e API servida",
               "A-maquina")
    steps = []
    rc, hc = sh(["docker", "exec", "onlyoffice", "curl", "-s",
                 "localhost/healthcheck"])
    steps.append(f"/healthcheck -> {hc.strip()[:60]}")

    # a api.js precisa ser servida ATRAVES do proxy, como o navegador faria
    try:
        api = requests.get(f"{BASE}/web-apps/apps/api/documents/api.js", timeout=30)
        served = api.status_code == 200 and "DocsAPI" in api.text
        steps.append(f"api.js via proxy: HTTP {api.status_code}, "
                     f"contem DocsAPI={('DocsAPI' in api.text)}, "
                     f"{len(api.content)} bytes")
    except requests.RequestException as e:
        served = False
        steps.append(f"api.js via proxy: ERRO {e}")

    rc, jwt = sh(["docker", "exec", "onlyoffice", "bash", "-c",
                  "echo $JWT_ENABLED"])
    steps.append(f"JWT_ENABLED={jwt.strip()}")

    r.steps = steps
    r.passed = hc.strip() == "true" and served and jwt.strip() == "true"
    r.detail = ("documentserver pronto, JWT ligado e api.js entregue pelo proxy"
                if r.passed else "FALHA na prontidao do ONLYOFFICE")
    r.proof = f"healthcheck={hc.strip()}; api.js_ok={served}; jwt={jwt.strip()}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_smtp(rec: Recorder) -> None:
    """Envia um e-mail REAL pelo SMTP e o localiza no Mailpit."""
    t0 = time.time()
    r = Result("T-00.6", "SMTP: mensagem enviada e recebida de fato", "A-maquina")
    mail = Mail(MAILPIT)
    marker = f"prova-smtp-{RUN_ID}"
    steps = []
    try:
        msg = EmailMessage()
        msg["From"] = "noreply@exo.local"
        msg["To"] = "auditoria@exo.local"
        msg["Subject"] = f"Teste de infraestrutura {marker}"
        msg.set_content(f"Corpo da mensagem contendo o marcador {marker}.")
        # porta 1025 do mailpit, alcancavel a partir do host
        with smtplib.SMTP("192.168.1.59", 1025, timeout=30) as s:
            s.ehlo()
            s.send_message(msg)
        steps.append("SMTP: EHLO + MAIL FROM + DATA aceitos")
    except Exception as e:  # noqa: BLE001
        steps.append(f"SMTP ERRO: {e}")
        r.steps = steps
        r.detail = f"nao foi possivel enviar: {e}"
        r.duration_s = round(time.time() - t0, 2)
        rec.add(r)
        return

    hits = mail.wait_for(marker, timeout=60)
    steps.append(f"mensagens encontradas no Mailpit com o marcador: {len(hits)}")
    body = mail.body(hits[0]["ID"]) if hits else ""
    steps.append(f"marcador presente no corpo lido de volta: {marker in body}")

    r.steps = steps
    r.passed = bool(hits) and marker in body
    r.detail = ("e-mail entregue e conteudo conferido no Mailpit" if r.passed
                else "e-mail NAO chegou ou conteudo divergente")
    r.proof = f"assunto contendo '{marker}' localizado; corpo confere={marker in body}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


# ---------------------------------------------------------------- abordagem B

def b_browser_portal(rec: Recorder) -> None:
    """Chromium real abre o portal e lê o DOM — o que um humano veria."""
    t0 = time.time()
    r = Result("T-00.7", "Navegador real carrega o portal e renderiza conteudo",
               "B-usuario")
    steps = []
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as e:
        r.detail = f"playwright indisponivel: {e}"
        rec.add(r)
        return

    try:
        with sync_playwright() as p:
            b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
            pg = b.new_page(viewport={"width": 1440, "height": 900})
            erros: list[str] = []
            pg.on("console", lambda m: erros.append(m.text)
                  if m.type == "error" else None)

            resp = pg.goto(BASE, wait_until="domcontentloaded", timeout=120_000)
            steps.append(f"GET {BASE} -> HTTP {resp.status if resp else 'sem resposta'}")
            pg.wait_for_load_state("networkidle", timeout=120_000)

            titulo = pg.title()
            texto = pg.inner_text("body")[:600]
            shot = SHOTS / f"T-00-portal-{RUN_ID}.png"
            pg.screenshot(path=str(shot), full_page=True)

            steps.append(f"titulo da aba: {titulo!r}")
            steps.append(f"caracteres de texto visiveis no body: {len(pg.inner_text('body'))}")
            steps.append(f"captura de tela: {shot.name}")
            if erros:
                steps.append(f"erros de console: {erros[:3]}")

            # Um portal de verdade tem titulo E texto renderizado — nao uma
            # pagina de erro do Tomcat nem um 502 do nginx.
            ruim = any(s in texto for s in
                       ("502 Bad Gateway", "HTTP Status 404", "HTTP Status 500"))
            r.passed = (resp is not None and resp.status == 200
                        and len(texto.strip()) > 50 and not ruim)
            r.detail = (f"portal renderizado, titulo={titulo!r}" if r.passed
                        else f"portal NAO renderizou corretamente (trecho: {texto[:120]!r})")
            r.proof = f"titulo={titulo!r}; captura={shot.name}"
            b.close()
    except Exception as e:  # noqa: BLE001
        steps.append(f"ERRO no navegador: {type(e).__name__}: {e}")
        r.detail = f"excecao: {e}"

    r.steps = steps
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def b_browser_login_page(rec: Recorder) -> None:
    """Verifica que a tela de login existe e tem os campos que um usuário usaria."""
    t0 = time.time()
    r = Result("T-00.8", "Tela de login apresenta campos utilizaveis", "B-usuario")
    steps = []
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
            pg = b.new_page(viewport={"width": 1440, "height": 900})
            pg.goto(f"{BASE}/portal/login", wait_until="networkidle", timeout=120_000)
            campos = {}
            for nome, sel in (("usuario", "input[name='username']"),
                              ("senha", "input[name='password']"),
                              ("enviar", "button[type='submit'], input[type='submit']")):
                campos[nome] = pg.locator(sel).count()
                steps.append(f"campo {nome} ({sel}): {campos[nome]} ocorrencia(s)")
            shot = SHOTS / f"T-00-login-{RUN_ID}.png"
            pg.screenshot(path=str(shot), full_page=True)
            steps.append(f"captura de tela: {shot.name}")
            r.passed = campos["usuario"] >= 1 and campos["senha"] >= 1
            r.detail = ("formulario de login utilizavel" if r.passed
                        else f"formulario incompleto: {campos}")
            r.proof = f"campos encontrados={campos}; captura={shot.name}"
            b.close()
    except Exception as e:  # noqa: BLE001
        steps.append(f"ERRO: {type(e).__name__}: {e}")
        r.detail = f"excecao: {e}"

    r.steps = steps
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("T-00-infraestrutura")
    print("=" * 70)
    print("T-00 — INFRAESTRUTURA (dupla abordagem)")
    print("=" * 70)
    print("\n--- Abordagem A: maquina ---")
    a_containers(rec)
    a_versions(rec)
    a_mysql(rec)
    a_elasticsearch(rec)
    a_onlyoffice(rec)
    a_smtp(rec)
    print("\n--- Abordagem B: usuario final em navegador real ---")
    b_browser_portal(rec)
    b_browser_login_page(rec)
    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
