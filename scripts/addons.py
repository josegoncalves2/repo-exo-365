#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ============================================================================
# addons.py -- todos os add-ons da suite sob UM manifesto declarativo.
#
#   ./scripts/addons.py resolver     # consulta o catalogo e PROPOE versao/add-on
#   ./scripts/addons.py conferir     # o manifesto ainda bate com o catalogo?
#   ./scripts/addons.py baixar       # baixa p/ conf/addons/cache e sela o sha256
#   ./scripts/addons.py instalar     # instala num PLF_HOME (usado no build)
#   ./scripts/addons.py listar       # o que ha' instalado num PLF_HOME
#
# A lista e a regra moram em conf/addons/manifesto.json -- FONTE UNICA.
#
# POR QUE ESTE ARQUIVO EXISTE (e o que ele substitui):
#
#   Ate' aqui cada add-on entrava no Dockerfile como um bloco a mao -- COPY do
#   zip, unzip, cp de cada .war e cada .jar, um bloco por add-on. Funciona para
#   dois. Para dezoito e' insustentavel: cada add-on novo e' um bloco novo, o
#   conjunto de arquivos de cada zip vira conhecimento tacito do Dockerfile, e
#   nada avisa quando o upstream muda de layout.
#
#   A eXo ja' resolve isso com o Add-on Manager oficial (comando 'addon'), que
#   sabe desempacotar, registrar o que instalou e desinstalar. A imagem
#   exoplatform/exo-community NAO o traz -- mas ele E' um add-on do catalogo
#   (exo-addons-manager), publico, AGPLv3. Entao ele mesmo entra primeiro, e
#   instala todo o resto.
#
# DISTRIBUICAO -- por que '--no-compat' aparece aqui e nao e' gambiarra:
#
#   A imagem se identifica como distribuicao 'exo_community' (medido:
#   PlatformSettings.distributionType). Parte do catalogo declara suportar
#   'exo_community,community,enterprise' e parte so' 'community,enterprise'.
#   Os do segundo grupo sao recusados pelo teste de compatibilidade, embora
#   sejam a MESMA edicao community: e' rotulagem do catalogo, nao restricao
#   tecnica nem de licenca (todos AGPLv3/LGPLv3, mustAcceptLicense=false).
#   Prova empirica: exo-glpi-integration 7.2.0 declara so' 'community,
#   enterprise' e roda nesta instalacao desde 2026-08-25.
#   Por isso o manifesto marca, add-on a add-on, quem precisa de --no-compat --
#   e o campo e' obrigatorio, para que a decisao fique escrita e nao vire um
#   flag global aplicado no escuro.
#
# REPRODUTIBILIDADE: o manifesto fixa versao E sha256. O build recusa um zip
# cujo sha256 nao bata com o selado. 'baixar --selar' e' o unico caminho para
# gravar um sha256 novo, e ele mostra o que mudou.
# ============================================================================
import argparse
import functools
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFESTO = os.path.join(RAIZ, "conf", "addons", "manifesto.json")
CACHE = os.path.join(RAIZ, "conf", "addons", "cache")

# --------------------------------------------------------------------------
# Comparacao de versao no estilo Maven ComparableVersion.
# Precisa disto porque o catalogo expressa compatibilidade em FAIXA maven
# ("[7.2-m1,)", "[7.2.1]") e ordenar por string erra o essencial:
# '7.2.1-RC01' < '7.2.1' e '7.2-m1' < '7.2'. Ordenar texto inverte os dois.
# --------------------------------------------------------------------------
QUALIFICADOR = {"alpha": 0, "a": 0, "beta": 1, "b": 1, "milestone": 2, "m": 2,
                "rc": 3, "cr": 3, "snapshot": 4,
                "": 5, "ga": 5, "final": 5, "release": 5, "sp": 6}
_DESCONHECIDO = 7   # qualificador que nao conhecemos ordena depois dos conhecidos


def _pedacos(versao):
    partes = re.split(r"[.\-_]|(?<=\d)(?=[a-z])|(?<=[a-z])(?=\d)", versao.lower())
    return [p for p in partes if p]


def _chave(versao):
    fora = []
    for p in _pedacos(versao):
        if p.isdigit():
            fora.append((1, int(p), ""))
        else:
            fora.append((0, QUALIFICADOR.get(p, _DESCONHECIDO), p))
    return fora


def compara(a, b):
    ka, kb = _chave(a), _chave(b)
    for i in range(max(len(ka), len(kb))):
        # ausencia equivale a ".0": 7.2 e 7.2.0 sao a mesma versao
        x = ka[i] if i < len(ka) else (1, 0, "")
        y = kb[i] if i < len(kb) else (1, 0, "")
        if x != y:
            return -1 if x < y else 1
    return 0


