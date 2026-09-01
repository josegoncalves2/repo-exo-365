# -*- coding: utf-8 -*-
"""Asseveracoes e andaimes. Sem framework, de proposito (ver __init__)."""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
import traceback
from typing import Callable, List, Tuple

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

CASOS: List[Tuple[str, Callable]] = []
_TOTAL = {"asseveracoes": 0}


def caso(nome: str):
    def envolver(f):
        CASOS.append((nome, f))
        return f
    return envolver


class Falha(AssertionError):
    pass


def certo(condicao, mensagem: str) -> None:
    _TOTAL["asseveracoes"] += 1
    if not condicao:
        raise Falha(mensagem)


def igual(obtido, esperado, mensagem: str) -> None:
    _TOTAL["asseveracoes"] += 1
    if obtido != esperado:
        raise Falha(f"{mensagem}\n    esperado: {esperado!r}\n    obtido:   {obtido!r}")


def contem(agulha, palheiro, mensagem: str) -> None:
    _TOTAL["asseveracoes"] += 1
    if agulha not in palheiro:
        raise Falha(f"{mensagem}\n    procurado: {agulha!r}\n    em:        {palheiro!r}")


def nao_contem(agulha, palheiro, mensagem: str) -> None:
    _TOTAL["asseveracoes"] += 1
    if agulha in palheiro:
        raise Falha(f"{mensagem}\n    NAO devia conter: {agulha!r}\n    em: {palheiro!r}")


def levanta(excecao, funcao, mensagem: str):
    _TOTAL["asseveracoes"] += 1
    try:
        funcao()
    except excecao as e:
        return e
    except Exception as e:                                  # noqa: BLE001
        raise Falha(f"{mensagem}\n    levantou {type(e).__name__}: {e}") from e
    raise Falha(f"{mensagem}\n    nao levantou nada")


class Temporario:
    """Diretorio de trabalho apagado ao fim. So' existe dentro do teste."""

    def __enter__(self) -> str:
        self._caminho = tempfile.mkdtemp(prefix="dlp-teste-")
        return self._caminho

    def __exit__(self, *_):
        shutil.rmtree(self._caminho, ignore_errors=True)


# ------------------------------------------------------------------- fabrica
def montar_servico(dados_dir: str, correio=None, acao_nao_aplicavel="BLOQUEAR"):
    """Servico completo, do jeito que `principal.py` monta, so' que em disco
    temporario. Testar contra uma montagem diferente da de producao provaria
    o andaime, nao o produto."""
    from acoes.cripto import RepositorioCertificados
    from acoes.executor import Configuracao as ConfExecutor
    from acoes.executor import Executor
    from acoes.liberacao import Liberacoes
    from acoes.notificacao import ConfiguracaoCorreio, Notificador
    from acoes.quarentena import Quarentena
    from incidentes.repositorio import Repositorio
    from motor.cofre import Cofre, chave_persistente
    from servico import ServicoDlp

    repositorio = Repositorio(os.path.join(dados_dir, "dlp.db"))
    cofre = Cofre(os.path.join(dados_dir, "cofre"),
                  chave_persistente(os.path.join(dados_dir, "chave.bin")))
    quarentena = Quarentena(repositorio, cofre)
    liberacoes = Liberacoes(repositorio)
    certificados = RepositorioCertificados(os.path.join(dados_dir, "certs"))
    conf = correio or ConfiguracaoCorreio(
        host="", administradores=("seguranca@pmeto.local",),
        dominio_padrao="pmeto.local")
    notificador = Notificador(repositorio, conf)
    executor = Executor(quarentena, liberacoes, notificador, certificados,
                        ConfExecutor(acao_nao_aplicavel=acao_nao_aplicavel,
                                     dominio_email=conf.dominio_padrao))
    servico = ServicoDlp(repositorio, b"sal-de-teste-com-32-bytes-exatos!",
                         executor)
    return {"repo": repositorio, "cofre": cofre, "quarentena": quarentena,
            "liberacoes": liberacoes, "certificados": certificados,
            "notificador": notificador, "executor": executor,
            "servico": servico}


def politica_de(*regras):
    """Substitui a politica vigente pelas regras dadas."""
    from politica.motor import MotorPolitica
    return MotorPolitica(list(regras))


def executar_todos() -> int:
    largura = 68
    passou, falhou = 0, 0
    print("=" * largura)
    print(" SUITE DO MOTOR DE DLP")
    print("=" * largura)
    for nome, funcao in CASOS:
        try:
            funcao()
        except Exception as e:                              # noqa: BLE001
            falhou += 1
            print(f"[FALHOU] {nome}")
            if isinstance(e, Falha):
                for linha in str(e).splitlines():
                    print(f"         {linha}")
            else:
                traceback.print_exc()
        else:
            passou += 1
            print(f"[ok]     {nome}")
    print("-" * largura)
    print(f" {passou} caso(s) passaram, {falhou} falharam, "
          f"{_TOTAL['asseveracoes']} asseveracoes")
    print("=" * largura)
    return 0 if falhou == 0 else 1
