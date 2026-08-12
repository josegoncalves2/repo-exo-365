#!/usr/bin/env python3
"""
T-08 — Chat (substituto do Microsoft Teams para mensageria).

Este teste também faltava na suíte. Na linha 7.x o eXo trocou o chat nativo pela
integração com o protocolo Matrix; sem exercitar a troca real de mensagens, não há
como afirmar que o equivalente ao Teams funciona.

Regra do projeto: "o servidor respondeu /health" não é teste de chat. O que prova a
função é **uma pessoa enviar e OUTRA receber exatamente aquele texto**.

Abordagem A (máquina / protocolo Matrix)
  A1. Cria dois usuários reais no homeserver.
  A2. Ambos autenticam de fato e recebem access_token próprio.
  A3. O primeiro cria uma sala privada e convida o segundo.
  A4. O segundo ACEITA o convite (entra na sala por vontade própria).
  A5. O primeiro envia uma mensagem com texto único.
  A6. O segundo lê a sala e precisa encontrar AQUELE texto, enviado por AQUELE
      remetente. Comparação exata — não "a requisição deu 200".
  A7. O segundo responde; o primeiro precisa ler a resposta (mão dupla).
  A8. Envio de anexo: o primeiro sobe um arquivo e publica na sala; o segundo
      baixa pelo content repository e confere o conteúdo byte a byte.

Abordagem B (usuário final, navegador real)
  B1. Chromium real faz login no portal do eXo com credenciais verdadeiras.
  B2. Navega até a aplicação de chat pela interface.
  B3. Confirma que a interface de chat carrega para o usuário final, com
      captura de tela como evidência.
"""
from __future__ import annotations

import json
import subprocess
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import (ADMIN_PASS, ADMIN_USER, BASE, EVIDENCE,  # noqa: E402
                    Recorder, Result, RUN_ID)

import requests  # noqa: E402

CAPTURAS = EVIDENCE / "capturas"
CAPTURAS.mkdir(parents=True, exist_ok=True)

RAIZ = Path(__file__).resolve().parent.parent


def ler_env(chave: str, padrao: str = "") -> str:
    env = RAIZ / ".env"
    if env.exists():
        for linha in env.read_text().splitlines():
            linha = linha.strip()
            if linha.startswith(f"{chave}=") and not linha.startswith("#"):
                return linha.split("=", 1)[1].strip()
    return padrao


SERVIDOR = ler_env("MATRIX_SERVER_NAME", "192.168.1.59")
# O Synapse não publica porta no host; fala-se com ele por dentro da rede do
# Docker, que é como o eXo também o acessa.
CONTAINER = "exo-synapse"


def matrix(metodo: str, caminho: str, token: str | None = None,
           corpo: dict | bytes | None = None,
           tipo: str | None = None) -> tuple[int, dict]:
    """Chama a API do Matrix de dentro do container do Synapse."""
    cmd = ["docker", "exec", "-i", CONTAINER, "curl", "-s", "-w", "\n%{http_code}",
           "-X", metodo, "--max-time", "60"]
    entrada = None
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    if corpo is not None:
        if isinstance(corpo, bytes):
            cmd += ["-H", f"Content-Type: {tipo or 'application/octet-stream'}",
                    "--data-binary", "@-"]
            entrada = corpo
        else:
            cmd += ["-H", "Content-Type: application/json", "-d", "@-"]
            entrada = json.dumps(corpo).encode()
    cmd.append(f"http://localhost:8008{caminho}")
    r = subprocess.run(cmd, input=entrada, capture_output=True, timeout=120)
    saida = r.stdout.decode(errors="replace")
    if "\n" in saida:
        corpo_txt, _, codigo = saida.rpartition("\n")
    else:
        corpo_txt, codigo = saida, "0"
    try:
        dados = json.loads(corpo_txt) if corpo_txt.strip() else {}
    except ValueError:
        dados = {"_bruto": corpo_txt[:400]}
    return int(codigo or 0), dados


