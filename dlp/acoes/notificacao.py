# -*- coding: utf-8 -*-
"""Acoes NOTIFICAR_USUARIO, NOTIFICAR_ADMIN e ORIENTAR: e-mail que sai mesmo.

O DEFEITO QUE ISTO CORRIGE. Seis dos dez modelos de politica citavam
NOTIFICAR_ADMIN ou NOTIFICAR_USUARIO. Quem lia a politica no console acreditava
que alguem era avisado. Ninguem era: as tres acoes eram nome numa lista.

TRES DECISOES QUE IMPORTAM:

1. FILA PERSISTENTE, nao envio sincrono. O aviso e' gravado no banco ANTES de
   qualquer tentativa de rede. Enviar dentro do caminho da decisao amarraria o
   tempo de resposta do download ao tempo do servidor de e-mail, e um relay
   lento viraria portal lento. Pior: uma reinicializacao engoliria justamente o
   aviso do incidente que motivou a reinicializacao.

2. REENVIO COM ESPERA CRESCENTE e teto de tentativas. Relay cai. Sem reenvio, o
   aviso se perde em silencio, que e' a pior falha possivel num sistema cuja
   funcao e' avisar. Esgotadas as tentativas, o registro fica em FALHA com o
   erro -- visivel no console, nao escondido no log.

3. O AVISO NAO CARREGA O DADO. Vai o tipo do achado, a quantidade, a regra, o
   canal e o numero do incidente. Nunca o valor. Um e-mail de DLP com o CPF
   dentro seria o vazamento que ele existe para impedir -- e e-mail e' o canal
   menos controlado de todos.
"""
from __future__ import annotations

import smtplib
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from email.utils import formatdate, make_msgid
from typing import Dict, List, Optional, Sequence

TIPOS = ("USUARIO", "ADMIN", "ORIENTACAO")
TETO_TENTATIVAS = 6
ESPERA_BASE_SEGUNDOS = 30


def _agora() -> datetime:
    return datetime.now(timezone.utc)


def _iso(momento: datetime) -> str:
    return momento.isoformat(timespec="seconds")


@dataclass
class ConfiguracaoCorreio:
    """Para onde o proprio DLP entrega os avisos que ele gera.

    ATENCAO AO LACO: quando o portal e' apontado para o proxy SMTP do DLP, os
    avisos do DLP NAO podem sair pelo mesmo proxy -- um aviso sobre e-mail
    bloqueado seria inspecionado, poderia ser bloqueado, geraria outro aviso, e
    assim por diante. Por isso este destino e' configurado separadamente e
    aponta para o relay FINAL, nunca para a porta do proprio DLP.
    """
    host: str = ""
    porta: int = 25
    remetente: str = "dlp@localhost"
    usuario: str = ""
    senha: str = ""
    starttls: bool = False
    tempo_limite: int = 20
    administradores: Sequence[str] = ()
    dominio_padrao: str = ""
    url_console: str = ""

    @property
    def ativo(self) -> bool:
        return bool(self.host)


def endereco_de(usuario: str, informado: str, dominio_padrao: str) -> str:
    """Resolve o e-mail do usuario.

    Prioridade: o endereco que o PORTAL informou (ele conhece o cadastro, o
    DLP nao) e, na falta dele, login@dominio_padrao -- e so' quando um dominio
    padrao foi configurado. Sem isso, devolve vazio, e um aviso sem destinatario
    fica registrado como FALHA em vez de ser enviado para um endereco inventado.
    """
    if informado and "@" in informado:
        return informado.strip()
    if usuario and dominio_padrao:
        if "@" in usuario:
            return usuario.strip()
        return f"{usuario.strip()}@{dominio_padrao.strip().lstrip('@')}"
    return ""


