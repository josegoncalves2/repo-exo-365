# -*- coding: utf-8 -*-
"""A API REST falada por HTTP de verdade, com token, rotas e codigos.

Cobre o contrato que o console do portal consome. Erro de rota ou de codigo de
status so' aparece quando alguem fala HTTP com o servico -- chamar a funcao
Python por dentro provaria a funcao, nao a API.
"""
from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request

from testes.apoio import (Temporario, caso, certo, contem, igual,
                          montar_servico, nao_contem)

CPF_VALIDO = "529.982.247-25"
TOKEN = "token-de-teste-nao-usado-em-producao"


class _Api:
    """Sobe a API em porta efemera com um estado proprio."""

    def __init__(self, dir_, **extra):
        import servidor as api
        from descoberta.origens import OrigemArquivos
        from descoberta.rastreador import Rastreador
        from integracao.siem import EnvioSyslog
        import os

        self.p = montar_servico(dir_, **extra)
        acervo = os.path.join(dir_, "acervo")
        os.makedirs(acervo, exist_ok=True)
        self.rastreador = Rastreador(self.p["repo"], self.p["servico"],
                                     {"local": OrigemArquivos(acervo)})
        api.construir(self.p["repo"], self.p["servico"], TOKEN,
                      EnvioSyslog(host=""), self.p["quarentena"],
                      self.p["liberacoes"], self.p["executor"],
                      self.p["certificados"], self.rastreador)
        self.servidor = api.servir("127.0.0.1", 0)
        self.porta = self.servidor.socket.getsockname()[1]
        threading.Thread(target=self.servidor.serve_forever,
                         daemon=True).start()

    def parar(self):
        self.servidor.shutdown()

    def chamar(self, metodo, caminho, corpo=None, token=TOKEN):
        dados = json.dumps(corpo).encode("utf-8") if corpo is not None else None
        pedido = urllib.request.Request(
            f"http://127.0.0.1:{self.porta}{caminho}", data=dados,
            method=metodo)
        pedido.add_header("Content-Type", "application/json")
        if token:
            pedido.add_header("X-DLP-Token", token)
        try:
            with urllib.request.urlopen(pedido, timeout=25) as r:
                bruto = r.read()
                tipo = r.headers.get("Content-Type", "")
                if "json" in tipo:
                    return r.status, json.loads(bruto)
                return r.status, bruto
        except urllib.error.HTTPError as e:
            bruto = e.read()
            try:
                return e.code, json.loads(bruto)
            except ValueError:
                return e.code, bruto


@caso("API: /saude nao exige token e conta o que existe")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, corpo = api.chamar("GET", "/saude", token=None)
            igual(codigo, 200, "/saude e' o healthcheck do container")
            igual(corpo["estado"], "ok", "estado")
            certo("quarentena" in corpo, "a saude tem de mostrar a quarentena")
            certo("notificacoes" in corpo, "e a fila de avisos")
            certo("descoberta" in corpo, "e as origens de varredura")
        finally:
            api.parar()


@caso("API: qualquer outra rota exige o token")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, _ = api.chamar("GET", "/incidentes", token=None)
            igual(codigo, 401, "sem token nao se le incidente")
            codigo, _ = api.chamar("GET", "/incidentes", token="errado")
            igual(codigo, 401, "token errado tambem nao")
        finally:
            api.parar()


@caso("API: canal invalido e' recusado com a lista de validos")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, corpo = api.chamar("POST", "/analisar",
                                       {"canal": "TELEPATIA", "texto": "x"})
            igual(codigo, 400, "canal invalido e' erro do cliente")
            contem("DOWNLOAD", corpo["erro"], "a mensagem tem de listar os validos")
            contem("EMAIL_INTERNO", corpo["erro"],
                   "EMAIL_INTERNO passou a ser canal catalogado")
        finally:
            api.parar()


@caso("API: operacao que muda estado exige 'autor'")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, corpo = api.chamar("POST", "/liberacoes",
                                       {"usuario": "pedro", "recurso": "/x",
                                        "justificativa": "porque sim"})
            igual(codigo, 400, "acao sem autor nao pode ser auditada depois")
            contem("autor", corpo["erro"], "a mensagem tem de dizer o que falta")
        finally:
            api.parar()


