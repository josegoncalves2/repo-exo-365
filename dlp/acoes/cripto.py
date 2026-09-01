# -*- coding: utf-8 -*-
"""Acao CRIPTOGRAFAR: entrega cifrada em vez de negar ou entregar em claro.

DUAS FORMAS, porque sao dois canais com destinatarios diferentes:

  1. ARQUIVO (download, WebDAV, nuvem, endpoint) -> ZIP AES-256 no padrao
     WinZip AE-2. Escolhido porque e' o unico formato de arquivo cifrado que
     7-Zip, WinRAR, Keka e o Explorador de Arquivos de qualquer estacao abrem
     sem instalar nada. Formato proprio seria criptografia perfeita e inutil:
     o servidor publico do outro lado nao conseguiria abrir, e a acao viraria
     um BLOQUEAR disfarcado.

  2. E-MAIL -> S/MIME (CMS EnvelopedData) para o certificado do destinatario,
     quando ha' um cadastrado; e STARTTLS obrigatorio na entrega ao relay em
     qualquer caso. Sem certificado, a acao NAO e' dada por cumprida: quem
     chamou decide o que fazer (o padrao e' nao deixar sair em claro).

O AE-2 e' implementado aqui porque a `zipfile` da biblioteca padrao nao escreve
arquivo cifrado. O que NAO e' implementado a mao e' a criptografia: AES, HMAC e
PBKDF2 vem da `cryptography` e do `hashlib`. A especificacao seguida e' a nota
tecnica do formato AE-x da WinZip (AE-2: CRC gravado como zero, integridade
garantida pelo codigo de autenticacao HMAC-SHA1).
"""
from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import struct
import zlib
from dataclasses import dataclass
from typing import List, Optional, Sequence, Tuple

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# ---------------------------------------------------------------- constantes
_METODO_AES = 99                 # compressao 99 = "conteudo cifrado com AE"
_ID_EXTRA_AE = 0x9901
_VERSAO_AE2 = 2
_FORCA_AES256 = 3
_TAMANHO_SAL = 16                # 16 bytes para AES-256
_ITERACOES_PBKDF2 = 1000         # fixado pela especificacao AE-x
_TAMANHO_AUTENTICACAO = 10       # HMAC-SHA1 truncado, pela especificacao
_ASSINATURA_LOCAL = 0x04034B50
_ASSINATURA_CENTRAL = 0x02014B50
_ASSINATURA_FIM = 0x06054B50

ALFABETO_SENHA = ("ABCDEFGHJKLMNPQRSTUVWXYZ"      # sem I e O
                  "abcdefghijkmnopqrstuvwxyz"      # sem l
                  "23456789")                      # sem 0 e 1


def gerar_senha(tamanho: int = 24) -> str:
    """Senha aleatoria sem caracteres que se confundem ao serem ditados.

    A senha e' lida por gente: vai por e-mail e as vezes por telefone. I/l/1 e
    O/0 juntos produzem chamado de suporte, nao seguranca.
    """
    return "".join(secrets.choice(ALFABETO_SENHA) for _ in range(tamanho))


# ------------------------------------------------------------------- AE-2
def _derivar(senha: str, sal: bytes) -> Tuple[bytes, bytes, bytes]:
    """PBKDF2-HMAC-SHA1 -> (chave AES, chave HMAC, verificador de senha)."""
    bruto = hashlib.pbkdf2_hmac("sha1", senha.encode("utf-8"), sal,
                                _ITERACOES_PBKDF2, 32 + 32 + 2)
    return bruto[:32], bruto[32:64], bruto[64:]


def _fluxo_ctr(chave: bytes, dados: bytes) -> bytes:
    """CTR do AE-x: contador de 16 bytes LITTLE-ENDIAN, incrementado ANTES.

    Nao da' para usar o modo CTR pronto da biblioteca: ele conta big-endian
    sobre o bloco inteiro, e o AE-x conta little-endian. A diferenca so'
    aparece no bloco 256 -- ou seja, funcionaria nos testes pequenos e
    produziria arquivo ilegivel em producao. Por isso o fluxo e' montado aqui,
    com AES-ECB sobre cada bloco de contador.
    """
    cifra = Cipher(algorithms.AES(chave), modes.ECB()).encryptor()
    saida = bytearray(len(dados))
    contador = bytearray(16)
    for inicio in range(0, len(dados), 16):
        for i in range(16):
            contador[i] = (contador[i] + 1) & 0xFF
            if contador[i] != 0:
                break
        mascara = cifra.update(bytes(contador))
        fim = min(inicio + 16, len(dados))
        for j in range(inicio, fim):
            saida[j] = dados[j] ^ mascara[j - inicio]
    return bytes(saida)


