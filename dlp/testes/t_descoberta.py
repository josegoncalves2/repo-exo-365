# -*- coding: utf-8 -*-
"""Varredura de dados em repouso: o canal DESCOBERTA que nao tinha produtor."""
from __future__ import annotations

import os
import time

from testes.apoio import (Temporario, caso, certo, contem, igual, levanta,
                          montar_servico, nao_contem)

CPF_VALIDO = "529.982.247-25"


def _acervo(raiz: str) -> None:
    os.makedirs(os.path.join(raiz, "rh", "fichas"), exist_ok=True)
    os.makedirs(os.path.join(raiz, "obras"), exist_ok=True)
    with open(os.path.join(raiz, "rh", "fichas", "maria.txt"), "w",
              encoding="utf-8") as f:
        f.write(f"Ficha funcional. O numero {CPF_VALIDO} consta do cadastro.")
    with open(os.path.join(raiz, "rh", "avisos.txt"), "w", encoding="utf-8") as f:
        f.write("Reuniao de equipe na quinta-feira as 14h.")
    with open(os.path.join(raiz, "obras", "cronograma.txt"), "w",
              encoding="utf-8") as f:
        f.write("Cronograma da pavimentacao do bairro centro.")


def _esperar(rastreador, identificador, repo, tempo=30):
    limite = time.time() + tempo
    while time.time() < limite:
        v = repo.varredura(identificador)
        if v and v["estado"] != "EM_ANDAMENTO":
            return v
        time.sleep(0.05)
    raise AssertionError(f"varredura {identificador} nao terminou em {tempo}s")


@caso("descoberta: varre o acervo, classifica tudo e abre incidente so' no que tem")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        acervo = os.path.join(dir_, "acervo")
        _acervo(acervo)
        from descoberta.origens import OrigemArquivos
        from descoberta.rastreador import Rastreador
        rastreador = Rastreador(p["repo"], p["servico"],
                                {"rh": OrigemArquivos(acervo)})
        inicio = rastreador.iniciar("rh", autor="teste")
        v = _esperar(rastreador, inicio["varredura"], p["repo"])

        igual(v["estado"], "CONCLUIDA", f"varredura falhou: {v['detalhe']}")
        igual(v["inspecionados"], 3, "os tres arquivos tem de ser lidos")
        igual(v["com_achado"], 1, "so' a ficha tem dado pessoal")
        igual(v["erros"], 0, f"nenhum erro esperado: {v['detalhe']}")

        # O MAPA: todo recurso fica classificado, com achado ou sem.
        ficha = p["repo"].classificacao_de("rh:rh/fichas/maria.txt")
        certo(ficha is not None, "a ficha tem de ficar classificada")
        igual(ficha["classificacao"], "SIGILOSO", "CPF torna o arquivo sigiloso")
        limpo = p["repo"].classificacao_de("rh:rh/avisos.txt")
        certo(limpo is not None,
              "arquivo sem achado tambem entra no mapa -- e' o que responde "
              "'onde estao os dados', e nao so' 'o que vazou'")
        igual(limpo["classificacao"], "PUBLICO", "aviso de reuniao e' publico")

        incidentes = p["repo"].listar({"canal": "DESCOBERTA"})
        igual(len(incidentes), 1, "um incidente, so' para o arquivo com achado")
        contem("maria.txt", incidentes[0].nome_arquivo, "o incidente aponta o arquivo")
        nao_contem(CPF_VALIDO, incidentes[0].como_json(),
                   "nem na descoberta o valor pode ser guardado")


@caso("descoberta: a varredura NAO altera o acervo")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        acervo = os.path.join(dir_, "acervo")
        _acervo(acervo)
        antes = {}
        for pasta, _, arquivos in os.walk(acervo):
            for nome in arquivos:
                caminho = os.path.join(pasta, nome)
                with open(caminho, "rb") as f:
                    antes[caminho] = f.read()
        from descoberta.origens import OrigemArquivos
        from descoberta.rastreador import Rastreador
        rastreador = Rastreador(p["repo"], p["servico"],
                                {"rh": OrigemArquivos(acervo)})
        inicio = rastreador.iniciar("rh", autor="teste")
        _esperar(rastreador, inicio["varredura"], p["repo"])
        depois = {}
        for pasta, _, arquivos in os.walk(acervo):
            for nome in arquivos:
                caminho = os.path.join(pasta, nome)
                with open(caminho, "rb") as f:
                    depois[caminho] = f.read()
        igual(depois, antes,
              "remediacao automatica sobre documento de trabalho apaga o acervo "
              "quando a regra esta' mal calibrada; a varredura so' classifica")