def cria_usuario(nome: str, senha: str) -> bool:
    r = subprocess.run(
        ["docker", "exec", CONTAINER, "register_new_matrix_user",
         "-u", nome, "-p", senha, "--no-admin",
         "-c", "/data/homeserver.yaml", "http://localhost:8008"],
        capture_output=True, timeout=120)
    saida = (r.stdout + r.stderr).decode(errors="replace")
    return r.returncode == 0 or "already taken" in saida.lower()


def entra(nome: str, senha: str) -> tuple[str | None, str | None]:
    cod, d = matrix("POST", "/_matrix/client/v3/login", corpo={
        "type": "m.login.password",
        "identifier": {"type": "m.id.user", "user": nome},
        "password": senha,
    })
    if cod == 200 and "access_token" in d:
        return d["access_token"], d["user_id"]
    return None, None


# ---------------------------------------------------------------------------
# Abordagem A
# ---------------------------------------------------------------------------

def a_conversa_real(rec: Recorder) -> bool:
    t0 = time.time()
    r = Result("T-08", "Dois usuarios trocam mensagens reais pelo Matrix", "A-maquina")
    steps: list[str] = []
    ok = False

    ua, sa = f"t08a{RUN_ID}".lower(), "Prova@2026#Chat1"
    ub, sb = f"t08b{RUN_ID}".lower(), "Prova@2026#Chat2"
    texto_ida = f"Mensagem de ida {RUN_ID} — {uuid.uuid4().hex[:8]}"
    texto_volta = f"Resposta de volta {RUN_ID} — {uuid.uuid4().hex[:8]}"

    try:
        steps.append(f"criando usuarios {ua} e {ub}")
        if not (cria_usuario(ua, sa) and cria_usuario(ub, sb)):
            raise RuntimeError("nao foi possivel criar os usuarios no homeserver")

        ta, ida_id = entra(ua, sa)
        tb, ib_id = entra(ub, sb)
        steps.append(f"login A: {ida_id} | login B: {ib_id}")
        if not (ta and tb):
            raise RuntimeError("um dos usuarios nao conseguiu autenticar")

        cod, d = matrix("POST", "/_matrix/client/v3/createRoom", ta, {
            "name": f"Prova T-08 {RUN_ID}", "preset": "private_chat",
            "invite": [ib_id],
        })
        sala = d.get("room_id")
        steps.append(f"sala criada: HTTP {cod} {sala}")
        if not sala:
            raise RuntimeError(f"sala nao criada: {d}")

        cod, d = matrix("POST", f"/_matrix/client/v3/rooms/{sala}/join", tb, {})
        steps.append(f"B aceitou o convite: HTTP {cod}")
        if cod != 200:
            raise RuntimeError(f"B nao entrou na sala: {d}")

        cod, d = matrix("PUT",
                        f"/_matrix/client/v3/rooms/{sala}/send/m.room.message/"
                        f"{uuid.uuid4().hex}", ta,
                        {"msgtype": "m.text", "body": texto_ida})
        steps.append(f"A enviou a mensagem: HTTP {cod} event={d.get('event_id')}")

        # --- a prova: B le a sala e precisa encontrar o texto de A ---
        time.sleep(2)
        cod, d = matrix("GET", f"/_matrix/client/v3/rooms/{sala}/messages"
                               f"?dir=b&limit=30", tb)
        eventos = d.get("chunk", [])
        recebida = next((e for e in eventos
                         if e.get("content", {}).get("body") == texto_ida), None)
        steps.append(f"B leu {len(eventos)} eventos; encontrou a mensagem de A: "
                     f"{recebida is not None}")
        if not recebida:
            raise RuntimeError("B NAO recebeu o texto enviado por A")
        if recebida.get("sender") != ida_id:
            raise RuntimeError(f"remetente errado: {recebida.get('sender')}")
        steps.append(f"remetente conferido: {recebida['sender']}")

        # --- mao dupla: B responde, A precisa ler ---
        matrix("PUT", f"/_matrix/client/v3/rooms/{sala}/send/m.room.message/"
                      f"{uuid.uuid4().hex}", tb,
               {"msgtype": "m.text", "body": texto_volta})
        time.sleep(2)
        cod, d = matrix("GET", f"/_matrix/client/v3/rooms/{sala}/messages"
                               f"?dir=b&limit=30", ta)
        volta = any(e.get("content", {}).get("body") == texto_volta
                    for e in d.get("chunk", []))
        steps.append(f"A recebeu a resposta de B: {volta}")
        if not volta:
            raise RuntimeError("A NAO recebeu a resposta de B")

        # --- anexo: A envia arquivo, B baixa e compara ---
        conteudo = f"anexo de prova {RUN_ID}\n".encode() * 20
        cod, d = matrix("POST", f"/_matrix/media/v3/upload?filename=prova{RUN_ID}.txt",
                        ta, conteudo, tipo="text/plain")
        mxc = d.get("content_uri", "")
        steps.append(f"upload do anexo: HTTP {cod} {mxc}")
        if mxc.startswith("mxc://"):
            matrix("PUT", f"/_matrix/client/v3/rooms/{sala}/send/m.room.message/"
                          f"{uuid.uuid4().hex}", ta,
                   {"msgtype": "m.file", "body": f"prova{RUN_ID}.txt", "url": mxc})
            servidor, _, media_id = mxc[len("mxc://"):].partition("/")
            baixado = subprocess.run(
                ["docker", "exec", CONTAINER, "curl", "-s", "--max-time", "60",
                 "-H", f"Authorization: Bearer {tb}",
                 f"http://localhost:8008/_matrix/client/v1/media/download/"
                 f"{servidor}/{media_id}"],
                capture_output=True, timeout=120).stdout
            igual = baixado == conteudo
            steps.append(f"B baixou o anexo: {len(baixado)} bytes, "
                         f"identico ao enviado: {igual}")
            if not igual:
                raise RuntimeError("o anexo baixado por B difere do enviado por A")
        else:
            raise RuntimeError(f"upload do anexo falhou: {d}")

        ok = True
    except Exception as e:  # noqa: BLE001
        steps.append(f"ERRO: {e}")

    r.steps = steps
    r.passed = ok
    r.detail = ("dois usuarios trocaram mensagens nos dois sentidos e um anexo, "
                "com conteudo conferido" if ok
                else "a troca real de mensagens nao pode ser comprovada")
    r.proof = f"ida={texto_ida!r} volta={texto_volta!r}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return ok


