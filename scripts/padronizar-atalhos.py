#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ============================================================================
# padronizar-atalhos.py -- os 13 atalhos do painel (App Center) sob UMA regra.
#
#   ./scripts/padronizar-atalhos.py                    # mostra o que mudaria
#   ./scripts/padronizar-atalhos.py --aplicar          # grava no banco
#   ./scripts/padronizar-atalhos.py --seed /opt/exo/lib   # corrige os jars (build)
#
# A regra e a lista completa moram em conf/atalhos/padrao.json -- FONTE UNICA.
# Nenhum nome e' repetido aqui dentro.
#
# DOIS LADOS, DOIS LUGARES -- e e' por isso que a tela estava misturada:
#
#   proprios (IS_SYSTEM=0)  -> so' existem no banco.  Modo --aplicar.
#   sistema  (IS_SYSTEM=1)  -> a eXo injeta no boot a partir de um
#                              applications.json DENTRO de um jar, com titulo
#                              literal em ingles. Modo --seed, no build.
#
# Por que --seed e nao um UPDATE: ApplicationCenterInjectService so' atualiza um
# atalho de sistema ja' existente quando o descritor traz "override": true --
# com false ele registra "Ignore updating system application, override flag is
# turned off" e mantem o ingles. Corrigindo o seed, o nome e a tecla passam a
# ser REAPLICADOS a cada start, entao nao ha como voltarem sozinhos.
#
# IDEMPOTENTE dos dois lados: so' escreve onde difere.
# ============================================================================
import argparse, json, os, subprocess, sys, zipfile

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PADRAO = os.path.join(RAIZ, "conf", "atalhos", "padrao.json")


def carrega(caminho=None):
    with open(caminho or PADRAO, encoding="utf-8") as fh:
        d = json.load(fh)
    teclas = [v["tecla"] for v in d["sistema"].values()] + [v["tecla"] for v in d["proprios"].values()]
    if len(set(teclas)) != len(teclas):
        repetidas = sorted({t for t in teclas if teclas.count(t) > 1})
        sys.exit(f"padrao.json invalido: tecla repetida {repetidas} -- a regra 6 exige tecla unica")
    return d


# --------------------------------------------------------------------------
# LADO SISTEMA -- reescreve o applications.json dentro dos jars (build)
# --------------------------------------------------------------------------
def le_json_do_jar(caminho):
    with zipfile.ZipFile(caminho) as z:
        return json.loads(z.read("applications.json"))


def grava_json_no_jar(caminho, doc):
    """Reescreve o jar inteiro trocando applications.json.

    zipfile nao substitui entrada no lugar -- 'a' criaria uma SEGUNDA
    applications.json e a leitura passaria a depender de qual das duas o
    leitor escolhe. Entao regrava tudo, preservando nome, ordem e metodo de
    compressao de cada entrada, e so' troca o conteudo do alvo.
    """
    texto = json.dumps(doc, ensure_ascii=False, indent=4).encode("utf-8")
    tmp = caminho + ".novo"
    with zipfile.ZipFile(caminho) as origem, zipfile.ZipFile(tmp, "w") as destino:
        for info in origem.infolist():
            dado = texto if info.filename == "applications.json" else origem.read(info.filename)
            novo_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            novo_info.compress_type = info.compress_type
            novo_info.external_attr = info.external_attr
            destino.writestr(novo_info, dado)
    os.replace(tmp, caminho)


def seed(libdir, padrao):
    alvos = {}
    for nome, cfg in padrao["sistema"].items():
        alvos.setdefault(cfg["jar"], {})[nome] = cfg

    total = 0
    for jar, apps in sorted(alvos.items()):
        caminho = os.path.join(libdir, jar)
        if not os.path.exists(caminho):
            sys.exit(f"FALTA {caminho} -- o seed dos atalhos de sistema mudou de jar nesta versao")
        doc = le_json_do_jar(caminho)
        visto = set()
        for desc in doc["descriptors"]:
            cfg = apps.get(desc["name"])
            if not cfg:
                continue
            visto.add(desc["name"])
            desc["override"] = True          # sem isto a eXo ignora e o ingles volta
            desc["application"]["title"] = cfg["titulo"]
            desc["application"]["shortcut"] = cfg["tecla"]
            total += 1
        faltando = set(apps) - visto
        if faltando:
            sys.exit(f"{jar}: descritor(es) {sorted(faltando)} nao existe(m) mais neste jar")
        grava_json_no_jar(caminho, doc)
        # conferencia: le de volta DE DENTRO do jar, nao da variavel em memoria
        for desc in le_json_do_jar(caminho)["descriptors"]:
            cfg = apps.get(desc["name"])
            if cfg and (desc["application"]["title"] != cfg["titulo"]
                        or desc["application"]["shortcut"] != cfg["tecla"]
                        or desc["override"] is not True):
                sys.exit(f"{jar}: {desc['name']} nao gravou como esperado")
        print(f"  OK {jar}: {', '.join(sorted(apps))}")
    print(f"seed corrigido: {total} atalho(s) de sistema em {len(alvos)} jar(es)")


