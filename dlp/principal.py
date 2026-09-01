# -*- coding: utf-8 -*-
"""Ponto de entrada do container DLP.

Sobe, nesta ordem: repositorio -> cofre -> quarentena -> liberacoes ->
notificador -> executor -> servico -> API, ICAP, SMTP, carteiro e agendador de
varredura.

A ORDEM NAO E' ARBITRARIA. O executor precisa da quarentena, que precisa do
cofre, que precisa da chave; o servico precisa do executor porque e' ele quem
transforma decisao em efeito. Montar isto dentro do modulo HTTP (onde estava)
misturava arranque com roteamento e escondia a dependencia.

Todo parametro vem do ambiente. Nada de segredo no codigo -- a chave do cofre,
o sal do EDM/IDM e o token da API sao gerados no primeiro arranque e guardados
no volume, com modo 600, se nao forem informados.
"""
from __future__ import annotations

import os
import secrets
import signal
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from acoes.cripto import RepositorioCertificados                # noqa: E402
from acoes.executor import Configuracao as ConfExecutor         # noqa: E402
from acoes.executor import Executor                             # noqa: E402
from acoes.liberacao import Liberacoes                          # noqa: E402
from acoes.notificacao import (Carteiro, ConfiguracaoCorreio,   # noqa: E402
                               Notificador)
from acoes.quarentena import Quarentena                         # noqa: E402
from canais.correio import ServidorSmtp                         # noqa: E402
from canais.icap import ServidorIcap                            # noqa: E402
from descoberta.origens import ErroDeOrigem, OrigemArquivos, OrigemWebdav  # noqa: E402
from descoberta.rastreador import Agendador, Rastreador         # noqa: E402
from incidentes.repositorio import Repositorio                  # noqa: E402
from integracao.siem import EnvioSyslog                         # noqa: E402
from motor.cofre import Cofre, chave_persistente                # noqa: E402
from politica.modelo import Contexto                            # noqa: E402
from servico import ServicoDlp                                  # noqa: E402
import servidor as api                                          # noqa: E402


def _ambiente(nome: str, padrao: str = "") -> str:
    return os.environ.get(nome, padrao).strip()


def _inteiro(nome: str, padrao: int) -> int:
    try:
        return int(_ambiente(nome, str(padrao)))
    except ValueError:
        return padrao


def _sim(nome: str, padrao: str = "nao") -> bool:
    return _ambiente(nome, padrao).lower() in ("sim", "1", "true", "yes")


def _lista(nome: str) -> tuple:
    return tuple(v.strip() for v in _ambiente(nome).split(",") if v.strip())