def na_faixa(faixa, versao):
    """Faixa maven: [a,) (a,b] [a,b) [a] -- vazio aceita tudo."""
    if not faixa:
        return True
    faixa = faixa.strip()
    if not (faixa[0] in "[(" and faixa[-1] in "])"):
        return compara(faixa, versao) == 0
    abre, fecha, corpo = faixa[0], faixa[-1], faixa[1:-1]
    if "," not in corpo:
        return compara(corpo, versao) == 0
    baixo, alto = corpo.split(",", 1)
    if baixo:
        limite = compara(baixo, versao)
        if limite > 0 or (limite == 0 and abre == "("):
            return False
    if alto:
        limite = compara(versao, alto)
        if limite > 0 or (limite == 0 and fecha == ")"):
            return False
    return True


# --------------------------------------------------------------------------
def carrega_manifesto():
    with open(MANIFESTO, encoding="utf-8") as fh:
        m = json.load(fh)
    vistos = set()
    for a in m["addons"]:
        for campo in ("id", "versao", "categoria", "porque", "no_compat"):
            if campo not in a:
                sys.exit(f"manifesto invalido: add-on {a.get('id','?')} sem o campo '{campo}'")
        if a["id"] in vistos:
            sys.exit(f"manifesto invalido: add-on repetido {a['id']}")
        vistos.add(a["id"])
    return m


def baixa_catalogo(url, destino=None):
    destino = destino or os.path.join(CACHE, "catalogo.json")
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    # No build (ADDONS_CATALOGO_CACHE=1) o catalogo vem do arquivo ja' versionado
    # -- e' o que permite construir a imagem sem rede.
    if os.path.exists(destino) and os.environ.get("ADDONS_CATALOGO_CACHE") == "1":
        with open(destino, encoding="utf-8") as fh:
            return json.load(fh)
    with urllib.request.urlopen(url, timeout=180) as r:
        dados = r.read()
    with open(destino, "wb") as fh:
        fh.write(dados)
    return json.loads(dados)


def entradas(catalogo, aid):
    return [x for x in catalogo if x.get("id") == aid]


def melhor(catalogo, aid, plataforma, distro, aceita_instavel=False):
    """A maior versao do add-on compativel com a plataforma e a distribuicao."""
    cand = [x for x in entradas(catalogo, aid)
            if "SNAPSHOT" not in x.get("version", "")
            and distro in str(x.get("supportedDistributions"))
            and (aceita_instavel or not x.get("unstable"))
            and na_faixa(x.get("compatibility"), plataforma)]
    if not cand:
        return None
    return sorted(cand, key=functools.cmp_to_key(
        lambda a, b: compara(a["version"], b["version"])))[-1]


def registro(catalogo, aid, versao):
    return next((x for x in entradas(catalogo, aid) if x.get("version") == versao), None)


def sha256_arquivo(caminho):
    h = hashlib.sha256()
    with open(caminho, "rb") as fh:
        for bloco in iter(lambda: fh.read(1 << 20), b""):
            h.update(bloco)
    return h.hexdigest()


def caminho_cache(a):
    return os.path.join(CACHE, f"{a['id']}-{a['versao']}.zip")


def gera_catalogo_local(m, cat, destino, dir_zips):
    """Catalogo com downloadUrl file:// apontando para os zips vendorizados.

    POR QUE ISTO EXISTE: medido, o Add-on Manager IGNORA um zip ja' presente em
    addons/archives/ e rebaixa do repositorio -- 'addon install' sem rede falha
    mesmo com o arquivo do lado. O unico ponto onde a origem do binario e'
    escolhida e' o downloadUrl do catalogo. Entao o build usa um catalogo local
    identico ao oficial em TODOS os metadados (nome, licenca, compatibilidade,
    distribuicoes -- copiados do registro real) e diferente em UM campo: a URL
    passa a ser file://, do cache que o git versiona.

    Consequencia pratica: o `docker build` nao depende de addons.exoplatform.org
    nem de repository.exoplatform.org estarem no ar, e dois builds da mesma
    arvore instalam byte a byte o mesmo add-on.
    """
    doc = []
    for a in m["addons"]:
        reg = registro(cat, a["id"], a["versao"])
        if reg is None:
            sys.exit(f"{a['id']}:{a['versao']} nao esta no catalogo -- nao da' para gerar o local")
        item = dict(reg)
        item["downloadUrl"] = "file://" + os.path.join(dir_zips, f"{a['id']}-{a['versao']}.zip")
        doc.append(item)
    with open(destino, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False)
    return destino


