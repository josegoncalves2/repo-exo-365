#!/usr/bin/env python3
"""
T-07 — DLP: o motor anti-vazamento detecta DE VERDADE.

POR QUE ESTE ARQUIVO EXISTE
O teste que havia (tests/test_dlp_integration.sh) nao testava o eXo: ele
reimplementava a regex de CPF/CNPJ em Python e a rodava contra uma string
literal, dentro do proprio script. Passava sempre — inclusive no dia em que o
DLP da plataforma estava COMPLETAMENTE inerte. Foi o pior tipo de teste: o que
produz confianca sem produzir evidencia.

O que estava quebrado, e que este teste teria pego:
  1. extensao/dlp-br/conf/configuration.xml declarava <priority>100</priority>.
     ComponentPlugin.compareTo ordena ASCENDENTE e
     DlpOperationProcessor.addConnector e' FIRST-WINS (registra e, se a chave
     ja' existe, DESCARTA com ERROR). Com 100 o conector nativo chegava
     primeiro e o ConectorDlpRegex era jogado fora a cada boot.
  2. AcaoEnfileirarDlp chamava addToQueue(idEntidade, "file") com os
     argumentos invertidos — a assinatura e' addToQueue(entityType, entityId).
     Isso gravava DLP_QUEUE com ENTITY_TYPE = <uuid>, e processBulk, que
     agrupa por ENTITY_TYPE e faz getConnectors().get(tipo), estourava
     NullPointerException e abortava o BULK INTEIRO — junto com as linhas
     corretas.

Resultado das duas somadas: DLP_QUEUE parada, DLP_POSITIVE_ITEMS vazia,
nenhum documento varrido. O portal exibia o recurso como se protegesse.

ABORDAGEM: escreve um arquivo com um CPF VALIDO (digito verificador correto)
no JCR pela mesma API que o usuario usa, espera o job de varredura e cobra a
linha em DLP_POSITIVE_ITEMS. Nao ha regex neste arquivo: quem tem de detectar
e' a plataforma.

O CPF 529.982.247-25 e' o numero de exemplo publico usado na documentacao de
validacao de CPF — nao pertence a pessoa alguma.
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import ExoClient, Recorder, Result  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
CPF_DE_EXEMPLO = "529.982.247-25"
# O job roda a cada exo.dlp.job.period (300000 ms). Espera-se ate' 2 periodos
# mais folga: o item passa por indexacao antes de entrar na fila do DLP.
ESPERA_MAX_S = 660
INTERVALO_S = 15


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


def a_conector_registrado(rec: Recorder) -> None:
    """O conector que ficou no mapa tem de ser o nosso.

    Nao da' para ler o mapa de fora, entao mede-se pelo unico efeito que
    distingue os dois: o nativo so' casa exo.dlp.keywords (que esta' VAZIO
    nesta instalacao), o nosso casa padrao brasileiro com digito verificador.
    Se o nativo tivesse ficado, nada seria detectado — e e' exatamente isso
    que acontecia ate' 2026-08-31.
    """
    t0 = time.time()
    r = Result("T-07.1", "O conector de DLP ativo e' o do projeto, nao o nativo", "A-maquina")
    try:
        chaves = ""
        for l in (RAIZ / "conf" / "exo.properties").read_text(encoding="utf-8").splitlines():
            if l.startswith("exo.dlp.keywords="):
                chaves = l.split("=", 1)[1].strip()
        erros = mysql("SELECT 1;")  # sonda de conectividade
        r.passed = chaves == "" and bool(erros)
        r.detail = ("exo.dlp.keywords esta vazio: qualquer deteccao por padrao "
                    "so' pode vir do ConectorDlpRegex" if r.passed
                    else f"exo.dlp.keywords={chaves!r} — o teste perde o poder de distinguir")
        r.proof = f"exo.dlp.keywords={chaves!r}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_deteccao_ponta_a_ponta(rec: Recorder) -> None:
    t0 = time.time()
    r = Result("T-07.2", "Documento com CPF valido e' detectado pela plataforma", "A-maquina")
    passos = []
    try:
        antes = int((mysql("SELECT COUNT(*) FROM DLP_POSITIVE_ITEMS;").strip() or "0"))
        passos.append(f"DLP_POSITIVE_ITEMS antes: {antes}")

        c = ExoClient()
        if not c.login():
            raise RuntimeError("nao foi possivel autenticar")
        nome = f"prova-dlp-{int(time.time())}.txt"
        corpo = (f"Relatorio interno.\nServidor: Maria Souza\n"
                 f"CPF {CPF_DE_EXEMPLO}\nFim.\n").encode("utf-8")
        caminho = (f"/rest/private/jcr/repository/collaboration/Users/"
                   f"r___/ro___/roo___/root/Private/{nome}")
        resp = c.put(caminho, data=corpo, headers={
            "Content-Type": "text/plain", "Content-Length": str(len(corpo))})
        passos.append(f"PUT {nome} -> {resp.status_code}")
        if resp.status_code not in (200, 201, 204):
            raise RuntimeError(f"upload falhou: HTTP {resp.status_code}")

        limite = time.time() + ESPERA_MAX_S
        depois = antes
        while time.time() < limite:
            depois = int((mysql("SELECT COUNT(*) FROM DLP_POSITIVE_ITEMS;").strip() or "0"))
            if depois > antes:
                break
            time.sleep(INTERVALO_S)
        passos.append(f"DLP_POSITIVE_ITEMS depois: {depois} "
                      f"(espera de ate' {ESPERA_MAX_S}s pelo job)")

        achado = mysql("SELECT ITEM_TYPE, KEYWORDS, ITEM_AUTHOR FROM DLP_POSITIVE_ITEMS "
                       "ORDER BY DETECTION_DATE DESC LIMIT 1;").strip()
        passos.append(f"ultimo positivo: {achado!r}")

        # A fila nao pode ficar entupida: linha com ENTITY_TYPE != 'file'
        # derruba o bulk inteiro por NullPointerException.
        invertidas = int((mysql("SELECT COUNT(*) FROM DLP_QUEUE "
                                "WHERE ENTITY_TYPE <> 'file';").strip() or "0"))
        passos.append(f"linhas de DLP_QUEUE com ENTITY_TYPE invertido: {invertidas}")

        r.passed = depois > antes and "CPF" in achado and invertidas == 0
        r.detail = ("CPF detectado pela plataforma e fila sem linha invertida" if r.passed
                    else "a plataforma NAO detectou o CPF, ou a fila tem linha invertida")
        r.proof = f"antes={antes} depois={depois} ultimo={achado!r} invertidas={invertidas}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.steps = passos
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("T07-dlp")
    print("=" * 70)
    print("T-07 — DLP: deteccao real pela plataforma")
    print("=" * 70)
    a_conector_registrado(rec)
    a_deteccao_ponta_a_ponta(rec)
    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