@caso("descoberta: a segunda passagem incremental pula o que nao mudou")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        acervo = os.path.join(dir_, "acervo")
        _acervo(acervo)
        from descoberta.origens import OrigemArquivos
        from descoberta.rastreador import Rastreador
        rastreador = Rastreador(p["repo"], p["servico"],
                                {"rh": OrigemArquivos(acervo)})
        primeira = rastreador.iniciar("rh", modo="INCREMENTAL", autor="teste")
        _esperar(rastreador, primeira["varredura"], p["repo"])

        segunda = rastreador.iniciar("rh", modo="INCREMENTAL", autor="teste")
        v2 = _esperar(rastreador, segunda["varredura"], p["repo"])
        igual(v2["inspecionados"], 0, "nada mudou: nada a reler")
        igual(v2["ignorados"], 3, "os tres tem de ser pulados")

        with open(os.path.join(acervo, "obras", "cronograma.txt"), "a",
                  encoding="utf-8") as f:
            f.write(f"\nResponsavel com CPF {CPF_VALIDO}.")
        terceira = rastreador.iniciar("rh", modo="INCREMENTAL", autor="teste")
        v3 = _esperar(rastreador, terceira["varredura"], p["repo"])
        igual(v3["inspecionados"], 1, "so' o arquivo alterado e' relido")
        igual(v3["com_achado"], 1, "e o achado novo aparece")


@caso("descoberta: arquivo acima do teto vira NAO_CLASSIFICADO, nunca 'limpo'")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        acervo = os.path.join(dir_, "acervo")
        os.makedirs(acervo)
        with open(os.path.join(acervo, "grande.bin"), "wb") as f:
            f.write(b"x" * 5000)
        from descoberta.origens import OrigemArquivos
        from descoberta.rastreador import Rastreador
        rastreador = Rastreador(p["repo"], p["servico"],
                                {"a": OrigemArquivos(acervo)}, teto_arquivo=1000)
        inicio = rastreador.iniciar("a", autor="teste")
        v = _esperar(rastreador, inicio["varredura"], p["repo"])
        igual(v["ignorados"], 1, "acima do teto e' ignorado")
        registro = p["repo"].classificacao_de("a:grande.bin")
        igual(registro["classificacao"], "NAO_CLASSIFICADO",
              "'grande demais' nao pode virar 'limpo'")
        certo(not registro["extracao_completa"], "e fica marcado como incompleto")


@caso("descoberta: nao sai da raiz nem por link simbolico")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        acervo = os.path.join(dir_, "acervo")
        fora = os.path.join(dir_, "fora")
        os.makedirs(acervo)
        os.makedirs(fora)
        with open(os.path.join(fora, "segredo.txt"), "w", encoding="utf-8") as f:
            f.write(f"CPF {CPF_VALIDO} fora do alvo")
        os.symlink(fora, os.path.join(acervo, "atalho"))
        from descoberta.origens import ErroDeOrigem, OrigemArquivos
        from descoberta.rastreador import Rastreador
        origem = OrigemArquivos(acervo)
        rastreador = Rastreador(p["repo"], p["servico"], {"a": origem})
        inicio = rastreador.iniciar("a", autor="teste")
        v = _esperar(rastreador, inicio["varredura"], p["repo"])
        igual(v["arquivos"], 0,
              "um link para fora faria a varredura ler o que ninguem mandou ler")
        levanta(ErroDeOrigem, lambda: list(origem.listar("../fora")),
                "alvo fora da raiz tem de ser recusado")


