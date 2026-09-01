# -*- coding: utf-8 -*-
"""As dez acoes da politica, uma a uma: o que executa e o que degrada.

Este arquivo e' a resposta direta ao item 1 do PENDENCIAS.md, que listava
QUARENTENAR, NOTIFICAR_USUARIO, NOTIFICAR_ADMIN, ORIENTAR e CRIPTOGRAFAR como
ENCENACAO -- nome na lista, nenhum codigo executando.
"""
from __future__ import annotations

import os

from testes.apoio import (Temporario, caso, certo, contem, igual, levanta,
                          montar_servico, nao_contem)

TEXTO_SENSIVEL = ("Encaminho a ficha do servidor. O CPF informado para deposito "
                  "da folha e' 529.982.247-25 e o telefone de contato "
                  "e' (11) 98888-7777.")


def _incidente(**campos):
    base = {"identificador": "inc-teste", "canal": "DOWNLOAD",
            "usuario": "maria.souza", "nome_arquivo": "ficha.txt",
            "tipo_arquivo": "txt", "mime": "text/plain", "severidade": "ALTA",
            "classificacao": "SIGILOSO", "regra": "R1", "regra_nome": "Regra 1",
            "momento": "2026-08-31T12:00:00+00:00", "evidencia": [],
            "extracao_completa": True}
    base.update(campos)
    return base


def _ocorrencias(texto):
    from motor.deteccao import Varredura
    return [o for a in Varredura().varrer(texto) for o in a.ocorrencias]


@caso("acao QUARENTENAR: retem o conteudo no cofre e impede a saida")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        conteudo = TEXTO_SENSIVEL.encode("utf-8")
        r = p["executor"].aplicar(
            ["QUARENTENAR"], True, conteudo, TEXTO_SENSIVEL,
            _ocorrencias(TEXTO_SENSIVEL), _incidente(),
            {"usuario": "maria.souza", "canal": "DOWNLOAD",
             "nome_arquivo": "ficha.txt", "recurso": "/docs/ficha.txt"})
        certo(not r.permitido, "quarentena tem de impedir a saida")
        certo(r.quarentena, "quarentena tem de devolver o identificador do item")
        item = p["quarentena"].obter(r.quarentena)
        igual(item["estado"], "RETIDO", "o item nasce RETIDO")
        igual(item["usuario"], "maria.souza", "custodia registra quem tentou")
        # A prova de que RETEVE, e nao apenas bloqueou: o conteudo volta.
        igual(p["quarentena"].conteudo(r.quarentena), conteudo,
              "o conteudo retido tem de ser restauravel byte a byte")


@caso("acao QUARENTENAR: liberar cria autorizacao que faz a proxima passar")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        r = p["executor"].aplicar(
            ["QUARENTENAR"], True, b"conteudo", "texto", [], _incidente(),
            {"usuario": "joao.lima", "canal": "DOWNLOAD",
             "recurso": "/docs/x.txt"})
        p["quarentena"].liberar(r.quarentena, "ana.fiscal",
                                "documento publico, falso positivo")
        igual(p["quarentena"].obter(r.quarentena)["estado"], "LIBERADO",
              "o item passa a LIBERADO")
        p["liberacoes"].criar("ana.fiscal", "joao.lima", "/docs/x.txt",
                              "DOWNLOAD", "documento publico")
        certo(p["liberacoes"].valida_para("joao.lima", "/docs/x.txt", "DOWNLOAD"),
              "a liberacao tem de valer para o mesmo usuario e recurso")
        certo(not p["liberacoes"].valida_para("outro.usuario", "/docs/x.txt",
                                              "DOWNLOAD"),
              "liberacao nao pode valer para outro usuario")
        certo(not p["liberacoes"].valida_para("joao.lima", "/docs/OUTRO.txt",
                                              "DOWNLOAD"),
              "liberacao nao pode valer para outro recurso")


@caso("acao QUARENTENAR: liberar sem justificativa e' recusado")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        r = p["executor"].aplicar(["QUARENTENAR"], True, b"x", "x", [],
                                  _incidente(), {"usuario": "u"})
        levanta(ValueError,
                lambda: p["quarentena"].liberar(r.quarentena, "ana", "   "),
                "liberacao sem motivo escrito nao pode ser auditada depois")


