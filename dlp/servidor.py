# -*- coding: utf-8 -*-
"""API REST do DLP. Biblioteca padrao apenas -- zero dependencia externa.

POR QUE SEM FRAMEWORK: este servico decide se dado pessoal sai da prefeitura.
Cada dependencia e' superficie de ataque de cadeia de suprimento em algo que le
TODO arquivo que passa. `http.server` + `sqlite3` sao mantidos junto com o
Python. O custo e' escrever roteamento a mao; o beneficio e' nao ter que
confiar em pacote de terceiro para ler o que ha de mais sensivel na casa.

(A UNICA excecao no servico inteiro e' a `cryptography`, usada no cofre e na
acao CRIPTOGRAFAR. A razao esta' no cabecalho de `motor/cofre.py`: AES escrito
a mao e' pior que AES auditado. Paranoia tecnica nao e' escrever a propria
cifra; e' nao escrever.)

AUTENTICACAO: token compartilhado no cabecalho `X-DLP-Token`, comparado em
tempo constante. O token vem do ambiente, nunca do codigo.
"""
from __future__ import annotations

import base64
import hmac
import json
import re
import traceback
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Dict, List, Tuple
from urllib.parse import parse_qs, unquote, urlparse

from acoes.cripto import RepositorioCertificados
from acoes.executor import Executor
from acoes.liberacao import Liberacoes
from acoes.quarentena import Quarentena
from descoberta.rastreador import Rastreador
from incidentes.modelo import ESTADOS
from incidentes.repositorio import Repositorio
from integracao.siem import EnvioSyslog
from motor.cofre import CofreCorrompido
from politica.modelo import ACOES, CANAIS, Condicao, Contexto, Excecao, Regra
from politica.modelos_prontos import catalogo
from relatorios import gerador
from servico import ServicoDlp

TETO_CORPO = 64 * 1024 * 1024


class Rotas:
    def __init__(self):
        self._rotas: List[Tuple[str, re.Pattern, Callable]] = []

    def registrar(self, metodo: str, padrao: str):
        expr = re.compile("^" + re.sub(r"\{(\w+)\}", r"(?P<\1>[^/]+)", padrao) + "$")

        def envolver(f):
            self._rotas.append((metodo, expr, f))
            return f
        return envolver

    def resolver(self, metodo: str, caminho: str):
        houve_caminho = False
        for m, expr, f in self._rotas:
            casou = expr.match(caminho)
            if casou:
                houve_caminho = True
                if m == metodo:
                    return f, casou.groupdict()
        return (None, {"_metodo_invalido": True}) if houve_caminho else (None, {})


ROTAS = Rotas()


class Contexto_App:
    """Estado do processo. Um so', criado no arranque."""
    repo: Repositorio
    servico: ServicoDlp
    syslog: EnvioSyslog
    token: str
    quarentena: Quarentena
    liberacoes: Liberacoes
    executor: Executor
    certificados: RepositorioCertificados
    rastreador: Rastreador


APP = Contexto_App()


def _ok(dados, codigo=200):
    return codigo, "application/json; charset=utf-8", json.dumps(
        dados, ensure_ascii=False, default=str).encode("utf-8")


def _erro(mensagem, codigo=400):
    return _ok({"erro": mensagem}, codigo)


def _autor(d: Dict) -> str:
    """Quem esta' pedindo. Vem do console, que ja' autenticou no portal.

    Vazio nao vira "desconhecido" em operacao que muda estado: acao sem autor
    e' acao que ninguem consegue auditar depois, e o console SEMPRE tem o
    usuario. As rotas de leitura nao chamam esta funcao.
    """
    valor = (d.get("autor") or "").strip()
    if not valor:
        raise ValueError("campo 'autor' e' obrigatorio nesta operacao")
    return valor


# ------------------------------------------------------------------ saude
@ROTAS.registrar("GET", "/saude")
def saude(_req, _corpo, _params, _q):
    return _ok({"estado": "ok", "servico": "dlp", "versao": "1.1",
                "regras": len(APP.servico.regras()),
                "indices_edm": sorted(APP.servico.edm),
                "indices_idm": sorted(APP.servico.idm),
                "dicionarios": sorted(APP.servico.varredura.dicionarios),
                "siem": {"ativo": APP.syslog.ativo, "host": APP.syslog.host,
                         "formato": APP.syslog.formato,
                         "ultimo_erro": APP.syslog.ultimo_erro},
                "incidentes": APP.repo.contar(),
                "revisao_pendente": APP.repo.contar({"estado": "EM_ANALISE"}),
                "quarentena": APP.quarentena.resumo(),
                "notificacoes": {
                    "pendentes": APP.repo.contar_notificacoes("PENDENTE"),
                    "enviadas": APP.repo.contar_notificacoes("ENVIADA"),
                    "falhas": APP.repo.contar_notificacoes("FALHA")},
                "correio": {"ativo": APP.executor.notificador.conf.ativo,
                            "host": APP.executor.notificador.conf.host,
                            "administradores":
                                list(APP.executor.notificador.conf.administradores)},
                "descoberta": {
                    "origens": APP.rastreador.origens_disponiveis(),
                    "em_andamento": APP.rastreador.em_andamento()}})