@caso("API: politica vazia e' recusada (desligar o DLP nao pode ser acidente)")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, corpo = api.chamar("PUT", "/politica",
                                       {"autor": "ana", "regras": []})
            igual(codigo, 400, "PUT com lista vazia desligaria tudo em silencio")
            contem("ativa", corpo["erro"], "a mensagem ensina o caminho certo")
        finally:
            api.parar()


@caso("API: regra com acao ou canal invalido nao entra")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            base = {"identificador": "X1", "nome": "teste",
                    "condicao": {"rotulos": ["CPF"]}, "acoes": ["EXPLODIR"]}
            codigo, corpo = api.chamar("PUT", "/politica",
                                       {"autor": "ana", "regras": [base]})
            igual(codigo, 400, "acao inexistente")
            contem("EXPLODIR", corpo["erro"], "diz qual acao")

            base["acoes"] = ["BLOQUEAR"]
            base["condicao"] = {"rotulos": ["CPF"], "canais": ["POMBO_CORREIO"]}
            codigo, corpo = api.chamar("PUT", "/politica",
                                       {"autor": "ana", "regras": [base]})
            igual(codigo, 400, "canal inexistente")
            contem("POMBO_CORREIO", corpo["erro"], "diz qual canal")
        finally:
            api.parar()


@caso("API: identificador de regra repetido e' recusado")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            regra = {"identificador": "R1", "nome": "a",
                     "condicao": {"rotulos": ["CPF"]}, "acoes": ["REGISTRAR"]}
            codigo, corpo = api.chamar(
                "PUT", "/politica", {"autor": "ana", "regras": [regra, dict(regra)]})
            igual(codigo, 400, "duas regras com o mesmo id: a segunda seria "
                               "invisivel e ninguem entenderia por que")
            contem("R1", corpo["erro"], "diz qual identificador")
        finally:
            api.parar()


@caso("API: ciclo completo de quarentena pela rede")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            api.chamar("PUT", "/politica", {"autor": "ana", "regras": [{
                "identificador": "Q1", "nome": "retem CPF",
                "condicao": {"rotulos": ["CPF"], "canais": ["DOWNLOAD"]},
                "acoes": ["QUARENTENAR"], "severidade": "ALTA",
                "prioridade": 1}]})
            import base64
            conteudo = f"Ficha com CPF {CPF_VALIDO}.".encode("utf-8")
            codigo, r = api.chamar("POST", "/analisar", {
                "canal": "DOWNLOAD", "usuario": "pedro.alves",
                "recurso": "/docs/ficha.txt", "nome_arquivo": "ficha.txt",
                "conteudo_base64": base64.b64encode(conteudo).decode()})
            igual(codigo, 200, "analise responde 200 mesmo quando bloqueia")
            certo(not r["permitido"], "tem de bloquear")
            certo(r["quarentena"], "e reter")

            codigo, lista = api.chamar("GET", "/quarentena")
            igual(codigo, 200, "listagem")
            igual(lista["resumo"]["RETIDO"], 1, "um item retido")

            codigo, bruto = api.chamar(
                "GET", f"/quarentena/{r['quarentena']}/conteudo")
            igual(codigo, 200, "restauracao")
            igual(bruto, conteudo, "o original tem de voltar byte a byte")

            codigo, _ = api.chamar(
                "POST", f"/quarentena/{r['quarentena']}/liberar",
                {"autor": "ana.fiscal", "justificativa": "falso positivo"})
            igual(codigo, 200, "liberacao")

            codigo, r2 = api.chamar("POST", "/analisar", {
                "canal": "DOWNLOAD", "usuario": "pedro.alves",
                "recurso": "/docs/ficha.txt", "nome_arquivo": "ficha.txt",
                "conteudo_base64": base64.b64encode(conteudo).decode()})
            certo(r2["permitido"],
                  "depois de liberado, a mesma saida tem de passar")

            codigo, corpo = api.chamar(
                "POST", f"/quarentena/{r['quarentena']}/liberar",
                {"autor": "ana", "justificativa": "de novo"})
            igual(codigo, 400, "item ja' liberado nao se libera duas vezes")
        finally:
            api.parar()


