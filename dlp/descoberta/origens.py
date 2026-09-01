# -*- coding: utf-8 -*-
"""De onde a varredura le. Cada origem sabe listar e ler; nada mais.

A separacao existe para que acrescentar um repositorio (SMB montado, um bucket,
outro portal) seja escrever uma classe com dois metodos, sem tocar no motor nem
na politica. E' tambem o que permite testar o rastreador sem rede.

SOBRE "INTEGRACAO != IMPLEMENTACAO", que e' regra deste projeto: compartilhamento
CIFS/SMB e NFS sao varridos pela `OrigemArquivos`, apontada para o ponto de
montagem. Montar o compartilhamento e' tarefa do sistema operacional, nao deste
servico -- e e' assim que qualquer DLP serio faz. O conector esta' pronto e
funciona no minuto em que existir um caminho montado.
"""
from __future__ import annotations

import base64
import os
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Iterator, List, Optional

ESPACO_DAV = "{DAV:}"


@dataclass(frozen=True)
class Recurso:
    """Um item encontrado. `assinatura` e' o que decide se mudou."""
    caminho: str
    nome: str
    tamanho: int
    assinatura: str
    e_pasta: bool = False


class ErroDeOrigem(Exception):
    """Falha ao listar ou ler. O rastreador conta e segue -- nao aborta."""


class OrigemArquivos:
    """Sistema de arquivos local ou montado (CIFS/SMB, NFS, disco).

    A raiz e' resolvida uma vez e TODO caminho e' conferido contra ela: sem
    isso, um link simbolico dentro do compartilhamento faria a varredura sair
    do alvo e ler /etc do proprio container.
    """

    tipo = "ARQUIVOS"

    def __init__(self, raiz: str):
        self.raiz = os.path.realpath(raiz)
        if not os.path.isdir(self.raiz):
            raise ErroDeOrigem(f"raiz inexistente ou nao e' diretorio: {raiz}")

    def _dentro(self, caminho: str) -> bool:
        real = os.path.realpath(caminho)
        return real == self.raiz or real.startswith(self.raiz + os.sep)

    def listar(self, alvo: str = "") -> Iterator[Recurso]:
        inicio = os.path.join(self.raiz, alvo.lstrip("/")) if alvo else self.raiz
        if not self._dentro(inicio):
            raise ErroDeOrigem(f"alvo fora da raiz da origem: {alvo}")
        for pasta, subpastas, arquivos in os.walk(inicio):
            # Nao segue link que aponte para fora: a varredura tem de ficar
            # dentro do que o operador mandou varrer.
            subpastas[:] = [d for d in subpastas
                            if self._dentro(os.path.join(pasta, d))]
            for nome in arquivos:
                completo = os.path.join(pasta, nome)
                if not self._dentro(completo) or os.path.islink(completo):
                    continue
                try:
                    st = os.stat(completo)
                except OSError as e:
                    raise ErroDeOrigem(f"{completo}: {e}") from e
                yield Recurso(
                    caminho=os.path.relpath(completo, self.raiz),
                    nome=nome, tamanho=st.st_size,
                    assinatura=f"{st.st_size}:{int(st.st_mtime)}")

    def ler(self, recurso: Recurso) -> bytes:
        completo = os.path.join(self.raiz, recurso.caminho)
        if not self._dentro(completo):
            raise ErroDeOrigem(f"leitura fora da raiz: {recurso.caminho}")
        with open(completo, "rb") as f:
            return f.read()

    def descricao(self) -> str:
        return f"arquivos:{self.raiz}"


