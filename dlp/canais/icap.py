# -*- coding: utf-8 -*-
"""DLP Protector: servidor ICAP (RFC 3507) para inspecao em linha.

Squid, Blue Coat e outros proxies falam ICAP. Apontando o proxy da prefeitura
para ca', TODO upload de navegador -- para Gmail, Drive, WeTransfer, o que for
-- passa pelo mesmo motor e pela mesma politica do portal.

Implementa REQMOD (inspeciona o que SOBE) e RESPMOD (o que DESCE), mais o
OPTIONS que o proxy consulta no arranque. Sem dependencia externa.
"""
from __future__ import annotations

import re
import socketserver
import traceback
from typing import Callable, Dict, Tuple

VERSAO = "ICAP/1.0"


class ServidorIcap(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, endereco, decidir: Callable[..., Dict], servico_nome="dlp"):
        self.decidir = decidir
        self.servico_nome = servico_nome
        super().__init__(endereco, ManipuladorIcap)


class ManipuladorIcap(socketserver.StreamRequestHandler):
    timeout = 60

    def handle(self):                                       # noqa: A003
        try:
            linha = self.rfile.readline()
            if not linha:
                return
            partes = linha.decode("latin-1").split()
            if len(partes) < 2:
                return
            metodo, _uri = partes[0].upper(), partes[1]
            cabecalhos = self._ler_cabecalhos()
            if metodo == "OPTIONS":
                self._opcoes()
            elif metodo in ("REQMOD", "RESPMOD"):
                self._modificar(metodo, cabecalhos)
            else:
                self._estado(405, "Method Not Allowed")
        except Exception:                                   # noqa: BLE001
            traceback.print_exc()
            try:
                self._estado(500, "Server Error")
            except Exception:                               # noqa: BLE001
                pass

    def _estado(self, codigo: int, texto: str) -> None:
        """Resposta ICAP sem corpo.

        DEFEITO CORRIGIDO em 2026-08-31: os dois pontos que respondem erro
        chamavam `self._responder(...)`, um metodo que NAO EXISTE nesta classe.
        O AttributeError caia no `except` de cima, que tentava responder de
        novo pelo mesmo metodo inexistente e engolia a segunda falha -- o
        resultado era o proxy sem resposta nenhuma, esperando ate' o tempo
        limite. Um metodo ICAP desconhecido travava a conexao em vez de
        devolver 405. Nunca foi percebido porque o ICAP jamais tinha sido
        exercitado ponta a ponta (PENDENCIAS, item 3).
        """
        self.wfile.write(
            (f"{VERSAO} {codigo} {texto}\r\n"
             f"ISTag: \"dlp-1\"\r\n"
             f"Encapsulated: null-body=0\r\n\r\n").encode("latin-1"))

    def _ler_cabecalhos(self) -> Dict[str, str]:
        c: Dict[str, str] = {}
        while True:
            linha = self.rfile.readline()
            if not linha or linha in (b"\r\n", b"\n"):
                break
            nome, _, valor = linha.decode("latin-1").partition(":")
            c[nome.strip().lower()] = valor.strip()
        return c

    def _opcoes(self):
        corpo = (
            f"{VERSAO} 200 OK\r\n"
            f"Methods: REQMOD, RESPMOD\r\n"
            f"Service: DLP eXo 1.0\r\n"
            f"ISTag: \"dlp-1\"\r\n"
            f"Max-Connections: 200\r\n"
            f"Options-TTL: 3600\r\n"
            f"Allow: 204\r\n"
            f"Preview: 0\r\n"
            f"Transfer-Complete: *\r\n"
            f"Encapsulated: null-body=0\r\n\r\n"
        ).encode("latin-1")
        self.wfile.write(corpo)

    def _modificar(self, metodo: str, cabecalhos: Dict[str, str]):
        encapsulado = cabecalhos.get("encapsulated", "")
        corpo_http, cabecalho_http = self._ler_encapsulado(encapsulado)
        usuario = (cabecalhos.get("x-client-username")
                   or cabecalhos.get("x-authenticated-user") or "")
        ip = cabecalhos.get("x-client-ip", "")
        destino, nome = _extrair_destino_e_nome(cabecalho_http)

        resultado = self.server.decidir(
            dados=corpo_http or None, canal="ICAP", usuario=_decodificar_usuario(usuario),
            ip=ip, destino=destino, nome_arquivo=nome)

        if not resultado.get("permitido", True):
            self._bloquear(metodo, resultado)
            return

        transformado = resultado.get("conteudo_base64")
        if transformado:
            # MASCARAR e CRIPTOGRAFAR no caminho de rede. Sem isto o ICAP so'
            # sabia deixar passar ou barrar: uma regra que mandasse mascarar
            # entregaria o conteudo INTEIRO, porque 204 significa "nao mexi em
            # nada". A acao existia na politica e nao existia neste canal.
            import base64 as _b64
            self._substituir(metodo, _b64.b64decode(transformado),
                             resultado.get("mime_saida", ""),
                             cabecalho_http)
            return
        # 204: "nao modifiquei nada" -- o proxy segue com o original.
        self.wfile.write(f"{VERSAO} 204 No Content\r\n\r\n".encode("latin-1"))

    def _ler_encapsulado(self, encapsulado: str) -> Tuple[bytes, str]:
        """Le o corpo em chunked, que e' como o ICAP encapsula."""
        if "body=" not in encapsulado:
            return b"", ""
        cabecalho_http = b""
        # Cabecalho HTTP encapsulado vem antes do corpo, terminado por linha vazia.
        if "hdr=" in encapsulado:
            while True:
                linha = self.rfile.readline()
                if not linha or linha in (b"\r\n", b"\n"):
                    break
                cabecalho_http += linha
        pedacos = []
        while True:
            tamanho_linha = self.rfile.readline()
            if not tamanho_linha:
                break
            try:
                tamanho = int(tamanho_linha.split(b";")[0].strip() or b"0", 16)
            except ValueError:
                break
            if tamanho == 0:
                self.rfile.readline()
                break
            pedacos.append(self.rfile.read(tamanho))
            self.rfile.readline()
        return b"".join(pedacos), cabecalho_http.decode("latin-1", "replace")

    def _substituir(self, metodo: str, conteudo: bytes, mime: str,
                    cabecalho_http: str) -> None:
        """Devolve ao proxy o conteudo TRANSFORMADO no lugar do original."""
        tipo = mime or "application/octet-stream"
        if metodo == "RESPMOD":
            cabecalho_interno = (
                "HTTP/1.1 200 OK\r\n"
                f"Content-Type: {tipo}\r\n"
                f"Content-Length: {len(conteudo)}\r\n\r\n").encode("latin-1")
            rotulo = "res"
        else:
            # Em REQMOD o que se substitui e' o CORPO da requisicao que sobe.
            # A primeira linha original e' preservada: trocar o metodo ou a URL
            # do usuario nao e' funcao do DLP.
            primeira = (cabecalho_http.split("\r\n", 1)[0]
                        if cabecalho_http else "POST / HTTP/1.1")
            cabecalho_interno = (
                f"{primeira}\r\n"
                f"Content-Type: {tipo}\r\n"
                f"Content-Length: {len(conteudo)}\r\n\r\n").encode("latin-1")
            rotulo = "req"
        cabecalho = (f"{VERSAO} 200 OK\r\n"
                     f"ISTag: \"dlp-1\"\r\n"
                     f"Encapsulated: {rotulo}-hdr=0, "
                     f"{rotulo}-body={len(cabecalho_interno)}\r\n\r\n").encode("latin-1")
        corpo = (cabecalho_interno + f"{len(conteudo):x}\r\n".encode("latin-1")
                 + conteudo + b"\r\n0\r\n\r\n")
        self.wfile.write(cabecalho + corpo)

    def _bloquear(self, metodo: str, resultado: Dict):
        mensagem = resultado.get("mensagem") or \
            "Envio bloqueado pela politica de protecao de dados."
        pagina = (
            "<!doctype html><meta charset='utf-8'>"
            "<title>Envio bloqueado</title>"
            "<body style=\"font-family:system-ui,sans-serif;max-width:40rem;"
            "margin:3rem auto\"><h1>Envio bloqueado</h1>"
            f"<p>{mensagem}</p>"
            f"<p style='color:#555'>Regra: {resultado.get('regra_nome','')}<br>"
            f"Incidente: {resultado.get('incidente','')}</p>"
            "</body>").encode("utf-8")
        resposta_http = (
            "HTTP/1.1 403 Forbidden\r\n"
            "Content-Type: text/html; charset=utf-8\r\n"
            f"Content-Length: {len(pagina)}\r\n\r\n").encode("latin-1")
        encapsulado = f"res-hdr=0, res-body={len(resposta_http)}"
        cabecalho = (f"{VERSAO} 200 OK\r\n"
                     f"ISTag: \"dlp-1\"\r\n"
                     f"Encapsulated: {encapsulado}\r\n\r\n").encode("latin-1")
        corpo = (resposta_http + f"{len(pagina):x}\r\n".encode("latin-1")
                 + pagina + b"\r\n0\r\n\r\n")
        self.wfile.write(cabecalho + corpo)


def _decodificar_usuario(valor: str) -> str:
    """Squid manda o usuario em base64 quando configurado assim."""
    if not valor:
        return ""
    import base64
    try:
        return base64.b64decode(valor).decode("utf-8", "replace")
    except Exception:                                       # noqa: BLE001
        return valor


_HOST = re.compile(r"^Host:\s*(.+)$", re.IGNORECASE | re.MULTILINE)
_NOME = re.compile(r'filename\*?=(?:UTF-8\'\')?"?([^";\r\n]+)', re.IGNORECASE)


def _extrair_destino_e_nome(cabecalho_http: str) -> Tuple[str, str]:
    destino = ""
    m = _HOST.search(cabecalho_http or "")
    if m:
        destino = m.group(1).strip()
    nome = ""
    n = _NOME.search(cabecalho_http or "")
    if n:
        nome = n.group(1).strip()
    return destino, nome