# ---------------------------------------------------------------- analise
@ROTAS.registrar("POST", "/analisar")
def analisar(_req, corpo, _params, _q):
    """Ponto unico de decisao. Todo canal entra por aqui."""
    d = _json(corpo)
    canal = (d.get("canal") or "").upper()
    if canal not in CANAIS:
        return _erro(f"canal invalido: {canal}. Validos: {', '.join(CANAIS)}")
    dados = None
    if d.get("conteudo_base64"):
        try:
            dados = base64.b64decode(d["conteudo_base64"])
        except Exception:                                   # noqa: BLE001
            return _erro("conteudo_base64 invalido")
    ctx = Contexto(
        canal=canal, usuario=d.get("usuario", ""), email=d.get("email", ""),
        grupos=tuple(d.get("grupos", [])), ip=d.get("ip", ""),
        destino=d.get("destino", ""), nome_arquivo=d.get("nome_arquivo", ""),
        tipo_arquivo=d.get("tipo_arquivo", ""))
    # TRES modos, e a diferenca nao e' cosmetica:
    #   simular   -> nao grava incidente e nao age. "O que aconteceria se".
    #   observacao-> GRAVA o incidente e NAO age. E' o modo do portal enquanto
    #                a politica esta' sendo dimensionada. Enquanto os dois
    #                estavam amarrados, a observacao retinha copia no cofre e
    #                mandava e-mail -- ou seja, nao era observacao.
    #   normal    -> grava e age.
    simular = bool(d.get("simular", False))
    observacao = bool(d.get("observacao", False))
    resultado = APP.servico.analisar(
        dados, ctx, texto_direto=d.get("texto", ""),
        recurso=d.get("recurso", ""),
        registrar=d.get("registrar", not simular),
        efeitos=not (simular or observacao))
    if resultado.get("incidente"):
        _enviar_siem(resultado["incidente"])
    return _ok(resultado)


def _enviar_siem(identificador: str) -> None:
    if not APP.syslog.ativo:
        return
    inc = APP.repo.obter(identificador)
    if inc:
        APP.syslog.enviar(inc.como_dicionario())


# ------------------------------------------------------------- incidentes
@ROTAS.registrar("GET", "/incidentes")
def listar_incidentes(_req, _corpo, _params, q):
    filtros = {k: q.get(k, [None])[0] for k in
               ("estado", "canal", "usuario", "severidade", "origem", "regra",
                "responsavel", "desde", "ate", "busca")}
    filtros = {k: v for k, v in filtros.items() if v}
    if q.get("permitido"):
        filtros["permitido"] = q["permitido"][0].lower() in ("1", "true", "sim")
    limite = min(int(q.get("limite", ["50"])[0]), 500)
    desloc = int(q.get("deslocamento", ["0"])[0])
    itens = APP.repo.listar(filtros, limite, desloc)
    return _ok({"total": APP.repo.contar(filtros), "limite": limite,
                "deslocamento": desloc,
                "itens": [i.como_dicionario() for i in itens]})


@ROTAS.registrar("GET", "/incidentes/{identificador}")
def obter_incidente(_req, _corpo, params, _q):
    inc = APP.repo.obter(params["identificador"])
    return _ok(inc.como_dicionario()) if inc else _erro("incidente inexistente", 404)


@ROTAS.registrar("POST", "/incidentes/{identificador}/estado")
def mudar_estado(_req, corpo, params, _q):
    d = _json(corpo)
    inc = APP.repo.obter(params["identificador"])
    if not inc:
        return _erro("incidente inexistente", 404)
    novo = (d.get("estado") or "").upper()
    if novo not in ESTADOS:
        return _erro(f"estado invalido. Validos: {', '.join(ESTADOS)}")
    autor = _autor(d)
    inc.mudar_estado(autor, novo, d.get("detalhe", ""))
    APP.repo.salvar(inc)
    APP.repo.auditar(autor, "INCIDENTE_ESTADO", inc.identificador, novo)
    return _ok(inc.como_dicionario())


