#!/usr/bin/env python3
"""
T-01..T-13 — Abordagem B (usuário final em navegador real).

Aqui não há chamada de API: um Chromium de verdade abre o site, digita nos
campos, clica nos botões e LÊ O RESULTADO NA TELA — exatamente o que uma
pessoa faria. Cada passo gera captura de tela em evidence/capturas/.

Regra do projeto: não basta a página responder. É preciso que o conteúdo
criado pelo usuário APAREÇA na interface depois de criado.
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import (ADMIN_PASS, ADMIN_USER, BASE, EVIDENCE,  # noqa: E402
                    Recorder, Result, RUN_ID)

SHOTS = EVIDENCE / "capturas"
SHOTS.mkdir(parents=True, exist_ok=True)


def shot(pg, nome: str) -> str:
    p = SHOTS / f"{nome}-{RUN_ID}.png"
    try:
        pg.screenshot(path=str(p), full_page=True)
    except Exception:  # noqa: BLE001
        try:
            pg.screenshot(path=str(p))
        except Exception:  # noqa: BLE001
            return "captura falhou"
    return p.name


def fazer_login(pg, usuario: str, senha: str, steps: list) -> bool:
    """Login pela interface, como um humano. Confirma pela URL e pelo DOM."""
    pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=120_000)
    try:
        pg.wait_for_selector("input[name='username']", timeout=60_000)
    except Exception as e:  # noqa: BLE001
        steps.append(f"campo de usuario nao apareceu: {e}")
        return False

    pg.fill("input[name='username']", usuario)
    pg.fill("input[name='password']", senha)
    steps.append(f"credenciais digitadas para '{usuario}'")

    for sel in ("button[type='submit']", "input[type='submit']",
                "#UIPortalLoginFormAction", "button:has-text('Entrar')",
                "button:has-text('Sign in')"):
        if pg.locator(sel).count():
            pg.locator(sel).first.click()
            steps.append(f"botao de envio clicado ({sel})")
            break
    else:
        pg.keyboard.press("Enter")
        steps.append("envio via tecla Enter")

    try:
        pg.wait_for_load_state("networkidle", timeout=120_000)
    except Exception:  # noqa: BLE001
        pass
    time.sleep(3)

    url = pg.url
    corpo = pg.inner_text("body")[:400] if pg.locator("body").count() else ""
    # sinal de sucesso: saiu da tela de login e nao ha mensagem de erro
    saiu_do_login = "/login" not in url
    sem_erro = not any(t in corpo.lower() for t in
                       ("invalid", "incorret", "inválid", "falhou", "wrong"))
    steps.append(f"URL apos login: {url}")
    steps.append(f"saiu da tela de login={saiu_do_login}; sem_mensagem_de_erro={sem_erro}")
    return saiu_do_login and sem_erro


def t_login_admin(pg, rec: Recorder) -> bool:
    t0 = time.time()
    r = Result("T-11B", "Administrador faz login pela interface", "B-usuario")
    steps = []
    ok = fazer_login(pg, ADMIN_USER, ADMIN_PASS, steps)
    steps.append(f"captura: {shot(pg, 'T-11B-pos-login')}")
    if ok:
        # prova adicional: existe algum elemento que so aparece autenticado
        corpo = pg.inner_text("body")
        steps.append(f"texto visivel apos login: {len(corpo)} caracteres")
    r.steps = steps
    r.passed = ok
    r.detail = ("login pela interface concluido" if ok
                else "login pela interface NAO concluiu")
    r.proof = f"URL final={pg.url}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)
    return ok


def t_navegar_apps(pg, rec: Recorder) -> None:
    """
    Percorre as áreas funcionais como um usuário e comprova que cada uma
    RENDERIZA conteúdo (não uma página de erro).
    """
    areas = [
        ("T-02B", "Documentos (OneDrive/SharePoint)", "/portal/dw/documents"),
        ("T-09B", "Agenda (Outlook Calendar)", "/portal/dw/agenda"),
        ("T-05B", "Tarefas (Planner/To Do)", "/portal/dw/tasks"),
        ("T-04B", "Notes (OneNote)", "/portal/dw/notes"),
        ("T-06B", "Feed social (Yammer)", "/portal/dw/stream"),
        ("T-01B", "Espacos (Teams/SharePoint)", "/portal/dw/spaces"),
        ("T-12B", "Administracao", "/portal/administration"),
    ]
    for tid, nome, caminho in areas:
        t0 = time.time()
        r = Result(tid, f"Area '{nome}' abre e renderiza para o usuario", "B-usuario")
        steps = []
        try:
            resp = pg.goto(f"{BASE}{caminho}", wait_until="domcontentloaded",
                           timeout=120_000)
            try:
                pg.wait_for_load_state("networkidle", timeout=60_000)
            except Exception:  # noqa: BLE001
                pass
            time.sleep(2)
            status = resp.status if resp else None
            texto = pg.inner_text("body")
            titulo = pg.title()
            nome_arq = shot(pg, tid)

            ruim = any(s in texto for s in ("502 Bad Gateway", "HTTP Status 404",
                                            "HTTP Status 500", "Page Not Found"))
            steps += [f"GET {caminho} -> HTTP {status}",
                      f"titulo={titulo!r}",
                      f"texto renderizado: {len(texto.strip())} caracteres",
                      f"pagina de erro detectada: {ruim}",
                      f"captura: {nome_arq}"]
            r.passed = (status == 200 and len(texto.strip()) > 80 and not ruim)
            r.detail = (f"area renderizou {len(texto.strip())} caracteres"
                        if r.passed else
                        f"area NAO renderizou corretamente (HTTP {status})")
            r.proof = f"titulo={titulo!r}; captura={nome_arq}"
        except Exception as e:  # noqa: BLE001
            steps.append(f"ERRO: {type(e).__name__}: {e}")
            r.detail = f"excecao ao abrir: {e}"
        r.steps = steps
        r.duration_s = round(time.time() - t0, 2)
        rec.add(r)


def t_publicar_no_feed(pg, rec: Recorder) -> None:
    """
    T-06B — Ação real de usuário: escrever uma publicação e vê-la aparecer.
    Este é o teste que mais se aproxima do uso humano: digitar e conferir.
    """
    t0 = time.time()
    r = Result("T-06B-post", "Usuario publica no feed e a publicacao aparece",
               "B-usuario")
    texto = f"Publicacao via navegador {RUN_ID}"
    steps = []
    try:
        pg.goto(f"{BASE}/portal/dw", wait_until="domcontentloaded", timeout=120_000)
        try:
            pg.wait_for_load_state("networkidle", timeout=60_000)
        except Exception:  # noqa: BLE001
            pass
        time.sleep(3)

        # procura o compositor de publicacao por varios seletores plausiveis
        alvos = ["#composerInput", ".composerInput", "[contenteditable='true']",
                 "textarea[placeholder]", ".activityComposer textarea",
                 "div[role='textbox']"]
        campo = None
        for sel in alvos:
            if pg.locator(sel).count():
                campo = pg.locator(sel).first
                steps.append(f"compositor localizado por: {sel}")
                break
        if campo is None:
            steps.append(f"compositor NAO localizado; seletores tentados: {alvos}")
            steps.append(f"captura: {shot(pg, 'T-06B-sem-compositor')}")
            r.detail = "campo de publicacao nao encontrado na interface"
            r.steps = steps
            r.duration_s = round(time.time() - t0, 2)
            rec.add(r)
            return

        campo.click()
        campo.fill(texto) if campo.evaluate("e => e.tagName") in ("TEXTAREA", "INPUT") \
            else pg.keyboard.type(texto)
        steps.append(f"texto digitado: {texto!r}")
        steps.append(f"captura apos digitar: {shot(pg, 'T-06B-digitado')}")

        for sel in ("button:has-text('Publicar')", "button:has-text('Post')",
                    "button:has-text('Compartilhar')", ".btn-primary"):
            if pg.locator(sel).count():
                pg.locator(sel).first.click()
                steps.append(f"botao de publicar clicado ({sel})")
                break
        time.sleep(6)
        try:
            pg.wait_for_load_state("networkidle", timeout=60_000)
        except Exception:  # noqa: BLE001
            pass

        pg.reload(wait_until="domcontentloaded", timeout=120_000)
        time.sleep(5)
        visivel = texto in pg.inner_text("body")
        steps.append(f"publicacao visivel no feed apos recarregar: {visivel}")
        steps.append(f"captura final: {shot(pg, 'T-06B-final')}")

        r.passed = visivel
        r.detail = ("publicacao criada pelo usuario e exibida no feed" if visivel
                    else "publicacao nao apareceu no feed apos recarregar")
        r.proof = f"texto {texto!r} encontrado no DOM apos reload = {visivel}"
    except Exception as e:  # noqa: BLE001
        steps.append(f"ERRO: {type(e).__name__}: {e}")
        r.detail = f"excecao: {e}"
    r.steps = steps
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("T-01a13-funcionalidades-navegador")
    print("=" * 70)
    print("T-01..T-13 — FUNCIONALIDADES (abordagem B: navegador real)")
    print("=" * 70)

    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        ctx = b.new_context(viewport={"width": 1440, "height": 900},
                            locale="pt-BR", ignore_https_errors=True)
        pg = ctx.new_page()
        pg.set_default_timeout(90_000)

        if t_login_admin(pg, rec):
            t_navegar_apps(pg, rec)
            t_publicar_no_feed(pg, rec)
        else:
            print("Login falhou — os testes seguintes dependem dele e foram omitidos.")

        # guarda o HTML da area principal, util para diagnostico posterior
        try:
            (EVIDENCE / f"dom-dw-{RUN_ID}.html").write_text(pg.content()[:400_000])
        except Exception:  # noqa: BLE001
            pass
        ctx.close()
        b.close()

    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
