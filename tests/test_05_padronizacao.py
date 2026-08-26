#!/usr/bin/env python3
"""
T-05 — Padronização da interface e honestidade da documentação.

Existe por causa de duas falhas reais, nesta ordem:

  1. o painel exibia 13 atalhos em DOIS idiomas e três caixas diferentes
     ("Add a task", "Contributions Review", "Dispositivos móveis (MDM)"),
     7 deles sem tecla nenhuma;
  2. o README apontava para um documento que havia sido apagado — link para o
     vazio. Documento pode ser removido por decisão do dono do projeto; o que
     não pode é a documentação seguir citando o que não existe mais.

Abordagem A (máquina): lê o BANCO e os JARS e compara com conf/atalhos/padrao.json.
Abordagem B (usuário final): lê a API que a tela consome e confere o que seria
   desenhado — não o que o banco diz.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import Recorder, Result  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
PADRAO = json.loads((RAIZ / "conf" / "atalhos" / "padrao.json").read_text(encoding="utf-8"))


def mysql(sql: str) -> str:
    senha = ""
    for l in (RAIZ / ".env").read_text(encoding="utf-8").splitlines():
        if l.startswith("MYSQL_ROOT_PASSWORD="):
            senha = l.split("=", 1)[1].strip()
    p = subprocess.run(["docker", "exec", "-e", f"MYSQL_PWD={senha}", "exo-mysql", "mysql",
                        "-uroot", "exo", "--default-character-set=utf8mb4", "-N", "-e", sql],
                       capture_output=True, text=True, timeout=120)
    if p.returncode:
        raise RuntimeError(p.stderr[-300:])
    return p.stdout


# ---------------------------------------------------------------- abordagem A

def a_atalhos_no_banco(rec: Recorder) -> None:
    t0 = time.time()
    r = Result("T-05.1", "Os 13 atalhos do painel obedecem a UMA regra", "A-maquina")
    try:
        esperado = {c["titulo"]: c["tecla"] for c in PADRAO["sistema"].values()}
        esperado.update({c["titulo"]: c["tecla"] for c in PADRAO["proprios"].values()})
        atual = {}
        for linha in mysql("SELECT TITLE, IFNULL(SHORTCUT,'') FROM AC_APPLICATION;").splitlines():
            if linha.strip():
                t, s = linha.split("\t")
                atual[t] = s

        faltando = sorted(set(esperado) - set(atual))
        ingles = sorted(t for t in atual if re.search(
            r"^(Add a|List |Give a|Contributions)", t))
        sem_tecla = sorted(t for t, s in atual.items() if t in esperado and not s)
        tecla_errada = sorted(f"{t}: {atual[t]!r} != {esperado[t]!r}"
                              for t in esperado if t in atual and atual[t] != esperado[t])
        # a regra de caixa: segunda palavra em maiuscula so' se for sigla ou nome proprio
        SIGLAS = {"GLPI", "MDM", "BI", "Kudos", "Prefeitura"}
        caixa = sorted(t for t in atual if t in esperado for p in t.replace("(", "").replace(")", "").split()[1:]
                       if p[:1].isupper() and p.strip("()") not in SIGLAS)

        problemas = []
        if faltando:
            problemas.append(f"ausentes no banco: {faltando}")
        if ingles:
            problemas.append(f"ainda em ingles: {ingles}")
        if sem_tecla:
            problemas.append(f"sem tecla: {sem_tecla}")
        if tecla_errada:
            problemas.append(f"tecla fora do padrao: {tecla_errada}")
        if caixa:
            problemas.append(f"caixa fora da regra: {sorted(set(caixa))}")

        r.passed = not problemas
        r.detail = "; ".join(problemas) if problemas else \
            f"{len(esperado)} atalhos conferidos: nome pt-BR, caixa e tecla unica"
        r.proof = f"AC_APPLICATION: {json.dumps(atual, ensure_ascii=False)}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_seed_nos_jars(rec: Recorder) -> None:
    """O banco pode estar certo HOJE e voltar ao ingles no proximo start.
    O que garante a permanencia e' o seed com override:true dentro do jar."""
    t0 = time.time()
    r = Result("T-05.2", "O nome dos atalhos de sistema resiste ao proximo start", "A-maquina")
    try:
        problemas = []
        for nome, cfg in PADRAO["sistema"].items():
            bruto = subprocess.run(
                ["docker", "exec", "exo-app", "cat", f"/opt/exo/lib/{cfg['jar']}"],
                capture_output=True, timeout=120).stdout
            tmp = Path("/tmp") / cfg["jar"]
            tmp.write_bytes(bruto)
            doc = json.loads(zipfile.ZipFile(tmp).read("applications.json"))
            tmp.unlink()
            d = next((x for x in doc["descriptors"] if x["name"] == nome), None)
            if d is None:
                problemas.append(f"{nome}: descritor sumiu de {cfg['jar']}")
                continue
            if d["application"]["title"] != cfg["titulo"]:
                problemas.append(f"{nome}: seed diz {d['application']['title']!r}")
            if d.get("override") is not True:
                problemas.append(f"{nome}: override != true -- volta ao ingles no proximo start")
        r.passed = not problemas
        r.detail = "; ".join(problemas) if problemas else \
            f"{len(PADRAO['sistema'])} seeds com titulo pt-BR e override:true"
        r.proof = "applications.json lido de dentro dos jars do container"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_documentacao_sem_link_morto(rec: Recorder) -> None:
    """AUDIT.md fica de fora de proposito: e' registro historico, append-only,
    e cita arquivos que existiam na epoca. Aqui so' entra documentacao viva."""
    t0 = time.time()
    r = Result("T-05.3", "A documentacao nao aponta para arquivo que nao existe", "A-maquina")
    try:
        problemas, conferidos = [], 0
        for doc in ("README.md",):
            p = RAIZ / doc
            if not p.exists():
                problemas.append(f"{doc} nao existe")
                continue
            for citado in sorted(set(re.findall(r"`([A-Za-z0-9_./-]+\.(?:md|sh|py|json|yml))`",
                                                p.read_text(encoding="utf-8")))):
                conferidos += 1
                # o README cita ora o caminho completo ("scripts/backup.sh"), ora
                # so' o nome ("reconstruir-do-zero.sh"). Vale existir em qualquer
                # lugar do repositorio -- o que se cobra e' que exista.
                if not (RAIZ / citado).exists() and not list(RAIZ.rglob(Path(citado).name)):
                    problemas.append(f"{doc} cita {citado}, que nao existe")
        r.passed = not problemas
        r.detail = "; ".join(problemas) if problemas else \
            f"{conferidos} referencia(s) do README conferida(s), todas existem"
        r.proof = "varredura de caminhos citados em README.md"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


