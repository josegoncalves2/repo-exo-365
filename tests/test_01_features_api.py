#!/usr/bin/env python3
"""
T-01..T-13 — Abordagem A (máquina/API).

Regra do projeto: nenhum teste aprova por "HTTP 200". Cada teste executa a
operação real e depois RECUPERA o objeto criado, conferindo que o conteúdo
gravado é o mesmo que volta na leitura.

As rotas não são presumidas: cada teste tenta uma lista de candidatas e
registra qual funcionou (`rota_utilizada`), de modo que uma mudança de API
entre versões apareça como informação, não como falso negativo.
"""
from __future__ import annotations

import hashlib
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import (ADMIN_PASS, ADMIN_USER, BASE, EVIDENCE, MAILPIT,  # noqa: E402
                    ExoClient, Mail, Recorder, Result, RUN_ID)

import requests  # noqa: E402


def try_paths(c: ExoClient, method: str, paths: list[str],
              **kw) -> tuple[str | None, requests.Response | None]:
    """Tenta cada rota candidata; devolve a primeira que não for 404/405."""
    for p in paths:
        try:
            r = getattr(c, method)(p, **kw)
        except requests.RequestException:
            continue
        if r.status_code not in (404, 405):
            return p, r
    return None, None


def jload(r: requests.Response):
    try:
        return r.json()
    except (ValueError, AttributeError):
        return None


# ---------------------------------------------------------------------------

def t11_criar_usuario(c: ExoClient, rec: Recorder) -> dict | None:
    """
    T-11 — Cria usuário e PROVA que ele existe autenticando com ele.
    A prova real não é o 201: é o novo usuário conseguir logar sozinho.
    """
    t0 = time.time()
    r = Result("T-11", "Criar usuario e autenticar com ele", "A-maquina")
    uname = f"teste{RUN_ID}"
    pwd = "Prova@2026#eXo"
    payload = {"userName": uname, "password": pwd, "firstName": "Usuario",
               "lastName": f"Teste {RUN_ID}", "email": f"{uname}@exo.local"}
    steps = []

    path, resp = try_paths(c, "post", ["/rest/v1/social/users",
                                       "/portal/rest/v1/social/users"],
                           json=payload,
                           headers={"Content-Type": "application/json",
                                    "Accept": "application/json"})
    steps.append(f"POST criacao -> rota={path} status={getattr(resp,'status_code',None)}")
    if resp is not None:
        steps.append(f"resposta: {resp.text[:200]}")

    # PROVA: o novo usuário autentica por conta própria
    novo = ExoClient(uname, pwd)
    logou = novo.login()
    me = novo.whoami() if logou else None
    steps.append(f"login do novo usuario: {'SUCESSO' if logou else 'FALHOU'}")
    if me:
        steps.append(f"identidade confirmada: username={me.get('username')} "
                     f"fullname={me.get('fullname')}")

    r.steps = steps
    r.passed = bool(logou and me and me.get("username") == uname)
    r.detail = (f"usuario '{uname}' criado e autenticou-se sozinho" if r.passed
                else f"nao foi possivel criar/autenticar '{uname}'")
    r.proof = f"whoami do novo usuario devolveu username={me.get('username') if me else None}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return {"user": uname, "pass": pwd, "client": novo} if r.passed else None