@ROTAS.registrar("POST", "/incidentes/{identificador}/atribuir")
def atribuir(_req, corpo, params, _q):
    d = _json(corpo)
    inc = APP.repo.obter(params["identificador"])
    if not inc:
        return _erro("incidente inexistente", 404)
    autor = _autor(d)
    inc.atribuir(autor, d.get("responsavel", ""))
    APP.repo.salvar(inc)
    APP.repo.auditar(autor, "INCIDENTE_ATRIBUIDO", inc.identificador,
                     d.get("responsavel", ""))
    return _ok(inc.como_dicionario())


@ROTAS.registrar("POST", "/incidentes/{identificador}/anotar")
def anotar(_req, corpo, params, _q):
    d = _json(corpo)
    inc = APP.repo.obter(params["identificador"])
    if not inc:
        return _erro("incidente inexistente", 404)
    if not d.get("texto"):
        return _erro("texto obrigatorio")
    autor = _autor(d)
    inc.anotar(autor, d["texto"])
    APP.repo.salvar(inc)
    APP.repo.auditar(autor, "INCIDENTE_ANOTADO", inc.identificador,
                     d["texto"][:120])
    return _ok(inc.como_dicionario())


@ROTAS.registrar("POST", "/incidentes/lote")
def lote(_req, corpo, _params, _q):
    """Acao em lote sobre incidentes -- o console precisa disso."""
    d = _json(corpo)
    ids = d.get("identificadores", [])
    autor = _autor(d)
    novo = (d.get("estado") or "").upper()
    if novo and novo not in ESTADOS:
        return _erro("estado invalido")
    alterados = []
    for i in ids:
        inc = APP.repo.obter(i)
        if not inc:
            continue
        if novo:
            inc.mudar_estado(autor, novo, d.get("detalhe", ""))
        if d.get("responsavel"):
            inc.atribuir(autor, d["responsavel"])
        APP.repo.salvar(inc)
        alterados.append(i)
    APP.repo.auditar(autor, "INCIDENTE_LOTE", ",".join(alterados[:20]),
                     f"{len(alterados)} incidente(s)")
    return _ok({"alterados": len(alterados)})


# ------------------------------------------------------- fila de revisao
@ROTAS.registrar("GET", "/revisao")
def fila_revisao(_req, _corpo, _params, q):
    limite = min(int(q.get("limite", ["100"])[0]), 500)
    desloc = int(q.get("deslocamento", ["0"])[0])
    itens = APP.liberacoes.fila(limite, desloc)
    return _ok({"total": APP.repo.contar({"estado": "EM_ANALISE"}),
                "itens": itens})


@ROTAS.registrar("POST", "/revisao/{identificador}/aprovar")
def aprovar_revisao(_req, corpo, params, _q):
    d = _json(corpo)
    try:
        return _ok(APP.liberacoes.aprovar(
            params["identificador"], _autor(d), d.get("justificativa", ""),
            int(d.get("horas", 24)), int(d.get("teto_usos", 1))))
    except KeyError:
        return _erro("incidente inexistente", 404)
    except ValueError as e:
        return _erro(str(e))


@ROTAS.registrar("POST", "/revisao/{identificador}/reprovar")
def reprovar_revisao(_req, corpo, params, _q):
    d = _json(corpo)
    try:
        return _ok(APP.liberacoes.reprovar(params["identificador"], _autor(d),
                                           d.get("justificativa", "")))
    except KeyError:
        return _erro("incidente inexistente", 404)
    except ValueError as e:
        return _erro(str(e))


# ------------------------------------------------------------- quarentena
@ROTAS.registrar("GET", "/quarentena")
def listar_quarentena(_req, _corpo, _params, q):
    filtros = {k: q.get(k, [None])[0] for k in
               ("estado", "usuario", "canal", "incidente", "severidade", "busca")}
    filtros = {k: v for k, v in filtros.items() if v}
    limite = min(int(q.get("limite", ["50"])[0]), 500)
    desloc = int(q.get("deslocamento", ["0"])[0])
    return _ok({"resumo": APP.quarentena.resumo(),
                "itens": APP.quarentena.listar(filtros, limite, desloc)})


@ROTAS.registrar("GET", "/quarentena/{identificador}")
def obter_quarentena(_req, _corpo, params, _q):
    item = APP.quarentena.obter(params["identificador"])
    return _ok(item) if item else _erro("item inexistente", 404)


@ROTAS.registrar("GET", "/quarentena/{identificador}/conteudo")
def conteudo_quarentena(_req, _corpo, params, _q):
    """Restauracao: devolve o ORIGINAL retido, conferido por sha256.

    E' a razao de a quarentena existir. Enquanto ela apenas bloqueava, o objeto
    do incidente se perdia e o analista investigava no escuro.
    """
    try:
        dados = APP.quarentena.conteudo(params["identificador"])
    except KeyError:
        return _erro("item inexistente", 404)
    except FileNotFoundError as e:
        return _erro(f"conteudo ausente no cofre: {e}", 410)
    except CofreCorrompido as e:
        return _erro(str(e), 409)
    item = APP.quarentena.obter(params["identificador"]) or {}
    return 200, (item.get("mime") or "application/octet-stream"), dados


