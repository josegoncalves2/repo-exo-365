#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
repovoar.py — recria os usuarios perdidos no apagamento de 2026-08-26 09:00.

A FONTE nao e' memoria nem chute: e' o indice `profile_v4` do Elasticsearch,
que sobreviveu ao `rm -rf data/mysql/*` e foi extraido para
evidence/resgate/usuarios-recuperados.csv (login, nome, e-mail).

O que NAO volta por aqui, e nao ha como voltar: as senhas (o dump nao existia
na epoca do apagamento). Cada conta nasce com senha forte gerada, gravada em
evidence/resgate/credenciais-novas.txt com permissao 600, para o operador
distribuir e forcar troca no primeiro acesso.

Idempotente: quem ja' existe no store de ORGANIZACAO e' pulado.
Uso:  tests/.venv/bin/python scripts/repovoar.py [--simular]
"""
from __future__ import annotations

import csv
import os
import pathlib
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / "scripts"))
sys.path.insert(0, str(RAIZ / "tests"))

CSV = RAIZ / "evidence" / "resgate" / "usuarios-recuperados.csv"
SAIDA = RAIZ / "evidence" / "resgate" / "credenciais-novas.txt"
SIMULAR = "--simular" in sys.argv

# contas que o assistente do eXo ja' criou -- nao mexer
JA_EXISTEM = {"root", "saexo"}


def main() -> int:
    if not CSV.exists():
        sys.exit(f"nao achei {CSV} -- rode o resgate do Elasticsearch antes")

    import exo_estrutura as E

    pessoas = []
    with CSV.open(encoding="utf-8") as fh:
        for linha in csv.DictReader(fh):
            login = (linha.get("userName") or "").strip()
            if not login or login in JA_EXISTEM:
                continue
            pessoas.append({
                "login": login,
                "nome": (linha.get("name") or "").strip(),
                "email": (linha.get("email") or "").strip(),
                "senha": None,                      # senha_forte() gera
            })

    print(f"{len(pessoas)} contas a recriar (root e saexo ja' existem)\n")
    exo = E.conectar(dry=SIMULAR)
    motor = E.Provisionador(exo, log=print, dry=SIMULAR)
    motor.criados = {"usuarios": []}
    motor.credenciais = []

    criados, pulados, falhos = [], [], []
    for p in pessoas:
        existe, habil = motor._existe_usuario(p["login"])
        if existe:
            pulados.append(p["login"])
            print(f"  ja' existe: {p['login']}")
            continue
        try:
            motor._criar_usuario(p)
            criados.append(p["login"])
        except Exception as e:                                   # noqa: BLE001
            falhos.append((p["login"], str(e)[:120]))
            print(f"  FALHOU {p['login']}: {str(e)[:120]}")

    if motor.credenciais and not SIMULAR:
        SAIDA.parent.mkdir(parents=True, exist_ok=True)
        with SAIDA.open("w", encoding="utf-8") as fh:
            fh.write("# senhas geradas em " + __import__("datetime")
                     .datetime.now().isoformat(timespec="seconds") + "\n")
            fh.write("# distribuir e EXIGIR troca no primeiro acesso\n")
            for login, senha in motor.credenciais:
                fh.write(f"{login}\t{senha}\n")
        os.chmod(SAIDA, 0o600)
        print(f"\ncredenciais gravadas em {SAIDA} (chmod 600)")

    print(f"\ncriados={len(criados)}  ja_existiam={len(pulados)}  falharam={len(falhos)}")
    for l, e in falhos:
        print(f"  {l}: {e}")
    return 1 if falhos else 0


if __name__ == "__main__":
    sys.exit(main())
