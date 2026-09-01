# -*- coding: utf-8 -*-
"""DLP Email Security: proxy SMTP que inspeciona antes de entregar.

Fica ENTRE quem envia e o servidor de saida. Le corpo e TODO anexo, aplica a
mesma politica, e entao entrega, MASCARA, CIFRA ou recusa com 550. Recusar no
SMTP e' o unico ponto em que o e-mail ainda nao saiu -- depois disso nao ha'
volta.

Sem dependencia externa: `smtpd` saiu do Python 3.12, entao o protocolo e'
falado direto no socket, que e' simples o bastante para os verbos que importam.

TRES DEFEITOS CORRIGIDOS EM 2026-08-31, achados ao ligar o canal ao trafego
real pela primeira vez (PENDENCIAS, item 3 -- "codigo escrito e nao verificado"):

  1. MASCARA QUE NUNCA ACONTECIA. A entrega usava
     `veredito.get("mensagem_final", bruto)` e NADA, em lugar nenhum, escrevia
     `mensagem_final`. O padrao valia sempre: a mensagem seguia inteira. Uma
     regra que mandasse mascarar entregava o CPF em claro e registrava
     "MASCARAR" no incidente. Agora a mensagem e' REMONTADA parte a parte com
     o conteudo que o executor devolveu.
  2. ENTREGA SEM TLS. A conexao com o relay era `smtplib.SMTP` puro. Um DLP
     que inspeciona o anexo e entao entrega em texto claro na rede protege
     contra o usuario e nao contra a rede.
  3. LACO DE AVISO. Os proprios avisos do DLP passariam por aqui, poderiam ser
     bloqueados, gerariam outro aviso, e assim por diante. Mensagem com o
     cabecalho `X-DLP-Origem` e' reconhecida e segue direto.
"""
from __future__ import annotations

import email
import email.policy
import re
import socketserver
import ssl
import traceback
from typing import Callable, Dict, List, Optional, Sequence, Tuple

CABECALHO_ORIGEM = "X-DLP-Origem"


class ServidorSmtp(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, endereco, decidir: Callable[..., Dict],
                 destino_real: Tuple[str, int],
                 dominios_internos: Tuple[str, ...] = (),
                 starttls_saida: bool = False,
                 certificados=None):
        self.decidir = decidir
        self.destino_real = destino_real
        self.dominios_internos = tuple(d.lower() for d in dominios_internos)
        self.starttls_saida = starttls_saida
        self.certificados = certificados
        super().__init__(endereco, ManipuladorSmtp)