# --------------------------------------------------------------------------
# comandos
# --------------------------------------------------------------------------
def cmd_resolver(args):
    m = carrega_manifesto()
    cat = baixa_catalogo(m["catalogo"])
    plat, distro = m["plataforma"], m["distribuicao"]
    print(f"plataforma {plat} / distribuicao {distro}\n")
    print(f"{'ADD-ON':36} {'MANIFESTO':14} {'PROPOSTO':14} DISTRIBUICOES")
    print("-" * 100)
    for a in m["addons"]:
        exato = melhor(cat, a["id"], plat, distro, aceita_instavel=True)
        largo = melhor(cat, a["id"], plat, "community", aceita_instavel=True)
        escolha = exato or largo
        reg = registro(cat, a["id"], a["versao"])
        marca = " " if reg and escolha and escolha["version"] == a["versao"] else "*"
        print(f"{marca}{a['id']:35} {a['versao']:14} "
              f"{(escolha['version'] if escolha else '—'):14} "
              f"{(reg or {}).get('supportedDistributions','(versao fora do catalogo)')}")
    print("\n* = o catalogo tem versao diferente da fixada no manifesto")


def cmd_conferir(args):
    m = carrega_manifesto()
    cat = baixa_catalogo(m["catalogo"])
    plat, distro = m["plataforma"], m["distribuicao"]
    problemas = []
    print(f"{'ADD-ON':36} {'VERSAO':14} {'LIC':8} {'INST':6} {'DIST':6} FAIXA")
    print("-" * 92)
    for a in m["addons"]:
        reg = registro(cat, a["id"], a["versao"])
        if not reg:
            problemas.append(f"{a['id']}:{a['versao']} nao existe no catalogo")
            print(f"{a['id']:36} {a['versao']:14} {'—':8} {'—':6} {'—':6} AUSENTE DO CATALOGO")
            continue
        distros = str(reg.get("supportedDistributions"))
        precisa = distro not in distros
        if precisa != bool(a["no_compat"]):
            problemas.append(
                f"{a['id']}: manifesto diz no_compat={a['no_compat']} mas o catalogo "
                f"declara '{distros}' (distribuicao desta imagem: {distro})")
        # O exo-addons-manager e' a UNICA excecao ao teste de faixa, e por um
        # motivo estrutural: ele nao e' instalado pelo 'addon install' -- e' o
        # proprio 'addon'. E' semeado a mao no build, antes de existir alguem
        # para instala-lo. Sua faixa e' [7.2.1-exo] (a versao da imagem eXo),
        # que por definicao nao casa com plataforma '7.2.1'. Cobrar a faixa dele
        # aqui reprovaria o build por uma comparacao que nao se aplica.
        if a["id"] != "exo-addons-manager" and not na_faixa(reg.get("compatibility"), plat):
            problemas.append(f"{a['id']}:{a['versao']} compat {reg.get('compatibility')} nao cobre {plat}")
        if reg.get("mustAcceptLicense"):
            problemas.append(f"{a['id']}:{a['versao']} EXIGE ACEITE DE LICENCA -- decisao humana")
        print(f"{a['id']:36} {a['versao']:14} {str(reg.get('license')):8} "
              f"{('instav' if reg.get('unstable') else 'estav'):6} "
              f"{('no-cmp' if precisa else 'ok'):6} {reg.get('compatibility')}")
    print()
    if problemas:
        for p in problemas:
            print(f"  DIVERGENCIA: {p}")
        return 1
    print("  manifesto e catalogo conferem.")
    return 0


