# -*- coding: utf-8 -*-
"""Acao REVISAO_MANUAL, do bloqueio ate' a devolucao.

O DEFEITO QUE ISTO CORRIGE: REVISAO_MANUAL impedia a saida e nascia
EM_ANALISE... e acabava ali. Nao havia fila, ninguem era chamado, e nao existia
nenhum caminho pelo qual o conteudo voltasse a passar depois de revisado. Na
pratica era um bloqueio permanente com nome simpatico.

O CICLO COMPLETO, que agora existe:

  bloqueio -> incidente EM_ANALISE -> aparece na FILA DE REVISAO -> analista
  atribui, examina a evidencia mascarada e o objeto retido -> APROVA (nasce uma
  liberacao) ou REPROVA (incidente CONFIRMADO) -> na aprovacao, a proxima
  tentativa do MESMO usuario sobre o MESMO recurso passa, e o uso e' contado.

TRES GUARDAS CONTRA A LIBERACAO VIRAR PORTA DOS FUNDOS:

  1. ESCOPO ESTREITO -- vale para um usuario e um recurso. Nao ha' casamento
     por prefixo: liberar '/documentos' liberaria o acervo inteiro sem que
     ninguem percebesse.
  2. PRAZO -- expira. Liberacao eterna e' excecao de politica que ninguem
     lembra de ter criado.
  3. CONTAGEM DE USOS -- por padrao vale uma vez. O que foi autorizado foi uma
     transferencia, nao um canal aberto.

Toda liberacao carrega autor e justificativa, e o uso e' registrado no
incidente seguinte. Se alguem liberar o que nao devia, o registro diz quem.
"""
from __future__ import annotations

import secrets
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional


def _agora() -> datetime:
    return datetime.now(timezone.utc)


def _iso(momento: datetime) -> str:
    return momento.isoformat(timespec="seconds")


class Liberacoes:
    def __init__(self, repositorio):
        self.repo = repositorio

    # ----------------------------------------------------------------- criar
    def criar(self, autor: str, usuario: str, recurso: str, canal: str,
              justificativa: str, horas: int = 24, teto_usos: int = 1,
              incidente: str = "") -> Dict:
        if not autor.strip():
            raise ValueError("autor e' obrigatorio")
        if not usuario.strip():
            # Liberacao sem usuario valeria para qualquer um. Nao existe caso
            # legitimo para isso num controle de vazamento.
            raise ValueError("usuario e' obrigatorio: nao ha' liberacao geral")
        if not justificativa.strip():
            raise ValueError("justificativa e' obrigatoria")
        if horas <= 0 or horas > 720:
            raise ValueError("prazo deve estar entre 1 e 720 horas (30 dias)")
        identificador = "l-" + secrets.token_hex(8)
        expira = _iso(_agora() + timedelta(hours=horas))
        self.repo.criar_liberacao({
            "identificador": identificador, "autor": autor,
            "incidente": incidente, "usuario": usuario.strip(),
            "recurso": recurso or "", "canal": canal or "",
            "expira_em": expira, "teto_usos": teto_usos,
            "estado": "ATIVA", "justificativa": justificativa})
        self.repo.auditar(autor, "LIBERACAO_CRIADA", identificador,
                          f"usuario={usuario} recurso={recurso or '(qualquer)'} "
                          f"canal={canal or '(qualquer)'} expira={expira} "
                          f"usos={teto_usos}: {justificativa}")
        return {"identificador": identificador, "expira_em": expira,
                "teto_usos": teto_usos, "usuario": usuario, "recurso": recurso}

    # -------------------------------------------------------------- consultar
    def valida_para(self, usuario: str, recurso: str, canal: str) -> Optional[Dict]:
        if not usuario:
            return None
        return self.repo.liberacao_valida(usuario, recurso or "", canal or "",
                                          _iso(_agora()))

    def consumir(self, identificador: str) -> None:
        self.repo.consumir_liberacao(identificador)

    def listar(self, filtros: Optional[Dict] = None, limite: int = 100) -> List[Dict]:
        registros = self.repo.liberacoes(filtros, limite)
        agora = _iso(_agora())
        for r in registros:
            # "ATIVA" no banco e ja' vencida no relogio confundiria o analista.
            # O estado exibido conta a verdade do momento da consulta.
            if r["estado"] == "ATIVA" and r["expira_em"] and r["expira_em"] < agora:
                r["estado"] = "EXPIRADA"
        return registros

    def revogar(self, identificador: str, autor: str) -> bool:
        revogada = self.repo.revogar_liberacao(identificador, autor)
        if revogada:
            self.repo.auditar(autor, "LIBERACAO_REVOGADA", identificador, "")
        return revogada

    # --------------------------------------------------------- fila de revisao
    def fila(self, limite: int = 100, deslocamento: int = 0) -> List[Dict]:
        """Incidentes esperando decisao humana. E' a tela que faltava."""
        return [i.como_dicionario() for i in
                self.repo.listar({"estado": "EM_ANALISE"}, limite, deslocamento)]

    def aprovar(self, incidente_id: str, autor: str, justificativa: str,
                horas: int = 24, teto_usos: int = 1) -> Dict:
        incidente = self.repo.obter(incidente_id)
        if incidente is None:
            raise KeyError(incidente_id)
        liberacao = self.criar(autor, incidente.usuario, incidente.recurso,
                               incidente.canal, justificativa, horas, teto_usos,
                               incidente_id)
        incidente.mudar_estado(autor, "RESOLVIDO",
                               f"revisao aprovada; liberacao {liberacao['identificador']}")
        incidente.anotar(autor, justificativa)
        self.repo.salvar(incidente)
        return {"incidente": incidente.como_dicionario(), "liberacao": liberacao}

    def reprovar(self, incidente_id: str, autor: str, justificativa: str) -> Dict:
        incidente = self.repo.obter(incidente_id)
        if incidente is None:
            raise KeyError(incidente_id)
        if not justificativa.strip():
            raise ValueError("justificativa e' obrigatoria")
        incidente.mudar_estado(autor, "CONFIRMADO", "revisao reprovada")
        incidente.anotar(autor, justificativa)
        self.repo.salvar(incidente)
        self.repo.auditar(autor, "REVISAO_REPROVADA", incidente_id, justificativa)
        return incidente.como_dicionario()