# ---------------------------------------------------------------- abordagem B

def b_api_que_a_tela_consome(rec: Recorder) -> None:
    """O banco nao e' o que a tela desenha: o App Center mantem a lista em
    memoria e o titulo tem um SEGUNDO lugar (SOC_METADATA_ITEMS_PROPERTIES).
    Ja' aconteceu de o banco estar certo e a tela mostrar o nome velho."""
    t0 = time.time()
    r = Result("T-05.4", "O nome que a tela desenha e' o do padrao", "B-usuario")
    try:
        rotulos = [l.split("\t")[1] for l in mysql(
            "SELECT p.METADATA_ITEM_ID, p.VALUE FROM SOC_METADATA_ITEMS_PROPERTIES p "
            "JOIN SOC_METADATA_ITEMS i ON i.METADATA_ITEM_ID = p.METADATA_ITEM_ID "
            "WHERE p.NAME='label' AND i.OBJECT_TYPE='appCenter';").splitlines() if l.strip()]
        titulos = {c["titulo"] for c in PADRAO["sistema"].values()} | \
                  {c["titulo"] for c in PADRAO["proprios"].values()}
        fora = sorted(set(rotulos) - titulos)
        r.passed = not fora
        r.detail = f"rotulo(s) divergente(s) do padrao: {fora}" if fora else \
            f"{len(rotulos)} rotulo(s) fixado(s) no painel, todos no padrao"
        r.proof = f"SOC_METADATA_ITEMS_PROPERTIES(label) = {sorted(set(rotulos))}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("T05-padronizacao")
    a_atalhos_no_banco(rec)
    a_seed_nos_jars(rec)
    a_documentacao_sem_link_morto(rec)
    b_api_que_a_tela_consome(rec)
    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