class ManipuladorSmtp(socketserver.StreamRequestHandler):
    timeout = 120

    def _escrever(self, texto: str):
        self.wfile.write((texto + "\r\n").encode("utf-8"))
        self.wfile.flush()

    def handle(self):                                       # noqa: A003
        try:
            self._escrever("220 dlp.exo ESMTP pronto")
            remetente, destinatarios = "", []
            while True:
                linha = self.rfile.readline()
                if not linha:
                    return
                comando = linha.decode("utf-8", "replace").strip()
                verbo = comando.split(" ")[0].upper() if comando else ""

                if verbo in ("HELO", "EHLO"):
                    self._escrever("250-dlp.exo")
                    self._escrever("250-SIZE 52428800")
                    self._escrever("250 8BITMIME")
                elif verbo == "MAIL":
                    remetente = _endereco(comando)
                    self._escrever("250 2.1.0 ok")
                elif verbo == "RCPT":
                    destinatarios.append(_endereco(comando))
                    self._escrever("250 2.1.5 ok")
                elif verbo == "DATA":
                    self._escrever("354 envie a mensagem, termine com <CRLF>.<CRLF>")
                    bruto = self._ler_dados()
                    try:
                        veredito = self._inspecionar(remetente, destinatarios, bruto)
                    except Exception as e:                  # noqa: BLE001
                        # FALHA FECHADA. Se a inspecao explode, a mensagem NAO
                        # segue: um DLP que entrega o que nao conseguiu ler
                        # basta ser derrubado para ser contornado.
                        traceback.print_exc()
                        self._escrever(f"451 4.7.1 inspecao de DLP falhou: {e}")
                        remetente, destinatarios = "", []
                        continue
                    if veredito["permitido"]:
                        try:
                            self._entregar(remetente, destinatarios,
                                           veredito["mensagem_final"])
                            self._escrever("250 2.0.0 aceito")
                        except Exception as e:              # noqa: BLE001
                            traceback.print_exc()
                            self._escrever(f"451 4.4.1 entrega ao relay falhou: {e}")
                    else:
                        motivo = (veredito.get("mensagem")
                                  or "conteudo bloqueado pela politica de DLP")
                        incidente = veredito.get("incidente") or ""
                        sufixo = f" (incidente {incidente})" if incidente else ""
                        self._escrever(f"550 5.7.1 {motivo}{sufixo}")
                    remetente, destinatarios = "", []
                elif verbo == "RSET":
                    remetente, destinatarios = "", []
                    self._escrever("250 2.0.0 ok")
                elif verbo == "NOOP":
                    self._escrever("250 2.0.0 ok")
                elif verbo == "QUIT":
                    self._escrever("221 2.0.0 ate logo")
                    return
                else:
                    self._escrever("502 5.5.2 comando nao implementado")
        except Exception:                                   # noqa: BLE001
            traceback.print_exc()

    def _ler_dados(self) -> bytes:
        linhas: List[bytes] = []
        while True:
            linha = self.rfile.readline()
            if not linha or linha.rstrip(b"\r\n") == b".":
                break
            if linha.startswith(b".."):
                linha = linha[1:]
            linhas.append(linha)
        return b"".join(linhas)

    # ------------------------------------------------------------- inspecao
    def _inspecionar(self, remetente: str, destinatarios: List[str],
                     bruto: bytes) -> Dict:
        msg = email.message_from_bytes(bruto, policy=email.policy.default)

        if msg.get(CABECALHO_ORIGEM):
            # Aviso gerado pelo proprio DLP. Inspeciona-lo criaria o laco
            # descrito no cabecalho deste arquivo -- e o aviso ja' e' escrito
            # para nao conter valor sensivel.
            return {"permitido": True, "mensagem_final": bruto,
                    "motivo": "aviso do proprio DLP: nao reinspecionado"}

        externos = [d for d in destinatarios if not self._interno(d)]
        # Mensagem que nao sai da casa e' outro evento, e a politica trata os
        # dois de forma diferente. EMAIL_INTERNO e' canal catalogado.
        canal = "EMAIL" if externos else "EMAIL_INTERNO"
        destino = ", ".join(externos or destinatarios)

        # S/MIME so' e' oferecido ao motor quando ha' certificado para TODOS os
        # destinatarios externos. Prometer cifra que nao se consegue entregar
        # seria a mesma encenacao que este trabalho esta' desfazendo.
        pode_smime = self._pode_smime(externos)

        pior: Optional[Dict] = None
        transformadas = 0
        cifra_pendente = False

        assunto = str(msg.get("Subject", ""))
        corpo_texto, partes = _decompor(msg)

        texto_total = f"{assunto}\n{corpo_texto}"
        if texto_total.strip():
            r = self.server.decidir(dados=None, texto=texto_total, canal=canal,
                                    usuario=remetente, destino=destino,
                                    nome_arquivo="(corpo da mensagem)",
                                    cifra_delegada=pode_smime)
            if not r.get("permitido", True):
                pior = r
            cifra_pendente = cifra_pendente or bool(r.get("cifra_pendente"))
            if r.get("texto_mascarado"):
                _substituir_corpo(msg, r["texto_mascarado"])
                transformadas += 1

        for parte, dados, nome in partes:
            r = self.server.decidir(dados=dados, canal=canal, usuario=remetente,
                                    destino=destino, nome_arquivo=nome,
                                    cifra_delegada=pode_smime)
            if not r.get("permitido", True):
                pior = r
                continue
            cifra_pendente = cifra_pendente or bool(r.get("cifra_pendente"))
            if r.get("conteudo_base64"):
                import base64
                _substituir_anexo(parte, base64.b64decode(r["conteudo_base64"]),
                                  r.get("mime_saida", ""),
                                  r.get("nome_saida", "") or nome)
                transformadas += 1

        if pior is not None:
            return {"permitido": False, "mensagem": pior.get("mensagem", ""),
                    "incidente": pior.get("incidente", ""),
                    "mensagem_final": bruto}

        final = msg.as_bytes() if transformadas else bruto

        if cifra_pendente:
            final = self._envelopar(final, externos)

        return {"permitido": True, "mensagem_final": final,
                "transformadas": transformadas}

    def _pode_smime(self, externos: Sequence[str]) -> bool:
        if not externos or self.server.certificados is None:
            return False
        return all(self.server.certificados.obter(e) is not None
                   for e in externos)

    def _envelopar(self, mensagem: bytes, externos: Sequence[str]) -> bytes:
        from acoes.cripto import envelopar_smime
        resultado = envelopar_smime(mensagem, externos, self.server.certificados)
        if not resultado.aplicado:
            # O certificado sumiu entre a conferencia e o envelope (foi
            # removido, ou expirou a leitura). Levantar aqui faz o `handle`
            # responder 451 e a mensagem NAO sai -- que e' o comportamento
            # certo para uma cifra prometida e nao cumprida.
            raise RuntimeError(f"S/MIME prometido e nao cumprido: {resultado.motivo}")
        return resultado.conteudo

    def _interno(self, endereco: str) -> bool:
        dominio = endereco.split("@")[-1].lower()
        return any(dominio == d or dominio.endswith("." + d)
                   for d in self.server.dominios_internos)

    # -------------------------------------------------------------- entrega
    def _entregar(self, remetente: str, destinatarios: List[str], bruto: bytes):
        import smtplib
        host, porta = self.server.destino_real
        with smtplib.SMTP(host, porta, timeout=30) as s:
            s.ehlo()
            if self.server.starttls_saida:
                # O contexto padrao do Python confere cadeia e nome. Um relay
                # com certificado proprio precisa da CA no truststore do
                # sistema -- e e' isso mesmo que se quer: aceitar qualquer
                # certificado tornaria o TLS decorativo.
                s.starttls(context=ssl.create_default_context())
                s.ehlo()
            s.sendmail(remetente, destinatarios, bruto)


