# -*- coding: utf-8 -*-
"""O caminho inteiro: extrair -> detectar -> decidir -> EXECUTAR -> registrar."""
from __future__ import annotations

from testes.apoio import (Temporario, caso, certo, contem, igual,
                          montar_servico, nao_contem)

CPF_VALIDO = "529.982.247-25"


def _regra(identificador, nome, acoes, **cond):
    from politica.modelo import Condicao, Regra
    return Regra(identificador, nome, Condicao(**cond), acoes,
                 severidade="ALTA", prioridade=10,
                 mensagem_usuario="Conteudo com dado pessoal.")


@caso("servico: CPF valido SEM a palavra 'CPF' por perto e' detectado")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        texto = f"O servidor informou o numero {CPF_VALIDO} para deposito."
        r = p["servico"].analisar(texto.encode("utf-8"),
                                  Contexto(canal="DOWNLOAD", usuario="u",
                                           nome_arquivo="oficio.txt"))
        rotulos = [e["rotulo"] for e in r["evidencia"]]
        contem("CPF", rotulos, "a deteccao e' do NUMERO, nao da palavra")
        igual(r["severidade"], "ALTA", "CPF e' severidade ALTA")


@caso("servico: numero invalido NAO vira incidente (digito verificador)")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        r = p["servico"].analisar(b"protocolo 111.111.111-11 registrado",
                                  Contexto(canal="DOWNLOAD", usuario="u"))
        igual([e["rotulo"] for e in r["evidencia"]], [],
              "sem validacao de digito, o DLP alerta para todo numero e acaba "
              "desligado")


@caso("servico: a evidencia gravada NUNCA carrega o valor em claro")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        texto = f"CPF {CPF_VALIDO} e cartao 4111 1111 1111 1111 (visa)."
        r = p["servico"].analisar(texto.encode("utf-8"),
                                  Contexto(canal="DOWNLOAD", usuario="u"))
        bruto = str(r["evidencia"])
        nao_contem(CPF_VALIDO, bruto, "o console viraria repositorio de CPF")
        nao_contem("4111 1111 1111 1111", bruto, "cartao em claro na evidencia")
        incidente = p["repo"].obter(r["incidente"])
        nao_contem(CPF_VALIDO, incidente.como_json(), "nem no banco")


@caso("servico: bloqueio -> revisao -> aprovacao -> a MESMA saida passa")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        p["servico"].salvar_politica(
            [_regra("T1", "CPF exige revisao", ("REVISAO_MANUAL",),
                    rotulos=("CPF",), canais=("DOWNLOAD",))], "teste")
        texto = f"Documento com CPF {CPF_VALIDO}."
        alvo = "/docs/ficha.txt"

        primeira = p["servico"].analisar(
            texto.encode("utf-8"),
            Contexto(canal="DOWNLOAD", usuario="pedro.alves"), recurso=alvo)
        certo(not primeira["permitido"], "a primeira tentativa e' barrada")
        contem("REVISAO_MANUAL", primeira["acoes_executadas"], "acao executada")
        igual(p["repo"].obter(primeira["incidente"]).estado, "EM_ANALISE",
              "o incidente nasce na fila de revisao")

        p["liberacoes"].aprovar(primeira["incidente"], "ana.fiscal",
                                "documento ja' e' publico")

        segunda = p["servico"].analisar(
            texto.encode("utf-8"),
            Contexto(canal="DOWNLOAD", usuario="pedro.alves"), recurso=alvo)
        certo(segunda["permitido"],
              "depois de aprovada, a mesma transferencia tem de passar -- e' o "
              "unico jeito de REVISAO_MANUAL nao ser bloqueio permanente")
        certo(segunda["liberacao"], "o incidente registra qual liberacao valeu")
        contem("ana.fiscal", segunda["motivo"], "quem liberou fica escrito")

        terceira = p["servico"].analisar(
            texto.encode("utf-8"),
            Contexto(canal="DOWNLOAD", usuario="pedro.alves"), recurso=alvo)
        certo(not terceira["permitido"],
              "a liberacao valia UMA vez; a terceira volta a ser barrada")