class Notificador:
    """Enfileira e entrega. A entrega roda em thread propria (ver `Carteiro`)."""

    def __init__(self, repositorio, configuracao: ConfiguracaoCorreio):
        self.repo = repositorio
        self.conf = configuracao

    # ------------------------------------------------------------ enfileirar
    def enfileirar(self, tipo: str, destinatario: str, assunto: str,
                   corpo: str, incidente: str = "") -> Optional[int]:
        if tipo not in TIPOS:
            raise ValueError(f"tipo de notificacao invalido: {tipo}")
        if not destinatario:
            # Registra a impossibilidade em vez de descartar em silencio: no
            # console aparece "sem destinatario", que e' acionavel (cadastrar o
            # e-mail do usuario). Descartar produziria a mesma tela vazia de
            # antes, e ninguem saberia que faltou avisar alguem.
            identificador = self.repo.enfileirar_notificacao(
                {"tipo": tipo, "destinatario": "", "incidente": incidente,
                 "assunto": assunto, "corpo": corpo})
            self.repo.marcar_notificacao(
                identificador, "FALHA",
                "sem endereco de destino: o portal nao informou o e-mail do "
                "usuario e nao ha' dominio padrao configurado (DLP_DOMINIO_EMAIL)",
                _iso(_agora()))
            return identificador
        return self.repo.enfileirar_notificacao(
            {"tipo": tipo, "destinatario": destinatario, "incidente": incidente,
             "assunto": assunto, "corpo": corpo})

    def avisar_administradores(self, assunto: str, corpo: str,
                               incidente: str = "") -> List[int]:
        if not self.conf.administradores:
            identificador = self.repo.enfileirar_notificacao(
                {"tipo": "ADMIN", "destinatario": "", "incidente": incidente,
                 "assunto": assunto, "corpo": corpo})
            self.repo.marcar_notificacao(
                identificador, "FALHA",
                "nenhum administrador cadastrado em DLP_EMAIL_ADMINISTRADORES",
                _iso(_agora()))
            return [identificador]
        return [self.enfileirar("ADMIN", e, assunto, corpo, incidente)
                for e in self.conf.administradores]

    # --------------------------------------------------------------- entrega
    def entregar(self, registro: Dict) -> None:
        """Envia UM aviso. Excecao aqui e' tratada pelo Carteiro."""
        if not self.conf.ativo:
            raise RuntimeError(
                "correio do DLP nao configurado: defina DLP_NOTIFICA_SMTP_HOST")
        mensagem = EmailMessage()
        mensagem["From"] = self.conf.remetente
        mensagem["To"] = registro["destinatario"]
        mensagem["Subject"] = registro["assunto"]
        mensagem["Date"] = formatdate(localtime=True)
        mensagem["Message-ID"] = make_msgid(domain="dlp.exo")
        # Marca a origem para que uma regra de caixa postal consiga separar
        # aviso de DLP de mensagem comum, e para que o proprio proxy SMTP
        # reconheca e nao reinspecione o que ele mesmo gerou.
        mensagem["X-DLP-Origem"] = "servico-dlp"
        mensagem["X-DLP-Tipo"] = registro["tipo"]
        if registro.get("incidente"):
            mensagem["X-DLP-Incidente"] = registro["incidente"]
        mensagem["Auto-Submitted"] = "auto-generated"
        mensagem.set_content(registro["corpo"])

        with smtplib.SMTP(self.conf.host, self.conf.porta,
                          timeout=self.conf.tempo_limite) as sessao:
            sessao.ehlo()
            if self.conf.starttls:
                sessao.starttls()
                sessao.ehlo()
            if self.conf.usuario:
                sessao.login(self.conf.usuario, self.conf.senha)
            sessao.send_message(mensagem)


class Carteiro(threading.Thread):
    """Drena a fila. Espera crescente por tentativa, teto declarado."""

    def __init__(self, notificador: Notificador, intervalo: int = 10):
        super().__init__(name="Carteiro do DLP", daemon=True)
        self._notificador = notificador
        self._intervalo = intervalo
        self._parar = threading.Event()

    def parar(self) -> None:
        self._parar.set()

    def run(self) -> None:                                  # noqa: D102
        while not self._parar.is_set():
            try:
                self._rodada()
            except Exception as e:                          # noqa: BLE001
                print(f"[dlp] carteiro: falha na rodada: {e}", flush=True)
            self._parar.wait(self._intervalo)

    def _rodada(self) -> None:
        repo = self._notificador.repo
        pendentes = repo.notificacoes_pendentes(_iso(_agora()))
        for registro in pendentes:
            try:
                self._notificador.entregar(registro)
                repo.marcar_notificacao(registro["id"], "ENVIADA", "",
                                        _iso(_agora()))
                print(f"[dlp] aviso {registro['id']} entregue a "
                      f"{registro['destinatario']}", flush=True)
            except Exception as e:                          # noqa: BLE001
                tentativas = int(registro.get("tentativas") or 0) + 1
                if tentativas >= TETO_TENTATIVAS:
                    repo.marcar_notificacao(registro["id"], "FALHA", str(e),
                                            _iso(_agora()))
                    print(f"[dlp] aviso {registro['id']} DESISTIU apos "
                          f"{tentativas} tentativas: {e}", flush=True)
                else:
                    espera = ESPERA_BASE_SEGUNDOS * (2 ** (tentativas - 1))
                    proxima = _iso(_agora() + timedelta(seconds=espera))
                    repo.marcar_notificacao(registro["id"], "PENDENTE", str(e),
                                            proxima)


