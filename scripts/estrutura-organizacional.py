#!/usr/bin/env python3
# ============================================================================
# estrutura-organizacional.py -- CLI da hierarquia organizacional do eXo.
#
# O trabalho pesado esta em exo_estrutura.py, compartilhado com a interface
# web (estrutura-web.py). Aqui e' so' a linha de comando.
#
# UM NIVEL POR VEZ
#   ./estrutura-organizacional.py --tipo secretaria --nome SITDS \
#       --rotulo "Secretaria de Inovacao" --gestores wilson.franca
#   ./estrutura-organizacional.py --tipo divisao --nome DIT --pai /SITDS \
#       --rotulo "Divisao de Inovacao Tecnologica" --gestores isabela.feitosa
#   ./estrutura-organizacional.py --tipo setor --nome ST --pai /SITDS/DIT \
#       --usuarios equipe.csv --descricao "Suporte e infraestrutura"
#
# ARVORE INTEIRA, de um JSON (mesmo formato que a interface web envia)
#   ./estrutura-organizacional.py --arquivo estrutura.json
#   ./estrutura-organizacional.py --arquivo estrutura.json --remover --sim
#
# REMOVER um nivel
#   ./estrutura-organizacional.py --tipo setor --nome ST --pai /SITDS/DIT \
#       --remover --sim
#
# Credenciais: EXO_URL, EXO_ADMIN_USER, EXO_ADMIN_PASS (ou --url/--user/--senha)
# Rollback: qualquer erro no meio desfaz o que ESTE run criou.
# ============================================================================
import argparse, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from exo_estrutura import (TIPOS, Cancelado, FalhaEtapa, Provisionador, conectar,
                           contar_niveis, provisionar_arvore, remover_arvore,
                           remover_nivel, slug_grupo)


def payload_de_um_nivel(a):
    """Monta a arvore de 1 nivel a partir dos parametros soltos da CLI."""
    no = {"nome": a.nome, "rotulo": a.rotulo, "descricao": a.descricao,
          "gestores": a.gestores, "usuarios": a.usuarios,
          "avatar": _le_img(a.avatar), "banner": _le_img(a.banner)}
    partes = [p for p in (a.pai or "").strip("/").split("/") if p]
    if a.tipo == "secretaria":
        return {"secretarias": [no]}
    if a.tipo == "divisao":
        if len(partes) != 1:
            raise FalhaEtapa("--tipo divisao exige --pai /SECRETARIA")
        return {"secretarias": [{"nome": partes[0], "_existente": True, "divisoes": [no]}]}
    if len(partes) != 2:
        raise FalhaEtapa("--tipo setor exige --pai /SECRETARIA/DIVISAO")
    return {"secretarias": [{"nome": partes[0], "_existente": True,
                             "divisoes": [{"nome": partes[1], "_existente": True,
                                           "setores": [no]}]}]}


def _le_img(caminho):
    if not caminho:
        return None
    if not os.path.isfile(caminho):
        raise FalhaEtapa(f"imagem nao encontrada: {caminho}")
    with open(caminho, "rb") as fh:
        return fh.read()


def main():
    p = argparse.ArgumentParser(
        description="Cria/remove Secretaria, Divisao e Setor com grupo, espaco, "
                    "aninhamento, cadeia de visibilidade, perfil e pessoas.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--tipo", choices=TIPOS)
    p.add_argument("--nome", help="Nome curto do nivel. Ex: SITDS, DIT, ST")
    p.add_argument("--pai", default="", help="Caminho do grupo pai. Ex: /SITDS")
    p.add_argument("--rotulo", default="", help="Nome de exibicao do espaco")
    p.add_argument("--descricao", default=None, help="Descricao do espaco (perfil)")
    p.add_argument("--avatar", default="", help="Arquivo de imagem do avatar")
    p.add_argument("--banner", default="", help="Arquivo de imagem do banner")
    p.add_argument("--gestores", default="",
                   help="CSV ou lista: entram como manager do nivel E do espaco")
    p.add_argument("--usuarios", default="", help="CSV ou lista: membros comuns")
    p.add_argument("--arquivo", default="", help="JSON com a arvore inteira")
    p.add_argument("--lobby", default="", help="id do espaco raiz (padrao: o sem pai)")
    p.add_argument("--url", default=os.environ.get("EXO_URL", "https://192.168.1.59"))
    p.add_argument("--user", default=os.environ.get("EXO_ADMIN_USER", "root"))
    p.add_argument("--senha", default=os.environ.get("EXO_ADMIN_PASS", ""))
    p.add_argument("--remover", action="store_true", help="DESFAZ em vez de criar")
    p.add_argument("--sim", action="store_true", help="nao pergunta na remocao")
    p.add_argument("--dry-run", action="store_true", help="mostra sem gravar")
    a = p.parse_args()

    if not a.arquivo and (not a.tipo or not a.nome):
        p.error("informe --arquivo, ou --tipo e --nome")

    try:
        if a.arquivo:
            with open(a.arquivo, encoding="utf-8") as fh:
                payload = json.load(fh)
        else:
            payload = payload_de_um_nivel(a)
        if a.lobby:
            payload["lobby"] = a.lobby

        exo = conectar(a.url, a.user, a.senha, a.dry_run, print)
        prov = Provisionador(exo, log=print, dry=a.dry_run)

        if a.remover:
            if a.arquivo:
                alvos = None
            else:
                grupo = slug_grupo(a.nome)
                alvos = [f"/{a.pai.strip('/')}/{grupo}" if a.pai else f"/{grupo}"]
            quantos = len(alvos) if alvos else contar_niveis(payload)
            if not a.sim and not a.dry_run:
                print(f"Isto remove {quantos} nivel(is) e TIRA AS PESSOAS DOS ESPACOS.")
                if input("Confirma? digite SIM: ").strip() != "SIM":
                    sys.exit("cancelado.")
            r = remover_arvore(prov, payload) if alvos is None else (
                [remover_nivel(prov, c) for c in alvos] and {"ok": True})
        else:
            print(f"Provisionando {contar_niveis(payload)} nivel(is)"
                  + ("  (simulacao, nada sera gravado)" if a.dry_run else ""))
            r = provisionar_arvore(prov, payload)

        sys.exit(0 if r.get("ok") else 1)

    except (FalhaEtapa, Cancelado) as e:
        sys.exit(f"ERRO: {e}")
    except KeyboardInterrupt:
        sys.exit("\ninterrompido.")


if __name__ == "__main__":
    main()