def cmd_baixar(args):
    m = carrega_manifesto()
    cat = baixa_catalogo(m["catalogo"])
    os.makedirs(CACHE, exist_ok=True)
    mudou, falhou = [], []
    for a in m["addons"]:
        reg = registro(cat, a["id"], a["versao"])
        if not reg:
            falhou.append(f"{a['id']}:{a['versao']} fora do catalogo")
            continue
        alvo = caminho_cache(a)
        if os.path.exists(alvo) and a.get("sha256") == sha256_arquivo(alvo):
            print(f"  ja' em cache  {a['id']}-{a['versao']}.zip")
            continue
        print(f"  baixando      {a['id']}-{a['versao']}.zip ...", end="", flush=True)
        tmp = alvo + ".parcial"
        try:
            with urllib.request.urlopen(reg["downloadUrl"], timeout=600) as r, open(tmp, "wb") as fh:
                while True:
                    bloco = r.read(1 << 20)
                    if not bloco:
                        break
                    fh.write(bloco)
        except Exception as e:                                   # noqa: BLE001
            if os.path.exists(tmp):
                os.remove(tmp)
            falhou.append(f"{a['id']}:{a['versao']} -- {e}")
            print(" FALHOU")
            continue
        soma = sha256_arquivo(tmp)
        if a.get("sha256") and a["sha256"] != soma and not args.selar:
            os.remove(tmp)
            falhou.append(f"{a['id']}:{a['versao']} sha256 DIFERENTE do selado "
                          f"(selado {a['sha256'][:16]}..., baixado {soma[:16]}...)")
            print(" SHA256 NAO BATE")
            continue
        os.replace(tmp, alvo)
        if a.get("sha256") != soma:
            mudou.append((a["id"], a.get("sha256"), soma))
            a["sha256"] = soma
        print(f" ok ({os.path.getsize(alvo):,} bytes)")

    if args.selar and mudou:
        with open(MANIFESTO, "w", encoding="utf-8") as fh:
            json.dump(m, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        print("\n  sha256 selados no manifesto:")
        for aid, antes, agora in mudou:
            print(f"    {aid}: {(antes or '(vazio)')[:16]} -> {agora[:16]}")
    for f in falhou:
        print(f"  FALHOU: {f}")
    return 1 if falhou else 0


def cmd_instalar(args):
    """Instala no PLF_HOME. Roda DENTRO do build da imagem, sem rede."""
    m = carrega_manifesto()
    raiz, addon = args.raiz, os.path.join(args.raiz, "addon")
    if not os.path.exists(addon):
        sys.exit(f"{addon} nao existe -- o exo-addons-manager precisa ser semeado antes")
    cache = args.cache or CACHE
    cat = baixa_catalogo(m["catalogo"], os.path.join(cache, "catalogo.json"))
    local = gera_catalogo_local(m, cat, os.path.join(cache, "catalogo-local.json"), cache)
    catalogo = "file://" + local
    falhou = []
    for a in m["addons"]:
        if a["id"] == "exo-addons-manager":
            continue                       # ele proprio ja' esta' semeado
        zip_local = os.path.join(cache, f"{a['id']}-{a['versao']}.zip")
        if not os.path.exists(zip_local):
            falhou.append(f"{a['id']}: {zip_local} ausente (rode 'baixar')")
            continue
        if a.get("sha256") and sha256_arquivo(zip_local) != a["sha256"]:
            falhou.append(f"{a['id']}: sha256 do cache diverge do manifesto")
            continue
        cmd = [addon, "install", f"{a['id']}:{a['versao']}",
               f"--catalog={catalogo}", "--conflict=overwrite", "-B"]
        if a["no_compat"]:
            cmd.append("--no-compat")
        if a.get("instavel"):
            cmd.append("--unstable")
        print(f"\n### {a['id']}:{a['versao']}  ({a['categoria']})")
        r = subprocess.run(cmd, cwd=raiz, text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        saida = re.sub(r"\x1b\[[0-9;]*m", "", r.stdout)
        print("\n".join("    " + l for l in saida.splitlines()[-6:]))
        if r.returncode != 0:
            falhou.append(f"{a['id']}: addon install saiu {r.returncode}")
    print()
    for f in falhou:
        print(f"  FALHOU: {f}")
    return 1 if falhou else 0


def cmd_listar(args):
    addon = os.path.join(args.raiz, "addon")
    if not os.path.exists(addon):
        sys.exit(f"{addon} nao existe")
    r = subprocess.run([addon, "list", "--installed", "--offline", "-B"],
                       cwd=args.raiz, text=True,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(re.sub(r"\x1b\[[0-9;]*m", "", r.stdout))
    return r.returncode


def main():
    p = argparse.ArgumentParser(description="Add-ons da suite, sob um manifesto unico.")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("resolver", help="propoe a versao de cada add-on a partir do catalogo")
    sub.add_parser("conferir", help="confere o manifesto contra o catalogo oficial")
    b = sub.add_parser("baixar", help="baixa os zips para conf/addons/cache")
    b.add_argument("--selar", action="store_true",
                   help="grava no manifesto o sha256 do que baixou (unico caminho para mudar sha256)")
    i = sub.add_parser("instalar", help="instala no PLF_HOME (usado no build)")
    i.add_argument("--raiz", default="/opt/exo")
    i.add_argument("--cache", help="pasta dos zips vendorizados (padrao: conf/addons/cache)")
    l = sub.add_parser("listar", help="lista o que esta instalado num PLF_HOME")
    l.add_argument("--raiz", default="/opt/exo")
    args = p.parse_args()
    return {"resolver": cmd_resolver, "conferir": cmd_conferir, "baixar": cmd_baixar,
            "instalar": cmd_instalar, "listar": cmd_listar}[args.cmd](args) or 0


if __name__ == "__main__":
    sys.exit(main())