def _segredo_persistente(caminho: str, tamanho: int = 32) -> bytes:
    """Gera uma vez e reusa. Trocar o sal invalidaria todo indice EDM/IDM."""
    if os.path.exists(caminho):
        with open(caminho, "rb") as f:
            return f.read()
    valor = secrets.token_bytes(tamanho)
    os.makedirs(os.path.dirname(caminho), exist_ok=True)
    fd = os.open(caminho, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(valor)
    return valor


def decidir(dados=None, texto="", canal="API", usuario="", ip="", destino="",
            nome_arquivo="", grupos=(), recurso="", email="",
            cifra_delegada=False):
    """Adaptador usado por ICAP e SMTP -- mesmo caminho de decisao da API."""
    ctx = Contexto(canal=canal, usuario=usuario, email=email, ip=ip,
                   destino=destino, nome_arquivo=nome_arquivo,
                   grupos=tuple(grupos))
    resultado = api.APP.servico.analisar(dados, ctx, texto_direto=texto,
                                         recurso=recurso,
                                         cifra_delegada=cifra_delegada)
    if resultado.get("incidente"):
        api._enviar_siem(resultado["incidente"])
    return resultado


def _montar_origens(dados_dir: str) -> dict:
    """Origens de varredura de dados em repouso.

    Uma origem que nao consegue ser construida (WebDAV sem credencial, caminho
    inexistente) NAO derruba o servico: fica de fora, com o motivo no log. Um
    DLP que nao sobe porque um compartilhamento de rede caiu e' um DLP
    desligado -- que e' pior do que um DLP com uma origem a menos.
    """
    origens = {}
    base = _ambiente("DLP_DESCOBERTA_URL")
    if base:
        try:
            origens["portal"] = OrigemWebdav(
                base, _ambiente("DLP_DESCOBERTA_USUARIO"),
                _ambiente("DLP_DESCOBERTA_SENHA"),
                _inteiro("DLP_DESCOBERTA_TEMPO_LIMITE", 60))
        except ErroDeOrigem as e:
            print(f"[dlp] origem 'portal' indisponivel: {e}", flush=True)

    # Caminhos montados no container: e' por aqui que CIFS/SMB e NFS sao
    # varridos. Montar o compartilhamento e' tarefa do sistema, nao deste
    # servico -- o conector fica pronto e funciona assim que houver montagem.
    for item in _lista("DLP_DESCOBERTA_CAMINHOS"):
        nome, _, caminho = item.partition("=")
        nome, caminho = nome.strip(), (caminho or nome).strip()
        try:
            origens[nome] = OrigemArquivos(caminho)
        except ErroDeOrigem as e:
            print(f"[dlp] origem '{nome}' indisponivel: {e}", flush=True)

    padrao = os.path.join(dados_dir, "descoberta")
    if not origens and os.path.isdir(padrao):
        origens["local"] = OrigemArquivos(padrao)
    return origens


def principal() -> int:
    dados_dir = _ambiente("DLP_DADOS", "/dados")
    banco = os.path.join(dados_dir, "dlp.db")
    sal = _segredo_persistente(os.path.join(dados_dir, "sal.bin"))
    chave_cofre = chave_persistente(os.path.join(dados_dir, "chave-cofre.bin"))
    token = _ambiente("DLP_TOKEN")
    if not token:
        caminho_token = os.path.join(dados_dir, "token.txt")
        if os.path.exists(caminho_token):
            token = open(caminho_token).read().strip()
        else:
            token = secrets.token_urlsafe(32)
            fd = os.open(caminho_token, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(token)
            print(f"[dlp] token gerado em {caminho_token} (modo 600)", flush=True)

    syslog = EnvioSyslog(
        host=_ambiente("DLP_SIEM_HOST"), porta=_inteiro("DLP_SIEM_PORTA", 514),
        protocolo=_ambiente("DLP_SIEM_PROTOCOLO", "udp"),
        formato=_ambiente("DLP_SIEM_FORMATO", "cef"))

    dicionarios = {}
    caminho_dic = os.path.join(dados_dir, "dicionarios.json")
    if os.path.exists(caminho_dic):
        import json
        with open(caminho_dic, encoding="utf-8") as f:
            dicionarios = json.load(f)

    # ------------------------------------------------------------- montagem
    repositorio = Repositorio(banco)
    cofre = Cofre(os.path.join(dados_dir, "cofre"), chave_cofre)
    quarentena = Quarentena(repositorio, cofre)
    liberacoes = Liberacoes(repositorio)
    certificados = RepositorioCertificados(os.path.join(dados_dir, "certificados"))

    conf_correio = ConfiguracaoCorreio(
        host=_ambiente("DLP_NOTIFICA_SMTP_HOST"),
        porta=_inteiro("DLP_NOTIFICA_SMTP_PORTA", 1025),
        remetente=_ambiente("DLP_NOTIFICA_REMETENTE", "dlp@pmeto.local"),
        usuario=_ambiente("DLP_NOTIFICA_SMTP_USUARIO"),
        senha=_ambiente("DLP_NOTIFICA_SMTP_SENHA"),
        starttls=_sim("DLP_NOTIFICA_STARTTLS"),
        administradores=_lista("DLP_EMAIL_ADMINISTRADORES"),
        dominio_padrao=_ambiente("DLP_DOMINIO_EMAIL"),
        url_console=_ambiente("DLP_URL_CONSOLE"))
    notificador = Notificador(repositorio, conf_correio)

    executor = Executor(quarentena, liberacoes, notificador, certificados,
                        ConfExecutor(
                            acao_nao_aplicavel=_ambiente(
                                "DLP_ACAO_NAO_APLICAVEL", "BLOQUEAR").upper(),
                            url_console=conf_correio.url_console,
                            dominio_email=conf_correio.dominio_padrao))

    servico = ServicoDlp(repositorio, sal, executor, dicionarios)
    origens = _montar_origens(dados_dir)
    rastreador = Rastreador(repositorio, servico, origens,
                            _inteiro("DLP_DESCOBERTA_TETO_ARQUIVO",
                                     32 * 1024 * 1024))

    api.construir(repositorio, servico, token, syslog, quarentena, liberacoes,
                  executor, certificados, rastreador)
    print(f"[dlp] motor pronto: {len(servico.regras())} regra(s), "
          f"{len(servico.edm)} indice(s) EDM, {len(servico.idm)} indice(s) IDM, "
          f"{len(servico.varredura.dicionarios)} dicionario(s), "
          f"{len(origens)} origem(ns) de descoberta", flush=True)
    if not conf_correio.ativo:
        print("[dlp] AVISO: correio nao configurado (DLP_NOTIFICA_SMTP_HOST "
              "vazio). As acoes NOTIFICAR_* e ORIENTAR vao ficar em FALHA na "
              "fila, visiveis no console -- nao em silencio.", flush=True)
    if not conf_correio.administradores:
        print("[dlp] AVISO: DLP_EMAIL_ADMINISTRADORES vazio; NOTIFICAR_ADMIN "
              "nao tem para quem enviar.", flush=True)

    # ------------------------------------------------------------- servidores
    servidores = []
    porta_api = _inteiro("DLP_PORTA_API", 8480)
    servidores.append(("API REST", api.servir("0.0.0.0", porta_api), porta_api))

    if _sim("DLP_ICAP", "sim"):
        porta_icap = _inteiro("DLP_PORTA_ICAP", 1344)
        servidores.append(("ICAP (Protector)",
                           ServidorIcap(("0.0.0.0", porta_icap), decidir),
                           porta_icap))

    if _sim("DLP_SMTP", "sim"):
        porta_smtp = _inteiro("DLP_PORTA_SMTP", 10025)
        destino = (_ambiente("DLP_SMTP_DESTINO_HOST", "mailpit"),
                   _inteiro("DLP_SMTP_DESTINO_PORTA", 1025))
        internos = _lista("DLP_DOMINIOS_INTERNOS") or ("pmeto.local",)
        servidores.append((
            "SMTP (Email Security)",
            ServidorSmtp(("0.0.0.0", porta_smtp), decidir, destino, internos,
                         starttls_saida=_sim("DLP_SMTP_STARTTLS_SAIDA"),
                         certificados=certificados),
            porta_smtp))

    for nome, s, porta in servidores:
        threading.Thread(target=s.serve_forever, name=nome, daemon=True).start()
        print(f"[dlp] {nome} escutando em 0.0.0.0:{porta}", flush=True)

    carteiro = Carteiro(notificador, _inteiro("DLP_NOTIFICA_INTERVALO", 10))
    carteiro.start()
    print("[dlp] carteiro de notificacoes em execucao", flush=True)

    agendador = None
    intervalo_varredura = _inteiro("DLP_DESCOBERTA_INTERVALO", 0)
    origem_agendada = _ambiente("DLP_DESCOBERTA_ORIGEM", "portal")
    if intervalo_varredura > 0 and origem_agendada in origens:
        agendador = Agendador(rastreador, origem_agendada,
                              _ambiente("DLP_DESCOBERTA_ALVO"),
                              intervalo_varredura,
                              _inteiro("DLP_DESCOBERTA_ESPERA_INICIAL", 300))
        agendador.start()
        print(f"[dlp] varredura incremental de '{origem_agendada}' a cada "
              f"{intervalo_varredura}s", flush=True)
    elif intervalo_varredura > 0:
        print(f"[dlp] AVISO: DLP_DESCOBERTA_INTERVALO={intervalo_varredura} mas "
              f"a origem '{origem_agendada}' nao existe; agendamento desligado. "
              f"Origens: {sorted(origens) or '(nenhuma)'}", flush=True)

    parar = threading.Event()

    def encerrar(_sinal, _quadro):
        print("[dlp] encerrando", flush=True)
        parar.set()

    signal.signal(signal.SIGTERM, encerrar)
    signal.signal(signal.SIGINT, encerrar)
    while not parar.is_set():
        time.sleep(1)
    carteiro.parar()
    if agendador is not None:
        agendador.parar()
    for _nome, s, _porta in servidores:
        s.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(principal())
