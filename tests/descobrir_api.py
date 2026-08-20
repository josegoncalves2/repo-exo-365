#!/usr/bin/env python3
"""
descobrir_api.py — sondagem da superfície REST real desta instalação.

Motivo: escrever testes contra endpoints PRESUMIDOS produz falsos negativos.
Este script autentica de verdade e descobre quais rotas existem, para que a
suíte seja construída sobre o que a instância realmente expõe.

Saída: evidence/descoberta-api.json + relatório legível no stdout.
"""
import json
import sys

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
from exolib import ExoClient, EVIDENCE, BASE, ADMIN_USER, ADMIN_PASS  # noqa: E402

CANDIDATES = [
    # plataforma
    "/rest/v1/platform/info",
    # social
    "/rest/v1/social/users",
    "/rest/v1/social/users/me",
    "/rest/v1/social/spaces",
    "/rest/v1/social/activities",
    "/rest/v1/social/spaceMemberships",
    "/rest/v1/social/identities",
    # notas / wiki
    "/rest/v1/notes",
    "/rest/notes/note",
    # tarefas
    "/rest/v1/tasks/projects",
    "/rest/tasks/projects",
    # agenda
    "/rest/v1/agenda/events",
    "/rest/v1/agenda/calendars",
    # documentos
    "/rest/v1/documents",
    "/rest/private/jcr",
    "/rest/jcr",
    # chat
    "/rest/v1/chat/whoami",
    "/chat/api/1.0/user/onlineUsers",
    # gamificação
    "/rest/v1/gamification/leaderboard",
    "/rest/gamification/api/badges",
    # onlyoffice
    "/rest/v1/onlyoffice/editor/status",
    # administração
    "/rest/v1/platform/branding",
    "/rest/private/v1/settings",
]


def main() -> int:
    c = ExoClient(ADMIN_USER, ADMIN_PASS)
    print(f"Base: {BASE}   usuario: {ADMIN_USER}")

    ok = c.login()
    me = c.whoami() if ok else None
    print(f"Login: {'SUCESSO' if ok else 'FALHA'}   metodo={c.auth_method}")
    if me:
        print(f"Identidade confirmada: username={me.get('username')} "
              f"id={me.get('id')} fullname={me.get('fullname')}")
    else:
        print("AVISO: nao foi possivel confirmar a identidade autenticada.")

    findings = {}
    for path in CANDIDATES:
        try:
            r = c.get(path, headers={"Accept": "application/json"},
                      allow_redirects=False, timeout=30)
            ctype = r.headers.get("Content-Type", "").split(";")[0]
            body = r.text[:200].replace("\n", " ")
            findings[path] = {"status": r.status_code, "ctype": ctype,
                              "sample": body}
            flag = "OK " if (r.status_code == 200 and "json" in ctype) else "   "
            print(f"{flag}{r.status_code:>3} {ctype:<28} {path}")
        except Exception as e:  # noqa: BLE001
            findings[path] = {"error": str(e)[:150]}
            print(f"ERR     {'':<28} {path}  {e}")

    out = EVIDENCE / "descoberta-api.json"
    out.write_text(json.dumps(
        {"base": BASE, "login_ok": ok, "auth_method": c.auth_method,
         "me": me, "endpoints": findings}, indent=2, ensure_ascii=False))
    print(f"\nGravado: {out}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