@caso("API: fila de revisao aparece, aprova e esvazia")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            api.chamar("PUT", "/politica", {"autor": "ana", "regras": [{
                "identificador": "V1", "nome": "revisar",
                "condicao": {"rotulos": ["CPF"]}, "acoes": ["REVISAO_MANUAL"],
                "prioridade": 1}]})
            _, r = api.chamar("POST", "/analisar", {
                "canal": "DOWNLOAD", "usuario": "pedro", "recurso": "/a.txt",
                "texto": f"CPF {CPF_VALIDO}"})
            certo(not r["permitido"], "barrado")
            codigo, fila = api.chamar("GET", "/revisao")
            igual(codigo, 200, "fila responde")
            igual(fila["total"], 1, "um item na fila")

            codigo, corpo = api.chamar(
                "POST", f"/revisao/{r['incidente']}/aprovar",
                {"autor": "ana.fiscal", "justificativa": "conferido"})
            igual(codigo, 200, "aprovacao")
            certo(corpo["liberacao"]["identificador"], "nasce uma liberacao")
            _, fila = api.chamar("GET", "/revisao")
            igual(fila["total"], 0, "a fila esvazia")
        finally:
            api.parar()


@caso("API: dicionario cadastrado passa a valer na hora")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, corpo = api.chamar("PUT", "/dicionarios/OPERACAO", {
                "autor": "ana", "termos": ["operacao andorinha"],
                "severidade": "ALTA"})
            igual(codigo, 200, "cadastro")
            igual(corpo["termos"], 1, "um termo")
            _, r = api.chamar("POST", "/analisar", {
                "canal": "EMAIL", "usuario": "u",
                "texto": "cronograma da Operacao Andorinha"})
            contem("OPERACAO", [e["rotulo"] for e in r["evidencia"]],
                   "sem recarregar, o termo so' valeria no proximo reinicio")

            codigo, _ = api.chamar("DELETE", "/dicionarios/OPERACAO",
                                   {"autor": "ana"})
            igual(codigo, 200, "remocao")
            _, r2 = api.chamar("POST", "/analisar", {
                "canal": "EMAIL", "usuario": "u",
                "texto": "cronograma da Operacao Andorinha"})
            igual([e["rotulo"] for e in r2["evidencia"]], [],
                  "removido para de valer na hora")
        finally:
            api.parar()


@caso("API: indice se desativa sem se perder, e so' some com confirmacao")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, _ = api.chamar("POST", "/indices/edm/folha", {
                "autor": "ana", "colunas": ["nome", "matricula"],
                "linhas": [["Maria Aparecida Souza", "2024-00871"]],
                "minimo": 2})
            igual(codigo, 200, "indexacao")

            codigo, corpo = api.chamar("POST", "/indices/edm/folha/estado",
                                       {"autor": "ana", "ativo": False})
            igual(codigo, 200, "desativacao")
            igual(corpo["carregados"]["edm"], [], "sai da decisao")

            codigo, corpo = api.chamar("GET", "/indices")
            igual(len(corpo["edm"]), 1, "mas continua guardado")
            certo(not corpo["edm"][0]["ativo"], "marcado como inativo")

            codigo, corpo = api.chamar("DELETE", "/indices/edm/folha",
                                       {"autor": "ana"})
            igual(codigo, 409, "remover sem confirmar tem de ser recusado")
            contem("estado", corpo["erro"], "e ensinar o caminho reversivel")

            codigo, _ = api.chamar("DELETE", "/indices/edm/folha",
                                   {"autor": "ana", "confirmar": True})
            igual(codigo, 200, "com confirmacao, remove")
            _, corpo = api.chamar("GET", "/indices")
            igual(corpo["edm"], [], "agora sumiu")
        finally:
            api.parar()


