# -*- coding: utf-8 -*-
"""Politicas: regra, condicao, acao, excecao e severidade.

Uma regra responde a: QUEM, levando O QUE, por QUAL canal, para ONDE, QUANDO.
Faltando qualquer um desses eixos, DLP vira "bloqueia tudo" ou "nao bloqueia
nada" -- e os dois acabam desligados.
"""
from __future__ import annotations

import fnmatch
from dataclasses import dataclass, field
from datetime import datetime, time
from typing import List, Optional, Sequence

SEVERIDADES = ("BAIXA", "MEDIA", "ALTA", "CRITICA")
_PESO = {s: i + 1 for i, s in enumerate(SEVERIDADES)}

CANAIS = (
    "DOWNLOAD", "LINK_PUBLICO", "COMPARTILHAMENTO_EXTERNO", "EMAIL",
    # EMAIL_INTERNO e' mensagem entre enderecos da propria casa. Existe como
    # canal PROPRIO porque a politica precisa tratar os dois de forma
    # diferente: circular um CPF entre dois setores da prefeitura nao e' o
    # mesmo evento que manda-lo para fora. Ele era produzido pelo proxy SMTP
    # SEM estar nesta lista -- entrava pelo adaptador interno, que nao valida,
    # e teria sido recusado como "canal invalido" se viesse pela API.
    "EMAIL_INTERNO", "CHAT",
    "EDITOR", "NUVEM", "API", "WEBDAV", "IMPRESSAO", "USB", "CLIPBOARD",
    "ENDPOINT", "ICAP", "DESCOBERTA",
)

ACOES = (
    "PERMITIR", "REGISTRAR", "NOTIFICAR_USUARIO", "NOTIFICAR_ADMIN",
    "ORIENTAR", "MASCARAR", "CRIPTOGRAFAR", "QUARENTENAR", "REVISAO_MANUAL",
    "BLOQUEAR",
)

# Acoes que IMPEDEM a saida do dado.
ACOES_IMPEDITIVAS = {"BLOQUEAR", "QUARENTENAR", "REVISAO_MANUAL"}


@dataclass
class Condicao:
    """Todos os campos preenchidos precisam casar (E logico)."""
    rotulos: Sequence[str] = ()            # CPF, CARTAO_CREDITO, ...
    categorias: Sequence[str] = ()         # PII, PCI-DSS, LGPD, ...
    severidade_minima: str = "BAIXA"
    ocorrencias_minimas: int = 1
    canais: Sequence[str] = ()
    usuarios: Sequence[str] = ()           # glob: "jose.*"
    grupos: Sequence[str] = ()             # "/platform/administrators"
    ips: Sequence[str] = ()                # prefixo: "192.168.1."
    destinos: Sequence[str] = ()           # dominio/host: "*.gmail.com"
    tipos_arquivo: Sequence[str] = ()      # extensao REAL
    arquivo_disfarcado: Optional[bool] = None
    indice_edm: Sequence[str] = ()
    indice_idm: Sequence[str] = ()
    classe_estatistica: Sequence[str] = ()
    horario_inicio: Optional[str] = None   # "08:00"
    horario_fim: Optional[str] = None      # "18:00"
    dias_semana: Sequence[int] = ()        # 0=segunda


@dataclass
class Excecao:
    """Quando casa, a regra NAO se aplica. Avaliada antes da condicao."""
    usuarios: Sequence[str] = ()
    grupos: Sequence[str] = ()
    ips: Sequence[str] = ()
    destinos: Sequence[str] = ()
    canais: Sequence[str] = ()
    motivo: str = ""


@dataclass
class Regra:
    identificador: str
    nome: str
    condicao: Condicao
    acoes: Sequence[str]
    severidade: str = "MEDIA"
    prioridade: int = 100                  # menor = avaliada antes
    ativa: bool = True
    excecoes: List[Excecao] = field(default_factory=list)
    mensagem_usuario: str = ""
    # Texto da acao ORIENTAR. Vazio usa o texto padrao do executor. E' campo
    # separado de `mensagem_usuario` de proposito: aquela explica o bloqueio,
    # esta ensina o caminho certo -- e as duas viajam em momentos diferentes.
    orientacao: str = ""
    conformidade: Sequence[str] = ()       # PCI-DSS, LGPD, ...

    def impede(self) -> bool:
        return any(a in ACOES_IMPEDITIVAS for a in self.acoes)