@ROTAS.registrar("POST", "/quarentena/{identificador}/liberar")
def liberar_quarentena(_req, corpo, params, _q):
    d = _json(corpo)
    try:
        autor = _autor(d)
        registro = APP.quarentena.liberar(params["identificador"], autor,
                                          d.get("justificativa", ""))
        liberacao = APP.liberacoes.criar(
            autor, registro["usuario"], registro["recurso"], registro["canal"],
            d.get("justificativa", ""), int(d.get("horas", 24)),
            int(d.get("teto_usos", 1)), registro["incidente"])
        return _ok({"quarentena": APP.quarentena.obter(params["identificador"]),
                    "liberacao": liberacao})
    except KeyError:
        return _erro("item inexistente", 404)
    except ValueError as e:
        return _erro(str(e))


@ROTAS.registrar("POST", "/quarentena/{identificador}/descartar")
def descartar_quarentena(_req, corpo, params, _q):
    d = _json(corpo)
    try:
        APP.quarentena.descartar(params["identificador"], _autor(d),
                                 d.get("justificativa", ""))
        return _ok(APP.quarentena.obter(params["identificador"]))
    except KeyError:
        return _erro("item inexistente", 404)
    except ValueError as e:
        return _erro(str(e))


# -------------------------------------------------------------- liberacoes
@ROTAS.registrar("GET", "/liberacoes")
def listar_liberacoes(_req, _corpo, _params, q):
    filtros = {k: q.get(k, [None])[0] for k in ("estado", "usuario", "incidente")}
    filtros = {k: v for k, v in filtros.items() if v}
    return _ok({"itens": APP.liberacoes.listar(
        filtros, min(int(q.get("limite", ["100"])[0]), 500))})


@ROTAS.registrar("POST", "/liberacoes")
def criar_liberacao(_req, corpo, _params, _q):
    d = _json(corpo)
    try:
        return _ok(APP.liberacoes.criar(
            _autor(d), d.get("usuario", ""), d.get("recurso", ""),
            d.get("canal", ""), d.get("justificativa", ""),
            int(d.get("horas", 24)), int(d.get("teto_usos", 1)),
            d.get("incidente", "")))
    except ValueError as e:
        return _erro(str(e))


@ROTAS.registrar("POST", "/liberacoes/{identificador}/revogar")
def revogar_liberacao(_req, corpo, params, _q):
    d = _json(corpo)
    try:
        if APP.liberacoes.revogar(params["identificador"], _autor(d)):
            return _ok({"revogada": params["identificador"]})
    except ValueError as e:
        return _erro(str(e))
    return _erro("liberacao inexistente ou ja' encerrada", 404)


# ------------------------------------------------------------ notificacoes
@ROTAS.registrar("GET", "/notificacoes")
def listar_notificacoes(_req, _corpo, _params, q):
    filtros = {k: q.get(k, [None])[0] for k in
               ("estado", "tipo", "destinatario", "incidente")}
    filtros = {k: v for k, v in filtros.items() if v}
    return _ok({"resumo": {e: APP.repo.contar_notificacoes(e)
                           for e in ("PENDENTE", "ENVIADA", "FALHA")},
                "itens": APP.repo.notificacoes(
                    filtros, min(int(q.get("limite", ["100"])[0]), 500))})


@ROTAS.registrar("POST", "/notificacoes/{identificador}/reenviar")
def reenviar_notificacao(_req, corpo, params, _q):
    """Recoloca um aviso em FALHA na fila. Erro de relay se corrige e reenvia.

    Sem isto, um problema de rede de dez minutos deixaria avisos perdidos para
    sempre, e a unica saida seria reabrir o incidente a mao.
    """
    d = _json(corpo)
    autor = _autor(d)
    try:
        identificador = int(params["identificador"])
    except ValueError:
        return _erro("identificador de notificacao deve ser numerico")
    from datetime import datetime, timezone
    APP.repo.marcar_notificacao(
        identificador, "PENDENTE", "reenfileirada por " + autor,
        datetime.now(timezone.utc).isoformat(timespec="seconds"))
    APP.repo.auditar(autor, "NOTIFICACAO_REENVIADA", str(identificador), "")
    return _ok({"reenfileirada": identificador})


