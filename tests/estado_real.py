#!/usr/bin/env python3
# ============================================================================
# estado_real.py -- FOTO INDEPENDENTE do que existe no eXo agora.
#
# Nao usa a logica do motor para decidir o que "deveria" existir: le grupos,
# espacos, aninhamento, bindings, membros e perfil direto da API e imprime.
# E' o arbitro do ciclo apagar/recriar -- se o motor mentir, isto denuncia.
# ============================================================================
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts"))
from exo_estrutura import (conectar, paginar, grupos_existentes, espacos,
                           bindings_detalhados, membros_do_grupo, membros_do_espaco)

ALVOS = ["/SITDS", "/SITDS/DIT", "/SITDS/DIT/ST"]
CONTAS = ["wilson.franca", "isabela.feitosa", "anderson.polizel", "kaua.ferri"]


def foto(exo):
    grupos = set(grupos_existentes(exo))
    esp = {s.get("prettyName"): s for s in espacos(exo).values()}
    porid = {str(s.get("id")): s for s in esp.values()}
    users = {}
    for c in CONTAS:
        st, t = exo._raw("GET", f"/portal/rest/v1/users/{c}")
        users[c] = json.loads(t) if st == 200 and t.strip() else None
    d = {"grupos": {}, "espacos": {}, "usuarios": {}}
    for c in CONTAS:
        u = users[c]
        d["usuarios"][c] = None if not u else {
            "nome": u.get("fullName") or f"{u.get('firstName','')} {u.get('lastName','')}".strip(),
            "email": u.get("email"), "habilitado": u.get("enabled", True)}
    for g in ALVOS:
        d["grupos"][g] = {"existe": g in grupos,
                          "membros": sorted(membros_do_grupo(exo, g)) if g in grupos else []}
    for pn, s in esp.items():
        sid = str(s.get("id"))
        pai = str(s.get("parentSpaceId") or "")
        d["espacos"][pn] = {
            "id": sid, "nome": s.get("displayName"),
            "pai": porid.get(pai, {}).get("prettyName") if pai else None,
            "descricao": (s.get("description") or "").strip(),
            "avatar": bool(s.get("avatarUrl")), "banner": bool(s.get("bannerUrl")),
            "visibilidade": s.get("visibility"), "inscricao": s.get("subscription"),
            "bindings": sorted(g for _, g in bindings_detalhados(exo, sid)),
            "membros": sorted(membros_do_espaco(exo, sid)),
        }
    return d


if __name__ == "__main__":
    exo = conectar(os.environ.get("EXO_URL", "http://127.0.0.1"),
                   os.environ.get("EXO_ADMIN_USER", "root"),
                   os.environ.get("EXO_ADMIN_PASS", ""), False, lambda *a: None)
    print(json.dumps(foto(exo), ensure_ascii=False, indent=2, sort_keys=True))
