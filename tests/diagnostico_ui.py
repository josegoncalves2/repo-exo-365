#!/usr/bin/env python3
"""
diagnostico_ui.py — descobre O QUE realmente quebra na interface.

Não presume nada. Abre a página num Chromium real e registra:
  * toda requisição que falhou (status >= 400) e para onde ela foi roteada;
  * todo erro de console (JS);
  * recursos CSS/JS que não carregaram;
  * captura de tela.

É o instrumento para diagnosticar "layout/CSS quebrado" com fatos,
em vez de adivinhar no arquivo de configuração do proxy.
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import BASE, EVIDENCE, RUN_ID  # noqa: E402

SHOTS = EVIDENCE / "capturas"
SHOTS.mkdir(parents=True, exist_ok=True)

PAGINAS = [
    ("inicial", "/"),
    ("login", "/portal/login"),
    ("portal", "/portal/"),
]


def main() -> int:
    from playwright.sync_api import sync_playwright

    relatorio = {}
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        for nome, caminho in PAGINAS:
            ctx = b.new_context(viewport={"width": 1440, "height": 900},
                                locale="pt-BR")
            pg = ctx.new_page()
            falhas, consoles, requisicoes = [], [], []

            def on_response(resp):
                requisicoes.append((resp.status, resp.url))
                if resp.status >= 400:
                    falhas.append({"status": resp.status, "url": resp.url,
                                   "tipo": resp.request.resource_type})

            def on_failed(req):
                falhas.append({"status": "REDE", "url": req.url,
                               "tipo": req.resource_type,
                               "erro": str(req.failure)})

            pg.on("response", on_response)
            pg.on("requestfailed", on_failed)
            pg.on("console", lambda m: consoles.append(
                {"tipo": m.type, "texto": m.text[:300]})
                if m.type in ("error", "warning") else None)

            print(f"\n{'='*68}\n{nome.upper()}  {BASE}{caminho}\n{'='*68}")
            try:
                r = pg.goto(f"{BASE}{caminho}", wait_until="domcontentloaded",
                            timeout=120_000)
                status = r.status if r else None
                try:
                    pg.wait_for_load_state("networkidle", timeout=60_000)
                except Exception:  # noqa: BLE001
                    pass
                pg.wait_for_timeout(3000)

                titulo = pg.title()
                texto = pg.inner_text("body") if pg.locator("body").count() else ""
                # quantos CSS o navegador de fato aplicou
                folhas = pg.evaluate("""() => {
                    const out = [];
                    for (const s of document.styleSheets) {
                      let regras = -1;
                      try { regras = s.cssRules ? s.cssRules.length : -1; } catch(e) {}
                      out.push({href: s.href, regras: regras});
                    }
                    return out;
                }""")
                aplicadas = sum(1 for f in folhas if (f.get("regras") or 0) > 0)
                arq = SHOTS / f"diag-{nome}-{RUN_ID}.png"
                pg.screenshot(path=str(arq), full_page=True)

                print(f"HTTP {status} | titulo={titulo!r} | texto={len(texto.strip())} chars")
                print(f"requisicoes={len(requisicoes)}  falhas={len(falhas)}  "
                      f"folhas de estilo={len(folhas)} (com regras aplicadas: {aplicadas})")
                if falhas:
                    print("\n-- REQUISICOES QUE FALHARAM --")
                    for f in falhas[:25]:
                        print(f"  {str(f['status']):>5}  {f['tipo']:<12} {f['url'][:110]}")
                    print("\n  resumo por status:",
                          dict(Counter(str(f["status"]) for f in falhas)))
                if consoles:
                    print("\n-- CONSOLE --")
                    for cmsg in consoles[:12]:
                        print(f"  [{cmsg['tipo']}] {cmsg['texto'][:140]}")
                print(f"\ncaptura: {arq.name}")

                relatorio[nome] = {
                    "url": f"{BASE}{caminho}", "http": status, "titulo": titulo,
                    "chars_texto": len(texto.strip()),
                    "total_requisicoes": len(requisicoes),
                    "falhas": falhas, "console": consoles,
                    "folhas_estilo": folhas, "css_aplicado": aplicadas,
                    "captura": arq.name,
                }
            except Exception as e:  # noqa: BLE001
                print(f"ERRO: {type(e).__name__}: {e}")
                relatorio[nome] = {"erro": f"{type(e).__name__}: {e}",
                                   "falhas": falhas}
            ctx.close()
        b.close()

    out = EVIDENCE / f"diagnostico-ui-{RUN_ID}.json"
    out.write_text(json.dumps(relatorio, indent=2, ensure_ascii=False))
    print(f"\nRelatorio completo: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
