#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
E2E AO VIVO — navegador REAL, visivel na tela, clicando botao por botao.

Sem curl, sem HTTP cru: tudo passa pelo Chromium como um usuario de verdade,
em DISPLAY=:77 (exposto por x11vnc/noVNC), com slow_mo para dar para acompanhar.

Os seletores NAO sao chute: sairam de tests/descobrir_barra.py, que abriu o
portal e leu o DOM real da barra superior:
    #topBarSiteNavigation        menu do site
    #btnChatButtonNew            chat
    #appcenterLauncherButton     central de aplicativos
    a[href='/portal/administration']   "Acessar Configuracoes da Plataforma"
    a[href='/portal/dw/settings']      configuracoes do usuario
"""
import os, sys, time, json, pathlib, datetime

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

BASE  = os.environ.get("EXO_BASE", "https://192.168.1.59")
USER  = os.environ.get("EXO_ADMIN_USER", "root")
SENHA = os.environ.get("EXO_ADMIN_PASS", "")
SHOTS = pathlib.Path("/opt/projetos/exo/evidence/ao-vivo")
SHOTS.mkdir(parents=True, exist_ok=True)

RESULTS = []
_n = [0]
ADMIN = {}          # rotulo -> href, preenchido por t_admin_mapa


def shot(pg, nome):
    try:
        pg.screenshot(path=str(SHOTS / f"{_n[0]:02d}-{nome}.png"))
    except Exception:
        pass


def teste(pg, nome, fn):
    _n[0] += 1
    print(f"\n{'='*72}\n[{_n[0]:02d}] {nome}\n{'='*72}", flush=True)
    t0 = time.time()
    try:
        det = fn(pg) or ""
        RESULTS.append((nome, "PASSOU", det, round(time.time()-t0, 1)))
        print(f"  >>> PASSOU  {det}", flush=True)
    except Exception as e:
        det = str(e).split("\n")[0][:220]
        RESULTS.append((nome, "FALHOU", det, round(time.time()-t0, 1)))
        print(f"  >>> FALHOU  {det}", flush=True)
    shot(pg, nome.lower().replace(" ", "-").replace("/", "-")[:44])
    pg.wait_for_timeout(600)


def ir(pg, caminho, espera=3000):
    pg.goto(f"{BASE}{caminho}", wait_until="domcontentloaded", timeout=90_000)
    pg.wait_for_timeout(espera)


def txt(pg):
    try:
        return pg.locator("body").inner_text(timeout=20_000)
    except Exception:
        return pg.content()


def sem_erro(pg):
    t = txt(pg)
    for ruim in ("HTTP Status 500", "Internal Server Error", "Exception report",
                 "HTTP Status 404", "page-not-found"):
        assert ruim not in t, f"a pagina devolveu '{ruim}'"
    return t


# ------------------------------------------------------------------- acesso
def t_login(pg):
    limite = time.time() + 900          # ate 15 min: o boot do eXo leva ~3 min
    tent = 0
    while time.time() < limite:
        tent += 1
        try:
            pg.goto(f"{BASE}/portal/login", wait_until="domcontentloaded", timeout=45_000)
            pg.wait_for_timeout(1500)
            if pg.locator("input[name='username']").count():
                print(f"  portal respondeu na tentativa {tent}", flush=True)
                break
        except Exception as e:
            print(f"  aguardando o portal ({tent}): {str(e).splitlines()[0][:70]}", flush=True)
        pg.wait_for_timeout(4000)
    else:
        raise AssertionError("a tela de login nunca renderizou o campo de usuario")
    pg.fill("input[name='username']", USER); pg.wait_for_timeout(300)
    pg.fill("input[name='password']", SENHA); pg.wait_for_timeout(300)
    shot(pg, "login-preenchido")
    pg.locator("button[type='submit'], input[type='submit']").first.click()
    pg.wait_for_load_state("domcontentloaded", timeout=90_000)
    pg.wait_for_timeout(4000)
    assert "/portal/login" not in pg.url, "o login nao autenticou"
    return f"autenticado como {USER}"


def t_barra(pg):
    """A barra superior existe com os quatro pontos de entrada esperados."""
    ir(pg, "/portal/dw", espera=6000)
    esperados = {
        "menu do site":          "#topBarSiteNavigation",
        "chat":                  "#btnChatButtonNew",
        "central de aplicativos": "#appcenterLauncherButton",
        "administracao":         "a[href='/portal/administration']",
    }
    faltando = [n for n, s in esperados.items() if pg.locator(s).count() == 0]
    assert not faltando, f"ausentes na barra: {', '.join(faltando)}"
    return "barra com menu, chat, aplicativos e administracao"


# ------------------------------------------------- 1. briefing: nativos
def t_chat(pg):
    """1.1 Chat — CLICA no balao da barra e confere que a conversa abre."""
    ir(pg, "/portal/dw", espera=5000)
    botao = pg.locator("#btnChatButtonNew")
    assert botao.count(), "o botao de chat nao esta na barra"
    botao.first.click()
    pg.wait_for_timeout(6000)
    shot(pg, "chat-aberto")
    corpo = txt(pg).lower()
    marcas = [m for m in ("conversa", "mensagem", "chat", "matrix", "sala")
              if m in corpo]
    assert marcas, "o clique no chat nao trouxe nada de conversa para a tela"
    return f"chat aberto (marcas na tela: {', '.join(marcas)})"


def t_apps(pg):
    """Central de aplicativos — abre e lista os aplicativos publicados."""
    ir(pg, "/portal/dw", espera=4000)
    pg.locator("#appcenterLauncherButton").first.click()
    pg.wait_for_timeout(3500)
    shot(pg, "central-aplicativos")
    apps = pg.locator("[aria-label^='Open application']")
    n = apps.count()
    nomes = []
    for i in range(min(n, 12)):
        a = apps.nth(i).get_attribute("aria-label") or ""
        nomes.append(a.replace("Open application :", "").strip()[:24])
    assert n > 0, "a central de aplicativos abriu vazia"
    pg.keyboard.press("Escape")
    return f"{n} aplicativos: {', '.join(nomes[:6])}"


def t_documentos(pg):
    """1.3 Documentos — a aplicacao de documentos abre com seus controles."""
    ir(pg, "/portal/dw/documents", espera=7000)
    sem_erro(pg)
    n = pg.locator("button, a").count()
    assert n > 15, f"a tela de documentos veio sem controles ({n})"
    return f"documentos aberto ({n} controles)"


def t_agenda(pg):
    """Agenda (base do conector Exchange/Office365)."""
    ir(pg, "/portal/dw/agenda", espera=7000)
    sem_erro(pg)
    t = txt(pg).lower()
    assert any(m in t for m in ("agenda", "evento", "calend")), "a agenda nao abriu"
    return "agenda aberta"


# ------------------------------------------------- 2. administracao
# Os caminhos abaixo NAO sao chute: saíram do banco, da arvore de navegacao do
# site 'administration' (PORTAL_NAVIGATION_NODES), medida em 2026-08-26:
#   home/security/quarantine                    pagina 127   -> DLP
#   home/security/multifactor-authentication    pagina 129   -> 2FA
#   home/security/transfert-rules               pagina 128   -> download/compartilhamento
#   home/applications/visio                     pagina 123   -> videoconferencia
#   home/applications/chat                      pagina 130   -> chat
#   home/applications/translation               pagina 124   -> traducao automatica
#   home/applications/applicationsCenter        pagina  99   -> central de aplicativos
#   home/applications/email-connector           pagina 126   -> conector de e-mail
#   home/applications/agendaAdminSettings       pagina 122   -> agenda
#   home/ai/models|agents|sources|tools         paginas 88-92-> assistente de IA
#   home/organisation/users|groups|spaces|roles paginas 100-103,121
ADM = "/portal/administration/home"


def tela(pg, caminho, marcas, minimo_controles=3):
    """Abre uma tela REAL da administracao e exige conteudo proprio dela."""
    ir(pg, f"{ADM}/{caminho}", espera=7000)
    sem_erro(pg)
    corpo = txt(pg).lower()
    achadas = [m for m in marcas if m.lower() in corpo]
    n = pg.locator("button, a, input, select, .v-input").count()
    assert achadas, (f"{caminho}: abriu sem nenhuma marca de {marcas} "
                     f"(primeiros 120 chars: {corpo[:120]!r})")
    assert n >= minimo_controles, f"{caminho}: tela sem controles ({n})"
    return f"{caminho} — {', '.join(achadas)} ({n} controles)"


def t_admin_alcancavel(pg):
    """A engrenagem leva mesmo ao centro de administracao."""
    ir(pg, "/portal/dw", espera=4000)
    eng = pg.locator("a[href='/portal/administration']").first
    assert eng.count(), "o link de administracao sumiu da barra"
    eng.click()
    pg.wait_for_load_state("domcontentloaded", timeout=90_000)
    pg.wait_for_timeout(7000)
    assert "/portal/administration" in pg.url, \
        f"a engrenagem NAO levou a administracao (foi para {pg.url})"
    return f"engrenagem -> {pg.url}"


def t_usuarios(pg):
    return tela(pg, "organisation/users", ["usuário", "user", "adicionar", "add"])


def t_grupos(pg):
    return tela(pg, "organisation/groups", ["grupo", "group"])


def t_espacos(pg):
    return tela(pg, "organisation/spaces", ["espaço", "space"])


def t_dlp(pg):
    return tela(pg, "security/quarantine", ["quarentena", "quarantine", "dlp"])


def t_2fa(pg):
    return tela(pg, "security/multifactor-authentication",
                ["fator", "factor", "otp", "mfa"])


def t_download(pg):
    return tela(pg, "security/transfert-rules",
                ["download", "transfer", "regra", "rule", "compartilh"])


def t_video(pg):
    return tela(pg, "applications/visio", ["jitsi", "visio", "vídeo", "video", "chamada", "call"])


def t_chat_admin(pg):
    return tela(pg, "applications/chat", ["chat", "matrix", "conversa"])


def t_traducao(pg):
    return tela(pg, "applications/translation", ["tradu", "translat", "idioma", "language"])


def t_appcenter_admin(pg):
    return tela(pg, "applications/applicationsCenter",
                ["aplicativ", "application", "app"])


def t_email(pg):
    return tela(pg, "applications/email-connector",
                ["e-mail", "email", "imap", "exchange", "conector", "connector"])


def t_agenda_admin(pg):
    return tela(pg, "applications/agendaAdminSettings",
                ["agenda", "calend", "conector", "connector"])


def t_ia(pg):
    return tela(pg, "ai/models", ["modelo", "model", "provedor", "provider", "ia", "ai"])


def t_ia_agentes(pg):
    return tela(pg, "ai/agents", ["agente", "agent"])


# ------------------------------------------------------------------ execucao
TESTES = [
    ("Login no portal",                       t_login),
    ("Barra superior do portal",              t_barra),
    ("Engrenagem abre a administracao",       t_admin_alcancavel),
    ("1.1 Chat integrado (barra)",            t_chat),
    ("1.1 Chat — administracao",              t_chat_admin),
    ("1.2 Videoconferencia (Jitsi)",          t_video),
    ("1.3 Documentos",                        t_documentos),
    ("1.4 Central de aplicativos (barra)",    t_apps),
    ("1.4 Central de aplicativos — admin",    t_appcenter_admin),
    ("1.4 Conector de e-mail (Exchange/IMAP)", t_email),
    ("1.4 Agenda — conectores",               t_agenda_admin),
    ("2.1 DLP (quarentena)",                  t_dlp),
    ("2.2 2FA / MFA",                         t_2fa),
    ("2.3 Restricoes de download",            t_download),
    ("3.x Usuarios",                          t_usuarios),
    ("3.x Grupos",                            t_grupos),
    ("3.x Espacos",                           t_espacos),
    ("Traducao automatica",                   t_traducao),
    ("Assistente de IA — modelos",            t_ia),
    ("Assistente de IA — agentes",            t_ia_agentes),
]


def main():
    if not SENHA:
        sys.exit("defina EXO_ADMIN_PASS")
    inicio = datetime.datetime.now()
    with sync_playwright() as p:
        nav = p.chromium.launch(
            headless=False, slow_mo=400,
            args=["--no-sandbox", "--disable-dev-shm-usage",
                  "--window-size=1600,1000", "--window-position=0,0"])
        pg = nav.new_context(ignore_https_errors=True,
                             viewport={"width": 1600, "height": 900},
                             locale="pt-BR").new_page()
        print(f"navegador REAL aberto em DISPLAY={os.environ.get('DISPLAY')}\n", flush=True)
        for nome, fn in TESTES:
            teste(pg, nome, fn)

        ok = sum(1 for r in RESULTS if r[1] == "PASSOU")
        print("\n" + "=" * 72)
        for nome, st, det, dur in RESULTS:
            print(f"  {st:7} {nome:38} {det[:78]}")
        print("=" * 72)
        print(f"  {ok}/{len(RESULTS)} passaram — {(datetime.datetime.now()-inicio).seconds}s")
        print(f"  evidencia (png): {SHOTS}")
        (SHOTS / "resultado.json").write_text(json.dumps(
            [{"teste": n, "status": s, "detalhe": d, "segundos": t}
             for n, s, d, t in RESULTS], ensure_ascii=False, indent=2), encoding="utf-8")
        pg.wait_for_timeout(3000)
        nav.close()
    sys.exit(0 if ok == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
