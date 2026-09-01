# -*- coding: utf-8 -*-
"""Acoes de resposta com EFEITO REAL.

Este pacote existe por causa de um defeito confessado em PENDENCIAS.md: a
politica declarava dez acoes e o codigo executava duas. Nome numa lista sem
codigo que o execute e' encenacao -- parece recurso, e nao e'. Cada modulo
aqui existe para que uma dessas acoes deixe de ser nome:

  quarentena  -> retem o conteudo em cofre cifrado, com caminho de restauracao
  liberacao   -> revisao manual que termina em alguem podendo baixar de novo
  notificacao -> e-mail que sai de verdade, com fila persistente e reenvio
  cripto      -> ZIP AES-256 e S/MIME de verdade, verificaveis por terceiro
  executor    -> aplica as acoes na ordem certa e devolve o que mudou
"""