@caso("acao QUARENTENAR: descartar NAO apaga a prova do cofre")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        r = p["executor"].aplicar(["QUARENTENAR"], True, b"prova", "prova", [],
                                  _incidente(), {"usuario": "u"})
        p["quarentena"].descartar(r.quarentena, "ana", "vazamento confirmado")
        igual(p["quarentena"].obter(r.quarentena)["estado"], "DESCARTADO",
              "estado tem de mudar")
        igual(p["quarentena"].conteudo(r.quarentena), b"prova",
              "apagar o objeto destruiria a prova do proprio incidente")


@caso("acao MASCARAR: em texto, o conteudo entregue sai redigido")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        r = p["executor"].aplicar(
            ["MASCARAR"], True, TEXTO_SENSIVEL.encode("utf-8"), TEXTO_SENSIVEL,
            _ocorrencias(TEXTO_SENSIVEL), _incidente(tipo_arquivo="txt"),
            {"usuario": "u", "nome_arquivo": "ficha.txt"})
        certo(r.permitido, "mascarar nao bloqueia")
        contem("MASCARAR", r.acoes_executadas, "a acao tem de constar")
        certo(r.conteudo is not None,
              "sem conteudo transformado, o portal entregaria o arquivo inteiro "
              "-- que era exatamente o defeito")
        saida = r.conteudo.decode("utf-8")
        nao_contem("529.982.247-25", saida, "o CPF nao pode sair em claro")
        contem("***", saida, "tem de haver mascara visivel no lugar")


@caso("acao MASCARAR: em PDF nao se aplica e DEGRADA para BLOQUEAR")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_, acao_nao_aplicavel="BLOQUEAR")
        r = p["executor"].aplicar(
            ["MASCARAR"], True, b"%PDF-1.4 conteudo binario", TEXTO_SENSIVEL,
            _ocorrencias(TEXTO_SENSIVEL), _incidente(tipo_arquivo="pdf"),
            {"usuario": "u", "nome_arquivo": "ficha.pdf"})
        certo(not r.permitido,
              "redigir dentro de um PDF entregaria arquivo corrompido; a saida "
              "honesta e' fechar a porta e dizer por que")
        igual(len(r.acoes_nao_aplicaveis), 1, "tem de registrar a degradacao")
        igual(r.acoes_nao_aplicaveis[0]["acao"], "MASCARAR", "qual acao caiu")
        contem("pdf", r.acoes_nao_aplicaveis[0]["motivo"], "o motivo diz o formato")
        contem("degradado para BLOQUEAR", r.motivo, "o motivo final e' explicito")


@caso("acao MASCARAR: politica pode escolher seguir mesmo sem poder mascarar")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_, acao_nao_aplicavel="PERMITIR")
        r = p["executor"].aplicar(
            ["MASCARAR"], True, b"%PDF-1.4 binario", TEXTO_SENSIVEL,
            _ocorrencias(TEXTO_SENSIVEL), _incidente(tipo_arquivo="pdf"),
            {"usuario": "u", "nome_arquivo": "f.pdf"})
        certo(r.permitido, "com DLP_ACAO_NAO_APLICAVEL=PERMITIR, segue")
        certo(r.conteudo is None, "mas NAO pode dizer que mascarou")
        nao_contem("MASCARAR", r.acoes_executadas,
                   "acao nao cumprida nao entra na lista de executadas")


