#!/usr/bin/env python3
# ============================================================================
# verificar-estrutura.py -- confere a arvore no eXo contra o que foi pedido.
#
# Nao repete a mesma consulta que o provisionamento usou: cada item e' medido
# pelo caminho de dados que o USUARIO FINAL enxerga sempre que possivel
# (modelo Social, que e' o que a UI consome), e nao pelo modelo de organizacao
# que o script escreveu. Conferir escrita com a mesma chamada da escrita nao
# prova nada.
#
#   ./verificar-estrutura.py conf/estrutura/sitds.json
# ============================================================================
import json, os, sys, urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import exo_estrutura as E

OK, FALHA = "OK", "FALHA"
resultado = []


def checa(item, condicao, detalhe=""):
    resultado.append((OK if condicao else FALHA, item, detalhe))
    print(f"  [{OK if condicao else FALHA}] {item}" + (f"  -- {detalhe}" if detalhe else ""))
    return condicao


def cadeia_de(caminho):
    p = caminho.strip("/").split("/")
    return ["/" + "/".join(p[:i + 1]) for i in range(len(p))]


def main():
    arquivo = sys.argv[1] if len(sys.argv) > 1 else "conf/estrutura/sitds.json"
    payload = json.load(open(arquivo, encoding="utf-8"))
    exo = E.conectar(log=lambda m: None)
    mapa = E.espacos(exo)
    grupos = E.grupos_existentes(exo)

    # achata a arvore pedida
    niveis = []
    for sec in payload.get("secretarias", []):
        cs = "/" + E.slug_grupo(sec["nome"])
        niveis.append(("secretaria", cs, sec, None))
        for div in sec.get("divisoes", []):
            cd = f"{cs}/{E.slug_grupo(div['nome'])}"
            niveis.append(("divisao", cd, div, cs))
            for st in div.get("setores", []):
                niveis.append(("setor", f"{cd}/{E.slug_grupo(st['nome'])}", st, cd))

    print(f"\n=== GRUPOS ({len(niveis)} esperados) ===")
    for tipo, cam, no, _ in niveis:
        checa(f"grupo {cam} existe", cam in grupos)

    print("\n=== ESPACOS E PERFIL ===")
    ids = {}
    for tipo, cam, no, _ in niveis:
        esp = E.espaco_do_grupo(exo, cam, mapa)
        if not checa(f"espaco de {cam} localizado", bool(esp)):
            continue
        ids[cam] = esp["id"]
        st, d = exo.get(f"/portal/rest/v1/social/spaces/{esp['id']}")
        rot = no.get("rotulo") or no["nome"]
        checa(f"  nome exibido = {rot!r}", (d.get("displayName") or "") == rot,
              f"achado {d.get('displayName')!r}")
        desc = no.get("descricao")
        if desc:
            atual = d.get("description") or ""
            checa(f"  descricao preenchida ({len(desc)} car.)", atual == desc,
                  f"{len(atual)} car. no espaco")
            checa("  descricao sem lixo tecnico vazando",
                  "[grupo:" not in atual and "/SITDS" not in atual)
        checa("  privado e fechado",
              d.get("visibility") == "private" and d.get("subscription") == "closed",
              f"{d.get('visibility')}/{d.get('subscription')}")
        for campo in ("avatarUrl", "bannerUrl"):
            u = d.get(campo)
            stc = exo._raw("GET", u)[0] if u else 0
            checa(f"  {campo[:-3]} servido", stc == 200, f"status {stc}")

    print("\n=== HIERARQUIA (aninhamento visivel na navegacao) ===")
    for tipo, cam, no, pai in niveis:
        if cam not in ids:
            continue
        st, d = exo.get(f"/portal/rest/v1/social/spaces/{ids[cam]}")
        if pai:
            checa(f"{cam} aninhado sob {pai}",
                  str(d.get("parentSpaceId")) == str(ids.get(pai)),
                  f"pai={d.get('parentSpaceId')} esperado={ids.get(pai)}")
        else:
            checa(f"{cam} aninhado no espaco raiz", bool(d.get("parentSpaceId")),
                  f"pai={d.get('parentSpaceId')}")

    print("\n=== VISIBILIDADE DESCE, NAO SOBE ===")
    for tipo, cam, no, _ in niveis:
        if cam not in ids:
            continue
        b = sorted(E.bindings_do_espaco(exo, ids[cam]))
        checa(f"{cam} enxerga exatamente a cadeia de cima",
              b == sorted(cadeia_de(cam)), f"{b}")

    print("\n=== PESSOAS (quem esta DENTRO de cada espaco) ===")
    # esperado: cada nivel tem os seus + todos os de cima
    acumulado = {}
    for tipo, cam, no, pai in niveis:
        gente = set(E.le_lista(no.get("gestores"))) | set(E.le_lista(no.get("usuarios")))
        acumulado[cam] = gente | (acumulado.get(pai) or set())
    todos = set().union(*acumulado.values()) if acumulado else set()
    for tipo, cam, no, _ in niveis:
        if cam not in ids:
            continue
        st, d = exo.get(f"/portal/rest/v1/social/spaces/{ids[cam]}/users?limit=200")
        m = set()
        for e in (d.get("users") or d.get("entities") or []) if isinstance(d, dict) else []:
            n = e.get("username") or e.get("remoteId") or e.get("id")
            if n:
                m.add(str(n).split("/")[-1])
        nossos = m & todos
        checa(f"{cam} tem exatamente {sorted(acumulado[cam])}",
              nossos == acumulado[cam], f"achado {sorted(nossos)}")

    print("\n=== GESTORES (poder real de administrar o espaco) ===")
    for tipo, cam, no, _ in niveis:
        if cam not in ids:
            continue
        ges = E.le_lista(no.get("gestores"))
        if not ges:
            continue
        st, d = exo.get(f"/portal/rest/v1/social/spaces/{ids[cam]}?expand=managers")
        nomes = [(m.get("username") or m.get("remoteId") or "")
                 for m in (d.get("managers") or []) if isinstance(m, dict)]
        for u in ges:
            checa(f"{u} e gestor do espaco de {cam}", u in nomes, f"managers={nomes}")

    falhas = [r for r in resultado if r[0] == FALHA]
    print(f"\n{'='*62}\n{len(resultado)} verificacoes, {len(falhas)} falha(s)")
    if falhas:
        for _, item, det in falhas:
            print(f"   FALHA: {item}  {det}")
    print(">>> " + ("TUDO CONFORME O PEDIDO" if not falhas else "NAO CONFORME"))
    return 1 if falhas else 0


if __name__ == "__main__":
    sys.exit(main())
