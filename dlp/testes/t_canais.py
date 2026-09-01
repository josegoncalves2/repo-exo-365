# -*- coding: utf-8 -*-
"""ICAP e SMTP falados de verdade, pelo socket.

PENDENCIAS.md, item 3: "os dois escutam, respondem, e NENHUM byte real passa
por eles. Nao ha' teste que abra conexao ICAP nem que fale SMTP com eles. ICAP
e SMTP sao codigo escrito e nao verificado."

Aqui eles passam a ser exercitados: o servidor sobe em porta efemera, um
cliente fala o protocolo, e o que se confere e' a resposta na rede -- nao o
retorno de uma funcao interna. Foi assim que apareceu o defeito do `_responder`
inexistente no ICAP, que travava a conexao em vez de devolver 405.
"""
from __future__ import annotations

import smtplib
import socket
import threading
from email.message import EmailMessage

from testes.apoio import (Temporario, caso, certo, contem, igual,
                          montar_servico, nao_contem)

CPF_VALIDO = "529.982.247-25"


def _regra_bloqueio(canais):
    from politica.modelo import Condicao, Regra
    return Regra("C1", "CPF nao sai", Condicao(rotulos=("CPF",), canais=canais),
                 ("BLOQUEAR",), severidade="ALTA", prioridade=1,
                 mensagem_usuario="Conteudo com CPF bloqueado pela politica.")


def _regra_mascara(canais):
    from politica.modelo import Condicao, Regra
    return Regra("C2", "CPF sai mascarado",
                 Condicao(rotulos=("CPF",), canais=canais),
                 ("MASCARAR", "REGISTRAR"), severidade="ALTA", prioridade=1)


class _Relay(threading.Thread):
    """Servidor SMTP minimo que guarda o que recebe. E' o 'Mailpit' do teste."""

    def __init__(self):
        super().__init__(daemon=True)
        self.recebidas = []
        self._soquete = socket.socket()
        self._soquete.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._soquete.bind(("127.0.0.1", 0))
        self._soquete.listen(5)
        self.porta = self._soquete.getsockname()[1]
        self._parar = False

    def run(self):
        while not self._parar:
            try:
                conexao, _ = self._soquete.accept()
            except OSError:
                return
            threading.Thread(target=self._atender, args=(conexao,),
                             daemon=True).start()

    def _atender(self, conexao):
        arquivo = conexao.makefile("rwb")
        arquivo.write(b"220 relay pronto\r\n")
        arquivo.flush()
        corpo = b""
        em_dados = False
        while True:
            linha = arquivo.readline()
            if not linha:
                break
            if em_dados:
                if linha.rstrip(b"\r\n") == b".":
                    self.recebidas.append(corpo)
                    corpo, em_dados = b"", False
                    arquivo.write(b"250 ok\r\n")
                    arquivo.flush()
                else:
                    corpo += linha
                continue
            verbo = linha.decode("latin-1").split(" ")[0].strip().upper()
            if verbo in ("HELO", "EHLO"):
                arquivo.write(b"250-relay\r\n250 8BITMIME\r\n")
            elif verbo == "DATA":
                arquivo.write(b"354 pode mandar\r\n")
                em_dados = True
            elif verbo == "QUIT":
                arquivo.write(b"221 tchau\r\n")
                arquivo.flush()
                break
            else:
                arquivo.write(b"250 ok\r\n")
            arquivo.flush()
        conexao.close()

    def parar(self):
        self._parar = True
        self._soquete.close()


def _subir(servidor):
    threading.Thread(target=servidor.serve_forever, daemon=True).start()
    return servidor.socket.getsockname()[1]