@caso("servico: liberacao de um usuario nao serve para outro")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        p["servico"].salvar_politica(
            [_regra("T2", "bloqueio", ("BLOQUEAR",), rotulos=("CPF",))], "teste")
        p["liberacoes"].criar("ana", "pedro.alves", "/x.txt", "DOWNLOAD", "ok")
        r = p["servico"].analisar(f"CPF {CPF_VALIDO}".encode("utf-8"),
                                  Contexto(canal="DOWNLOAD", usuario="INTRUSO"),
                                  recurso="/x.txt")
        certo(not r["permitido"], "a liberacao e' nominal")


def _docx(texto: str) -> bytes:
    """Documento .docx MINIMO e valido: o extrator le, mas redigir dentro dele
    e devolver o arquivo integro nao e' possivel."""
    import io
    import zipfile
    memoria = io.BytesIO()
    with zipfile.ZipFile(memoria, "w") as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0"?><Types xmlns="http://schemas.openxml'
                   'formats.org/package/2006/content-types"/>')
        z.writestr("word/document.xml",
                   '<?xml version="1.0"?><w:document xmlns:w="http://schemas.'
                   'openxmlformats.org/wordprocessingml/2006/main"><w:body>'
                   f'<w:p><w:r><w:t>{texto}</w:t></w:r></w:p></w:body>'
                   '</w:document>')
    return memoria.getvalue()


@caso("servico: o incidente separa o que a regra PEDIU do que ACONTECEU")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_, acao_nao_aplicavel="BLOQUEAR")
        from politica.modelo import Contexto
        p["servico"].salvar_politica(
            [_regra("T3", "mascara", ("MASCARAR", "REGISTRAR"),
                    rotulos=("CPF",))], "teste")
        # .docx: o texto E' lido (a regra casa), mas redigir dentro do pacote
        # OOXML e devolver o arquivo integro nao e' possivel -- entao a regra
        # pede MASCARAR e o executor degrada, dizendo por que.
        documento = _docx(f"Ficha funcional. CPF {CPF_VALIDO}.")
        r = p["servico"].analisar(documento,
                                  Contexto(canal="DOWNLOAD", usuario="u",
                                           nome_arquivo="ficha.docx"))
        certo(not r["permitido"], "sem poder mascarar, a saida e' negada")
        incidente = p["repo"].obter(r["incidente"])
        contem("MASCARAR", incidente.acoes, "a regra pediu MASCARAR")
        nao_contem("MASCARAR", incidente.acoes_executadas,
                   "e NAO foi possivel cumprir -- o incidente nao pode mentir")
        igual(len(incidente.acoes_nao_aplicaveis), 1,
              "a degradacao tem de estar registrada")
        certo(any(t["acao"] == "ACAO_NAO_APLICAVEL" for t in incidente.trilha),
              "a trilha tem de contar o que aconteceu")


@caso("servico: extracao incompleta nunca vira 'limpo'")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        # PDF sem camada de texto: e' o formato mais comum de documento
        # sensivel digitalizado numa prefeitura.
        pdf = b"%PDF-1.4\n%%EOF\n"
        r = p["servico"].analisar(pdf, Contexto(canal="DOWNLOAD", usuario="u",
                                                nome_arquivo="digitalizado.pdf"))
        certo(not r["extracao_completa"], "a leitura foi parcial")
        certo(r["severidade"] != "NENHUMA",
              "'nao consegui ler' nao pode virar 'esta limpo'")
        certo(r["motivo_parcial"], "o motivo tem de estar escrito")


