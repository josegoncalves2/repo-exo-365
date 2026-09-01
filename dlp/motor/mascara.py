# -*- coding: utf-8 -*-
"""Mascaramento. A evidencia NUNCA guarda o valor inteiro.

Um console de DLP que mostra o CPF inteiro vira, ele proprio, um repositorio de
dado pessoal -- e um alvo. O analista precisa de o BASTANTE para localizar e
agir: o tipo, os ultimos digitos e a posicao. Nao precisa do numero.
"""
from __future__ import annotations

import re
from typing import List, Sequence


def _so_visivel_fim(bruto: str, visiveis: int = 2) -> str:
    d = re.sub(r"\D", "", bruto)
    if len(d) <= visiveis:
        return "*" * len(d)
    corpo = re.sub(r"\d", "*", bruto)
    fim, saida, restantes = list(bruto), [], visiveis
    for i in range(len(bruto) - 1, -1, -1):
        if bruto[i].isdigit() and restantes > 0:
            restantes -= 1
            saida.append(bruto[i])
        else:
            saida.append(corpo[i])
    return "".join(reversed(saida))


def mascarar(rotulo: str, bruto: str) -> str:
    if not bruto:
        return ""
    if rotulo == "EMAIL":
        usuario, _, dominio = bruto.partition("@")
        vis = usuario[0] if usuario else ""
        return f"{vis}{'*' * max(len(usuario) - 1, 1)}@{dominio}"
    if rotulo == "SEGREDO_EM_TEXTO_CLARO":
        if "BEGIN" in bruto.upper():
            return "-----BEGIN PRIVATE KEY----- (conteudo suprimido)"
        chave, sep, _valor = bruto.partition(":") if ":" in bruto else bruto.partition("=")
        return f"{chave.strip()}{sep} ********"
    if rotulo in ("DADO_SAUDE", "ORIGEM_RACIAL_RELIGIAO"):
        return bruto            # termo, nao identificador: nao ha o que mascarar
    if rotulo == "CHAVE_PIX_ALEATORIA":
        return bruto[:8] + "-****-****-****-************"
    if rotulo == "IBAN":
        return bruto[:6] + re.sub(r"[A-Z0-9]", "*", bruto[6:-2]) + bruto[-2:]
    return _so_visivel_fim(bruto, 2)


def trecho_mascarado(texto: str, inicio: int, fim: int, rotulo: str,
                     janela: int = 48, todas_ocorrencias=None) -> str:
    """Vizinhanca do achado, com o valor mascarado. E' a evidencia do incidente.

    MASCARA A VIZINHANCA INTEIRA, nao so' o achado alvo. Mascarar apenas o
    valor central deixava vazar, pela janela de contexto de um achado, o valor
    BRUTO de outro que estivesse por perto -- medido: o trecho do CPF exibia o
    numero do cartao de credito em claro. O console de DLP viraria, ele
    proprio, o vazamento.

    `todas_ocorrencias` sao as ocorrencias de TODOS os achados do documento;
    quando nao forem passadas, a vizinhanca e' varrida de novo aqui, para que
    a chamada simples continue segura por padrao.
    """
    if not texto:
        return ""
    ini = max(0, inicio - janela)
    f = min(len(texto), fim + janela)

    if todas_ocorrencias is None:
        from .deteccao import Varredura            # import tardio: evita ciclo
        vizinhas = Varredura().varrer(texto[ini:f])
        pares = [(o.inicio + ini, o.fim + ini, o.rotulo)
                 for a in vizinhas for o in a.ocorrencias]
    else:
        pares = [(o.inicio, o.fim, o.rotulo) for o in todas_ocorrencias
                 if o.fim > ini and o.inicio < f]
    pares.append((inicio, fim, rotulo))

    # Remove sobreposicao: um trecho ja' mascarado nao se mascara de novo.
    pares.sort(key=lambda x: (x[0], -x[1]))
    limpos, ultimo_fim = [], -1
    for a, b, r in pares:
        if a >= ultimo_fim:
            limpos.append((a, b, r))
            ultimo_fim = b

    partes, cursor = [], ini
    for a, b, r in limpos:
        a2, b2 = max(a, ini), min(b, f)
        if b2 <= cursor:
            continue
        partes.append(texto[cursor:a2])
        alvo_atual = (a == inicio and b == fim)
        m = mascarar(r, texto[a:b])
        partes.append(f"[{m}]" if alvo_atual else m)
        cursor = b2
    partes.append(texto[cursor:f])

    corpo = "".join(partes).replace("\n", " ")
    prefixo = "..." if ini > 0 else ""
    sufixo = "..." if f < len(texto) else ""
    return f"{prefixo}{corpo}{sufixo}".strip()


def redigir(texto: str, ocorrencias: Sequence) -> str:
    """Devolve o texto com TODA ocorrencia substituida pela mascara.

    E' a acao de resposta "redacao/mascaramento" -- serve para liberar uma
    versao segura do documento em vez de simplesmente negar.
    """
    if not texto or not ocorrencias:
        return texto
    partes: List[str] = []
    cursor = 0
    for o in sorted(ocorrencias, key=lambda x: x.inicio):
        if o.inicio < cursor:
            continue
        partes.append(texto[cursor:o.inicio])
        partes.append(mascarar(o.rotulo, texto[o.inicio:o.fim]))
        cursor = o.fim
    partes.append(texto[cursor:])
    return "".join(partes)