# ------------------------------------------------------------------ SMTP
@caso("SMTP: mensagem com CPF para fora e' RECUSADA com 550")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["servico"].salvar_politica([_regra_bloqueio(("EMAIL",))], "teste")
        relay = _Relay()
        relay.start()
        from canais.correio import ServidorSmtp
        servidor = ServidorSmtp(("127.0.0.1", 0), _decisor(p),
                                ("127.0.0.1", relay.porta), ("pmeto.local",))
        porta = _subir(servidor)
        try:
            msg = EmailMessage()
            msg["From"] = "servidor@pmeto.local"
            msg["To"] = "externo@gmail.com"
            msg["Subject"] = "ficha"
            msg.set_content(f"Segue o CPF {CPF_VALIDO} do interessado.")
            with smtplib.SMTP("127.0.0.1", porta, timeout=20) as s:
                erro = None
                try:
                    s.send_message(msg)
                except smtplib.SMTPDataError as e:
                    erro = e
            certo(erro is not None, "a mensagem tinha de ser recusada")
            igual(erro.smtp_code, 550, "recusa definitiva, nao temporaria")
            contem(b"incidente", erro.smtp_error,
                   "a recusa tem de citar o incidente, para o usuario poder pedir "
                   "revisao")
            igual(relay.recebidas, [], "nada pode ter chegado ao relay")
        finally:
            servidor.shutdown()
            relay.parar()


@caso("SMTP: mensagem interna com o mesmo CPF nao e' o mesmo evento")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["servico"].salvar_politica([_regra_bloqueio(("EMAIL",))], "teste")
        relay = _Relay()
        relay.start()
        from canais.correio import ServidorSmtp
        servidor = ServidorSmtp(("127.0.0.1", 0), _decisor(p),
                                ("127.0.0.1", relay.porta), ("pmeto.local",))
        porta = _subir(servidor)
        try:
            msg = EmailMessage()
            msg["From"] = "a@pmeto.local"
            msg["To"] = "b@pmeto.local"
            msg["Subject"] = "ficha"
            msg.set_content(f"CPF {CPF_VALIDO}")
            with smtplib.SMTP("127.0.0.1", porta, timeout=20) as s:
                s.send_message(msg)
            igual(len(relay.recebidas), 1,
                  "circular entre setores nao e' vazamento; a regra era so' de "
                  "EMAIL externo")
        finally:
            servidor.shutdown()
            relay.parar()


@caso("SMTP: MASCARAR reescreve o corpo entregue (o defeito do mensagem_final)")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["servico"].salvar_politica([_regra_mascara(("EMAIL",))], "teste")
        relay = _Relay()
        relay.start()
        from canais.correio import ServidorSmtp
        servidor = ServidorSmtp(("127.0.0.1", 0), _decisor(p),
                                ("127.0.0.1", relay.porta), ("pmeto.local",))
        porta = _subir(servidor)
        try:
            msg = EmailMessage()
            msg["From"] = "servidor@pmeto.local"
            msg["To"] = "externo@gmail.com"
            msg["Subject"] = "ficha"
            msg.set_content(f"O numero {CPF_VALIDO} e' do interessado.")
            with smtplib.SMTP("127.0.0.1", porta, timeout=20) as s:
                s.send_message(msg)
            igual(len(relay.recebidas), 1, "a mensagem tem de ser entregue")
            entregue = relay.recebidas[0]
            nao_contem(CPF_VALIDO.encode(), entregue,
                       "ANTES desta correcao a mensagem seguia inteira: nada "
                       "escrevia 'mensagem_final' e o padrao era o corpo bruto")
            contem(b"*", entregue, "a mascara tem de aparecer no lugar")
        finally:
            servidor.shutdown()
            relay.parar()


