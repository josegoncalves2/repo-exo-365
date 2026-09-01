# -*- coding: utf-8 -*-
"""Deteccao, validadores, mascara, extracao e SIEM."""
from __future__ import annotations

import io
import zipfile

from testes.apoio import caso, certo, contem, igual, nao_contem

CPF_VALIDO = "529.982.247-25"


@caso("validadores: aceitam o valido e recusam o que so' parece")
def _():
    from motor import validadores
    certo(validadores.cpf("529.982.247-25"), "CPF valido")
    certo(not validadores.cpf("111.111.111-11"),
          "sequencia repetida nao e' CPF, ainda que feche o digito")
    certo(not validadores.cpf("529.982.247-26"), "digito errado")
    certo(validadores.cnpj("11.222.333/0001-81"), "CNPJ valido")
    certo(not validadores.cnpj("11.222.333/0001-82"), "digito errado")
    certo(validadores.luhn("4111 1111 1111 1111"), "cartao valido por Luhn")
    certo(not validadores.luhn("4111 1111 1111 1112"), "Luhn tem de recusar")


@caso("deteccao: contexto obrigatorio evita a enxurrada de falso positivo")
def _():
    from motor.deteccao import Varredura
    v = Varredura()
    com = v.varrer("Pagamento no cartao 4111 1111 1111 1111, bandeira visa.")
    contem("CARTAO_CREDITO", [a.rotulo for a in com],
           "com a palavra do contexto, e' cartao")
    sem = v.varrer("Protocolo 4111 1111 1111 1111 aberto no sistema.")
    nao_contem("CARTAO_CREDITO", [a.rotulo for a in sem],
               "sem contexto, 16 digitos validos por Luhn sao qualquer numero; "
               "alertar aqui e' o caminho para o operador desligar o DLP")


@caso("deteccao: CPF nao exige a palavra 'CPF' por perto")
def _():
    from motor.deteccao import Varredura
    achados = Varredura().varrer(
        f"O servidor informou {CPF_VALIDO} para deposito da folha.")
    contem("CPF", [a.rotulo for a in achados],
           "era o defeito da instalacao anterior ao contrario: casava a PALAVRA "
           "e deixava passar o NUMERO")


@caso("mascara: a vizinhanca inteira e' mascarada, nao so' o achado alvo")
def _():
    from motor.deteccao import Varredura
    from motor.mascara import trecho_mascarado
    texto = (f"CPF {CPF_VALIDO} e cartao 4111 1111 1111 1111 da bandeira visa "
             "no mesmo paragrafo.")
    achados = Varredura().varrer(texto)
    cpf = next(a for a in achados if a.rotulo == "CPF")
    o = cpf.ocorrencias[0]
    trecho = trecho_mascarado(texto, o.inicio, o.fim, "CPF")
    nao_contem(CPF_VALIDO, trecho, "o alvo tem de sair mascarado")
    nao_contem("4111 1111 1111 1111", trecho,
               "o cartao vizinho vazaria pela janela de contexto do CPF")


@caso("mascara: redigir troca TODAS as ocorrencias e preserva o resto")
def _():
    from motor.deteccao import Varredura
    from motor.mascara import redigir
    texto = f"Primeiro {CPF_VALIDO}, depois 529.982.247-25 de novo. Fim."
    ocorrencias = [o for a in Varredura().varrer(texto) for o in a.ocorrencias]
    saida = redigir(texto, ocorrencias)
    nao_contem(CPF_VALIDO, saida, "nenhuma ocorrencia pode sobrar")
    contem("Primeiro", saida, "o texto ao redor tem de continuar legivel")
    contem("Fim.", saida, "o fim do texto nao pode ser cortado")


@caso("extracao: tipo real por assinatura desmascara extensao trocada")
def _():
    from motor import extracao
    memoria = io.BytesIO()
    with zipfile.ZipFile(memoria, "w") as z:
        z.writestr("word/document.xml", f"<w:t>CPF {CPF_VALIDO}</w:t>")
    dados = memoria.getvalue()
    r = extracao.extrair(dados, "relatorio.txt")
    certo(r.disfarcado,
          "arquivo docx com nome .txt tem de ser apontado como disfarcado")
    contem("difere do tipo real", r.motivo_parcial, "o motivo tem de dizer isso")


@caso("extracao: le dentro do compactado, recursivamente")
def _():
    from motor import extracao
    interno = io.BytesIO()
    with zipfile.ZipFile(interno, "w") as z:
        z.writestr("ficha.txt", f"CPF {CPF_VALIDO}")
    externo = io.BytesIO()
    with zipfile.ZipFile(externo, "w") as z:
        z.writestr("pacote.zip", interno.getvalue())
    r = extracao.extrair(externo.getvalue(), "envio.zip")
    contem(CPF_VALIDO, r.conteudo,
           "compactado dentro de compactado e' o esconderijo mais obvio")