def a_integracao_exo(rec: Recorder) -> bool:
    """O eXo precisa estar apontado para o MESMO homeserver, com o mesmo segredo."""
    t0 = time.time()
    r = Result("T-08", "eXo integrado ao homeserver (parametros conferidos)",
               "A-maquina")
    steps, ok = [], False
    try:
        props = subprocess.run(
            ["docker", "exec", "exo-app", "cat", "/etc/exo/exo.properties"],
            capture_output=True, timeout=60).stdout.decode()
        p = dict(l.split("=", 1) for l in props.splitlines()
                 if "=" in l and not l.strip().startswith("#"))
        hs = subprocess.run(
            ["docker", "exec", CONTAINER, "python3", "-c",
             "import yaml,json;c=yaml.safe_load(open('/data/homeserver.yaml'));"
             "print(json.dumps({'jwt':c['jwt_config']['secret'],"
             "'alg':c['jwt_config']['algorithm'],"
             "'reg':c['registration_shared_secret']}))"],
            capture_output=True, timeout=60).stdout.decode()
        h = json.loads(hs)

        jwt_igual = p.get("meeds.matrix.jwt.secret", "").strip() == h["jwt"]
        reg_igual = p.get("meeds.matrix.shared_secret_registration", "").strip() == h["reg"]
        steps.append(f"meeds.matrix.server.url = {p.get('meeds.matrix.server.url')}")
        steps.append(f"segredo JWT identico nos dois lados: {jwt_igual}")
        steps.append(f"shared_secret_registration identico: {reg_igual}")
        steps.append(f"algoritmo do JWT: {h['alg']} "
                     f"(tamanho do segredo: {len(h['jwt'])} bytes)")
        # O JJWT do eXo deriva o algoritmo do tamanho da chave: 64 bytes => HS512
        coerente = (h["alg"] == "HS512" and len(h["jwt"]) == 64) or \
                   (h["alg"] == "HS256" and len(h["jwt"]) == 32)
        steps.append(f"tamanho da chave coerente com o algoritmo: {coerente}")
        ok = jwt_igual and reg_igual and coerente
    except Exception as e:  # noqa: BLE001
        steps.append(f"ERRO: {e}")

    r.steps = steps
    r.passed = ok
    r.detail = ("eXo e Synapse compartilham exatamente os mesmos segredos, com "
                "algoritmo coerente" if ok else "divergencia entre eXo e Synapse")
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return ok