@caso("servico: simulacao decide sem reter, sem avisar e sem registrar")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        p["servico"].salvar_politica(
            [_regra("T4", "retem", ("QUARENTENAR", "NOTIFICAR_ADMIN"),
                    rotulos=("CPF",))], "teste")
        r = p["servico"].analisar(f"CPF {CPF_VALIDO}".encode("utf-8"),
                                  Contexto(canal="DOWNLOAD", usuario="u"),
                                  registrar=False, efeitos=False)
        certo(not r["permitido"], "a decisao e' a mesma")
        contem("QUARENTENAR", r["acoes_executadas"], "diz o que faria")
        igual(r.get("quarentena", ""), "", "mas NAO retem nada")
        igual(p["repo"].contar_quarentena(), 0, "cofre intocado")
        igual(len(p["repo"].notificacoes()), 0, "nenhum e-mail disparado")
        igual(p["repo"].contar(), 0, "nenhum incidente gravado")


@caso("servico: MODO OBSERVACAO grava o incidente e NAO age")
def _():
    """O modo em que o portal roda enquanto a politica e' dimensionada.

    Era um defeito medido em producao: `registrar` e `efeitos` estavam
    amarrados, entao a observacao RETINHA copia no cofre e mandava e-mail ao
    administrador. "Nada muda para o usuario" nao era verdade.
    """
    with Temporario() as dir_:
        from acoes.notificacao import ConfiguracaoCorreio
        p = montar_servico(dir_, correio=ConfiguracaoCorreio(
            host="relay", administradores=("seguranca@pmeto.local",),
            dominio_padrao="pmeto.local"))
        from politica.modelo import Contexto
        p["servico"].salvar_politica(
            [_regra("T7", "retem e avisa",
                    ("QUARENTENAR", "NOTIFICAR_ADMIN", "MASCARAR"),
                    rotulos=("CPF",))], "teste")
        r = p["servico"].analisar(f"CPF {CPF_VALIDO}".encode("utf-8"),
                                  Contexto(canal="DOWNLOAD", usuario="u",
                                           nome_arquivo="f.txt"),
                                  recurso="/a.txt",
                                  registrar=True, efeitos=False)
        igual(r["modo"], "OBSERVACAO", "o modo tem de estar na resposta")
        certo(r.get("incidente"), "o incidente TEM de ser gravado")
        igual(p["repo"].contar_quarentena(), 0,
              "observacao nao pode encher o cofre")
        igual(len(p["repo"].notificacoes()), 0,
              "observacao nao pode mandar e-mail para ninguem")
        igual(r.get("conteudo_base64", ""), "",
              "entregar arquivo transformado durante a observacao mudaria tudo")
        incidente = p["repo"].obter(r["incidente"])
        igual(incidente.modo, "OBSERVACAO", "o incidente registra o modo")
        igual(incidente.acoes_executadas, [],
              "nada foi executado; dizer o contrario seria mentir no relatorio")
        contem("QUARENTENAR", incidente.acoes_simuladas,
               "e o que SERIA feito fica registrado, para dimensionar")
        certo(any(t["acao"] == "ACAO_SIMULADA" for t in incidente.trilha),
              "a trilha tem de deixar claro que nada aconteceu")
        certo(p["repo"].classificacao_de("/a.txt") is not None,
              "a classificacao do recurso e' mapa, nao acao: vale em observacao")


@caso("servico: excecao de grupo libera sem apagar o registro")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Condicao, Contexto, Excecao, Regra
        p["servico"].salvar_politica([Regra(
            "T5", "bloqueia CPF", Condicao(rotulos=("CPF",)), ("BLOQUEAR",),
            excecoes=[Excecao(grupos=("/platform/administrators",),
                              motivo="administracao da plataforma")])], "teste")
        r = p["servico"].analisar(
            f"CPF {CPF_VALIDO}".encode("utf-8"),
            Contexto(canal="DOWNLOAD", usuario="root",
                     grupos=("/platform/administrators",)))
        certo(r["permitido"], "a excecao tem de valer")
        contem("administracao da plataforma", r["motivo"],
               "o motivo tem de dizer QUAL excecao valeu")


