# -*- coding: utf-8 -*-
"""Modelos de politica prontos, por norma. Ponto de partida, nao camisa de forca.

Cada modelo nasce com a acao mais branda que ainda cumpre a norma, e o console
permite subir. Nascer em BLOQUEAR travaria o trabalho no primeiro dia e a
politica seria desligada inteira -- que e' pior do que politica branda.
"""
from __future__ import annotations

from typing import List

from politica.modelo import Condicao, Excecao, Regra

CANAIS_SAIDA = ("DOWNLOAD", "LINK_PUBLICO", "COMPARTILHAMENTO_EXTERNO", "EMAIL",
                "CHAT", "NUVEM", "API", "WEBDAV", "IMPRESSAO", "USB",
                "CLIPBOARD", "ENDPOINT", "ICAP")


def catalogo() -> List[Regra]:
    return [
        Regra("PCI-001", "Cartao de pagamento nao sai da plataforma",
              Condicao(rotulos=("CARTAO_CREDITO",), canais=CANAIS_SAIDA,
                       severidade_minima="CRITICA"),
              ("BLOQUEAR", "NOTIFICAR_USUARIO", "NOTIFICAR_ADMIN"),
              severidade="CRITICA", prioridade=10,
              mensagem_usuario="Este conteudo tem numero de cartao de pagamento. "
                               "O envio foi bloqueado pela politica de seguranca.",
              conformidade=("PCI-DSS",)),

        Regra("SEG-001", "Credencial em texto claro nao sai",
              Condicao(rotulos=("SEGREDO_EM_TEXTO_CLARO",), canais=CANAIS_SAIDA),
              ("BLOQUEAR", "NOTIFICAR_ADMIN"),
              severidade="CRITICA", prioridade=11,
              mensagem_usuario="Ha credencial ou chave privada em texto claro "
                               "neste conteudo. O envio foi bloqueado.",
              conformidade=("SOX", "LGPD")),

        Regra("LGPD-001", "Dado pessoal sensivel exige revisao antes de sair",
              Condicao(categorias=("LGPD",), severidade_minima="CRITICA",
                       canais=CANAIS_SAIDA),
              ("REVISAO_MANUAL", "NOTIFICAR_ADMIN"),
              severidade="CRITICA", prioridade=20,
              mensagem_usuario="Este conteudo tem dado pessoal sensivel (art. 5, II "
                               "da LGPD) e foi encaminhado para revisao.",
              conformidade=("LGPD",)),

        Regra("LGPD-002", "Dado pessoal para fora da rede: mascara e registra",
              Condicao(categorias=("PII", "LGPD"), severidade_minima="ALTA",
                       canais=("EMAIL", "LINK_PUBLICO", "COMPARTILHAMENTO_EXTERNO",
                               "NUVEM", "CHAT")),
              ("MASCARAR", "REGISTRAR", "NOTIFICAR_USUARIO", "ORIENTAR"),
              severidade="ALTA", prioridade=30,
              excecoes=[Excecao(grupos=("/platform/administrators",),
                                motivo="administracao da plataforma")],
              mensagem_usuario="Ha dado pessoal neste conteudo. Os numeros foram "
                               "mascarados na copia enviada.",
              conformidade=("LGPD",)),

        Regra("PHI-001", "Dado de saude nao sai sem revisao",
              Condicao(categorias=("PHI", "HIPAA"), canais=CANAIS_SAIDA),
              ("REVISAO_MANUAL", "NOTIFICAR_ADMIN"),
              severidade="CRITICA", prioridade=21,
              mensagem_usuario="Conteudo com dado de saude. Encaminhado para revisao.",
              conformidade=("HIPAA", "LGPD")),

        Regra("EVA-001", "Arquivo com extensao disfarcada nao sai",
              Condicao(arquivo_disfarcado=True, canais=CANAIS_SAIDA),
              ("BLOQUEAR", "NOTIFICAR_ADMIN"),
              severidade="ALTA", prioridade=15,
              mensagem_usuario="A extensao do arquivo nao corresponde ao seu tipo "
                               "real. O envio foi bloqueado.",
              conformidade=("LGPD",)),

        Regra("EDM-001", "Registro do cadastro oficial nao sai",
              Condicao(indice_edm=("folha-pagamento",), canais=CANAIS_SAIDA),
              ("BLOQUEAR", "NOTIFICAR_ADMIN"),
              severidade="CRITICA", prioridade=12,
              mensagem_usuario="Este conteudo contem registro do cadastro oficial "
                               "da prefeitura. O envio foi bloqueado.",
              conformidade=("LGPD",)),

        Regra("IDM-001", "Documento sigiloso registrado nao sai",
              Condicao(indice_idm=("sigilosos",), canais=CANAIS_SAIDA),
              ("BLOQUEAR", "NOTIFICAR_ADMIN"),
              severidade="CRITICA", prioridade=13,
              mensagem_usuario="Este conteudo reproduz documento registrado como "
                               "sigiloso. O envio foi bloqueado.",
              conformidade=("LGPD",)),

        Regra("DESC-001", "Descoberta em repouso apenas classifica",
              Condicao(canais=("DESCOBERTA",), severidade_minima="MEDIA"),
              ("REGISTRAR",),
              severidade="MEDIA", prioridade=200,
              conformidade=("LGPD",)),

        Regra("GER-001", "Qualquer dado pessoal saindo fica registrado",
              Condicao(categorias=("PII", "PCI-DSS", "PHI", "LGPD", "SEGREDO"),
                       canais=CANAIS_SAIDA),
              ("REGISTRAR",),
              severidade="BAIXA", prioridade=900,
              conformidade=("LGPD",)),
    ]
