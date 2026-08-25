#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Portao do build: reprova a imagem se um atalho de sistema nao estiver no
# padrao. Le a verdade dos JARS e compara com conf/atalhos/padrao.json -- nao
# ha lista de nomes repetida aqui, entao o portao nao pode divergir da fonte.
#
#   conferir.py <dir_lib> <padrao.json>
import json, sys, zipfile
from pathlib import Path

lib, padrao = Path(sys.argv[1]), json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
falhas, conferidos = [], 0

for nome, cfg in padrao["sistema"].items():
    caminho = lib / cfg["jar"]
    if not caminho.exists():
        falhas.append(f"{cfg['jar']}: nao existe")
        continue
    doc = json.loads(zipfile.ZipFile(caminho).read("applications.json"))
    desc = next((d for d in doc["descriptors"] if d["name"] == nome), None)
    if desc is None:
        falhas.append(f"{cfg['jar']}: descritor '{nome}' sumiu")
        continue
    app = desc["application"]
    if app["title"] != cfg["titulo"]:
        falhas.append(f"{nome}: titulo '{app['title']}' != '{cfg['titulo']}'")
    if app.get("shortcut") != cfg["tecla"]:
        falhas.append(f"{nome}: tecla '{app.get('shortcut')}' != '{cfg['tecla']}'")
    if desc.get("override") is not True:
        falhas.append(f"{nome}: override != true -- o nome voltaria ao ingles no proximo start")
    if app["title"] == cfg["de"]:
        falhas.append(f"{nome}: continua com o titulo original em ingles")
    conferidos += 1

if falhas:
    print("ATALHOS REPROVADOS:", *falhas, sep="\n  - ", file=sys.stderr)
    sys.exit(1)
print(f"OK: {conferidos} atalhos de sistema em pt-BR, com tecla, reaplicados a cada start")