# ---------------------------------------------------------------------------
# Abordagem B
# ---------------------------------------------------------------------------

def b_chat_no_navegador(rec: Recorder) -> bool:
    t0 = time.time()
    r = Result("T-08", "Usuario final abre o chat no portal (navegador real)",
               "B-usuario")
    steps, ok = [], False
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as e:
        r.steps = [f"playwright indisponivel: {e}"]
        rec.add(r)
        return False

    try:
        with sync_playwright() as p:
            nav = p.chromium.launch(args=["--no-sandbox"])
            ctx = nav.new_context(viewport={"width": 1440, "height": 900},
                                  locale="pt-BR")
            pg = ctx.new_page()
            falhas = []
            pg.on("response", lambda r_: falhas.append(f"{r_.status} {r_.url[:90]}")
                  if r_.status >= 500 else None)

            pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded",
                    timeout=90_000)
            pg.fill("input[name='username']", ADMIN_USER)
            pg.fill("input[name='password']", ADMIN_PASS)
            pg.press("input[name='password']", "Enter")
            pg.wait_for_load_state("networkidle", timeout=90_000)
            steps.append(f"login efetuado; URL atual: {pg.url[:90]}")

            pg.goto(f"{BASE}/portal/dw/chat", wait_until="domcontentloaded",
                    timeout=90_000)
            time.sleep(8)
            steps.append(f"pagina do chat: HTTP-final {pg.url[:90]}")

            corpo = pg.inner_text("body")[:400].replace("\n", " ")
            steps.append(f"texto visivel: {corpo[:200]!r}")
            shot = CAPTURAS / f"t08-chat-{RUN_ID}.png"
            pg.screenshot(path=str(shot), full_page=False)
            steps.append(f"captura: {shot.name}")

            # Sucesso = a aplicação de chat montou (não é 404/erro do portal)
            ruim = any(t in corpo.lower() for t in
                       ("página não encontrada", "page not found",
                        "erro interno", "internal server error", "http status 5"))
            ok = (not ruim) and not falhas
            if falhas:
                steps.append(f"respostas 5xx: {falhas[:4]}")
            ctx.close(); nav.close()
    except Exception as e:  # noqa: BLE001
        steps.append(f"ERRO: {e}")

    r.steps = steps
    r.passed = ok
    r.detail = ("a aplicacao de chat carrega para o usuario final, sem erro de "
                "servidor" if ok else "o chat nao carregou corretamente no navegador")
    r.proof = f"evidence/capturas/t08-chat-{RUN_ID}.png"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return ok


def main() -> int:
    rec = Recorder(f"t08-chat-{RUN_ID}")
    print("=" * 67)
    print(f" T-08 — Chat / Matrix  (RUN_ID {RUN_ID})")
    print("=" * 67)
    a_conversa_real(rec)
    a_integracao_exo(rec)
    b_chat_no_navegador(rec)
    caminho = rec.dump()
    print("-" * 67)
    print(f" T-08: {rec.passed} passaram, {rec.failed} falharam")
    print(f" Evidencia: {caminho}")
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