# ---------------------------------------------------------------- politica
@ROTAS.registrar("GET", "/politica")
def ler_politica(_req, _corpo, _params, _q):
    return _ok({"regras": [asdict(r) for r in APP.servico.regras()],
                "acoes_disponiveis": list(ACOES),
                "canais_disponiveis": list(CANAIS)})


@ROTAS.registrar("GET", "/politica/modelos")
def modelos_politica(_req, _corpo, _params, _q):
    """Catalogo pronto, para o console oferecer 'restaurar modelo'.

    Existe porque uma politica editada ate' o ponto de nao funcionar mais nao
    tinha caminho de volta a nao ser apagar o banco.
    """
    return _ok({"regras": [asdict(r) for r in catalogo()]})


@ROTAS.registrar("PUT", "/politica")
def gravar_politica(_req, corpo, _params, _q):
    d = _json(corpo)
    try:
        autor = _autor(d)
    except ValueError as e:
        return _erro(str(e))
    try:
        regras = [Regra(condicao=Condicao(**r.get("condicao", {})),
                        excecoes=[Excecao(**e) for e in r.get("excecoes", [])],
                        **{k: v for k, v in r.items()
                           if k not in ("condicao", "excecoes")})
                  for r in d.get("regras", [])]
    except TypeError as e:
        return _erro(f"regra malformada: {e}")
    if not regras:
        # Gravar politica vazia desligaria o DLP inteiro com um PUT, em
        # silencio. Se e' para desligar, desliga-se regra a regra com
        # `ativa: false`, e cada uma fica registrada.
        return _erro("politica sem regras: use 'ativa: false' para desligar "
                     "regras, em vez de gravar uma lista vazia")
    identificadores = [r.identificador for r in regras]
    duplicados = {i for i in identificadores if identificadores.count(i) > 1}
    if duplicados:
        return _erro(f"identificadores repetidos: {sorted(duplicados)}")
    for r in regras:
        invalidas = [a for a in r.acoes if a not in ACOES]
        if invalidas:
            return _erro(f"acao invalida em '{r.identificador}': {invalidas}")
        canais_maus = [c for c in r.condicao.canais if c not in CANAIS]
        if canais_maus:
            return _erro(f"canal invalido em '{r.identificador}': {canais_maus}")
    APP.servico.salvar_politica(regras, autor)
    return _ok({"regras": len(regras)})


# ------------------------------------------------------------ EDM/IDM/ML
@ROTAS.registrar("GET", "/indices")
def listar_indices(_req, _corpo, _params, _q):
    dados = APP.repo.indices()
    dados["carregados"] = {"edm": sorted(APP.servico.edm),
                           "idm": sorted(APP.servico.idm)}
    return _ok(dados)


@ROTAS.registrar("POST", "/indices/edm/{nome}")
def indexar_edm(_req, corpo, params, _q):
    d = _json(corpo)
    linhas = d.get("linhas", [])
    if not linhas:
        return _erro("linhas obrigatorias")
    try:
        autor = _autor(d)
    except ValueError as e:
        return _erro(str(e))
    r = APP.servico.indexar_edm(params["nome"], d.get("colunas", []), linhas,
                                int(d.get("minimo", 2)), autor)
    return _ok(r)


@ROTAS.registrar("POST", "/indices/idm/{nome}")
def indexar_idm(_req, corpo, params, _q):
    d = _json(corpo)
    try:
        autor = _autor(d)
    except ValueError as e:
        return _erro(str(e))
    texto = d.get("texto", "")
    if d.get("conteudo_base64"):
        from motor import extracao as _ex
        texto = _ex.extrair(base64.b64decode(d["conteudo_base64"]),
                            d.get("nome_arquivo", "")).conteudo
    if not texto:
        return _erro("texto ou conteudo_base64 obrigatorio")
    return _ok(APP.servico.indexar_idm(params["nome"], d.get("documento", "sem-nome"),
                                       texto, autor))


@ROTAS.registrar("POST", "/indices/{tipo}/{nome}/estado")
def estado_indice(_req, corpo, params, _q):
    """Liga e desliga um indice sem destrui-lo.

    E' a resposta certa para "indexei errado": desligar tira o indice da
    decisao imediatamente e preserva o material para conferencia. Apagar
    tambem existe (DELETE), mas nao e' o primeiro movimento -- indice apagado
    por engano so' volta reindexando o cadastro inteiro.
    """
    d = _json(corpo)
    tabela = {"edm": "indice_edm", "idm": "indice_idm"}.get(params["tipo"])
    if not tabela:
        return _erro("tipo deve ser 'edm' ou 'idm'")
    try:
        autor = _autor(d)
    except ValueError as e:
        return _erro(str(e))
    ativo = bool(d.get("ativo", True))
    if not APP.repo.ativar_indice(tabela, params["nome"], ativo):
        return _erro("indice inexistente", 404)
    APP.servico.recarregar_indices()
    APP.repo.auditar(autor, "INDICE_ESTADO",
                     f"{params['tipo']}/{params['nome']}",
                     "ativado" if ativo else "desativado")
    return _ok({"indice": params["nome"], "tipo": params["tipo"], "ativo": ativo,
                "carregados": {"edm": sorted(APP.servico.edm),
                               "idm": sorted(APP.servico.idm)}})


