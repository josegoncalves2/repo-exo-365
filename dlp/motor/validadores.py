# -*- coding: utf-8 -*-
"""Validadores por digito verificador.

POR QUE ISTO E' O CORACAO DO MOTOR: casar "\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}" acha
CPF e acha tambem numero de nota fiscal, codigo de patrimonio e data com ponto.
Um DLP que dispara em qualquer coisa com essa forma vira ruido, o operador
desliga, e ai nao ha DLP nenhum. O digito verificador e' o que separa "tem a
forma de" de "e'".

Nenhuma funcao aqui lanca excecao: entrada torta devolve False.
"""
from __future__ import annotations

import re

_SO_DIGITOS = re.compile(r"\D")


def _digitos(valor: str) -> str:
    return _SO_DIGITOS.sub("", valor or "")


def _todos_iguais(d: str) -> bool:
    return len(set(d)) <= 1


def cpf(valor: str) -> bool:
    """CPF: dois digitos verificadores, modulo 11, pesos decrescentes."""
    d = _digitos(valor)
    if len(d) != 11 or _todos_iguais(d):
        return False
    for tamanho in (9, 10):
        soma = sum(int(d[i]) * (tamanho + 1 - i) for i in range(tamanho))
        resto = (soma * 10) % 11
        if resto == 10:
            resto = 0
        if resto != int(d[tamanho]):
            return False
    return True


def cnpj(valor: str) -> bool:
    """CNPJ: modulo 11 com pesos ciclicos 2..9."""
    d = _digitos(valor)
    if len(d) != 14 or _todos_iguais(d):
        return False
    for tamanho in (12, 13):
        pesos = [((tamanho - 1 - i) % 8) + 2 for i in range(tamanho)]
        soma = sum(int(d[i]) * pesos[i] for i in range(tamanho))
        resto = soma % 11
        esperado = 0 if resto < 2 else 11 - resto
        if esperado != int(d[tamanho]):
            return False
    return True


def luhn(valor: str) -> bool:
    """Luhn (ISO/IEC 7812). Cartao de credito e outros identificadores."""
    d = _digitos(valor)
    if len(d) < 12 or len(d) > 19 or _todos_iguais(d):
        return False
    soma, alternar = 0, False
    for c in reversed(d):
        n = int(c)
        if alternar:
            n *= 2
            if n > 9:
                n -= 9
        soma += n
        alternar = not alternar
    return soma % 10 == 0


def pis_pasep(valor: str) -> bool:
    """PIS/PASEP/NIT: modulo 11, pesos 3,2,9,8,7,6,5,4,3,2."""
    d = _digitos(valor)
    if len(d) != 11 or _todos_iguais(d):
        return False
    pesos = [3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
    soma = sum(int(d[i]) * pesos[i] for i in range(10))
    resto = 11 - (soma % 11)
    if resto >= 10:
        resto = 0
    return resto == int(d[10])


def titulo_eleitor(valor: str) -> bool:
    """Titulo de eleitor: 2 DV, com os pesos e a regra de UF (codigos 01..28)."""
    d = _digitos(valor)
    if len(d) not in (12,) or _todos_iguais(d):
        return False
    sequencial, uf, dv = d[:8], d[8:10], d[10:]
    if not ("01" <= uf <= "28"):
        return False
    soma = sum(int(sequencial[i]) * (i + 2) for i in range(8))
    d1 = soma % 11
    if d1 == 10:
        d1 = 0
    if d1 == 0 and uf in ("01", "02"):
        d1 = 1
    soma2 = int(uf[0]) * 7 + int(uf[1]) * 8 + d1 * 9
    d2 = soma2 % 11
    if d2 == 10:
        d2 = 0
    if d2 == 0 and uf in ("01", "02"):
        d2 = 1
    return dv == f"{d1}{d2}"


def cnh(valor: str) -> bool:
    """CNH: 11 digitos, dois DV por modulo 11 com pesos decrescentes/crescentes."""
    d = _digitos(valor)
    if len(d) != 11 or _todos_iguais(d):
        return False
    soma = sum(int(d[i]) * (9 - i) for i in range(9))
    dsc = 0
    d1 = soma % 11
    if d1 >= 10:
        d1, dsc = 0, 2
    soma2 = sum(int(d[i]) * (1 + i) for i in range(9))
    d2 = soma2 % 11
    if d2 >= 10:
        d2 = 0
    d2 = d2 - dsc if d2 - dsc >= 0 else 0
    return int(d[9]) == d1 and int(d[10]) == d2


def cep(valor: str) -> bool:
    """CEP nao tem DV. Exige a forma pontuada para nao casar qualquer 8 digitos."""
    return bool(re.fullmatch(r"\d{5}-\d{3}", (valor or "").strip()))


def iban(valor: str) -> bool:
    """IBAN: modulo 97 sobre a string rearranjada (ISO 13616)."""
    v = re.sub(r"\s", "", (valor or "")).upper()
    if not re.fullmatch(r"[A-Z]{2}\d{2}[A-Z0-9]{10,30}", v):
        return False
    girado = v[4:] + v[:4]
    numero = "".join(str(int(c, 36)) for c in girado)
    return int(numero) % 97 == 1


def renavam(valor: str) -> bool:
    """RENAVAM: 11 digitos, DV por modulo 11 com pesos 3,2,9,8,7,6,5,4,3,2."""
    d = _digitos(valor)
    if len(d) != 11 or _todos_iguais(d):
        return False
    pesos = [3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
    soma = sum(int(d[i]) * pesos[i] for i in range(10))
    dv = (soma * 10) % 11
    if dv == 10:
        dv = 0
    return dv == int(d[10])


def cns(valor: str) -> bool:
    """Cartao Nacional de Saude: modulo 11 sobre 15 digitos, pesos 15..1."""
    d = _digitos(valor)
    if len(d) != 15 or _todos_iguais(d):
        return False
    soma = sum(int(d[i]) * (15 - i) for i in range(15))
    return soma % 11 == 0


SEM_VALIDADOR = lambda _v: True  # noqa: E731 - detector que so' depende da forma

REGISTRO = {
    "cpf": cpf, "cnpj": cnpj, "luhn": luhn, "pis_pasep": pis_pasep,
    "titulo_eleitor": titulo_eleitor, "cnh": cnh, "cep": cep, "iban": iban,
    "renavam": renavam, "cns": cns, "nenhum": SEM_VALIDADOR,
}