@caso("descoberta: duas varreduras ao mesmo tempo sao recusadas")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        acervo = os.path.join(dir_, "acervo")
        _acervo(acervo)
        from descoberta.origens import OrigemArquivos
        from descoberta.rastreador import Rastreador
        rastreador = Rastreador(p["repo"], p["servico"],
                                {"a": OrigemArquivos(acervo)})
        primeira = rastreador.iniciar("a", autor="teste")
        try:
            levanta(RuntimeError, lambda: rastreador.iniciar("a", autor="teste"),
                    "descoberta pode esperar; download do usuario nao")
        finally:
            _esperar(rastreador, primeira["varredura"], p["repo"])


@caso("descoberta: origem inexistente e' erro claro, nao varredura vazia")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from descoberta.rastreador import Rastreador
        rastreador = Rastreador(p["repo"], p["servico"], {})
        levanta(KeyError, lambda: rastreador.iniciar("nao-existe", autor="t"),
                "origem desconhecida tem de falhar dizendo quais existem")


@caso("descoberta: WebDAV le a listagem PROPFIND de verdade")
def _():
    """Servidor HTTP minimo falando PROPFIND: prova o analisador de XML e a
    travessia em largura sem depender do portal estar de pe'."""
    import http.server
    import threading

    corpo_pasta = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response><D:href>/acervo/</D:href><D:propstat><D:prop>
    <D:resourcetype><D:collection/></D:resourcetype>
  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/acervo/rh/</D:href><D:propstat><D:prop>
    <D:resourcetype><D:collection/></D:resourcetype>
  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/acervo/leia.txt</D:href><D:propstat><D:prop>
    <D:resourcetype/><D:getcontentlength>21</D:getcontentlength>
    <D:getetag>"aaa"</D:getetag>
  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>"""
    corpo_rh = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response><D:href>/acervo/rh/</D:href><D:propstat><D:prop>
    <D:resourcetype><D:collection/></D:resourcetype>
  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
  <D:response><D:href>/acervo/rh/ficha%20da%20maria.txt</D:href>
    <D:propstat><D:prop>
    <D:resourcetype/><D:getcontentlength>40</D:getcontentlength>
    <D:getetag>"bbb"</D:getetag>
  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>"""

    class Manipulador(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def _responder(self, codigo, corpo, tipo="application/xml"):
            dados = corpo.encode("utf-8") if isinstance(corpo, str) else corpo
            self.send_response(codigo)
            self.send_header("Content-Type", tipo)
            self.send_header("Content-Length", str(len(dados)))
            self.end_headers()
            self.wfile.write(dados)

        def do_PROPFIND(self):                              # noqa: N802
            if self.path.rstrip("/").endswith("/rh"):
                self._responder(207, corpo_rh)
            else:
                self._responder(207, corpo_pasta)

        def do_GET(self):                                   # noqa: N802
            self._responder(200, f"Ficha: CPF {CPF_VALIDO}.", "text/plain")

    servidor = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Manipulador)
    threading.Thread(target=servidor.serve_forever, daemon=True).start()
    porta = servidor.server_address[1]
    try:
        with Temporario() as dir_:
            p = montar_servico(dir_)
            from descoberta.origens import OrigemWebdav
            from descoberta.rastreador import Rastreador
            origem = OrigemWebdav(f"http://127.0.0.1:{porta}/acervo/", "u", "s")
            achados = list(origem.listar())
            caminhos = sorted(r.caminho for r in achados)
            igual(caminhos, ["leia.txt", "rh/ficha da maria.txt"],
                  "a travessia tem de descer na subpasta e decodificar %20")
            rastreador = Rastreador(p["repo"], p["servico"], {"portal": origem})
            inicio = rastreador.iniciar("portal", autor="teste")
            v = _esperar(rastreador, inicio["varredura"], p["repo"])
            igual(v["estado"], "CONCLUIDA", f"falhou: {v['detalhe']}")
            igual(v["inspecionados"], 2, "os dois arquivos tem de ser lidos")
            igual(v["com_achado"], 2, "os dois devolvem o mesmo conteudo com CPF")
    finally:
        servidor.shutdown()