@ROTAS.registrar("DELETE", "/indices/{tipo}/{nome}")
def remover_indice(_req, corpo, params, q):
    d = _json(corpo)
    autor = (d.get("autor") or q.get("autor", [""])[0]).strip()
    if not autor:
        return _erro("campo 'autor' e' obrigatorio nesta operacao")
    confirmado = str(d.get("confirmar") or
                     q.get("confirmar", [""])[0]).lower() in ("1", "true", "sim")
    if not confirmado:
        # Remocao exige confirmacao explicita porque e' irreversivel sem o
        # cadastro original em maos. Desativar (POST .../estado) resolve o caso
        # comum e nao perde nada.
        return _erro("remocao exige 'confirmar': true. Para tirar o indice da "
                     "decisao sem perde-lo, use POST /indices/{tipo}/{nome}/estado "
                     "com 'ativo': false", 409)
    if params["tipo"] == "edm":
        removido = APP.repo.remover_indice_edm(params["nome"])
    elif params["tipo"] == "idm":
        removido = APP.repo.remover_indice_idm(
            params["nome"], (d.get("documento") or
                             q.get("documento", [""])[0])) > 0
    else:
        return _erro("tipo deve ser 'edm' ou 'idm'")
    if not removido:
        return _erro("indice inexistente", 404)
    APP.servico.recarregar_indices()
    APP.repo.auditar(autor, "INDICE_REMOVIDO",
                     f"{params['tipo']}/{params['nome']}", "confirmado")
    return _ok({"removido": params["nome"], "tipo": params["tipo"]})


@ROTAS.registrar("POST", "/modelo/treinar")
def treinar(_req, corpo, _params, _q):
    d = _json(corpo)
    if not d.get("classe") or not d.get("texto"):
        return _erro("classe e texto obrigatorios")
    try:
        autor = _autor(d)
    except ValueError as e:
        return _erro(str(e))
    return _ok(APP.servico.treinar_estatistico(d["classe"], d["texto"], autor))


# ------------------------------------------------------------- dicionarios
@ROTAS.registrar("GET", "/dicionarios")
def listar_dicionarios(_req, _corpo, _params, _q):
    return _ok({"cadastrados": APP.repo.dicionarios(),
                "em_uso": {n: len(t) for n, t
                           in APP.servico.varredura.dicionarios.items()}})


@ROTAS.registrar("PUT", "/dicionarios/{nome}")
def gravar_dicionario(_req, corpo, params, _q):
    d = _json(corpo)
    termos = [t.strip() for t in d.get("termos", []) if t and t.strip()]
    if not termos:
        return _erro("termos obrigatorios")
    try:
        autor = _autor(d)
    except ValueError as e:
        return _erro(str(e))
    APP.repo.guardar_dicionario(params["nome"], termos,
                                (d.get("severidade") or "MEDIA").upper(),
                                d.get("categorias", ["DICIONARIO"]), autor)
    APP.repo.auditar(autor, "DICIONARIO_GRAVADO", params["nome"],
                     f"{len(termos)} termo(s)")
    return _ok({"dicionario": params["nome"], "termos": len(termos),
                "em_uso": APP.servico.recarregar_dicionarios()})


@ROTAS.registrar("DELETE", "/dicionarios/{nome}")
def remover_dicionario(_req, corpo, params, q):
    d = _json(corpo)
    autor = (d.get("autor") or q.get("autor", [""])[0]).strip()
    if not autor:
        return _erro("campo 'autor' e' obrigatorio nesta operacao")
    if not APP.repo.remover_dicionario(params["nome"]):
        return _erro("dicionario inexistente", 404)
    APP.repo.auditar(autor, "DICIONARIO_REMOVIDO", params["nome"], "")
    return _ok({"removido": params["nome"],
                "em_uso": APP.servico.recarregar_dicionarios()})


# ------------------------------------------------------------ certificados
@ROTAS.registrar("GET", "/certificados")
def listar_certificados(_req, _corpo, _params, _q):
    return _ok({"itens": APP.certificados.listar()})


