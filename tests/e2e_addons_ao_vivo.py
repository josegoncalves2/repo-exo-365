#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Teste end-to-end AO VIVO, em navegador de verdade, visivel.

  DISPLAY=:98 tests/.venv/bin/python tests/e2e_addons_ao_vivo.py

Nao usa curl e nao bate em API. Abre o Chromium num display X real (:98,
exportado por x11vnc -> noVNC em http://192.168.1.59:6082/vnc.html), faz login
como um usuario faria, e percorre tela por tela o que o operador cobrou:
chat, videoconferencia, edicao de documentos, DLP, 2FA, gerenciadores e IA.

Cada passo:
  - navega e ESPERA a tela desenhar (nao mede HTTP, mede pixel/DOM);
  - grava screenshot em evidence/ao-vivo-<pid>/ (uma pasta por sessao);
  - registra PASSOU/FALHOU com o motivo, sem arredondar.

slow_mo deixa a acao acompanhavel a olho nu -- o objetivo aqui e' ser assistido,
nao ser rapido.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

RAIZ = Path(__file__).resolve().parent.parent
# Pasta por SESSAO, nao compartilhada. Em 2026-08-26 havia SEIS sessoes Claude
# vivas nesta maquina e tres escrevendo teste ao vivo ao mesmo tempo: quem
# gravasse em evidence/ao-vivo/ sobrescrevia a prova das outras (os arquivos sao
# numerados NN-nome.png e as numeracoes colidem). O PID do processo e' o que
# separa, porque e' o unico identificador que a sessao conhece de si mesma.
SAIDA = RAIZ / "evidence" / f"ao-vivo-{os.getppid()}"
SAIDA.mkdir(parents=True, exist_ok=True)

BASE = os.environ.get("EXO_BASE", "http://192.168.1.59")
USUARIO = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "pmotiadm")

RESULTADOS: list[dict] = []
_n = [0]


def passo(nome: str, ok: bool, detalhe: str, prova: str = "") -> None:
    RESULTADOS.append({"passo": nome, "ok": ok, "detalhe": detalhe, "prova": prova})
    print(f"[{'PASSOU' if ok else 'FALHOU'}] {nome} — {detalhe}", flush=True)


def foto(page, rotulo: str) -> str:
    _n[0] += 1
    p = SAIDA / f"{_n[0]:02d}-{re.sub(r'[^a-z0-9]+', '-', rotulo.lower()).strip('-')}.png"
    try:
        page.screenshot(path=str(p), full_page=False)
    except Exception:                                            # noqa: BLE001
        return ""
    return str(p.relative_to(RAIZ))


def banner(page, texto: str, segundos: float = 2.0) -> None:
    """Escreve na propria pagina o que esta sendo testado.

    Existe para quem esta ASSISTINDO pelo noVNC: sem isso o video e' uma
    sequencia de telas sem narracao, e nao da' para saber qual item do briefing
    cada clique esta comprovando."""
    try:
        page.evaluate(
            """(t) => {
              let d = document.getElementById('__narracao__');
              if (!d) {
                d = document.createElement('div');
                d.id = '__narracao__';
                d.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;'
                  + 'background:#0b1020;color:#7ee787;font:600 18px/1.5 ui-monospace,monospace;'
                  + 'padding:12px 18px;border-top:3px solid #7ee787;white-space:pre-wrap';
                document.body.appendChild(d);
              }
              d.textContent = t;
            }""", texto)
    except Exception:                                            # noqa: BLE001
        pass
    time.sleep(segundos)


def texto_da_pagina(page) -> str:
    try:
        return page.inner_text("body", timeout=15000)
    except Exception:                                            # noqa: BLE001
        return ""


# ---------------------------------------------------------------------------

def main() -> int:
    with sync_playwright() as pw:
        navegador = pw.chromium.launch(
            headless=False,                 # VISIVEL -- e' o ponto deste arquivo
            slow_mo=450,                    # acompanhavel a olho nu
            args=["--start-maximized", "--window-size=1600,900",
                  "--window-position=0,0", "--no-sandbox",
                  "--disable-dev-shm-usage", "--ignore-certificate-errors"],
        )
        ctx = navegador.new_context(
            viewport={"width": 1600, "height": 860},
            ignore_https_errors=True,
            locale="pt-BR",
            record_video_dir=str(SAIDA / "video"),
            record_video_size={"width": 1600, "height": 860},
        )
        page = ctx.new_page()
        page.set_default_timeout(45000)

        try:
            executar(page)
        except Exception as e:                                   # noqa: BLE001
            passo("execucao", False, f"interrompido: {type(e).__name__}: {e}")
        finally:
            resumo = {
                "base": BASE,
                "quando": time.strftime("%Y-%m-%d %H:%M:%S"),
                "total": len(RESULTADOS),
                "passaram": sum(1 for r in RESULTADOS if r["ok"]),
                "falharam": sum(1 for r in RESULTADOS if not r["ok"]),
                "passos": RESULTADOS,
            }
            (SAIDA / "resultado.json").write_text(
                json.dumps(resumo, indent=2, ensure_ascii=False), encoding="utf-8")
            print("\n" + "=" * 70)
            print(f"RESULTADO: {resumo['passaram']} passaram, {resumo['falharam']} falharam")
            for r in RESULTADOS:
                if not r["ok"]:
                    print(f"   FALHOU: {r['passo']} — {r['detalhe']}")
            print("=" * 70)
            try:
                ctx.close()
                navegador.close()
            except Exception:                                    # noqa: BLE001
                pass
    return 0 if all(r["ok"] for r in RESULTADOS) else 1


