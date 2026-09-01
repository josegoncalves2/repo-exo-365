# -*- coding: utf-8 -*-
"""Cofre: guarda binario CIFRADO em repouso, com integridade verificavel.

POR QUE O COFRE EXISTE. Quarentena que "bloqueia e esquece" nao e' quarentena:
o arquivo continua exatamente onde estava, ninguem consegue examina-lo depois,
e nao ha' caminho de restauracao. Para a quarentena ser real e' preciso um
lugar onde o conteudo retido fique -- e esse lugar passa a concentrar
justamente o material mais sensivel da casa. Guardar isso em claro seria
trocar um risco por um pior.

DESENHO. AES-256-GCM, uma chave POR ITEM derivada da chave mestra com HKDF-
SHA256 usando o identificador do item como sal. Consequencias praticas:

  * dois itens nunca compartilham chave, entao vazar um nao vaza o acervo;
  * GCM autentica: adulteracao no disco e' detectada na leitura, nao ignorada;
  * o identificador entra na derivacao, entao trocar o nome do arquivo no disco
    impede a decifragem -- o item nao pode ser "movido de gaveta" sem que se
    perceba.

A chave mestra e' gerada uma vez, guardada com modo 600 no volume, e NUNCA
aparece no codigo, no log ou na API.

BIBLIOTECA DE TERCEIRO, DELIBERADO. O resto deste servico e' biblioteca padrao
por principio (ver servidor.py). Aqui a regra se inverte, e a razao e' honesta:
AES escrito a mao e' pior que AES da `cryptography`, que e' auditada, tem
backend em libcrypto e recebe correcao de canal lateral. Paranoia tecnica nao
e' escrever a propria cifra; e' nao escrever.
"""
from __future__ import annotations

import hashlib
import os
import secrets
from dataclasses import dataclass
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

TAMANHO_NONCE = 12          # recomendado para GCM
TAMANHO_CHAVE = 32          # AES-256
_INFO = b"cofre-dlp-exo-v1"


class CofreCorrompido(Exception):
    """Conteudo adulterado, truncado ou cifrado com outra chave mestra."""


@dataclass(frozen=True)
class ItemGuardado:
    identificador: str
    sha256: str
    tamanho: int


class Cofre:
    """Armazem de blobs cifrados. Um arquivo por item, nome = identificador."""

    def __init__(self, diretorio: str, chave_mestra: bytes):
        if len(chave_mestra) < TAMANHO_CHAVE:
            raise ValueError("chave mestra do cofre curta demais")
        self._dir = diretorio
        self._mestra = chave_mestra
        os.makedirs(self._dir, mode=0o700, exist_ok=True)

    # ------------------------------------------------------------- derivacao
    def _chave(self, identificador: str) -> bytes:
        derivador = HKDF(algorithm=hashes.SHA256(), length=TAMANHO_CHAVE,
                         salt=identificador.encode("utf-8"), info=_INFO)
        return derivador.derive(self._mestra)

    def _caminho(self, identificador: str) -> str:
        # Identificador e' gerado aqui (hex), nunca vem do usuario. A conferencia
        # existe porque um dia alguem vai passar um caminho por engano, e o
        # cofre nao pode virar escrita arbitraria em disco.
        if not identificador or not all(c in "0123456789abcdef" for c in identificador):
            raise ValueError(f"identificador de cofre invalido: {identificador!r}")
        return os.path.join(self._dir, identificador + ".cofre")

    # --------------------------------------------------------------- escrita
    def guardar(self, dados: bytes) -> ItemGuardado:
        """Cifra e grava. Devolve identificador, hash do CLARO e tamanho.

        O sha256 e' do conteudo em claro de proposito: e' o que permite ao
        analista confirmar que o arquivo restaurado e' bit a bit o que foi
        retido. Guardar o hash do cifrado nao provaria nada ao usuario final.
        """
        identificador = secrets.token_hex(16)
        nonce = os.urandom(TAMANHO_NONCE)
        cifrado = AESGCM(self._chave(identificador)).encrypt(nonce, dados, None)
        caminho = self._caminho(identificador)
        # O_EXCL: colisao de identificador (improvavel, mas nao impossivel de
        # se induzir) falha em vez de sobrescrever material retido.
        fd = os.open(caminho, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(nonce + cifrado)
        return ItemGuardado(identificador, hashlib.sha256(dados).hexdigest(),
                            len(dados))

    # ---------------------------------------------------------------- leitura
    def ler(self, identificador: str) -> bytes:
        caminho = self._caminho(identificador)
        if not os.path.exists(caminho):
            raise FileNotFoundError(f"item {identificador} nao esta' no cofre")
        with open(caminho, "rb") as f:
            bruto = f.read()
        if len(bruto) <= TAMANHO_NONCE:
            raise CofreCorrompido(f"item {identificador} truncado")
        try:
            return AESGCM(self._chave(identificador)).decrypt(
                bruto[:TAMANHO_NONCE], bruto[TAMANHO_NONCE:], None)
        except InvalidTag as e:
            raise CofreCorrompido(
                f"item {identificador} nao autentica: conteudo adulterado no "
                "disco ou chave mestra trocada") from e

    def existe(self, identificador: str) -> bool:
        try:
            return os.path.exists(self._caminho(identificador))
        except ValueError:
            return False

    def bytes_ocupados(self) -> int:
        total = 0
        for nome in os.listdir(self._dir):
            if nome.endswith(".cofre"):
                total += os.path.getsize(os.path.join(self._dir, nome))
        return total


def chave_persistente(caminho: str) -> bytes:
    """Le a chave mestra do volume; gera na primeira vez, com modo 600.

    Nao ha' rotacao automatica: girar a chave sem reescrever o acervo tornaria
    ilegivel tudo o que ja' esta' retido. Rotacao e' operacao consciente, com o
    acervo relido item a item -- e por isso nao acontece por acidente aqui.
    """
    if os.path.exists(caminho):
        with open(caminho, "rb") as f:
            valor = f.read()
        if len(valor) < TAMANHO_CHAVE:
            raise ValueError(f"chave mestra em {caminho} curta demais")
        return valor
    valor = secrets.token_bytes(TAMANHO_CHAVE)
    os.makedirs(os.path.dirname(caminho) or ".", exist_ok=True)
    fd = os.open(caminho, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(valor)
    return valor