@caso("acao CRIPTOGRAFAR: produz ZIP que o 7z abre, e a senha vai por e-mail")
def _():
    import subprocess
    with Temporario() as dir_:
        from acoes.notificacao import ConfiguracaoCorreio
        p = montar_servico(dir_, correio=ConfiguracaoCorreio(
            host="", dominio_padrao="pmeto.local",
            administradores=("seguranca@pmeto.local",)))
        original = TEXTO_SENSIVEL.encode("utf-8")
        r = p["executor"].aplicar(
            ["CRIPTOGRAFAR"], True, original, TEXTO_SENSIVEL,
            _ocorrencias(TEXTO_SENSIVEL), _incidente(tipo_arquivo="txt"),
            {"usuario": "maria.souza", "nome_arquivo": "ficha.txt"})
        certo(r.permitido, "cifrar entrega, nao barra")
        contem("CRIPTOGRAFAR", r.acoes_executadas, "acao executada")
        igual(r.mime, "application/zip", "a saida e' um ZIP")
        igual(r.nome_arquivo, "ficha.txt.zip", "o nome ganha .zip")
        nao_contem(b"529.982.247-25", r.conteudo, "o ZIP nao pode ter o claro")

        # A senha NAO esta' na resposta: ela vai por outro canal.
        avisos = p["repo"].notificacoes({"tipo": "USUARIO"})
        certo(avisos, "a senha tem de ser enviada ao usuario")
        senha_no_aviso = [a for a in avisos if "Senha do arquivo" in a["assunto"]]
        certo(senha_no_aviso, "tem de haver um aviso com a senha")
        corpo = senha_no_aviso[0]["corpo"]
        senha = [l.strip() for l in corpo.splitlines() if l.startswith("    ")][0]

        caminho = os.path.join(dir_, "saida.zip")
        with open(caminho, "wb") as f:
            f.write(r.conteudo)
        saiu = os.path.join(dir_, "saiu")
        subprocess.run(["7z", "x", f"-o{saiu}", f"-p{senha}", caminho],
                       capture_output=True, check=True)
        with open(os.path.join(saiu, "ficha.txt"), "rb") as f:
            igual(f.read(), original,
                  "a senha do aviso tem de abrir o arquivo entregue")


@caso("acao CRIPTOGRAFAR: mascara ANTES de cifrar")
def _():
    import subprocess
    with Temporario() as dir_:
        p = montar_servico(dir_)
        r = p["executor"].aplicar(
            ["MASCARAR", "CRIPTOGRAFAR"], True, TEXTO_SENSIVEL.encode("utf-8"),
            TEXTO_SENSIVEL, _ocorrencias(TEXTO_SENSIVEL),
            _incidente(tipo_arquivo="txt"),
            {"usuario": "u", "nome_arquivo": "ficha.txt"})
        avisos = [a for a in p["repo"].notificacoes({"tipo": "USUARIO"})
                  if "Senha do arquivo" in a["assunto"]]
        senha = [l.strip() for l in avisos[0]["corpo"].splitlines()
                 if l.startswith("    ")][0]
        caminho = os.path.join(dir_, "s.zip")
        with open(caminho, "wb") as f:
            f.write(r.conteudo)
        saiu = os.path.join(dir_, "saiu")
        subprocess.run(["7z", "x", f"-o{saiu}", f"-p{senha}", caminho],
                       capture_output=True, check=True)
        with open(os.path.join(saiu, "ficha.txt"), "rb") as f:
            dentro = f.read().decode("utf-8")
        nao_contem("529.982.247-25", dentro,
                   "o envelope tem de guardar a versao JA' redigida")


@caso("acao NOTIFICAR_USUARIO e NOTIFICAR_ADMIN: avisos entram na fila")
def _():
    with Temporario() as dir_:
        from acoes.notificacao import ConfiguracaoCorreio
        p = montar_servico(dir_, correio=ConfiguracaoCorreio(
            host="relay.interno", administradores=("seguranca@pmeto.local",
                                                   "cso@pmeto.local"),
            dominio_padrao="pmeto.local"))
        r = p["executor"].aplicar(
            ["BLOQUEAR", "NOTIFICAR_USUARIO", "NOTIFICAR_ADMIN"], True,
            TEXTO_SENSIVEL.encode("utf-8"), TEXTO_SENSIVEL,
            _ocorrencias(TEXTO_SENSIVEL), _incidente(), {"usuario": "maria.souza"})
        igual(len(r.notificacoes), 3,
              "um aviso ao usuario e um para cada administrador")
        fila = p["repo"].notificacoes()
        destinos = sorted(a["destinatario"] for a in fila)
        igual(destinos, ["cso@pmeto.local", "maria.souza@pmeto.local",
                         "seguranca@pmeto.local"],
              "o endereco do usuario sai de login + dominio padrao")
        for aviso in fila:
            nao_contem("529.982.247-25", aviso["corpo"],
                       "aviso de DLP com o CPF dentro seria o proprio vazamento")