@caso("API: varredura de descoberta comeca, termina e aparece na listagem")
def _():
    import os
    import time
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            acervo = os.path.join(dir_, "acervo")
            with open(os.path.join(acervo, "ficha.txt"), "w",
                      encoding="utf-8") as f:
                f.write(f"Ficha com CPF {CPF_VALIDO}")
            codigo, corpo = api.chamar("POST", "/descoberta/varreduras",
                                       {"autor": "ana", "origem": "local"})
            igual(codigo, 200, "inicio")
            identificador = corpo["varredura"]
            limite = time.time() + 30
            estado = ""
            while time.time() < limite:
                _, v = api.chamar("GET",
                                  f"/descoberta/varreduras/{identificador}")
                estado = v["estado"]
                if estado != "EM_ANDAMENTO":
                    break
                time.sleep(0.05)
            igual(estado, "CONCLUIDA", "a varredura tem de concluir")
            igual(v["com_achado"], 1, "o arquivo com CPF tem de ser achado")

            codigo, corpo = api.chamar("POST", "/descoberta/varreduras",
                                       {"autor": "ana", "origem": "inexistente"})
            igual(codigo, 404, "origem desconhecida")
        finally:
            api.parar()


@caso("API: certificado S/MIME entra pelo cadastro e aparece na listagem")
def _():
    import datetime
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            chave = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            nome = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "tribunal")])
            agora = datetime.datetime.now(datetime.timezone.utc)
            cert = (x509.CertificateBuilder().subject_name(nome)
                    .issuer_name(nome).public_key(chave.public_key())
                    .serial_number(x509.random_serial_number())
                    .not_valid_before(agora - datetime.timedelta(days=1))
                    .not_valid_after(agora + datetime.timedelta(days=30))
                    .sign(chave, hashes.SHA256()))
            pem = cert.public_bytes(serialization.Encoding.PEM).decode()

            codigo, corpo = api.chamar("PUT", "/certificados/fiscal%40tce.gov.br",
                                       {"autor": "ana", "pem": pem})
            igual(codigo, 200, "cadastro do certificado")
            contem("tribunal", corpo["titular"], "o titular vem do proprio PEM")

            codigo, lista = api.chamar("GET", "/certificados")
            igual(len(lista["itens"]), 1, "aparece na listagem")

            codigo, corpo = api.chamar("PUT", "/certificados/x%40y.z",
                                       {"autor": "ana", "pem": "lixo"})
            igual(codigo, 400, "PEM invalido nao entra")
        finally:
            api.parar()


@caso("API: metodo errado devolve 405, rota inexistente devolve 404")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, _ = api.chamar("POST", "/painel", {})
            igual(codigo, 405, "rota existe, metodo nao")
            codigo, _ = api.chamar("GET", "/rota/que/nao/existe")
            igual(codigo, 404, "rota inexistente")
        finally:
            api.parar()


@caso("API: o incidente devolvido pela rede nao carrega o valor")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            _, r = api.chamar("POST", "/analisar", {
                "canal": "DOWNLOAD", "usuario": "u",
                "texto": f"documento com {CPF_VALIDO} dentro"})
            _, incidente = api.chamar("GET", f"/incidentes/{r['incidente']}")
            nao_contem(CPF_VALIDO, json.dumps(incidente),
                       "a API nao pode ser a via de vazamento do proprio DLP")
            _, csv = api.chamar("GET", "/relatorios/incidentes.csv")
            nao_contem(CPF_VALIDO.encode(), csv, "nem o CSV exportado")
        finally:
            api.parar()


@caso("API: agente registrado recebe a versao da politica e sabe quando envelhece")
def _():
    with Temporario() as dir_:
        api = _Api(dir_)
        try:
            codigo, corpo = api.chamar("POST", "/agentes/registrar", {
                "identificador": "estacao-rh-01", "nome": "RH 01",
                "sistema": "Windows 11", "versao": "1.0"})
            igual(codigo, 200, "registro do agente")
            versao = corpo["politica_versao"]
            certo(versao, "a versao da politica tem de voltar")

            api.chamar("POST", "/agentes/registrar", {
                "identificador": "estacao-rh-01", "politica_versao": versao})
            _, lista = api.chamar("GET", "/agentes")
            certo(not lista["agentes"][0]["politica_desatualizada"],
                  "agente com a politica corrente esta' em dia")

            api.chamar("PUT", "/politica", {"autor": "ana", "regras": [{
                "identificador": "N1", "nome": "nova",
                "condicao": {"rotulos": ["CPF"]}, "acoes": ["REGISTRAR"]}]})
            _, lista = api.chamar("GET", "/agentes")
            certo(lista["agentes"][0]["politica_desatualizada"],
                  "o campo politica_versao era gravado e NUNCA lido; agora e' "
                  "isto que ele responde")
        finally:
            api.parar()
