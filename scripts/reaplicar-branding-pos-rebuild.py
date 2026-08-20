#!/usr/bin/env python3
"""
reaplicar-branding-pos-rebuild.py — reaplica ajustes de fundo/branding que
NAO sobrevivem a um `docker compose up -d --build exo`.

DESCOBERTO AO VIVO em 2026-08-20: BrandingService.pageBackground volta pro
default (fileId=0, pageBackgroundPosition="top left") toda vez que o
container exo-app e' recriado -- mesmo a imagem tendo sido enviada por API
e confirmada por screenshot antes do rebuild. PORTAL_NAVIGATION_NODES (banco
MySQL "exo") NAO tem esse problema -- sobrevive normalmente, porque vive no
volume `data/mysql`, nao em algo que a app reimporta no boot.

Idempotente: cada ajuste so' e' reaplicado se detectado fora do estado
esperado. Seguro rodar repetidas vezes, inclusive com a stack ja' correta.

Uso:  tests/.venv/bin/python scripts/reaplicar-branding-pos-rebuild.py
   ou python3 scripts/reaplicar-branding-pos-rebuild.py   (usa requests do sistema)
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "tests"))
from exolib import ExoClient  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
IMAGEM_FUNDO = RAIZ / "conf" / "images" / "myworkspace-background.webp"
PAGE_REF = "portal::myworkspace::dashboard"


def log(msg: str) -> None:
    print(f"[reaplicar-branding] {msg}", flush=True)


def corrigir_branding(c: ExoClient) -> bool:
    """Garante pageBackground com imagem valida, size=cover, position=center center."""
    status, branding = c.json_get("/portal/rest/v1/platform/branding")
    if status != 200:
        log(f"ERRO: GET branding -> {status}")
        return False

    bg = branding.get("pageBackground") or {}
    precisa_imagem = not bg.get("fileId")
    precisa_size = branding.get("pageBackgroundSize") != "cover"
    precisa_pos = branding.get("pageBackgroundPosition") != "center center"

    if not (precisa_imagem or precisa_size or precisa_pos):
        log("branding OK (fileId, size=cover, position=center center) — nada a fazer")
        return True

    if precisa_imagem:
        if not IMAGEM_FUNDO.exists():
            log(f"ERRO: imagem de fundo nao encontrada em {IMAGEM_FUNDO}")
            return False
        upload_id = f"{int(time.time() * 1000) % 100000}-{int(time.time() * 1000)}"
        with open(IMAGEM_FUNDO, "rb") as f:
            r = c.post(f"/portal/upload?uploadId={upload_id}&action=upload",
                       files={"file": f})
        if not r.ok:
            log(f"ERRO: upload da imagem -> {r.status_code}")
            return False
        branding["pageBackground"] = {"uploadId": upload_id}
        log(f"imagem de fundo reenviada (uploadId={upload_id})")
    else:
        # mantem a imagem atual, so' ajusta os campos de exibicao
        branding["pageBackground"] = {"uploadId": bg.get("uploadId")}

    branding["pageBackgroundSize"] = "cover"
    branding["pageBackgroundPosition"] = "center center"

    r = c.put("/portal/rest/v1/platform/branding", json=branding,
               headers={"Content-Type": "application/json"})
    if r.status_code not in (200, 204):
        log(f"ERRO: PUT branding -> {r.status_code} {r.text[:200]}")
        return False

    # confere de verdade, nao so' o status HTTP
    status, branding2 = c.json_get("/portal/rest/v1/platform/branding")
    ok = (status == 200
          and (branding2.get("pageBackground") or {}).get("fileId")
          and branding2.get("pageBackgroundSize") == "cover"
          and branding2.get("pageBackgroundPosition") == "center center")
    log("branding corrigido e confirmado" if ok else f"branding NAO confirmado: {branding2}")
    return bool(ok)


def corrigir_container_dashboard(c: ExoClient) -> bool:
    """Garante que o container raiz do dashboard NAO tem fundo hardcoded
    (senao ele nunca herda o branding acima, voltando a ficar dessincronizado
    do resto do site -- era exatamente o defeito original do F-04/F-01)."""
    status, layout = c.json_get(f"/layout/rest/pages/layout?pageRef={PAGE_REF}")
    if status != 200:
        log(f"ERRO: GET layout do dashboard -> {status}")
        return False

    children = layout.get("children") or []
    if not children:
        log("ERRO: layout do dashboard sem container raiz")
        return False

    raiz = children[0]
    campos = ["backgroundImage", "backgroundColor", "backgroundSize", "backgroundPosition"]
    if not any(raiz.get(k) for k in campos):
        log("container do dashboard OK (sem fundo proprio, herda o branding) — nada a fazer")
        return True

    for k in campos:
        raiz[k] = None

    r = c.put(f"/layout/rest/pages/layout?pageRef={PAGE_REF}&expand=all",
               json=layout, headers={"Content-Type": "application/json"})
    if r.status_code != 200:
        log(f"ERRO: PUT layout do dashboard -> {r.status_code} {r.text[:200]}")
        return False
    log("container do dashboard corrigido (fundo proprio removido)")
    return True


def main() -> int:
    c = ExoClient()
    if not c.login():
        log("ERRO: falha ao autenticar como admin")
        return 1

    ok1 = corrigir_branding(c)
    ok2 = corrigir_container_dashboard(c)
    return 0 if (ok1 and ok2) else 1


if __name__ == "__main__":
    raise SystemExit(main())