# ---------------------------------------------------------------- redacao
def _linha_achados(incidente: Dict) -> str:
    partes = []
    for e in incidente.get("evidencia", []):
        partes.append(f"  - {e.get('rotulo')}: {e.get('quantidade')} ocorrencia(s), "
                      f"severidade {e.get('severidade')}")
    return "\n".join(partes) or "  - (nenhum rotulo isolado; ver o incidente)"


def texto_usuario(incidente: Dict, mensagem_regra: str, url_console: str,
                  acao_tomada: str) -> str:
    """Aviso ao autor da tentativa. Sem valor sensivel, com o que fazer."""
    return (
        f"{mensagem_regra or 'Uma transferencia sua foi avaliada pela politica de protecao de dados.'}\n"
        f"\n"
        f"O que aconteceu: {acao_tomada}\n"
        f"Canal: {incidente.get('canal', '')}\n"
        f"Arquivo: {incidente.get('nome_arquivo') or '(corpo de mensagem)'}\n"
        f"Momento: {incidente.get('momento', '')}\n"
        f"Numero do incidente: {incidente.get('identificador', '')}\n"
        f"\n"
        f"Tipos de dado identificados (o valor NAO e' reproduzido aqui):\n"
        f"{_linha_achados(incidente)}\n"
        f"\n"
        f"Se este conteudo e' necessario para o seu trabalho, procure a area de "
        f"tecnologia informando o numero do incidente acima. Um analista pode "
        f"liberar a transferencia depois de conferir.\n"
        + (f"\nPolitica e incidentes: {url_console}\n" if url_console else "")
    )


def texto_administrador(incidente: Dict, url_console: str,
                        acao_tomada: str) -> str:
    return (
        f"Incidente de DLP registrado.\n"
        f"\n"
        f"Severidade: {incidente.get('severidade', '')}\n"
        f"Classificacao: {incidente.get('classificacao', '')}\n"
        f"Regra: {incidente.get('regra_nome') or '(nenhuma)'} "
        f"[{incidente.get('regra') or '-'}]\n"
        f"Acao: {acao_tomada}\n"
        f"Usuario: {incidente.get('usuario') or '(nao identificado)'}\n"
        f"Origem: {incidente.get('ip', '')}\n"
        f"Canal: {incidente.get('canal', '')}\n"
        f"Destino: {incidente.get('destino') or '-'}\n"
        f"Arquivo: {incidente.get('nome_arquivo') or '(corpo de mensagem)'} "
        f"({incidente.get('mime', '')}, {incidente.get('tamanho', 0)} bytes)\n"
        f"Recurso: {incidente.get('recurso') or '-'}\n"
        f"Extracao completa: "
        f"{'sim' if incidente.get('extracao_completa', True) else 'NAO -- ' + str(incidente.get('motivo_parcial', ''))}\n"
        f"Numero do incidente: {incidente.get('identificador', '')}\n"
        f"\n"
        f"Achados (mascarados):\n{_linha_achados(incidente)}\n"
        + (f"\nAbrir no console: {url_console}\n" if url_console else "")
    )


def texto_orientacao(incidente: Dict, orientacao: str, url_console: str) -> str:
    """Coaching: explica POR QUE, sem repreender.

    Mensagem que so' diz "voce violou a politica" ensina a esconder o envio,
    nao a evitar o vazamento. Por isso o texto diz o que a norma exige e qual
    e' o caminho certo para o mesmo objetivo.
    """
    return (
        f"{orientacao}\n"
        f"\n"
        f"Contexto: em {incidente.get('momento', '')} uma transferencia pelo canal "
        f"{incidente.get('canal', '')} continha dado que a politica trata como "
        f"sensivel ({incidente.get('classificacao', '')}).\n"
        f"\n"
        f"Tipos identificados:\n{_linha_achados(incidente)}\n"
        f"\n"
        f"Caminhos recomendados:\n"
        f"  - compartilhe pelo proprio portal, com permissao nominal, em vez de "
        f"anexar copia;\n"
        f"  - quando o destino for externo, peca a versao mascarada ou cifrada "
        f"a area de tecnologia;\n"
        f"  - nao ha' sancao automatica: esta mensagem e' orientacao, e o "
        f"incidente {incidente.get('identificador', '')} fica registrado.\n"
        + (f"\nPolitica vigente: {url_console}\n" if url_console else "")
    )