def _campo_extra_ae(metodo_real: int) -> bytes:
    """Campo extra 0x9901: e' ele que diz ao descompactador que ha' AES aqui.

    Cabecalho (4 bytes: id + tamanho) mais 7 bytes de corpo -- versao(2),
    fabricante(2), forca(1) e o metodo de compressao REAL(2), que o campo 
    `compression` do ZIP nao pode carregar porque la' fica o valor 99.
    """
    corpo = struct.pack("<H2sBH", _VERSAO_AE2, b"AE", _FORCA_AES256, metodo_real)
    return struct.pack("<HH", _ID_EXTRA_AE, len(corpo)) + corpo


def _data_dos(momento: Optional[float] = None) -> Tuple[int, int]:
    """Hora e data no formato do MS-DOS, que e' o que o ZIP guarda.

    Zero em ambos e' tolerado pelos descompactadores, mas produz "1980-00-00"
    na listagem e faz o arquivo parecer corrompido para quem confere. O custo
    de gravar a hora certa e' uma linha.
    """
    import time as _t
    t = _t.localtime(momento if momento is not None else _t.time())
    if t.tm_year < 1980:
        return 0, 0x21
    hora = (t.tm_hour << 11) | (t.tm_min << 5) | (t.tm_sec // 2)
    data = ((t.tm_year - 1980) << 9) | (t.tm_mon << 5) | t.tm_mday
    return hora, data


def zip_aes256(nome_interno: str, conteudo: bytes, senha: str,
               comprimir: bool = True) -> bytes:
    """Monta um ZIP de UMA entrada, cifrada em AES-256 no padrao AE-2.

    `comprimir` usa deflate antes de cifrar. Comprimir depois de cifrar nao
    reduz nada (dado cifrado e' incompressivel), e por isso a ordem e' esta.
    """
    if not nome_interno:
        raise ValueError("nome interno do arquivo e' obrigatorio")
    nome = nome_interno.encode("utf-8")
    metodo_real = 8 if comprimir else 0
    if comprimir:
        compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
        corpo_claro = compressor.compress(conteudo) + compressor.flush()
    else:
        corpo_claro = conteudo

    sal = os.urandom(_TAMANHO_SAL)
    chave_aes, chave_hmac, verificador = _derivar(senha, sal)
    cifrado = _fluxo_ctr(chave_aes, corpo_claro)
    autenticacao = hmac.new(chave_hmac, cifrado, hashlib.sha1).digest()[
        :_TAMANHO_AUTENTICACAO]
    dados_entrada = sal + verificador + cifrado + autenticacao

    # AE-2 grava CRC zero de proposito: a integridade e' do HMAC, e o CRC do
    # claro num arquivo cifrado seria um oraculo sobre o conteudo.
    crc = 0
    tamanho_comprimido = len(dados_entrada)
    tamanho_original = len(conteudo)
    extra = _campo_extra_ae(metodo_real)
    hora, data = _data_dos()
    # bit 0 = conteudo cifrado; bit 11 = nome do arquivo em UTF-8. Sem o bit 11
    # o nome com acento vira mojibake em qualquer descompactador -- e nome de
    # documento de prefeitura tem acento.
    marcadores = 0x0801

    cabecalho_local = struct.pack(
        "<IHHHHHIIIHH", _ASSINATURA_LOCAL, 51, marcadores, _METODO_AES, hora,
        data, crc, tamanho_comprimido, tamanho_original, len(nome), len(extra))
    local = cabecalho_local + nome + extra + dados_entrada

    deslocamento_central = len(local)
    central = struct.pack(
        "<IHHHHHHIIIHHHHHII", _ASSINATURA_CENTRAL, 51, 51, marcadores,
        _METODO_AES, hora, data, crc, tamanho_comprimido, tamanho_original,
        len(nome), len(extra), 0, 0, 0, 0, 0) + nome + extra

    fim = struct.pack("<IHHHHIIH", _ASSINATURA_FIM, 0, 0, 1, 1, len(central),
                      deslocamento_central, 0)
    return local + central + fim


# ------------------------------------------------------------------- S/MIME
@dataclass(frozen=True)
class ResultadoSmime:
    aplicado: bool
    conteudo: bytes
    motivo: str = ""
    destinatarios: Sequence[str] = ()


class RepositorioCertificados:
    """Certificados dos destinatarios, um PEM por endereco, no volume.

    Nao ha' descoberta automatica de certificado: LDAP e DNS podem devolver
    certificado de terceiro, e cifrar para a chave errada e' vazar com a
    aparencia de proteger. O certificado entra por cadastro explicito, pela API
    do console.
    """

    def __init__(self, diretorio: str):
        self._dir = diretorio
        os.makedirs(self._dir, mode=0o700, exist_ok=True)

    @staticmethod
    def _arquivo(endereco: str) -> str:
        seguro = "".join(c if c.isalnum() or c in "._-@" else "_"
                         for c in endereco.strip().lower())
        if not seguro:
            raise ValueError("endereco vazio")
        return seguro + ".pem"

    def caminho(self, endereco: str) -> str:
        return os.path.join(self._dir, self._arquivo(endereco))

    def guardar(self, endereco: str, pem: bytes) -> dict:
        from cryptography import x509
        certificado = x509.load_pem_x509_certificate(pem)   # valida antes de gravar
        caminho = self.caminho(endereco)
        fd = os.open(caminho, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(pem)
        return {"endereco": endereco.strip().lower(),
                "titular": certificado.subject.rfc4514_string(),
                "emissor": certificado.issuer.rfc4514_string(),
                "valido_ate": certificado.not_valid_after_utc.isoformat(),
                "serie": format(certificado.serial_number, "x")}

    def obter(self, endereco: str):
        from cryptography import x509
        caminho = self.caminho(endereco)
        if not os.path.exists(caminho):
            return None
        with open(caminho, "rb") as f:
            return x509.load_pem_x509_certificate(f.read())

    def listar(self) -> List[dict]:
        from cryptography import x509
        saida = []
        for nome in sorted(os.listdir(self._dir)):
            if not nome.endswith(".pem"):
                continue
            with open(os.path.join(self._dir, nome), "rb") as f:
                try:
                    c = x509.load_pem_x509_certificate(f.read())
                except ValueError as e:                     # noqa: PERF203
                    saida.append({"endereco": nome[:-4], "erro": str(e)})
                    continue
            saida.append({"endereco": nome[:-4],
                          "titular": c.subject.rfc4514_string(),
                          "emissor": c.issuer.rfc4514_string(),
                          "valido_ate": c.not_valid_after_utc.isoformat(),
                          "serie": format(c.serial_number, "x")})
        return saida


def envelopar_smime(mensagem: bytes, destinatarios: Sequence[str],
                    certificados: RepositorioCertificados) -> ResultadoSmime:
    """Cifra a mensagem inteira para os certificados cadastrados.

    Devolve `aplicado=False` com o motivo quando NENHUM destinatario tem
    certificado. Quem chamou decide -- e o padrao do produto e' nao deixar
    sair em claro o que a politica mandou cifrar.
    """
    from cryptography.hazmat.primitives.serialization import Encoding, pkcs7

    achados, faltando = [], []
    construtor = pkcs7.PKCS7EnvelopeBuilder().set_data(mensagem)
    for endereco in destinatarios:
        certificado = certificados.obter(endereco)
        if certificado is None:
            faltando.append(endereco)
            continue
        construtor = construtor.add_recipient(certificado)
        achados.append(endereco)

    if not achados:
        return ResultadoSmime(
            False, mensagem,
            f"nenhum certificado S/MIME cadastrado para {', '.join(faltando) or '(sem destinatario)'}")
    cifrada = construtor.encrypt(Encoding.SMIME, [pkcs7.PKCS7Options.Text])
    motivo = ""
    if faltando:
        motivo = ("cifrado para " + ", ".join(achados)
                  + "; sem certificado para " + ", ".join(faltando))
    return ResultadoSmime(True, cifrada, motivo, achados)
