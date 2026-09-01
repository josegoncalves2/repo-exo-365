# -*- coding: utf-8 -*-
"""Suite propria do motor de DLP.

PENDENCIAS.md, item 10: "o motor tem 3.207 linhas sem um teste automatizado
dele mesmo. O que existe e' o teste de navegador, que cobre o caminho de
download e o console -- e mais nada."

Este pacote fecha esse buraco. Regras que a suite segue:

  * SEM pytest e sem qualquer dependencia de teste. O motor nao tem
    dependencia de terceiro no caminho de decisao; a suite dele tambem nao
    tem. Assim ela roda DENTRO do build da imagem, como portao, e nao apenas
    na maquina de quem escreveu.
  * NADA de rede externa e nada de servico de pe'. Os testes de ICAP e SMTP
    sobem o proprio servidor em porta efemera e falam o protocolo de verdade
    pelo socket -- que e' a primeira vez que esses dois canais sao
    exercitados ponta a ponta.
  * VERIFICACAO INDEPENDENTE onde ela e' possivel: o ZIP cifrado e' aberto
    pelo 7z, e o S/MIME e' decifrado pela chave privada. Um teste que so'
    confere o proprio codigo contra si mesmo prova apenas que ele e'
    consistente, nao que ele funciona.
"""
