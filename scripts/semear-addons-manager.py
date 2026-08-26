#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ============================================================================
# semear-addons-manager.py -- poe o comando 'addon' dentro do PLF_HOME.
#
#   python3 semear-addons-manager.py <raiz_do_repo_no_build> <PLF_HOME>
#
# O exo-addons-manager e' o unico add-on que nao pode ser instalado pelo
# 'addon install': ele E' o 'addon'. A imagem exoplatform/exo-community nao o
# traz (conferido: 0 ocorrencias de 'addon' em start_eXo.sh, e /opt/exo/addon
# nao existe), mas ele esta' no catalogo oficial como qualquer outro -- AGPLv3,
# publico, versao 7.2.1-exo casada com esta imagem.
#
# Entao ele e' semeado a mao, UMA vez, a partir do mesmo cache vendorizado e com
# o mesmo sha256 selado no manifesto que vale para todos os demais. Depois disso
# scripts/addons.py instala o resto pelo caminho oficial.
#
# Nao usa 'unzip': a imagem base pode nao ter o binario, e zipfile da stdlib
# resolve sem acrescentar dependencia ao build.
# ============================================================================
import hashlib
import json
import os
import sys
import zipfile


def main():
    if len(sys.argv) != 3:
        sys.exit("uso: semear-addons-manager.py <raiz_do_repo> <PLF_HOME>")
    raiz, plf = sys.argv[1], sys.argv[2]

    with open(os.path.join(raiz, "conf", "addons", "manifesto.json"), encoding="utf-8") as fh:
        manifesto = json.load(fh)
    mgr = next((a for a in manifesto["addons"] if a["id"] == "exo-addons-manager"), None)
    if mgr is None:
        sys.exit("manifesto sem exo-addons-manager -- sem ele nenhum add-on entra")

    zpath = os.path.join(raiz, "conf", "addons", "cache",
                         "%s-%s.zip" % (mgr["id"], mgr["versao"]))
    if not os.path.exists(zpath):
        sys.exit("%s ausente -- rode './scripts/addons.py baixar' antes do build" % zpath)

    h = hashlib.sha256()
    with open(zpath, "rb") as fh:
        for bloco in iter(lambda: fh.read(1 << 20), b""):
            h.update(bloco)
    if not mgr.get("sha256"):
        sys.exit("manifesto sem sha256 para o addons-manager -- rode 'baixar --selar'")
    if h.hexdigest() != mgr["sha256"]:
        sys.exit("sha256 do addons-manager NAO BATE com o manifesto\n"
                 "  selado : %s\n  no disco: %s" % (mgr["sha256"], h.hexdigest()))

    with zipfile.ZipFile(zpath) as z:
        nomes = z.namelist()
        for obrigatorio in ("addon", "addons/addons-manager.jar",
                            "addons/configuration/am.properties"):
            if obrigatorio not in nomes:
                sys.exit("o zip do addons-manager nao tem '%s' -- empacotamento mudou" % obrigatorio)
        z.extractall(plf)

    os.chmod(os.path.join(plf, "addon"), 0o755)
    print("OK: exo-addons-manager %s semeado em %s" % (mgr["versao"], plf))


if __name__ == "__main__":
    main()