def abrir(page, caminho: str, espera: float = 2.5) -> str:
    """Navega e devolve o texto visivel. NUNCA lanca -- um passo que falha nao
    pode derrubar os seguintes, senao um erro no meio esconde o resto da prova."""
    try:
        page.goto(f"{BASE}{caminho}", wait_until="domcontentloaded", timeout=60000)
        try:
            page.wait_for_load_state("networkidle", timeout=25000)
        except Exception:                                        # noqa: BLE001
            pass
        time.sleep(espera)
        return texto_da_pagina(page)
    except Exception as e:                                       # noqa: BLE001
        return f"__ERRO__ {type(e).__name__}: {e}"


def executar(page) -> None:
    total = 18

    # -- 1. LOGIN -----------------------------------------------------------
    page.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=90000)
    try:
        page.wait_for_load_state("networkidle", timeout=30000)
    except Exception:                                            # noqa: BLE001
        pass
    banner(page, f"1/{total}  LOGIN — entrando no portal como um usuario real", 2)
    foto(page, "tela de login")

    page.fill("input[name='username']", USUARIO)
    page.fill("input[name='password']", SENHA)
    page.click("button[type='submit'], input[type='submit']")
    # O eXo encadeia varios redirecionamentos apos o POST. Esperar UMA navegacao
    # (expect_navigation) expira no meio da cadeia -- foi o que derrubou a
    # primeira execucao. Esperar a URL SAIR de /portal/login e' o sinal correto.
    try:
        page.wait_for_url(lambda u: "/portal/login" not in u, timeout=90000)
        page.wait_for_load_state("networkidle", timeout=60000)
    except Exception:                                            # noqa: BLE001
        pass
    time.sleep(3)
    dentro = "/portal/login" not in page.url
    passo("login no portal", dentro,
          f"url apos login: {page.url}", foto(page, "apos login"))
    if not dentro:
        raise RuntimeError("login falhou — sem sessao o resto nao prova nada")

    # -- 2. PAINEL / ATALHOS ------------------------------------------------
    t = abrir(page, "/portal/myworkspace")
    banner(page, f"2/{total}  PAINEL — atalhos e o widget que dizia 'Ver mais comentarios'")
    ingles = [p for p in ("Add a task", "Contributions Review", "List favorites",
                          "Add a post", "List Spaces", "Give a Kudos") if p in t]
    ruim = "Ver mais comentários" in t or "Ver mais comentarios" in t
    passo("painel sem nome em ingles e sem 'Ver mais comentarios'",
          not ingles and not ruim,
          ("nenhum nome em ingles, nenhum 'Ver mais comentarios'" if not ingles and not ruim
           else f"ingles={ingles} ver_mais_comentarios={ruim}"),
          foto(page, "painel"))

    # -- 3. CHAT (item 1.1) -------------------------------------------------
    t = abrir(page, "/portal/administration/home/applications/chat")
    banner(page, f"3/{total}  ITEM 1.1 — Chat / modulo de conversacao integrado")
    ok = "__ERRO__" not in t and "page-not-found" not in page.url and len(t) > 80
    passo("1.1 chat integrado abre", ok, f"{len(t)} chars na tela; url={page.url}",
          foto(page, "1.1 chat"))

    # -- 4. VIDEOCONFERENCIA (item 1.2) -------------------------------------
    t = abrir(page, "/portal/myworkspace/dashboard/agenda")
    banner(page, f"4/{total}  ITEM 1.2 — Videoconferencia integrada (Jitsi), na Agenda")
    ok = "__ERRO__" not in t and "page-not-found" not in page.url and len(t) > 80
    passo("1.2 agenda (onde vive o botao de conferencia) abre", ok,
          f"{len(t)} chars; url={page.url}", foto(page, "1.2 agenda-jitsi"))

    # -- 5. DOCUMENTOS / ONLYOFFICE (item 1.3) ------------------------------
    t = abrir(page, "/portal/myworkspace/drives")
    banner(page, f"5/{total}  ITEM 1.3 — Edicao de documentos online (OnlyOffice)")
    ok = "__ERRO__" not in t and "page-not-found" not in page.url and len(t) > 80
    passo("1.3 documentos abre", ok, f"{len(t)} chars; url={page.url}",
          foto(page, "1.3 documentos"))

    # -- 6. MENU DE ADMINISTRACAO -------------------------------------------
    t = abrir(page, "/portal/administration")
    banner(page, f"6/{total}  ADMINISTRACAO — o que os add-ons publicaram na tela")
    ok = "__ERRO__" not in t and len(t) > 80
    passo("administracao abre", ok, f"{len(t)} chars; url={page.url}",
          foto(page, "administracao"))

    # -- 7..10. AS TELAS DOS ADD-ONS NOVOS ----------------------------------
    # Cada add-on publica (ou nao) um no de navegacao. O log do boot acusa
    # "Node with uri ... wasn't found" para varios deles -- entao aqui se separa
    # "add-on ausente" de "no de navegacao ausente", que sao coisas diferentes e
    # nao podem ser reportadas como a mesma falha.
    # CAMINHOS REAIS, descobertos navegando -- nao chutados.
    # A primeira versao deste teste usava /portal/administration/home/security/dlp,
    # /ai/settings e /automatic-translation, que NAO EXISTEM, e reportou quatro
    # add-ons como "sem tela". Errado: os nos existem com outros nomes. Chutar URL
    # e depois culpar o produto e' o mesmo defeito que o operador vem cobrando.
    alvos = [
        (7,  "2.1 DLP (quarentena)", "/portal/administration/home/security/quarantine",
         ["quarentena", "quarantine", "dlp"]),
        (8,  "2.2 2FA (MFA)", "/portal/administration/home/security/multifactor-authentication",
         ["factor", "fator", "otp"]),
        (9,  "2.3 Restricao de download", "/portal/administration/home/security/transfert-rules",
         ["download", "transfer", "rule", "regra"]),
        (10, "IA — modelos e provedor", "/portal/administration/home/ai/models",
         ["model", "provider", "provedor"]),
        (11, "IA — agentes", "/portal/administration/home/ai/agents",
         ["agent", "agente"]),
        (12, "Traducao automatica", "/portal/administration/home/applications/translation",
         ["tradu", "translation"]),
        (13, "Conector de agenda (Exchange)", "/portal/administration/home/applications/agendaAdminSettings",
         ["agenda", "connector", "conector"]),
        (14, "Chat", "/portal/administration/home/applications/chat",
         ["chat", "matrix"]),
        (15, "Videoconferencia (Jitsi)", "/portal/administration/home/applications/visio",
         ["visio", "jitsi", "video"]),
    ]
    for i, rotulo, caminho, palavras in alvos:
        t = abrir(page, caminho)
        banner(page, f"{i}/{total}  {rotulo} — tela de administracao do add-on")
        erro = "__ERRO__" in t or "page-not-found" in page.url
        achou = any(p in t.lower() for p in palavras)
        ok = achou and not erro
        det = ("tela respondeu com conteudo do add-on" if ok else
               ("erro de navegacao: " + t[:120]) if erro else
               "pagina abriu mas SEM conteudo do add-on -- provavel no de navegacao "
               "ausente (o boot registra \"Node with uri ... wasn't found\"), "
               "e NAO add-on ausente")
        passo(rotulo + " tem tela propria", ok, det, foto(page, rotulo))

    # -- 11. GLPI EM PORTUGUES (cobranca direta do operador) ----------------
    t = abrir(page, "/portal/administration/home/applications/applicationsCenter")
    banner(page, f"11/{total}  GLPI — a tela de chamados tem de estar em portugues")
    ingles_glpi = [p for p in ("Open settings", "Create a GLPI ticket", "My GLPI requests",
                               "See more", "Server address", "App token") if p in t]
    pt_glpi = [p for p in ("hamado", "Configura", "Ver mais", "Token do aplicativo") if p in t]
    passo("GLPI sem texto em ingles na tela", not ingles_glpi,
          (f"nenhum termo em ingles; em pt encontrei {pt_glpi}" if not ingles_glpi
           else f"AINDA EM INGLES: {ingles_glpi}"),
          foto(page, "glpi"))

    # -- 12. O ATALHO NOVO DA IA --------------------------------------------
    t = abrir(page, "/portal/myworkspace")
    banner(page, f"16/{total}  ATALHOS — o add-on de IA registrou 'Your Assistant', em ingles")
    tem_ingles = "Your Assistant" in t
    passo("atalho da IA em portugues", not tem_ingles,
          ("nenhum atalho em ingles no painel" if not tem_ingles else
           "'Your Assistant' aparece em ingles -- o meeds-ai grava esse titulo literal "
           "no seed; precisa entrar em conf/atalhos/padrao.json como os 6 de sistema"),
          foto(page, "atalho-ia"))

    banner(page, "FIM — resultado em evidence/ao-vivo-<pid>/resultado.json", 4)


if __name__ == "__main__":
    sys.exit(main())