@caso("extracao: compactado cifrado nao vira 'limpo'")
def _():
    from acoes.cripto import zip_aes256
    from motor import extracao
    # ZIP AES de verdade, do mesmo gerador que a acao CRIPTOGRAFAR usa. E' o
    # que aparece num anexo real quando alguem quer esconder o conteudo -- e a
    # biblioteca padrao nao sabe abrir metodo 99.
    bruto = zip_aes256("segredo.txt", f"CPF {CPF_VALIDO}".encode(), "senha-x")
    r = extracao.extrair(bruto, "protegido.zip")
    certo(not r.completo, "'nao consegui ler' nunca pode virar 'esta limpo'")
    certo(r.motivo_parcial, "o motivo tem de estar escrito")
    nao_contem(CPF_VALIDO, r.conteudo, "e nada pode ter sido lido de dentro")


@caso("extracao: um item ilegivel nao apaga os legiveis do mesmo pacote")
def _():
    from acoes.cripto import zip_aes256
    from motor import extracao
    memoria = io.BytesIO()
    with zipfile.ZipFile(memoria, "w") as z:
        z.writestr("aberto.txt", f"Ficha com CPF {CPF_VALIDO}")
        z.writestr("fechado.zip", zip_aes256("x.txt", b"outro", "senha"))
    r = extracao.extrair(memoria.getvalue(), "misto.zip")
    contem(CPF_VALIDO, r.conteudo,
           "o item legivel tinha de ser lido: antes, a excecao do item cifrado "
           "escapava do laco e descartava o pacote inteiro")
    certo(not r.completo, "e o pacote continua marcado como parcial")


@caso("extracao: executavel nao e' texto e diz isso")
def _():
    from motor import extracao
    r = extracao.extrair(b"\x7fELF\x02\x01\x01" + b"\x00" * 200, "programa.bin")
    certo(not r.completo, "binario nao pode ser dado por lido")
    contem("binario executavel", r.motivo_parcial, "o motivo tem de ser claro")


@caso("SIEM: CEF e LEEF saem no formato que o coletor espera, sem o valor")
def _():
    from integracao.siem import para_cef, para_leef
    incidente = {"identificador": "abc-123", "severidade": "ALTA",
                 "canal": "DOWNLOAD", "usuario": "maria.souza",
                 "ip": "192.168.1.77", "destino": "gmail.com",
                 "regra_nome": "CPF nao sai", "classificacao": "SIGILOSO",
                 "nome_arquivo": "ficha.txt", "permitido": False,
                 "evidencia": [{"rotulo": "CPF", "quantidade": 1,
                                "amostras": [{"trecho": "***.***.***-25"}]}]}
    cef = para_cef(incidente)
    contem("CEF:0|", cef, "cabecalho CEF")
    contem("maria.souza", cef, "o usuario e' o que o analista procura")
    nao_contem(CPF_VALIDO, cef, "o SIEM nao pode receber o valor")
    leef = para_leef(incidente)
    contem("LEEF:", leef, "cabecalho LEEF")
    nao_contem(CPF_VALIDO, leef, "idem para LEEF")


@caso("classificador estatistico: aprende e separa as classes")
def _():
    from motor.estatistica import ClassificadorBayes
    c = ClassificadorBayes()
    c.treinar("SIGILOSO", "processo administrativo disciplinar sindicancia "
                          "servidor penalidade suspensao apuracao")
    c.treinar("PUBLICO", "cronograma de pavimentacao asfalto bairro obra "
                         "licitacao publica edital de obras")
    r = c.classificar("sindicancia do servidor com apuracao de penalidade")
    igual(r.get("classe"), "SIGILOSO", "o texto disciplinar e' sigiloso")
    r2 = c.classificar("edital de obras de pavimentacao do bairro")
    igual(r2.get("classe"), "PUBLICO", "o texto de obra e' publico")


@caso("classificador estatistico: sobrevive a exportar e importar")
def _():
    from motor.estatistica import ClassificadorBayes
    c = ClassificadorBayes()
    c.treinar("SIGILOSO", "sindicancia servidor penalidade apuracao processo")
    c.treinar("PUBLICO", "edital obras pavimentacao cronograma licitacao")
    outro = ClassificadorBayes.importar(c.exportar())
    igual(outro.classificar("sindicancia do servidor").get("classe"),
          c.classificar("sindicancia do servidor").get("classe"),
          "o modelo tem de sobreviver ao reinicio do container")