class OrigemWebdav:
    """Acervo do portal pelo WebDAV/JCR.

    AUTENTICACAO: o webapp `webdav` do eXo declara DIGEST e a via REST/JCR
    aceita BASIC. Os dois tratadores sao instalados e o urllib escolhe pelo
    desafio que o servidor mandar -- fixar um deles quebraria a varredura numa
    das duas rotas, e qual delas depende de configuracao do portal, nao deste
    servico.

    LISTAGEM por PROPFIND com Depth:1, descendo pasta a pasta. Depth:infinity
    e' recusado por muitos servidores e, quando aceito, produz uma resposta
    unica de dezenas de megabytes -- exatamente o que nao se quer num
    inspetor que ja' vai ler cada arquivo.
    """

    tipo = "WEBDAV"

    def __init__(self, base: str, usuario: str, senha: str,
                 tempo_limite: int = 60):
        if not base:
            raise ErroDeOrigem("URL base do WebDAV nao configurada")
        self.base = base.rstrip("/") + "/"
        self.usuario = usuario
        self.tempo_limite = tempo_limite
        gestor = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        gestor.add_password(None, self.base, usuario, senha)
        self._abridor = urllib.request.build_opener(
            urllib.request.HTTPBasicAuthHandler(gestor),
            urllib.request.HTTPDigestAuthHandler(gestor))
        # Alguns servidores so' oferecem o desafio depois do 401. O cabecalho
        # pre-autenticado evita uma ida e volta por arquivo lido -- num acervo
        # de milhares de itens isso e' a diferenca entre minutos e horas.
        self._basico = "Basic " + base64.b64encode(
            f"{usuario}:{senha}".encode("utf-8")).decode("ascii")

    def _url(self, caminho: str) -> str:
        return urllib.parse.urljoin(
            self.base, urllib.parse.quote(caminho.lstrip("/")))

    def _requisitar(self, url: str, metodo: str,
                    cabecalhos: Optional[dict] = None) -> bytes:
        pedido = urllib.request.Request(url, method=metodo)
        pedido.add_header("Authorization", self._basico)
        for k, v in (cabecalhos or {}).items():
            pedido.add_header(k, v)
        try:
            with self._abridor.open(pedido, timeout=self.tempo_limite) as resposta:
                return resposta.read()
        except urllib.error.HTTPError as e:
            raise ErroDeOrigem(f"{metodo} {url}: HTTP {e.code} {e.reason}") from e
        except (urllib.error.URLError, OSError) as e:
            raise ErroDeOrigem(f"{metodo} {url}: {e}") from e

    def _propfind(self, caminho: str) -> List[Recurso]:
        corpo = self._requisitar(self._url(caminho), "PROPFIND",
                                 {"Depth": "1",
                                  "Content-Type": "application/xml"})
        try:
            raiz = ET.fromstring(corpo)
        except ET.ParseError as e:
            raise ErroDeOrigem(f"PROPFIND {caminho}: resposta nao e' XML: {e}") from e

        base_caminho = urllib.parse.urlparse(self.base).path
        achados: List[Recurso] = []
        for resposta in raiz.findall(f"{ESPACO_DAV}response"):
            href = resposta.findtext(f"{ESPACO_DAV}href") or ""
            relativo = urllib.parse.unquote(
                urllib.parse.urlparse(href).path)
            if relativo.startswith(base_caminho):
                relativo = relativo[len(base_caminho):]
            relativo = relativo.strip("/")
            # A propria pasta consultada volta na resposta; ignora-la evita
            # laco infinito de "pasta que contem a si mesma".
            if not relativo or relativo == caminho.strip("/"):
                continue
            propriedades = resposta.find(
                f"{ESPACO_DAV}propstat/{ESPACO_DAV}prop")
            if propriedades is None:
                continue
            e_pasta = propriedades.find(
                f"{ESPACO_DAV}resourcetype/{ESPACO_DAV}collection") is not None
            tamanho = int(propriedades.findtext(
                f"{ESPACO_DAV}getcontentlength") or 0)
            etag = (propriedades.findtext(f"{ESPACO_DAV}getetag") or "").strip('"')
            modificado = propriedades.findtext(
                f"{ESPACO_DAV}getlastmodified") or ""
            achados.append(Recurso(
                caminho=relativo,
                nome=relativo.rsplit("/", 1)[-1],
                tamanho=tamanho,
                assinatura=etag or f"{tamanho}:{modificado}",
                e_pasta=e_pasta))
        return achados

    def listar(self, alvo: str = "") -> Iterator[Recurso]:
        # Travessia em largura com fila explicita, e nao recursao: acervo com
        # hierarquia funda estouraria a pilha do interpretador, e um acervo de
        # prefeitura tem hierarquia funda.
        fila = [alvo.strip("/")]
        vistas = set()
        while fila:
            atual = fila.pop(0)
            if atual in vistas:
                continue
            vistas.add(atual)
            for recurso in self._propfind(atual):
                if recurso.e_pasta:
                    fila.append(recurso.caminho)
                else:
                    yield recurso

    def ler(self, recurso: Recurso) -> bytes:
        return self._requisitar(self._url(recurso.caminho), "GET")

    def descricao(self) -> str:
        return f"webdav:{self.base} (usuario {self.usuario})"