@caso("SMTP: anexo com CPF sai cifrado quando a politica manda")
def _():
    import subprocess
    import os
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Condicao, Regra
        p["servico"].salvar_politica([Regra(
            "C3", "anexo cifrado", Condicao(rotulos=("CPF",), canais=("EMAIL",)),
            ("CRIPTOGRAFAR",), severidade="ALTA", prioridade=1)], "teste")
        relay = _Relay()
        relay.start()
        from canais.correio import ServidorSmtp
        servidor = ServidorSmtp(("127.0.0.1", 0), _decisor(p),
                                ("127.0.0.1", relay.porta), ("pmeto.local",))
        porta = _subir(servidor)
        try:
            msg = EmailMessage()
            msg["From"] = "servidor@pmeto.local"
            msg["To"] = "externo@gmail.com"
            msg["Subject"] = "anexo"
            msg.set_content("Segue anexo.")
            msg.add_attachment(f"CPF {CPF_VALIDO} do servidor".encode("utf-8"),
                               maintype="text", subtype="plain",
                               filename="ficha.txt")
            with smtplib.SMTP("127.0.0.1", porta, timeout=20) as s:
                s.send_message(msg)
            igual(len(relay.recebidas), 1, "entregue")
            nao_contem(CPF_VALIDO.encode(), relay.recebidas[0],
                       "o anexo tinha de sair cifrado")
            contem(b"ficha.txt.zip", relay.recebidas[0],
                   "o anexo trocado tem de manter nome reconhecivel")
        finally:
            servidor.shutdown()
            relay.parar()


@caso("SMTP: aviso do proprio DLP nao e' reinspecionado (sem laco)")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["servico"].salvar_politica([_regra_bloqueio(("EMAIL",))], "teste")
        relay = _Relay()
        relay.start()
        from canais.correio import ServidorSmtp
        servidor = ServidorSmtp(("127.0.0.1", 0), _decisor(p),
                                ("127.0.0.1", relay.porta), ("pmeto.local",))
        porta = _subir(servidor)
        try:
            msg = EmailMessage()
            msg["From"] = "dlp@pmeto.local"
            msg["To"] = "externo@gmail.com"
            msg["Subject"] = "[DLP] incidente"
            msg["X-DLP-Origem"] = "servico-dlp"
            msg.set_content(f"Referencia interna {CPF_VALIDO}")
            with smtplib.SMTP("127.0.0.1", porta, timeout=20) as s:
                s.send_message(msg)
            igual(len(relay.recebidas), 1,
                  "sem esta guarda, um aviso sobre e-mail bloqueado geraria "
                  "outro aviso, indefinidamente")
        finally:
            servidor.shutdown()
            relay.parar()


@caso("SMTP: falha na inspecao NAO entrega (falha fechada)")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        relay = _Relay()
        relay.start()

        def explodir(**_):
            raise RuntimeError("motor indisponivel")

        from canais.correio import ServidorSmtp
        servidor = ServidorSmtp(("127.0.0.1", 0), explodir,
                                ("127.0.0.1", relay.porta), ("pmeto.local",))
        porta = _subir(servidor)
        try:
            msg = EmailMessage()
            msg["From"] = "a@pmeto.local"
            msg["To"] = "externo@gmail.com"
            msg["Subject"] = "x"
            msg.set_content("qualquer coisa")
            with smtplib.SMTP("127.0.0.1", porta, timeout=20) as s:
                erro = None
                try:
                    s.send_message(msg)
                except smtplib.SMTPDataError as e:
                    erro = e
            certo(erro is not None, "tinha de recusar")
            igual(erro.smtp_code, 451,
                  "451 e' recusa TEMPORARIA: o remetente tenta de novo depois, "
                  "em vez de perder a mensagem")
            igual(relay.recebidas, [],
                  "um DLP que entrega o que nao conseguiu ler basta ser "
                  "derrubado para ser contornado")
        finally:
            servidor.shutdown()
            relay.parar()


# ------------------------------------------------------------------ ICAP
def _falar_icap(porta: int, dados: bytes, tempo=20) -> bytes:
    with socket.create_connection(("127.0.0.1", porta), timeout=tempo) as s:
        s.sendall(dados)
        s.shutdown(socket.SHUT_WR)
        pedacos = []
        while True:
            pedaco = s.recv(65536)
            if not pedaco:
                break
            pedacos.append(pedaco)
    return b"".join(pedacos)