@caso("acao NOTIFICAR: sem endereco, o aviso fica em FALHA visivel")
def _():
    with Temporario() as dir_:
        from acoes.notificacao import ConfiguracaoCorreio
        p = montar_servico(dir_, correio=ConfiguracaoCorreio(
            host="relay.interno", administradores=(), dominio_padrao=""))
        p["executor"].aplicar(["NOTIFICAR_USUARIO", "NOTIFICAR_ADMIN"], True,
                              b"x", "x", [], _incidente(), {"usuario": ""})
        fila = p["repo"].notificacoes()
        igual(len(fila), 2, "os dois avisos tem de existir como registro")
        for aviso in fila:
            igual(aviso["estado"], "FALHA",
                  "descartar em silencio produziria a mesma tela vazia de antes")
            certo(aviso["ultimo_erro"], "o motivo tem de estar escrito")


@caso("acao ORIENTAR: manda o texto da regra, nao uma repreensao generica")
def _():
    with Temporario() as dir_:
        from acoes.notificacao import ConfiguracaoCorreio
        p = montar_servico(dir_, correio=ConfiguracaoCorreio(
            host="relay", dominio_padrao="pmeto.local"))
        r = p["executor"].aplicar(
            ["ORIENTAR"], True, b"x", TEXTO_SENSIVEL,
            _ocorrencias(TEXTO_SENSIVEL),
            _incidente(orientacao="Use o compartilhamento do portal, com "
                                  "permissao nominal."),
            {"usuario": "maria.souza"})
        contem("ORIENTAR", r.acoes_executadas, "acao executada")
        contem("compartilhamento do portal", r.orientacao,
               "a orientacao da regra tem de prevalecer sobre o texto padrao")
        avisos = [a for a in p["repo"].notificacoes({"tipo": "ORIENTACAO"})]
        igual(len(avisos), 1, "um e-mail de orientacao")
        contem("compartilhamento do portal", avisos[0]["corpo"],
               "o texto da regra tem de chegar ao usuario")


@caso("acao REVISAO_MANUAL: bloqueia agora e a aprovacao libera depois")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from incidentes.modelo import Incidente
        inc = Incidente(canal="DOWNLOAD", usuario="pedro.alves",
                        recurso="/docs/edital.txt", estado="EM_ANALISE",
                        severidade="ALTA")
        p["repo"].salvar(inc)
        fila = p["liberacoes"].fila()
        igual(len(fila), 1, "o incidente tem de aparecer na fila de revisao")
        resultado = p["liberacoes"].aprovar(inc.identificador, "ana.fiscal",
                                            "conferido: documento ja' publicado")
        igual(resultado["incidente"]["estado"], "RESOLVIDO",
              "aprovar encerra o incidente")
        certo(p["liberacoes"].valida_para("pedro.alves", "/docs/edital.txt",
                                          "DOWNLOAD"),
              "sem a liberacao, 'encaminhado para revisao' seria bloqueio eterno")
        igual(len(p["liberacoes"].fila()), 0, "a fila esvazia")


@caso("liberacao: expira, conta usos e nao aceita prazo absurdo")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["liberacoes"].criar("ana", "pedro", "/a.txt", "DOWNLOAD", "ok",
                              horas=1, teto_usos=1)
        certo(p["liberacoes"].valida_para("pedro", "/a.txt", "DOWNLOAD"),
              "vale na primeira vez")
        alvo = p["liberacoes"].valida_para("pedro", "/a.txt", "DOWNLOAD")
        p["liberacoes"].consumir(alvo["identificador"])
        certo(not p["liberacoes"].valida_para("pedro", "/a.txt", "DOWNLOAD"),
              "consumida uma vez, nao vale de novo: o que foi autorizado foi "
              "uma transferencia, nao um canal aberto")
        levanta(ValueError,
                lambda: p["liberacoes"].criar("ana", "pedro", "/b", "", "x",
                                              horas=10000),
                "prazo alem de 30 dias e' excecao que ninguem lembra de ter criado")
        levanta(ValueError,
                lambda: p["liberacoes"].criar("ana", "", "/b", "", "x"),
                "liberacao sem usuario valeria para qualquer um")


@caso("liberacao: revogar tira o efeito na hora")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        criada = p["liberacoes"].criar("ana", "pedro", "/a.txt", "DOWNLOAD",
                                       "motivo", horas=24, teto_usos=5)
        certo(p["liberacoes"].revogar(criada["identificador"], "ana"),
              "revogacao tem de ocorrer")
        certo(not p["liberacoes"].valida_para("pedro", "/a.txt", "DOWNLOAD"),
              "revogada nao pode continuar valendo")