@caso("servico: politica por horario e por canal")
def _():
    with Temporario() as dir_:
        import datetime
        p = montar_servico(dir_)
        from politica.modelo import Condicao, Contexto, Regra
        p["servico"].salvar_politica([Regra(
            "T6", "madrugada", Condicao(rotulos=("CPF",), canais=("EMAIL",),
                                        horario_inicio="00:00",
                                        horario_fim="05:00"),
            ("BLOQUEAR",))], "teste")
        madrugada = datetime.datetime(2026, 8, 31, 3, 0)
        tarde = datetime.datetime(2026, 8, 31, 15, 0)
        texto = f"CPF {CPF_VALIDO}".encode("utf-8")
        r1 = p["servico"].analisar(texto, Contexto(canal="EMAIL", usuario="u",
                                                   momento=madrugada))
        certo(not r1["permitido"], "as 3h a regra vale")
        r2 = p["servico"].analisar(texto, Contexto(canal="EMAIL", usuario="u",
                                                   momento=tarde))
        certo(r2["permitido"], "as 15h nao vale")
        r3 = p["servico"].analisar(texto, Contexto(canal="DOWNLOAD", usuario="u",
                                                   momento=madrugada))
        certo(r3["permitido"], "outro canal nao e' alcancado")


@caso("servico: EDM reconhece o registro do cadastro, e o indice nao guarda o dado")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        p["servico"].indexar_edm(
            "folha", ["nome", "matricula"],
            [["Maria Aparecida Souza", "2024-00871"],
             ["Joao Batista Lima", "2019-00432"]], 2, "teste")
        r = p["servico"].analisar(
            "Encaminho a Maria Aparecida Souza, matricula 2024-00871.".encode(),
            Contexto(canal="EMAIL", usuario="u"))
        contem("folha", r["indices_edm"],
               "nome + matricula juntos sao o registro do cadastro")
        bruto = p["repo"].ler_json("indice_edm", "nome", "folha")
        nao_contem("Maria Aparecida Souza", str(bruto),
                   "vazar o indice nao pode vazar a folha")


@caso("servico: IDM reconhece trecho de documento registrado")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        edital = ("O presente edital estabelece as condicoes de habilitacao "
                  "para o processo seletivo simplificado da secretaria "
                  "municipal de administracao, conforme os criterios anexos.")
        p["servico"].indexar_idm("sigilosos", "edital-2026", edital, "teste")
        colado = ("Segue trecho: para o processo seletivo simplificado da "
                  "secretaria municipal de administracao, conforme os criterios")
        r = p["servico"].analisar(colado.encode("utf-8"),
                                  Contexto(canal="EMAIL", usuario="u"))
        contem("sigilosos", r["indices_idm"],
               "trecho colado tem de casar a impressao digital do documento")


@caso("servico: indice desativado sai da decisao sem ser destruido")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        p["servico"].indexar_edm("folha", ["nome", "matricula"],
                                 [["Maria Aparecida Souza", "2024-00871"]], 2,
                                 "teste")
        p["repo"].ativar_indice("indice_edm", "folha", False)
        p["servico"].recarregar_indices()
        r = p["servico"].analisar(
            "Maria Aparecida Souza, matricula 2024-00871".encode(),
            Contexto(canal="EMAIL", usuario="u"))
        igual(r["indices_edm"], [], "desativado nao participa da decisao")
        certo(p["repo"].ler_json("indice_edm", "nome", "folha") is not None,
              "e continua guardado, para poder voltar")


@caso("servico: dicionario cadastrado pela API entra na varredura")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Contexto
        p["repo"].guardar_dicionario("PROJETO_SIGILOSO",
                                     ["operacao andorinha"], "ALTA",
                                     ["INTERNO"], "teste")
        p["servico"].recarregar_dicionarios()
        r = p["servico"].analisar(
            "Anexo o cronograma da Operacao Andorinha.".encode("utf-8"),
            Contexto(canal="EMAIL", usuario="u"))
        contem("PROJETO_SIGILOSO", [e["rotulo"] for e in r["evidencia"]],
               "o termo cadastrado tem de ser encontrado, sem acento e sem caixa")