def _reqmod(corpo: bytes, host="upload.externo.com") -> bytes:
    cabecalho_http = (f"POST /upload HTTP/1.1\r\nHost: {host}\r\n"
                      f"Content-Disposition: attachment; "
                      f"filename=\"ficha.txt\"\r\n\r\n").encode("latin-1")
    pedaco = f"{len(corpo):x}\r\n".encode("latin-1") + corpo + b"\r\n0\r\n\r\n"
    return (b"REQMOD icap://dlp/dlp ICAP/1.0\r\n"
            b"Host: dlp\r\n"
            b"X-Client-IP: 192.168.1.77\r\n"
            b"Encapsulated: req-hdr=0, req-body=" +
            str(len(cabecalho_http)).encode() + b"\r\n\r\n" +
            cabecalho_http + pedaco)


@caso("ICAP: OPTIONS responde o que o proxy precisa para se configurar")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from canais.icap import ServidorIcap
        servidor = ServidorIcap(("127.0.0.1", 0), _decisor(p))
        porta = _subir(servidor)
        try:
            resposta = _falar_icap(
                porta, b"OPTIONS icap://dlp/dlp ICAP/1.0\r\nHost: dlp\r\n\r\n")
            contem(b"ICAP/1.0 200 OK", resposta, "OPTIONS tem de responder 200")
            contem(b"Methods: REQMOD, RESPMOD", resposta,
                   "o proxy decide o que mandar por esta linha")
            contem(b"ISTag:", resposta, "ISTag e' obrigatorio na RFC 3507")
        finally:
            servidor.shutdown()


@caso("ICAP: metodo desconhecido devolve 405 (defeito do _responder inexistente)")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from canais.icap import ServidorIcap
        servidor = ServidorIcap(("127.0.0.1", 0), _decisor(p))
        porta = _subir(servidor)
        try:
            resposta = _falar_icap(
                porta, b"INVENTADO icap://dlp/dlp ICAP/1.0\r\nHost: dlp\r\n\r\n")
            certo(resposta, "ANTES da correcao a conexao fechava sem resposta: "
                            "o codigo chamava self._responder, que nao existia")
            contem(b"405", resposta, "metodo nao aceito tem de dizer 405")
        finally:
            servidor.shutdown()


@caso("ICAP: upload limpo passa com 204 (o proxy segue com o original)")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["servico"].salvar_politica([_regra_bloqueio(("ICAP",))], "teste")
        from canais.icap import ServidorIcap
        servidor = ServidorIcap(("127.0.0.1", 0), _decisor(p))
        porta = _subir(servidor)
        try:
            resposta = _falar_icap(porta, _reqmod(b"relatorio de obras 2026"))
            contem(b"204", resposta, "sem achado, nada e' modificado")
        finally:
            servidor.shutdown()


@caso("ICAP: upload com CPF e' bloqueado com pagina de aviso")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["servico"].salvar_politica([_regra_bloqueio(("ICAP",))], "teste")
        from canais.icap import ServidorIcap
        servidor = ServidorIcap(("127.0.0.1", 0), _decisor(p))
        porta = _subir(servidor)
        try:
            resposta = _falar_icap(
                porta, _reqmod(f"CPF {CPF_VALIDO} do interessado".encode()))
            contem(b"ICAP/1.0 200 OK", resposta, "bloqueio devolve corpo, nao 204")
            contem(b"403 Forbidden", resposta,
                   "o navegador tem de receber uma negativa")
            contem("bloqueado".encode("utf-8"), resposta,
                   "a pagina tem de explicar o que houve")
            certo(p["repo"].contar({"canal": "ICAP"}) >= 1,
                  "o incidente tem de ficar registrado com canal ICAP")
        finally:
            servidor.shutdown()


@caso("ICAP: MASCARAR substitui o corpo em vez de deixar passar inteiro")
def _():
    with Temporario() as dir_:
        p = montar_servico(dir_)
        p["servico"].salvar_politica([_regra_mascara(("ICAP",))], "teste")
        from canais.icap import ServidorIcap
        servidor = ServidorIcap(("127.0.0.1", 0), _decisor(p))
        porta = _subir(servidor)
        try:
            resposta = _falar_icap(
                porta, _reqmod(f"O numero {CPF_VALIDO} e' do servidor".encode()))
            nao_contem(CPF_VALIDO.encode(), resposta,
                       "com 204 o proxy seguiria com o original e o CPF subiria")
            contem(b"req-hdr=0", resposta, "REQMOD devolve o corpo substituido")
        finally:
            servidor.shutdown()