# --------------------------------------------------------------- auxiliares
def _decompor(msg) -> Tuple[str, List]:
    """Separa o corpo textual dos anexos, guardando a REFERENCIA da parte.

    Guardar a parte (e nao so' os bytes) e' o que permite reescrever o anexo no
    lugar. A versao anterior copiava os bytes para uma lista e por isso nunca
    teve como devolver a mensagem alterada.
    """
    corpo_texto = ""
    partes = []
    for parte in msg.walk():
        if parte.get_content_maintype() == "multipart":
            continue
        nome = parte.get_filename() or ""
        try:
            dados = parte.get_payload(decode=True) or b""
        except Exception:                                   # noqa: BLE001
            dados = b""
        if nome:
            partes.append((parte, dados, nome))
        elif parte.get_content_type() == "text/plain":
            corpo_texto += dados.decode(parte.get_content_charset() or "utf-8",
                                        "replace")
    return corpo_texto, partes


def _substituir_corpo(msg, texto: str) -> None:
    """Troca o texto de TODA parte text/plain pela versao redigida.

    Tambem remove as partes text/html quando existem: entregar o HTML original
    ao lado do texto mascarado entregaria o dado em claro pela outra metade da
    mensagem, e e' o cliente de e-mail quem escolhe qual mostrar.
    """
    trocou = False
    for parte in msg.walk():
        if parte.get_content_type() == "text/plain":
            parte.set_content(texto)
            trocou = True
    for parte in msg.walk():
        if parte.get_content_type() == "text/html":
            parte.set_content(
                "Esta mensagem continha dados pessoais e foi entregue apenas "
                "na versao em texto, com os valores mascarados pela politica "
                "de protecao de dados.")
    if not trocou and not msg.is_multipart():
        msg.set_content(texto)


def _substituir_anexo(parte, conteudo: bytes, mime: str, nome: str) -> None:
    tipo, _, subtipo = (mime or "application/octet-stream").partition("/")
    subtipo = (subtipo.split(";")[0].strip() or "octet-stream")
    parte.set_content(conteudo, maintype=tipo or "application",
                      subtype=subtipo, filename=nome)


_ENDERECO = re.compile(r"<([^>]*)>")


def _endereco(comando: str) -> str:
    m = _ENDERECO.search(comando)
    if m:
        return m.group(1).strip()
    _, _, resto = comando.partition(":")
    return resto.strip()