@ROTAS.registrar("PUT", "/certificados/{endereco}")
def gravar_certificado(_req, corpo, params, _q):
    """Cadastra o certificado S/MIME de um destinatario.

    Sem certificado, a acao CRIPTOGRAFAR no canal de e-mail nao tem para quem
    cifrar -- e o executor NAO da' a acao por cumprida. E' aqui que o
    administrador resolve isso.
    """
    d = _json(corpo)
    pem = d.get("pem", "")
    if "BEGIN CERTIFICATE" not in pem:
        return _erro("campo 'pem' deve conter um certificado X.509 em PEM")
    try:
        autor = _autor(d)
        info = APP.certificados.guardar(unquote(params["endereco"]),
                                        pem.encode("utf-8"))
    except ValueError as e:
        return _erro(f"certificado invalido: {e}")
    APP.repo.auditar(autor, "CERTIFICADO_GRAVADO", info["endereco"],
                     f"{info['titular']} valido ate' {info['valido_ate']}")
    return _ok(info)


# --------------------------------------------------------------- descoberta
@ROTAS.registrar("GET", "/descoberta/origens")
def origens_descoberta(_req, _corpo, _params, _q):
    return _ok({"itens": APP.rastreador.origens_disponiveis()})


@ROTAS.registrar("GET", "/descoberta/varreduras")
def listar_varreduras(_req, _corpo, _params, q):
    return _ok({"em_andamento": APP.rastreador.em_andamento(),
                "itens": APP.repo.varreduras(
                    min(int(q.get("limite", ["50"])[0]), 200))})


@ROTAS.registrar("POST", "/descoberta/varreduras")
def iniciar_varredura(_req, corpo, _params, _q):
    d = _json(corpo)
    try:
        return _ok(APP.rastreador.iniciar(
            d.get("origem", ""), d.get("alvo", ""),
            (d.get("modo") or "COMPLETA").upper(), _autor(d),
            d.get("usuario_atribuido", "")))
    except KeyError as e:
        return _erro(str(e), 404)
    except RuntimeError as e:
        return _erro(str(e), 409)
    except ValueError as e:
        return _erro(str(e))


@ROTAS.registrar("GET", "/descoberta/varreduras/{identificador}")
def obter_varredura(_req, _corpo, params, _q):
    v = APP.repo.varredura(params["identificador"])
    return _ok(v) if v else _erro("varredura inexistente", 404)


@ROTAS.registrar("POST", "/descoberta/varreduras/{identificador}/cancelar")
def cancelar_varredura(_req, corpo, params, _q):
    d = _json(corpo)
    try:
        autor = _autor(d)
    except ValueError as e:
        return _erro(str(e))
    if APP.rastreador.cancelar(params["identificador"], autor):
        return _ok({"cancelando": params["identificador"]})
    return _erro("varredura inexistente ou ja' encerrada", 404)


# ---------------------------------------------- relatorios e classificacao
@ROTAS.registrar("GET", "/painel")
def painel(_req, _corpo, _params, q):
    return _ok(gerador.painel(APP.repo, int(q.get("dias", ["30"])[0])))


@ROTAS.registrar("GET", "/relatorios/conformidade/{norma}")
def conformidade(_req, _corpo, params, q):
    dados = gerador.conformidade(APP.repo, params["norma"],
                                 int(q.get("dias", ["90"])[0]))
    if q.get("formato", [""])[0] == "html":
        return 200, "text/html; charset=utf-8", \
               gerador.html_conformidade(dados).encode("utf-8")
    return _ok(dados)


@ROTAS.registrar("GET", "/relatorios/incidentes.csv")
def csv_incidentes(_req, _corpo, _params, q):
    filtros = {k: q.get(k, [None])[0] for k in ("estado", "canal", "usuario",
                                                "severidade", "desde", "ate")}
    filtros = {k: v for k, v in filtros.items() if v}
    itens = APP.repo.listar(filtros, limite=100000)
    corpo = gerador.csv_incidentes([i.como_dicionario() for i in itens])
    return 200, "text/csv; charset=utf-8", corpo.encode("utf-8")


@ROTAS.registrar("GET", "/classificacao/{recurso}")
def classificacao(_req, _corpo, params, _q):
    d = APP.repo.classificacao_de(unquote(params["recurso"]))
    return _ok(d) if d else _erro("recurso nao classificado", 404)


@ROTAS.registrar("GET", "/auditoria")
def auditoria(_req, _corpo, _params, q):
    return _ok({"itens": APP.repo.auditoria(int(q.get("limite", ["100"])[0]))})


