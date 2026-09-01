# -*- coding: utf-8 -*-
"""Incidente: o registro que o analista usa para AGIR.

Guarda o bastante para decidir e NADA do valor sensivel. Se o incidente
guardasse o dado, o console viraria o maior repositorio de dado pessoal da
prefeitura -- e o alvo mais obvio.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Dict, List

ESTADOS = ("NOVO", "EM_ANALISE", "ESCALADO", "CONFIRMADO", "FALSO_POSITIVO",
           "RESOLVIDO")


def agora() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class Anotacao:
    autor: str
    texto: str
    momento: str = field(default_factory=agora)


@dataclass
class Incidente:
    identificador: str = field(default_factory=lambda: str(uuid.uuid4()))
    momento: str = field(default_factory=agora)
    canal: str = ""
    usuario: str = ""
    grupos: List[str] = field(default_factory=list)
    ip: str = ""
    destino: str = ""
    recurso: str = ""            # caminho/uuid do documento
    nome_arquivo: str = ""
    tipo_arquivo: str = ""
    mime: str = ""
    disfarcado: bool = False
    tamanho: int = 0
    severidade: str = "BAIXA"
    classificacao: str = ""      # PUBLICO / INTERNO / SIGILOSO
    regra: str = ""
    regra_nome: str = ""
    acoes: List[str] = field(default_factory=list)           # o que a regra PEDIU
    # O que de fato ACONTECEU. Enquanto so' existia `acoes`, o incidente
    # registrava a intencao da politica como se fosse o efeito -- e um
    # QUARENTENAR que apenas bloqueava ficava indistinguivel de um que reteve.
    acoes_executadas: List[str] = field(default_factory=list)
    # O que ACONTECERIA se o portal estivesse aplicando. So' e' preenchido em
    # MODO OBSERVACAO, e `acoes_executadas` fica vazio nesse caso: registrar
    # como "executado" o que ninguem executou seria a mesma encenacao que este
    # trabalho existe para desfazer.
    acoes_simuladas: List[str] = field(default_factory=list)
    # APLICADO: a acao teve efeito. OBSERVACAO: o incidente foi registrado e
    # NADA mudou para o usuario -- nem bloqueio, nem retencao, nem aviso.
    modo: str = "APLICADO"
    # Acao que a regra pediu e o executor NAO conseguiu cumprir, com o motivo.
    # Sem este campo, a degradacao (ex.: mascarar um PDF -> bloquear) seria
    # invisivel para quem le o incidente.
    acoes_nao_aplicaveis: List[Dict] = field(default_factory=list)
    quarentena: str = ""         # identificador do item retido, quando houve
    liberacao: str = ""          # liberacao que autorizou esta passagem
    notificacoes: int = 0        # avisos enfileirados por este incidente
    orientacao: str = ""         # texto de coaching efetivamente enviado
    mensagem_usuario: str = ""   # o que o usuario viu na tela
    permitido: bool = True
    motivo: str = ""
    conformidade: List[str] = field(default_factory=list)
    evidencia: List[Dict] = field(default_factory=list)   # sempre mascarada
    extracao_completa: bool = True
    motivo_parcial: str = ""
    indices_edm: List[str] = field(default_factory=list)
    indices_idm: List[str] = field(default_factory=list)
    classe_estatistica: str = ""
    estado: str = "NOVO"
    responsavel: str = ""
    anotacoes: List[Dict] = field(default_factory=list)
    trilha: List[Dict] = field(default_factory=list)
    origem: str = "PORTAL"       # PORTAL | ENDPOINT | ICAP | EMAIL | NUVEM

    def registrar_trilha(self, autor: str, acao: str, detalhe: str = "") -> None:
        self.trilha.append({"autor": autor, "acao": acao, "detalhe": detalhe,
                            "momento": agora()})

    def anotar(self, autor: str, texto: str) -> None:
        self.anotacoes.append(asdict(Anotacao(autor, texto)))
        self.registrar_trilha(autor, "ANOTACAO", texto[:80])

    def mudar_estado(self, autor: str, novo: str, detalhe: str = "") -> None:
        if novo not in ESTADOS:
            raise ValueError(f"estado invalido: {novo}")
        anterior, self.estado = self.estado, novo
        self.registrar_trilha(autor, "ESTADO", f"{anterior} -> {novo}. {detalhe}".strip())

    def atribuir(self, autor: str, responsavel: str) -> None:
        self.responsavel = responsavel
        self.registrar_trilha(autor, "ATRIBUICAO", responsavel)

    def como_dicionario(self) -> Dict:
        return asdict(self)

    def como_json(self) -> str:
        return json.dumps(self.como_dicionario(), ensure_ascii=False)

    @classmethod
    def de_dicionario(cls, d: Dict) -> "Incidente":
        campos = {k: v for k, v in d.items() if k in cls.__dataclass_fields__}
        return cls(**campos)