# --------------------------------------------------------------------------
# LADO PROPRIO -- banco
# --------------------------------------------------------------------------
def mysql(sql):
    senha = ""
    with open(os.path.join(RAIZ, ".env"), encoding="utf-8") as fh:
        for l in fh:
            if l.startswith("MYSQL_ROOT_PASSWORD="):
                senha = l.split("=", 1)[1].strip()
    p = subprocess.run(["docker", "exec", "-e", f"MYSQL_PWD={senha}", "exo-mysql", "mysql",
                        "-uroot", "exo", "--default-character-set=utf8mb4", "-N", "-e", sql],
                       capture_output=True, text=True, timeout=120)
    if p.returncode:
        sys.exit("SQL falhou: " + p.stderr[-400:])
    return p.stdout


def escapa(v):
    return "NULL" if v is None else "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def banco(padrao, aplicar):
    atual = {}
    NULO = "\x01"   # sentinela: distingue NULL de string vazia
    for linha in mysql("SELECT ID, URL, TITLE, IFNULL(DESCRIPTION,CHAR(1)), IFNULL(SHORTCUT,CHAR(1)), "
                       "IFNULL(APPLICATION_ORDER,-1) FROM AC_APPLICATION WHERE IS_SYSTEM=0;").splitlines():
        if linha.strip():
            i, u, t, d, s, o = linha.split("\t")
            atual[u] = (int(i), t, d, s, int(o))

    mudancas, reverter = [], []
    for url, cfg in padrao["proprios"].items():
        if url not in atual:
            print(f"  nao existe neste servidor: {cfg['titulo']} ({url}) -- pulado")
            continue
        i, t0, d0, s0, o0 = atual[url]
        campos, volta = [], []
        for coluna, novo, velho in (("TITLE", cfg["titulo"], t0),
                                    ("DESCRIPTION", cfg["descricao"], d0),
                                    ("SHORTCUT", cfg["tecla"], s0)):
            if novo != velho:
                campos.append(f"{coluna}={escapa(novo)}")
                volta.append(f"{coluna}=" + ("NULL" if velho == NULO else escapa(velho)))
        if cfg["ordem"] != o0:
            campos.append(f"APPLICATION_ORDER={cfg['ordem']}")
            volta.append(f"APPLICATION_ORDER={o0 if o0 >= 0 else 'NULL'}")
        if campos:
            # o que MUDA, campo a campo -- so' o titulo enganaria quando o nome
            # ja' esta certo e o que falta e' a tecla
            detalhe = "; ".join(f"{c.split('=')[0].lower()}: {v!r} -> {n!r}"
                                for c, n, v in (("TITLE", cfg["titulo"], t0),
                                                ("DESCRIPTION", cfg["descricao"], d0),
                                                ("SHORTCUT", cfg["tecla"], "(nenhuma)" if s0 == NULO else s0),
                                                ("APPLICATION_ORDER", cfg["ordem"], o0))
                                if str(n) != str(v))
            mudancas.append((i, cfg["titulo"], detalhe,
                             f"UPDATE AC_APPLICATION SET {', '.join(campos)} WHERE ID={i};"))
            reverter.append(f"UPDATE AC_APPLICATION SET {', '.join(volta)} WHERE ID={i};")

    if not mudancas:
        print("Banco: os atalhos proprios ja estao no padrao.")
        return
    print(f"{'ID':>3}  {'ATALHO':<28}  O QUE MUDA")
    for i, titulo, detalhe, _ in mudancas:
        print(f"{i:>3}  {titulo:<28}  {detalhe}")
    if not aplicar:
        print(f"\n{len(mudancas)} alteracao(oes). Rode com --aplicar para gravar.")
        return
    print("\n-- COMO DESFAZER --")
    for r in reverter:
        print("   " + r)
    mysql("\n".join(s for _, _, _, s in mudancas))
    # O titulo mora em DOIS lugares: alem de AC_APPLICATION.TITLE, a eXo grava um
    # snapshot em SOC_METADATA_ITEMS_PROPERTIES(label) quando o atalho e' fixado, e
    # e' ESSE que a caixa desenha. Alinhado pelo proprio JOIN -- sem repetir nomes.
    mysql("""
      UPDATE SOC_METADATA_ITEMS_PROPERTIES p
        JOIN SOC_METADATA_ITEMS i ON i.METADATA_ITEM_ID = p.METADATA_ITEM_ID
        JOIN AC_APPLICATION a     ON a.ID = i.OBJECT_ID
         SET p.VALUE = a.TITLE
       WHERE p.NAME = 'label' AND i.OBJECT_TYPE = 'appCenter' AND p.VALUE <> a.TITLE;""")
    print(f"\n{len(mudancas)} atalho(s) proprio(s) padronizado(s). O App Center guarda a")
    print("lista em memoria: os nomes novos aparecem no proximo start do exo-app.")


def main():
    a = argparse.ArgumentParser(description="Padroniza nome, tecla, descricao e ordem dos atalhos")
    a.add_argument("--aplicar", action="store_true", help="grava no banco (sem isto, so' mostra)")
    a.add_argument("--seed", metavar="LIBDIR", help="corrige o applications.json dos jars (build)")
    a.add_argument("--padrao", help="caminho alternativo do padrao.json (build)")
    args = a.parse_args()
    padrao = carrega(args.padrao)
    if args.seed:
        seed(args.seed, padrao)
    else:
        banco(padrao, args.aplicar)


if __name__ == "__main__":
    main()
