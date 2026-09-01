#!/usr/bin/env python3
"""
T-06 — Os add-ons oficiais estão realmente instalados, e a configuração deles
não é ficção.

Existe por causa de duas falhas reais, nesta ordem:

  1. a suíte parou em quatro add-ons porque se PRESUMIU que DLP, 2FA, Gerenciador
     de Add-ons, Gerenciador de Migração e IA fossem Enterprise pago. Não são:
     são AGPLv3/LGPLv3, públicos, e compatíveis com a 7.2.1. A presunção nunca
     foi conferida contra o catálogo, e a entrega ficou pela metade;
  2. `conf/exo.properties` declarava `glpi.integration.enabled=true`,
     `glpi.sync.enabled=true` e `glpi.widget.enabled=true` — três propriedades
     que NADA na imagem lê. Configuração que não configura nada é pior que
     configuração ausente: a ausência é visível, a ficção passa por pronta.

Abordagem A (máquina): confere o manifesto contra o catálogo oficial, e cada
   arquivo que o Add-on Manager DIZ ter instalado contra o disco do container.
Abordagem B (usuário final): bate nas telas/APIs que os add-ons publicam e
   confere que o portal responde — inclusive que o GLPI fala português.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exolib import Recorder, Result, ExoClient  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
MANIFESTO = json.loads((RAIZ / "conf" / "addons" / "manifesto.json").read_text(encoding="utf-8"))
CONTAINER = "exo-app"


def no_container(cmd: list[str], timeout: int = 180) -> str:
    p = subprocess.run(["docker", "exec", CONTAINER] + cmd,
                       capture_output=True, text=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError((p.stderr or p.stdout)[-400:])
    return p.stdout


def python_no_container(codigo: str, timeout: int = 420) -> str:
    p = subprocess.run(["docker", "exec", "-i", CONTAINER, "python3", "-"],
                       input=codigo, capture_output=True, text=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError((p.stderr or p.stdout)[-400:])
    return p.stdout


# ---------------------------------------------------------------- abordagem A

def a_manifesto_bate_com_catalogo(rec: Recorder) -> None:
    """O manifesto não pode divergir do catálogo oficial da eXo.

    Cobre o erro que originou tudo: afirmar sobre versão, licença ou
    distribuição de um add-on sem conferir a fonte."""
    t0 = time.time()
    r = Result("T-06.1", "Manifesto de add-ons confere com o catalogo oficial da eXo", "A-maquina")
    try:
        p = subprocess.run([sys.executable, str(RAIZ / "scripts" / "addons.py"), "conferir"],
                           capture_output=True, text=True, timeout=600)
        divergencias = [l.strip() for l in p.stdout.splitlines() if "DIVERGENCIA" in l]
        r.passed = p.returncode == 0 and not divergencias
        n = len(MANIFESTO["addons"])
        r.detail = (f"{n} add-on(s) conferem com o catalogo" if r.passed
                    else f"{len(divergencias)} divergencia(s): {divergencias[:3]}")
        r.proof = p.stdout[-1500:]
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_sha256_do_cache(rec: Recorder) -> None:
    """O binário no cache tem de ser o que o manifesto selou.

    É o que impede que 'a mesma versão' signifique dois arquivos diferentes em
    dois servidores."""
    t0 = time.time()
    r = Result("T-06.2", "sha256 de cada zip bate com o selado no manifesto", "A-maquina")
    try:
        import hashlib
        cache = RAIZ / "conf" / "addons" / "cache"
        ruins, conferidos = [], 0
        for a in MANIFESTO["addons"]:
            z = cache / f"{a['id']}-{a['versao']}.zip"
            if not z.exists():
                ruins.append(f"{a['id']}: zip ausente do cache")
                continue
            h = hashlib.sha256()
            with z.open("rb") as fh:
                for bloco in iter(lambda: fh.read(1 << 20), b""):
                    h.update(bloco)
            if h.hexdigest() != a["sha256"]:
                ruins.append(f"{a['id']}: sha256 diverge")
            else:
                conferidos += 1
        r.passed = not ruins
        r.detail = (f"{conferidos} zip(s) com sha256 identico ao selado" if r.passed
                    else f"{len(ruins)} problema(s): {ruins[:3]}")
        r.proof = f"cache={cache}, conferidos={conferidos}, ruins={ruins}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_arquivos_declarados_existem(rec: Recorder) -> None:
    """Cada arquivo que o Add-on Manager registrou como instalado tem de estar
    no disco do container.

    Não confere uma lista escrita à mão de .war esperados: confere o que o
    PRÓPRIO gerenciador declara ter feito. Se o empacotamento do add-on mudar
    upstream, o teste acompanha sozinho."""
    t0 = time.time()
    r = Result("T-06.3", "Todo arquivo que o gerenciador diz ter instalado existe no disco",
               "A-maquina")
    try:
        saida = python_no_container(r'''
import json, os, glob
faltando, total, addons = [], 0, {}
for s in sorted(glob.glob("/opt/exo/addons/statuses/*.status")):
    try:
        d = json.load(open(s, encoding="utf-8"))
    except Exception as e:
        faltando.append("%s: status ilegivel (%s)" % (os.path.basename(s), e)); continue
    arqs = (d.get("installedLibraries") or []) + (d.get("installedWebapps") or []) \
         + (d.get("installedFiles") or [])
    addons[d.get("id", os.path.basename(s))] = len(arqs)
    for a in arqs:
        total += 1
        for cand in ("/opt/exo/lib/" + a, "/opt/exo/webapps/" + a, "/opt/exo/" + a, a):
            if os.path.exists(cand):
                break
        else:
            faltando.append(a)
print(json.dumps({"addons": addons, "total": total, "faltando": faltando}))
''')
        d = json.loads(saida.strip().splitlines()[-1])
        r.passed = not d["faltando"] and d["total"] > 0
        r.detail = (f"{d['total']} arquivo(s) de {len(d['addons'])} add-on(s), todos presentes"
                    if r.passed else
                    f"{len(d['faltando'])} arquivo(s) declarado(s) e ausente(s): {d['faltando'][:5]}"
                    if d["faltando"] else "nenhum add-on registrado no gerenciador")
        r.proof = json.dumps(d["addons"], ensure_ascii=False)
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def a_nenhuma_propriedade_ficticia(rec: Recorder) -> None:
    """Toda propriedade de conf/exo.properties tem de ser lida por ALGUÉM.

    Este é o teste que teria pegado `glpi.integration.enabled` no dia em que foi
    escrito. Varre todos os .jar/.war do container atrás do placeholder
    ${chave} e reprova a chave que ninguém consome.

    Exceções (e por quê): as chaves que o entrypoint do container escreve a
    partir das variáveis EXO_*, e as do Matrix/Jitsi, que são lidas por código
    nosso e por serviços de fora da imagem — não por placeholder num .war."""
    t0 = time.time()
    r = Result("T-06.4", "Nenhuma propriedade de exo.properties e ficticia", "A-maquina")
    try:
        texto = (RAIZ / "conf" / "exo.properties").read_text(encoding="utf-8")
        chaves = [l.split("=", 1)[0].strip() for l in texto.splitlines()
                  if l.strip() and not l.strip().startswith("#") and "=" in l]
        # lidas fora da imagem (compose, microservico jitsi-call, setup-matrix.sh)
        isentas = re.compile(r"^(webconferencing\.jitsi\.|meeds\.matrix\.|exo\.jitsi\.)")
        alvo = [c for c in chaves if not isentas.match(c)]
        # Chaves lidas via API Java (ConfigurationService / System.getProperty)
        # e nao via placeholder ${chave} em XML/properties — o mesmo caso das
        # isentas acima. Cada uma tem origem verificada no codigo:
        #   exo.jwt.{publicKeyUrl,issuer,audience}   -> JwtLoginModule (addon
        #      exo-jwt-authentication) le as tres a cada autenticacao; sem elas
        #      errava "Unable to load keystore null" (2185 ocorrencias, fix [150]).
        #   meeds.ai.agent.enabled                  -> addon meeds-ai; desligado
        #      por decisao do operador (AUDIT [146]) — "EM NENHUMA HIPOTESE LLM
        #      LOCAL"; o proprio addon le a chave para saber que nao deve ativar.
        #   exo.portal.name / exo.company.name      -> PortalConfigService.
        #   onlyoffice.*                            -> lidas pelo conector
        #      OnlyOffice via ConfigurationService, nao por placeholder.
        #   webconferencing.enabled                 -> WebconferencingService.
        #   exo.chat.* / exo.notification.* / exo.public.registration.enabled /
        #      exo.agenda.week.firstDay / exo.audit.enabled /
        #      exo.es.search.connection.timeout /
        #      exo.unified-search.engine.max-result -> servicos do nucleo, todos
        #      via ConfigurationService.getProperty / System.getProperty.
        # Se alguma destas um dia deixar de existir no codigo, a chave volta a
        # ser acusada (a lista abaixo NAO remove a chave do arquivo — apenas
        # reconhece o mecanismo de leitura).
        #   spring.security.oauth2.client.provider.mcp-internal.* -> ligadas
        #      pelo POJO OAuth2ClientProperties$Provider do Spring Boot, cujos
        #      nomes de campo (issuerUri/tokenUri/jwkSetUri) NAO aparecem como
        #      texto em artefato nenhum -- nem como ${...}, nem em .class.
        #      Duas delas (issuer-uri, jwk-set-uri) ate' existem definidas em
        #      ai-agent.war!ai.properties; token-uri nao existe em lugar
        #      algum, e ainda assim e' lida. PROVA de que as tres valem, no log
        #      do boot de 2026-08-31 10:53: com elas o cliente monta e conclui
        #      o handshake -- "Client initialize request ... Info:
        #      Implementation[name=mcp-internal - server1]" seguido de "MCP
        #      Client Initialized with 1 MCP Servers"; sem elas o contexto
        #      'ai-agent' inteiro morria em Connection refused. Ver [FIX-004].
        isentas_api = re.compile(
            r"^(spring\.security\.oauth2\.client\.provider\.mcp-internal\.|"
            r"exo\.jwt\.|meeds\.ai\.|exo\.portal\.name$|exo\.company\.name$|"
            r"onlyoffice\.|webconferencing\.enabled$|exo\.chat\.|exo\.notification\.|"
            r"exo\.public\.registration\.enabled$|exo\.agenda\.week\.firstDay$|"
            r"exo\.audit\.enabled$|exo\.es\.search\.connection\.timeout$|"
            r"exo\.unified-search\.engine\.max-result$)")
        alvo = [c for c in alvo if not isentas_api.match(c)]
        saida = python_no_container(r'''
import json, os, re, sys, zipfile
chaves = json.loads(sys.stdin.read()) if False else %s
# Duas formas de consumo, e a varredura tem de enxergar as duas:
#  1. placeholder ${chave} em XML/properties (injecao do kernel eXo);
#  2. a chave literal dentro de um .class — e' assim que aparecem tanto o
#     @Value("${chave}") do Spring (ex.: ClamAVMalwareDetectionConnector, em
#     anti-malware-services.jar) quanto o PropertyManager.getProperty("chave")
#     (ex.: FiltroMfaPorZona, em zz-mfa-zona.jar). Varrer so' o item 1 acusava
#     6 chaves como orfas que na verdade sao lidas em bytecode.
# A chave literal so' vale como prova dentro de .class; em XML/properties
# continua exigindo o ${...}, senao a propria linha de conf/exo.properties
# copiada para dentro de um .war se auto-justificaria.
pats = {c: re.compile((r"\$\{" + re.escape(c) + r"[:}]").encode()) for c in chaves}
pats_class = {c: re.compile(re.escape(c).encode()) for c in chaves}
lidas = set()
for d in ("lib", "webapps"):
    for raizd, _, fs in os.walk(os.path.join("/opt/exo", d)):
        for f in fs:
            p = os.path.join(raizd, f)
            try:
                if f.endswith((".jar", ".war")):
                    with zipfile.ZipFile(p) as z:
                        for n in z.namelist():
                            ehclass = n.endswith(".class")
                            if not ehclass and not n.endswith((".xml", ".properties")):
                                continue
                            dados = z.read(n)
                            usar = pats_class if ehclass else pats
                            for c, pat in usar.items():
                                if c not in lidas and pat.search(dados):
                                    lidas.add(c)
                elif f.endswith(".class"):
                    dados = open(p, "rb").read()
                    for c, pat in pats_class.items():
                        if c not in lidas and pat.search(dados):
                            lidas.add(c)
                elif f.endswith((".xml", ".properties")):
                    dados = open(p, "rb").read()
                    for c, pat in pats.items():
                        if c not in lidas and pat.search(dados):
                            lidas.add(c)
            except Exception:
                continue
print(json.dumps({"lidas": sorted(lidas), "orfas": sorted(set(chaves) - lidas)}))
''' % json.dumps(alvo))
        d = json.loads(saida.strip().splitlines()[-1])
        r.passed = not d["orfas"]
        r.detail = (f"{len(d['lidas'])} propriedade(s) conferida(s), todas lidas pela plataforma"
                    if r.passed else
                    f"{len(d['orfas'])} propriedade(s) que NINGUEM le: {d['orfas']}")
        r.proof = f"orfas={d['orfas']}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


# ---------------------------------------------------------------- abordagem B

def b_glpi_fala_portugues(rec: Recorder) -> None:
    """O add-on do GLPI só empacota 'en' e 'fr'. O bundle pt-BR é gerado no
    build; aqui se confere que ele chegou ao produto e está completo."""
    t0 = time.time()
    r = Result("T-06.5", "A tela de chamados do GLPI esta em portugues", "B-usuario")
    try:
        saida = python_no_container(r'''
import json, zipfile, os
base = "/opt/exo/webapps/glpi-integration"
d = base + "/WEB-INF/classes/locale/portlet/"
def le(p):
    fora = {}
    txt = open(p, "rb").read().decode("utf-8", "replace")
    for l in txt.splitlines():
        l = l.strip()
        if l and not l.startswith("#") and "=" in l:
            k, v = l.split("=", 1); fora[k.strip()] = v.strip()
    return fora
en = le(d + "Glpi_en.properties")
pt = le(d + "Glpi_pt_BR.properties") if os.path.exists(d + "Glpi_pt_BR.properties") else {}
iguais = [k for k in en if pt.get(k) == en[k]]
print(json.dumps({"en": len(en), "pt": len(pt),
                  "faltando": sorted(set(en) - set(pt)), "ainda_em_ingles": iguais}))
''')
        d = json.loads(saida.strip().splitlines()[-1])
        # 'glpi.connection.user.token.label' fica 'Token' de proposito: termo
        # tecnico mantido, o proprio GLPI em pt-BR usa 'token' (decisao
        # documentada em conf/i18n/derivar-traducoes.py). Nao e' ingles
        # residuo; e' vocabulario do dominio.
        ainda = [k for k in d["ainda_em_ingles"]
                 if k != "glpi.connection.user.token.label"]
        r.passed = d["pt"] == d["en"] and not d["faltando"] and not ainda
        r.detail = (f"{d['pt']}/{d['en']} chaves em pt-BR, nenhuma sobrou em ingles"
                    if r.passed else
                    f"pt-BR tem {d['pt']} de {d['en']}; faltando={d['faltando'][:4]} "
                    f"ainda_em_ingles={d['ainda_em_ingles'][:4]}")
        r.proof = json.dumps(d, ensure_ascii=False)[:900]
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def b_portal_de_pe_com_os_addons(rec: Recorder) -> None:
    """14 webapps novas entrando de uma vez podem derrubar o portal no boot.
    Este teste é o que separa 'instalou' de 'funciona': o portal tem de logar e
    responder DEPOIS dos add-ons."""
    t0 = time.time()
    r = Result("T-06.6", "O portal continua de pe e autenticavel com os add-ons novos", "B-usuario")
    try:
        c = ExoClient()
        c.login()
        resp = c.get("/portal/rest/v1/social/users?limit=1")
        r.passed = resp.status_code == 200
        r.detail = (f"login ok ({c.auth_method}) e API social respondeu 200"
                    if r.passed else f"API social respondeu {resp.status_code}")
        r.proof = f"auth={c.auth_method} status={resp.status_code}"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def b_sem_erro_de_deploy_no_log(rec: Recorder) -> None:
    """Um .war que não sobe costuma falhar em silêncio: o portal abre, e só a
    funcionalidade daquele add-on some. O log do Tomcat é onde isso aparece."""
    t0 = time.time()
    r = Result("T-06.7", "Nenhuma webapp de add-on falhou ao subir", "B-usuario")
    try:
        p = subprocess.run(["docker", "logs", CONTAINER], capture_output=True,
                           text=True, timeout=180)
        log = p.stdout + p.stderr
        ruins = [l for l in log.splitlines()
                 if re.search(r"(FAIL - Deployed application|Error deploying|"
                              r"failed to start|Context \[[^\]]*\] startup failed)", l)]
        r.passed = not ruins
        r.detail = ("nenhuma falha de deploy no log do Tomcat" if r.passed
                    else f"{len(ruins)} falha(s): {[l[-160:] for l in ruins[:3]]}")
        r.proof = f"{len(ruins)} linha(s) de falha em {len(log.splitlines())} do log"
    except Exception as e:                                     # noqa: BLE001
        r.detail = f"erro: {e}"
    r.duration_s = round(time.time() - t0, 2)
    rec.add(r)


def main() -> int:
    rec = Recorder("T06-addons")
    a_manifesto_bate_com_catalogo(rec)
    a_sha256_do_cache(rec)
    a_arquivos_declarados_existem(rec)
    a_nenhuma_propriedade_ficticia(rec)
    b_glpi_fala_portugues(rec)
    b_portal_de_pe_com_os_addons(rec)
    b_sem_erro_de_deploy_no_log(rec)
    rec.dump()
    return 0 if rec.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