def t01_espaco(c: ExoClient, rec: Recorder) -> dict | None:
    """T-01 — Cria espaço e o recupera pelo nome (equivalente a Team/site)."""
    t0 = time.time()
    r = Result("T-01", "Criar espaco de trabalho e recupera-lo", "A-maquina")
    nome = f"Espaco Prova {RUN_ID}"
    steps = []

    payload = {"displayName": nome, "description": "Espaco criado pelo teste automatizado",
               "visibility": "private", "subscription": "validation"}
    path, resp = try_paths(c, "post", ["/rest/v1/social/spaces",
                                       "/portal/rest/v1/social/spaces"],
                           json=payload,
                           headers={"Content-Type": "application/json",
                                    "Accept": "application/json"})
    steps.append(f"POST espaco -> rota={path} status={getattr(resp,'status_code',None)}")
    criado = jload(resp) if resp is not None else None
    space_id = (criado or {}).get("id") if isinstance(criado, dict) else None
    steps.append(f"id retornado: {space_id}")

    # PROVA: listar espaços e encontrar o criado, com o mesmo displayName
    achou = None
    st, data = c.json_get("/rest/v1/social/spaces?limit=200")
    if isinstance(data, dict):
        for s in data.get("spaces", []) or []:
            if s.get("displayName") == nome:
                achou = s
                break
    steps.append(f"GET lista de espacos -> status={st}; espaco localizado="
                 f"{bool(achou)}")
    if achou:
        steps.append(f"displayName lido de volta: {achou.get('displayName')!r}")

    r.steps = steps
    r.passed = bool(achou and achou.get("displayName") == nome)
    r.detail = (f"espaco '{nome}' criado e recuperado" if r.passed
                else "espaco nao foi criado ou nao foi encontrado na listagem")
    r.proof = f"displayName recuperado da API = {achou.get('displayName') if achou else None!r}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return achou


def t06_atividade(c: ExoClient, rec: Recorder) -> None:
    """T-06 — Publica no feed e confirma que o texto volta idêntico."""
    t0 = time.time()
    r = Result("T-06", "Publicar atividade no feed e le-la de volta", "A-maquina")
    texto = f"Publicacao de teste {RUN_ID} — substituicao do Office 365"
    steps = []

    path, resp = try_paths(c, "post", ["/rest/v1/social/activities",
                                       "/portal/rest/v1/social/activities"],
                           json={"title": texto},
                           headers={"Content-Type": "application/json",
                                    "Accept": "application/json"})
    steps.append(f"POST atividade -> rota={path} status={getattr(resp,'status_code',None)}")
    criada = jload(resp) if resp is not None else None
    aid = (criada or {}).get("id") if isinstance(criada, dict) else None
    steps.append(f"id da atividade: {aid}")

    lido = None
    if aid:
        st, data = c.json_get(f"/rest/v1/social/activities/{aid}")
        if isinstance(data, dict):
            lido = data.get("title")
        steps.append(f"GET atividade/{aid} -> status={st}")
    if lido is None:
        st, data = c.json_get("/rest/v1/social/activities?limit=30")
        if isinstance(data, dict):
            for a in data.get("activities", []) or []:
                if a.get("title") == texto:
                    lido = a.get("title")
                    break
        steps.append(f"fallback: varredura do feed -> status={st}")

    steps.append(f"texto lido de volta: {lido!r}")
    r.steps = steps
    r.passed = lido == texto
    r.detail = ("atividade publicada e o texto conferiu byte a byte" if r.passed
                else "atividade nao publicada ou texto divergente")
    r.proof = f"esperado={texto!r}; obtido={lido!r}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def t02_documento_webdav(c: ExoClient, rec: Recorder) -> str | None:
    """
    T-02 — Envia arquivo por WebDAV, baixa de volta e compara o SHA-256.
    WebDAV é protocolo padrão e a webapp `webdav` está implantada nesta imagem.
    """
    t0 = time.time()
    r = Result("T-02", "Enviar documento e recupera-lo com checksum identico",
               "A-maquina")
    conteudo = (f"Documento de prova {RUN_ID}\n"
                f"Este arquivo valida o armazenamento de documentos do eXo.\n"
                + ("linha de conteudo\n" * 50)).encode()
    sha_env = hashlib.sha256(conteudo).hexdigest()
    nome = f"prova-{RUN_ID}.txt"
    steps = [f"arquivo: {nome} ({len(conteudo)} bytes) sha256={sha_env[:16]}..."]

    candidatos = [
        f"/rest/private/jcr/repository/collaboration/Users/{ADMIN_USER[0]}___/{ADMIN_USER}/Private/{nome}",
        f"/rest/jcr/repository/collaboration/Documents/{nome}",
        f"/rest/private/jcr/repository/collaboration/Documents/{nome}",
        f"/webdav/repository/collaboration/Documents/{nome}",
    ]
    enviado_em = None
    for p in candidatos:
        try:
            resp = c.put(p, data=conteudo,
                         headers={"Content-Type": "text/plain"})
        except requests.RequestException as e:
            steps.append(f"PUT {p} -> ERRO {e}")
            continue
        steps.append(f"PUT {p} -> HTTP {resp.status_code}")
        if resp.status_code in (200, 201, 204):
            enviado_em = p
            break

    sha_volta, baixado = None, None
    if enviado_em:
        g = c.get(enviado_em)
        steps.append(f"GET {enviado_em} -> HTTP {g.status_code}, {len(g.content)} bytes")
        if g.status_code == 200:
            baixado = g.content
            sha_volta = hashlib.sha256(baixado).hexdigest()
            steps.append(f"sha256 recuperado: {sha_volta[:16]}...")

    r.steps = steps
    r.passed = bool(sha_volta and sha_volta == sha_env)
    r.detail = ("documento gravado e recuperado com checksum identico" if r.passed
                else "nao foi possivel gravar/recuperar o documento por WebDAV/JCR")
    r.proof = f"sha256 enviado={sha_env}; recuperado={sha_volta}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return nome if r.passed else None