@caso("ICAP: CRIPTOGRAFAR devolve o envelope, e o 7z o abre")
def _():
    """Fecha o ultimo canal em que a cifra existia so' no papel.

    O ICAP e' o caminho do upload de navegador para fora (Gmail, Drive,
    WeTransfer). Uma regra que mande cifrar precisa produzir, AQUI, um arquivo
    que o destinatario consiga abrir -- senao a acao vira um bloqueio com nome
    bonito.
    """
    import os
    import re as _re
    import subprocess
    with Temporario() as dir_:
        p = montar_servico(dir_)
        from politica.modelo import Condicao, Regra
        p["servico"].salvar_politica([Regra(
            "C4", "cifra no ICAP", Condicao(rotulos=("CPF",), canais=("ICAP",)),
            ("CRIPTOGRAFAR",), severidade="ALTA", prioridade=1)], "teste")
        from canais.icap import ServidorIcap
        servidor = ServidorIcap(("127.0.0.1", 0), _decisor(p))
        porta = _subir(servidor)
        try:
            original = f"Ficha do servidor. CPF {CPF_VALIDO}.".encode("utf-8")
            resposta = _falar_icap(porta, _reqmod(original))
            nao_contem(CPF_VALIDO.encode(), resposta,
                       "o corpo devolvido ao proxy nao pode ter o valor em claro")
            contem(b"application/zip", resposta,
                   "o tipo devolvido tem de dizer que virou um ZIP")

            # O envelope comeca no primeiro "PK" depois do cabecalho encapsulado.
            inicio = resposta.find(b"PK\x03\x04")
            certo(inicio > 0, "o ZIP tem de estar dentro da resposta ICAP")
            # O corpo vem em pedacos (chunked); o ultimo pedaco termina em 0\r\n\r\n.
            fim = resposta.rfind(b"\r\n0\r\n\r\n")
            envelope = resposta[inicio:fim if fim > inicio else len(resposta)]

            avisos = [a for a in p["repo"].notificacoes({"tipo": "USUARIO"})
                      if "Senha do arquivo" in a["assunto"]]
            certo(avisos, "a senha tem de ser enviada por canal separado")
            senha = [l.strip() for l in avisos[0]["corpo"].splitlines()
                     if l.startswith("    ")][0]

            caminho = os.path.join(dir_, "icap.zip")
            with open(caminho, "wb") as f:
                f.write(envelope)
            saiu = os.path.join(dir_, "saiu")
            r = subprocess.run(["7z", "x", f"-o{saiu}", f"-p{senha}", caminho],
                               capture_output=True)
            certo(r.returncode == 0,
                  f"o 7z tem de abrir o envelope produzido no ICAP: "
                  f"{r.stderr.decode('utf-8', 'replace')[:200]}")
            extraidos = os.listdir(saiu)
            certo(extraidos, "tem de haver um arquivo dentro")
            with open(os.path.join(saiu, extraidos[0]), "rb") as f:
                igual(f.read(), original,
                      "o conteudo cifrado tem de ser o original, byte a byte")
        finally:
            servidor.shutdown()


def _decisor(p):
    from politica.modelo import Contexto

    def decidir(dados=None, texto="", canal="API", usuario="", ip="",
                destino="", nome_arquivo="", grupos=(), recurso="", email="",
                cifra_delegada=False):
        ctx = Contexto(canal=canal, usuario=usuario, email=email, ip=ip,
                       destino=destino, nome_arquivo=nome_arquivo,
                       grupos=tuple(grupos))
        return p["servico"].analisar(dados, ctx, texto_direto=texto,
                                     recurso=recurso,
                                     cifra_delegada=cifra_delegada)
    return decidir