@dataclass
class Contexto:
    """O que se sabe sobre a tentativa de saida."""
    canal: str
    usuario: str = ""
    # E-mail informado pelo PORTAL. O DLP nao tem cadastro de pessoas e nao
    # deve inventar endereco: sem isto, NOTIFICAR_USUARIO nao tinha para onde
    # ir e a acao era encenacao ainda que o envio funcionasse.
    email: str = ""
    grupos: Sequence[str] = ()
    ip: str = ""
    destino: str = ""
    nome_arquivo: str = ""
    tipo_arquivo: str = ""
    disfarcado: bool = False
    momento: Optional[datetime] = None
    indices_edm: Sequence[str] = ()
    indices_idm: Sequence[str] = ()
    classe_estatistica: str = ""


def _casa_glob(valor: str, padroes: Sequence[str]) -> bool:
    if not padroes:
        return True
    v = (valor or "").lower()
    return any(fnmatch.fnmatch(v, p.lower()) for p in padroes)


def _casa_prefixo(valor: str, prefixos: Sequence[str]) -> bool:
    if not prefixos:
        return True
    v = valor or ""
    return any(v.startswith(p) for p in prefixos)


def _casa_lista(valores: Sequence[str], esperados: Sequence[str]) -> bool:
    if not esperados:
        return True
    conj = {v.upper() for v in valores}
    return any(e.upper() in conj for e in esperados)


def excecao_aplica(exc: Excecao, ctx: Contexto) -> bool:
    campos = 0
    for atributo, teste in (
        (exc.usuarios, lambda: _casa_glob(ctx.usuario, exc.usuarios)),
        (exc.grupos, lambda: _casa_lista(ctx.grupos, exc.grupos)),
        (exc.ips, lambda: _casa_prefixo(ctx.ip, exc.ips)),
        (exc.destinos, lambda: _casa_glob(ctx.destino, exc.destinos)),
        (exc.canais, lambda: ctx.canal in exc.canais),
    ):
        if atributo:
            campos += 1
            if not teste():
                return False
    return campos > 0


def condicao_casa(cond: Condicao, achados: Sequence, ctx: Contexto) -> bool:
    rotulos = [a.rotulo for a in achados]
    categorias = [c for a in achados for c in a.categorias]
    total = sum(a.quantidade for a in achados)

    if cond.rotulos and not _casa_lista(rotulos, cond.rotulos):
        return False
    if cond.categorias and not _casa_lista(categorias, cond.categorias):
        return False
    if achados:
        maior = max(_PESO.get(a.severidade, 0) for a in achados)
        if maior < _PESO.get(cond.severidade_minima, 1):
            return False
    elif cond.rotulos or cond.categorias or cond.severidade_minima != "BAIXA":
        return False
    if total < cond.ocorrencias_minimas and (cond.rotulos or cond.categorias):
        return False
    if cond.canais and ctx.canal not in cond.canais:
        return False
    if not _casa_glob(ctx.usuario, cond.usuarios):
        return False
    if cond.grupos and not _casa_lista(ctx.grupos, cond.grupos):
        return False
    if not _casa_prefixo(ctx.ip, cond.ips):
        return False
    if not _casa_glob(ctx.destino, cond.destinos):
        return False
    if cond.tipos_arquivo and (ctx.tipo_arquivo or "").lower() not in \
            [t.lower() for t in cond.tipos_arquivo]:
        return False
    if cond.arquivo_disfarcado is not None and ctx.disfarcado != cond.arquivo_disfarcado:
        return False
    if cond.indice_edm and not _casa_lista(ctx.indices_edm, cond.indice_edm):
        return False
    if cond.indice_idm and not _casa_lista(ctx.indices_idm, cond.indice_idm):
        return False
    if cond.classe_estatistica and (ctx.classe_estatistica or "").upper() not in \
            [c.upper() for c in cond.classe_estatistica]:
        return False

    momento = ctx.momento or datetime.now()
    if cond.dias_semana and momento.weekday() not in cond.dias_semana:
        return False
    if cond.horario_inicio and cond.horario_fim:
        agora = momento.time()
        ini = _hora(cond.horario_inicio)
        fim = _hora(cond.horario_fim)
        dentro = (ini <= agora <= fim) if ini <= fim else (agora >= ini or agora <= fim)
        if not dentro:
            return False
    return True


def _hora(txt: str) -> time:
    h, _, m = txt.partition(":")
    return time(int(h), int(m or 0))