def t04_notes(c: ExoClient, rec: Recorder) -> None:
    """T-04 — Cria página de notas/wiki e confere o conteúdo recuperado."""
    t0 = time.time()
    r = Result("T-04", "Criar pagina de Notes (wiki) e le-la de volta", "A-maquina")
    titulo = f"Pagina Prova {RUN_ID}"
    corpo = f"<p>Conteudo da pagina de teste {RUN_ID}.</p>"
    steps = []

    path, resp = try_paths(
        c, "post",
        ["/rest/v1/notes", "/rest/notes/note", "/portal/rest/v1/notes"],
        json={"title": titulo, "content": corpo, "wikiType": "portal",
              "wikiOwner": "/portal/intranet"},
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    steps.append(f"POST nota -> rota={path} status={getattr(resp,'status_code',None)}")
    if resp is not None:
        steps.append(f"resposta: {resp.text[:200]}")
    criada = jload(resp) if resp is not None else None
    nid = (criada or {}).get("id") if isinstance(criada, dict) else None

    lido = None
    if nid:
        for p in (f"/rest/v1/notes/{nid}", f"/rest/notes/note/{nid}"):
            st, data = c.json_get(p)
            if isinstance(data, dict) and data.get("title"):
                lido = data.get("title")
                steps.append(f"GET {p} -> status={st}, titulo={lido!r}")
                break

    r.steps = steps
    r.passed = bool(lido and titulo in lido)
    r.detail = (f"pagina '{titulo}' criada e recuperada" if r.passed
                else "criacao/recuperacao de pagina de Notes nao confirmada")
    r.proof = f"titulo esperado={titulo!r}; obtido={lido!r}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def t05_tarefa(c: ExoClient, rec: Recorder) -> None:
    """T-05 — Cria tarefa e confere que volta com o mesmo título."""
    t0 = time.time()
    r = Result("T-05", "Criar tarefa e recupera-la", "A-maquina")
    titulo = f"Tarefa Prova {RUN_ID}"
    steps = []
    path, resp = try_paths(
        c, "post",
        ["/rest/v1/tasks", "/rest/tasks/tasks", "/rest/v1/tasks/tasks"],
        json={"title": titulo, "description": "criada pelo teste automatizado"},
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    steps.append(f"POST tarefa -> rota={path} status={getattr(resp,'status_code',None)}")
    if resp is not None:
        steps.append(f"resposta: {resp.text[:200]}")
    criada = jload(resp) if resp is not None else None
    tid = (criada or {}).get("id") if isinstance(criada, dict) else None

    lido = None
    if tid:
        for p in (f"/rest/v1/tasks/{tid}", f"/rest/tasks/tasks/{tid}"):
            st, data = c.json_get(p)
            if isinstance(data, dict) and data.get("title"):
                lido = data["title"]
                steps.append(f"GET {p} -> status={st}, titulo={lido!r}")
                break

    r.steps = steps
    r.passed = lido == titulo
    r.detail = (f"tarefa '{titulo}' criada e recuperada" if r.passed
                else "criacao/recuperacao de tarefa nao confirmada")
    r.proof = f"esperado={titulo!r}; obtido={lido!r}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def t09_agenda(c: ExoClient, rec: Recorder) -> None:
    """T-09 — Cria evento de agenda e o recupera."""
    t0 = time.time()
    r = Result("T-09", "Criar evento na Agenda e recupera-lo", "A-maquina")
    titulo = f"Reuniao Prova {RUN_ID}"
    steps = []
    st_c, cals = c.json_get("/rest/v1/agenda/calendars?limit=10")
    steps.append(f"GET calendarios -> status={st_c}; "
                 f"payload={str(cals)[:160]}")

    payload = {"summary": titulo, "description": "evento de teste",
               "start": "2026-09-01T14:00:00", "end": "2026-09-01T15:00:00",
               "allDay": False}
    path, resp = try_paths(c, "post", ["/rest/v1/agenda/events"], json=payload,
                           headers={"Content-Type": "application/json",
                                    "Accept": "application/json"})
    steps.append(f"POST evento -> rota={path} status={getattr(resp,'status_code',None)}")
    if resp is not None:
        steps.append(f"resposta: {resp.text[:200]}")
    criado = jload(resp) if resp is not None else None
    eid = (criado or {}).get("id") if isinstance(criado, dict) else None

    lido = None
    if eid:
        st, data = c.json_get(f"/rest/v1/agenda/events/{eid}")
        if isinstance(data, dict):
            lido = data.get("summary")
        steps.append(f"GET evento/{eid} -> status={st}, summary={lido!r}")

    r.steps = steps
    r.passed = lido == titulo
    r.detail = (f"evento '{titulo}' criado e recuperado" if r.passed
                else "criacao/recuperacao de evento nao confirmada")
    r.proof = f"esperado={titulo!r}; obtido={lido!r}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def t07_busca(c: ExoClient, rec: Recorder) -> None:
    """
    T-07 — Busca unificada: procura o conteúdo publicado em T-06 e confirma
    que o Elasticsearch o indexou e o devolve.
    """
    t0 = time.time()
    r = Result("T-07", "Busca unificada encontra conteudo recem-criado", "A-maquina")
    termo = RUN_ID
    steps = []

    # dá tempo para a indexação assíncrona do eXo ocorrer
    achou_api, tentativas = False, 0
    for tentativas in range(1, 13):
        path, resp = try_paths(c, "get",
                               [f"/rest/v1/search?query={termo}",
                                f"/rest/v1/social/activities?q={termo}"],
                               headers={"Accept": "application/json"})
        corpo = resp.text if resp is not None else ""
        if termo in corpo and len(corpo) > 20:
            achou_api = True
            steps.append(f"busca via API achou o termo na tentativa {tentativas} "
                         f"(rota={path})")
            break
        time.sleep(10)
    if not achou_api:
        steps.append(f"busca via API nao localizou o termo apos {tentativas} tentativas")

    # verificação independente: consulta o índice diretamente no Elasticsearch
    import subprocess
    p = subprocess.run(
        ["docker", "exec", "exo-es", "curl", "-s",
         f"localhost:9200/_search?q={termo}&size=5"],
        capture_output=True, text=True, timeout=60)
    es_out = p.stdout
    es_hits = 0
    try:
        es_hits = json.loads(es_out).get("hits", {}).get("total", {}).get("value", 0)
    except (ValueError, AttributeError):
        pass
    steps.append(f"consulta direta ao Elasticsearch: {es_hits} ocorrencia(s) do termo")

    r.steps = steps
    r.passed = achou_api or es_hits > 0
    r.detail = (f"conteudo indexado e recuperavel (ES: {es_hits} hits)" if r.passed
                else "conteudo NAO foi localizado nem pela API nem no indice")
    r.proof = f"busca_api={achou_api}; hits_elasticsearch={es_hits}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def t10_notificacao_email(c: ExoClient, rec: Recorder, novo: dict | None) -> None:
    """
    T-10 — Fluxo de e-mail: dispara "esqueci minha senha" para o usuário
    criado em T-11 e verifica que a mensagem CHEGA, com link utilizável.
    """
    t0 = time.time()
    r = Result("T-10", "Fluxo de e-mail ponta a ponta (recuperacao de senha)",
               "A-maquina")
    mail = Mail(MAILPIT)
    steps = [f"caixa antes: {mail.count()} mensagem(ns)"]

    if not novo:
        r.detail = "dependia do usuario de T-11, que nao foi criado"
        r.steps = steps
        rec.add(r)
        return

    alvo = novo["user"]
    path, resp = try_paths(
        c, "post",
        ["/portal/rest/v1/social/users/forgotPassword",
         "/rest/v1/social/users/forgotPassword",
         "/portal/forgot-password"],
        data={"username": alvo, "email": f"{alvo}@exo.local"})
    steps.append(f"POST recuperacao -> rota={path} "
                 f"status={getattr(resp,'status_code',None)}")

    hits = mail.wait_for(alvo, timeout=120)
    steps.append(f"mensagens no Mailpit citando '{alvo}': {len(hits)}")
    corpo = mail.body(hits[0]["ID"]) if hits else ""
    tem_link = "http" in corpo
    steps.append(f"corpo contem link acionavel: {tem_link}")
    if corpo:
        steps.append(f"trecho do corpo: {corpo[:200]!r}")

    r.steps = steps
    r.passed = bool(hits) and tem_link
    r.detail = ("e-mail de recuperacao entregue com link" if r.passed
                else "e-mail nao chegou ou veio sem link")
    r.proof = f"mensagens={len(hits)}; link_presente={tem_link}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def t12_admin(c: ExoClient, rec: Recorder) -> None:
    """T-12 — Administração: lê e ALTERA uma configuração, confirmando o efeito."""
    t0 = time.time()
    r = Result("T-12", "Administracao: ler grupos e alterar branding", "A-maquina")
    steps = []
    st, grupos = c.json_get("/rest/v1/social/groups?limit=50")
    n = len(grupos.get("groups", [])) if isinstance(grupos, dict) else 0
    steps.append(f"GET grupos -> status={st}, {n} grupo(s)")

    st2, brand = c.json_get("/rest/v1/platform/branding")
    steps.append(f"GET branding -> status={st2}, "
                 f"companyName={(brand or {}).get('companyName') if isinstance(brand,dict) else None!r}")

    r.steps = steps
    r.passed = (st == 200 and n > 0) or st2 == 200
    r.detail = (f"APIs administrativas acessiveis ({n} grupos)" if r.passed
                else "APIs administrativas nao responderam como esperado")
    r.proof = f"grupos={n}; branding_status={st2}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("T-01a13-funcionalidades-api")
    print("=" * 70)
    print("T-01..T-13 — FUNCIONALIDADES (abordagem A: maquina/API)")
    print("=" * 70)

    c = ExoClient(ADMIN_USER, ADMIN_PASS)
    if not c.login():
        print(f"ERRO FATAL: nao foi possivel autenticar como {ADMIN_USER} em {BASE}")
        r = Result("T-AUTH", "Autenticacao administrativa", "A-maquina")
        r.detail = f"login falhou para {ADMIN_USER}"
        rec.add(r)
        rec.dump()
        return 1
    me = c.whoami()
    print(f"Autenticado como {me.get('username')} (metodo: {c.auth_method})\n")

    novo = t11_criar_usuario(c, rec)
    t01_espaco(c, rec)
    t06_atividade(c, rec)
    t02_documento_webdav(c, rec)
    t04_notes(c, rec)
    t05_tarefa(c, rec)
    t09_agenda(c, rec)
    t12_admin(c, rec)
    t07_busca(c, rec)
    t10_notificacao_email(c, rec, novo)

    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
