# -*- coding: utf-8 -*-
"""Avaliacao de politica: da' o veredito e DIZ POR QUE.

O veredito sempre carrega a regra que decidiu e a evidencia mascarada. Console
que mostra "bloqueado" sem dizer qual regra e por qual achado e' console que o
analista nao consegue usar -- e politica que ninguem consegue ajustar.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

from motor.mascara import trecho_mascarado
from politica.modelo import (ACOES_IMPEDITIVAS, Contexto, Regra, condicao_casa,
                     excecao_aplica)


@dataclass
class Veredito:
    permitido: bool
    acoes: List[str] = field(default_factory=list)
    regra: Optional[str] = None
    regra_nome: str = ""
    severidade: str = "NENHUMA"
    mensagem: str = ""
    motivo: str = ""
    conformidade: List[str] = field(default_factory=list)
    evidencia: List[Dict[str, object]] = field(default_factory=list)
    excecao_aplicada: str = ""

    @property
    def impede(self) -> bool:
        return not self.permitido


class MotorPolitica:
    def __init__(self, regras: Sequence[Regra] = ()):
        self.regras = sorted(regras, key=lambda r: (r.prioridade, r.identificador))

    def substituir(self, regras: Sequence[Regra]) -> None:
        self.regras = sorted(regras, key=lambda r: (r.prioridade, r.identificador))

    def avaliar(self, achados: Sequence, ctx: Contexto,
                texto: str = "") -> Veredito:
        for regra in self.regras:
            if not regra.ativa:
                continue
            excecao = next((e for e in regra.excecoes if excecao_aplica(e, ctx)), None)
            if excecao is not None:
                if condicao_casa(regra.condicao, achados, ctx):
                    return Veredito(
                        True, ["PERMITIR"], regra.identificador, regra.nome,
                        regra.severidade,
                        motivo=f"excecao da regra '{regra.nome}': "
                               f"{excecao.motivo or 'sem motivo declarado'}",
                        excecao_aplicada=excecao.motivo or "sem motivo declarado",
                        evidencia=montar_evidencia(achados, texto))
                continue
            if condicao_casa(regra.condicao, achados, ctx):
                impede = any(a in ACOES_IMPEDITIVAS for a in regra.acoes)
                return Veredito(
                    not impede, list(regra.acoes), regra.identificador, regra.nome,
                    regra.severidade,
                    mensagem=regra.mensagem_usuario,
                    motivo=f"regra '{regra.nome}'",
                    conformidade=list(regra.conformidade),
                    evidencia=montar_evidencia(achados, texto))
        return Veredito(True, ["PERMITIR"], None, "", "NENHUMA",
                        motivo="nenhuma regra casou",
                        evidencia=montar_evidencia(achados, texto))


def montar_evidencia(achados: Sequence, texto: str,
                     por_rotulo: int = 3) -> List[Dict[str, object]]:
    """Evidencia SEMPRE mascarada: tipo, quantidade, posicao e trecho."""
    saida = []
    for a in achados:
        amostras = []
        for o in a.ocorrencias[:por_rotulo]:
            amostras.append({
                "posicao": o.inicio,
                "trecho": trecho_mascarado(texto, o.inicio, o.fim, o.rotulo)
                          if texto else "",
                "com_contexto": o.com_contexto,
            })
        saida.append({
            "rotulo": a.rotulo,
            "severidade": a.severidade,
            "categorias": list(a.categorias),
            "quantidade": a.quantidade,
            "amostras": amostras,
        })
    return saida
