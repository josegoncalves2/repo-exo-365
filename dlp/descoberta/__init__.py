# -*- coding: utf-8 -*-
"""Descoberta de dados em REPOUSO -- o canal DESCOBERTA que nao existia.

PENDENCIAS.md registrou o pior achado desta area: o canal DESCOBERTA era um
nome na lista de canais, o modelo de politica DESC-001 falava em varredura de
dados em repouso, e NAO HAVIA CRAWLER NENHUM neste servico. O que varria o
acervo era a extensao antiga `dlp-br`, com outro motor, escrevendo em outro
lugar -- dois DLPs paralelos que nao se falavam.

Este pacote e' o crawler proprio. Ele entra pelo MESMO `ServicoDlp.analisar`
que o download e o e-mail usam, com canal DESCOBERTA, e grava no MESMO banco de
incidentes. Um motor so', uma politica so', um acervo de incidentes so'.
"""