# ------------------------------------------------------------- agentes
@ROTAS.registrar("POST", "/agentes/registrar")
def registrar_agente(_req, corpo, _params, _q):
    d = _json(corpo)
    if not d.get("identificador"):
        return _erro("identificador obrigatorio")
    APP.repo.registrar_agente(d)
    regras = [asdict(r) for r in APP.servico.regras()]
    # A VERSAO da politica volta junto e e' a mesma que fica gravada no
    # registro do agente. Antes, `politica_versao` era gravada e nunca lida --
    # nao servia para nada. Agora e' o que permite ao console dizer "este
    # agente esta' com politica velha", que e' a unica razao do campo existir.
    return _ok({"registrado": True, "politica_versao": _versao_politica(regras),
                "politica": regras})


@ROTAS.registrar("GET", "/agentes")
def listar_agentes(_req, _corpo, _params, _q):
    atual = _versao_politica([asdict(r) for r in APP.servico.regras()])
    agentes = APP.repo.agentes()
    for a in agentes:
        a["politica_atual"] = atual
        a["politica_desatualizada"] = (a.get("politica_versao") or "") != atual
    return _ok({"agentes": agentes, "politica_versao": atual})


def _versao_politica(regras: List[Dict]) -> str:
    """Impressao digital da politica vigente. Muda quando a politica muda."""
    import hashlib
    bruto = json.dumps(regras, sort_keys=True, ensure_ascii=False,
                       default=str).encode("utf-8")
    return hashlib.sha256(bruto).hexdigest()[:16]


def _json(corpo: bytes) -> Dict:
    if not corpo:
        return {}
    try:
        d = json.loads(corpo.decode("utf-8"))
        return d if isinstance(d, dict) else {}
    except Exception:                                       # noqa: BLE001
        return {}


class Manipulador(BaseHTTPRequestHandler):
    server_version = "DLP/1.1"
    protocol_version = "HTTP/1.1"

    def log_message(self, formato, *args):                  # noqa: A003
        print(f"[api] {self.address_string()} {formato % args}", flush=True)

    def _autorizado(self) -> bool:
        if not APP.token:
            return True
        enviado = self.headers.get("X-DLP-Token", "")
        return hmac.compare_digest(enviado, APP.token)

    def _tratar(self, metodo: str):
        u = urlparse(self.path)
        caminho = u.path.rstrip("/") or "/saude"
        if caminho != "/saude" and not self._autorizado():
            self._responder(*_erro("token invalido ou ausente", 401))
            return
        f, params = ROTAS.resolver(metodo, caminho)
        if f is None:
            if params.get("_metodo_invalido"):
                self._responder(*_erro(f"metodo {metodo} nao aceito", 405))
            else:
                self._responder(*_erro("rota inexistente", 404))
            return
        tamanho = int(self.headers.get("Content-Length") or 0)
        if tamanho > TETO_CORPO:
            self._responder(*_erro("corpo acima do teto", 413))
            return
        corpo = self.rfile.read(tamanho) if tamanho else b""
        try:
            self._responder(*f(self, corpo, params, parse_qs(u.query)))
        except ValueError as e:
            # ValueError aqui e' quase sempre validacao de entrada (autor
            # ausente, prazo fora da faixa). Devolver 500 mandaria o console
            # dizer "erro interno" para um campo mal preenchido.
            self._responder(*_erro(str(e), 400))
        except Exception as e:                              # noqa: BLE001
            traceback.print_exc()
            self._responder(*_erro(f"falha interna: {e}", 500))

    def _responder(self, codigo: int, tipo: str, corpo: bytes):
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(corpo)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(corpo)

    def do_GET(self):                                       # noqa: N802
        self._tratar("GET")

    def do_POST(self):                                      # noqa: N802
        self._tratar("POST")

    def do_PUT(self):                                       # noqa: N802
        self._tratar("PUT")

    def do_DELETE(self):                                    # noqa: N802
        self._tratar("DELETE")


def construir(repositorio: Repositorio, servico: ServicoDlp, token: str,
              syslog: EnvioSyslog, quarentena: Quarentena,
              liberacoes: Liberacoes, executor: Executor,
              certificados: RepositorioCertificados,
              rastreador: Rastreador) -> None:
    """Monta o estado do processo. Chamado uma vez, no arranque.

    A montagem em si mudou de lugar: e' `principal.py` quem constroi cofre,
    quarentena, notificador e executor, porque a ordem de dependencia entre
    eles e' assunto do arranque, e nao da camada HTTP.
    """
    APP.repo = repositorio
    APP.servico = servico
    APP.syslog = syslog
    APP.token = token
    APP.quarentena = quarentena
    APP.liberacoes = liberacoes
    APP.executor = executor
    APP.certificados = certificados
    APP.rastreador = rastreador


def servir(host: str, porta: int) -> ThreadingHTTPServer:
    servidor = ThreadingHTTPServer((host, porta), Manipulador)
    servidor.daemon_threads = True
    return servidor
