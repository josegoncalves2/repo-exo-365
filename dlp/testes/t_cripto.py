# -*- coding: utf-8 -*-
"""Cifra: ZIP AES-256 conferido pelo 7z e S/MIME conferido pela chave privada.

Os dois casos usam uma implementacao INDEPENDENTE para abrir o que o nosso
codigo fechou. Um teste que cifra e decifra com o proprio codigo prova apenas
que ele e' consistente consigo mesmo -- e um arquivo que so' nos conseguimos
abrir e' um arquivo inutil para quem recebe.
"""
from __future__ import annotations

import os
import subprocess

from testes.apoio import (Temporario, caso, certo, contem, igual, levanta,
                          nao_contem)


def _tem_7z() -> bool:
    return any(os.access(os.path.join(p, "7z"), os.X_OK)
               for p in os.environ.get("PATH", "").split(os.pathsep))


@caso("cripto: ZIP AES-256 abre no 7z com a senha e recusa a senha errada")
def _():
    from acoes.cripto import gerar_senha, zip_aes256
    certo(_tem_7z(), "7z e' obrigatorio na imagem: e' a verificacao "
                     "independente da acao CRIPTOGRAFAR")
    with Temporario() as dir_:
        original = ("Relacao de servidores. CPF 529.982.247-25. "
                    "Linha repetida para haver o que comprimir.\n" * 60).encode()
        senha = gerar_senha()
        caminho = os.path.join(dir_, "saida.zip")
        with open(caminho, "wb") as f:
            f.write(zip_aes256("ficha funcional.txt", original, senha))

        listagem = subprocess.run(["7z", "l", "-slt", caminho],
                                  capture_output=True, text=True, check=True)
        contem("Method = AES-256", listagem.stdout,
               "o 7z tem de reconhecer AES-256")
        contem("ficha funcional.txt", listagem.stdout,
               "nome com espaco tem de sobreviver")

        saiu = os.path.join(dir_, "saiu")
        subprocess.run(["7z", "x", f"-o{saiu}", f"-p{senha}", caminho],
                       capture_output=True, check=True)
        with open(os.path.join(saiu, "ficha funcional.txt"), "rb") as f:
            igual(f.read(), original,
                  "o conteudo extraido tem de ser bit a bit o original")

        errado = subprocess.run(
            ["7z", "x", f"-o{os.path.join(dir_, 'nao')}", "-psenhaerrada",
             caminho], capture_output=True)
        certo(errado.returncode != 0, "senha errada nao pode abrir o arquivo")


@caso("cripto: o ZIP cifrado nao carrega o dado em claro")
def _():
    from acoes.cripto import gerar_senha, zip_aes256
    bruto = zip_aes256("dados.txt", b"CPF 529.982.247-25 do servidor",
                       gerar_senha())
    nao_contem(b"529.982.247-25", bruto, "valor em claro dentro do ZIP")


@caso("cripto: nome com acento sobrevive (marcador de UTF-8 no cabecalho)")
def _():
    from acoes.cripto import gerar_senha, zip_aes256
    certo(_tem_7z(), "7z ausente")
    with Temporario() as dir_:
        senha = gerar_senha()
        caminho = os.path.join(dir_, "acento.zip")
        with open(caminho, "wb") as f:
            f.write(zip_aes256("certidão de óbito.txt", b"conteudo", senha))
        listagem = subprocess.run(["7z", "l", "-slt", caminho],
                                  capture_output=True, text=True, check=True)
        contem("certidão de óbito.txt", listagem.stdout,
               "sem o bit 11 de UTF-8 o nome sai corrompido")


@caso("cripto: arquivo grande atravessa a fronteira de 256 blocos do contador")
def _():
    from acoes.cripto import gerar_senha, zip_aes256
    certo(_tem_7z(), "7z ausente")
    with Temporario() as dir_:
        # 64 KiB = 4096 blocos de 16 bytes. Se o contador CTR fosse big-endian
        # (o modo pronto da biblioteca), o erro so' apareceria depois do bloco
        # 255 -- passaria em qualquer teste pequeno e produziria arquivo
        # ilegivel em producao. E' exatamente o que este caso cobre.
        original = os.urandom(64 * 1024)
        senha = gerar_senha()
        caminho = os.path.join(dir_, "grande.zip")
        with open(caminho, "wb") as f:
            f.write(zip_aes256("grande.bin", original, senha, comprimir=False))
        saiu = os.path.join(dir_, "saiu")
        subprocess.run(["7z", "x", f"-o{saiu}", f"-p{senha}", caminho],
                       capture_output=True, check=True)
        with open(os.path.join(saiu, "grande.bin"), "rb") as f:
            igual(f.read(), original,
                  "64 KiB tem de voltar identico: cobre o contador CTR alem do "
                  "bloco 255")


@caso("cripto: senha gerada nao tem caractere que se confunde ao ser ditado")
def _():
    from acoes.cripto import gerar_senha
    senha = "".join(gerar_senha() for _ in range(40))
    for proibido in "IlO01":
        nao_contem(proibido, senha,
                   f"'{proibido}' na senha vira chamado de suporte")


@caso("cripto: S/MIME envelopa e a chave privada do destinatario reabre")
def _():
    from acoes.cripto import RepositorioCertificados, envelopar_smime
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs7
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    import datetime

    with Temporario() as dir_:
        chave = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        nome = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "destino"),
                          x509.NameAttribute(NameOID.EMAIL_ADDRESS,
                                             "fiscal@externo.gov.br")])
        agora = datetime.datetime.now(datetime.timezone.utc)
        certificado = (x509.CertificateBuilder()
                       .subject_name(nome).issuer_name(nome)
                       .public_key(chave.public_key())
                       .serial_number(x509.random_serial_number())
                       .not_valid_before(agora - datetime.timedelta(days=1))
                       .not_valid_after(agora + datetime.timedelta(days=30))
                       .sign(chave, hashes.SHA256()))
        pem = certificado.public_bytes(serialization.Encoding.PEM)

        repositorio = RepositorioCertificados(os.path.join(dir_, "certs"))
        info = repositorio.guardar("fiscal@externo.gov.br", pem)
        contem("destino", info["titular"], "o titular tem de ser lido do PEM")

        mensagem = (b"Subject: relatorio\r\n\r\nCPF 529.982.247-25 no corpo.\r\n")
        resultado = envelopar_smime(mensagem, ["fiscal@externo.gov.br"],
                                    repositorio)
        certo(resultado.aplicado, f"envelope nao aplicado: {resultado.motivo}")
        nao_contem(b"529.982.247-25", resultado.conteudo,
                   "o envelope S/MIME estaria vazando o valor")

        aberta = pkcs7.pkcs7_decrypt_smime(resultado.conteudo, certificado,
                                           chave, [])
        contem(b"529.982.247-25", aberta,
               "a chave privada do destinatario tem de reabrir a mensagem")


@caso("cripto: sem certificado, o S/MIME NAO e' dado por cumprido")
def _():
    from acoes.cripto import RepositorioCertificados, envelopar_smime
    with Temporario() as dir_:
        repositorio = RepositorioCertificados(os.path.join(dir_, "certs"))
        r = envelopar_smime(b"mensagem", ["ninguem@externo.gov.br"], repositorio)
        certo(not r.aplicado, "sem certificado a acao nao pode ser 'cumprida'")
        contem("nenhum certificado", r.motivo, "o motivo tem de dizer o que falta")


@caso("cripto: PEM invalido e' recusado ANTES de ser gravado")
def _():
    from acoes.cripto import RepositorioCertificados
    with Temporario() as dir_:
        repositorio = RepositorioCertificados(os.path.join(dir_, "certs"))
        levanta(ValueError,
                lambda: repositorio.guardar("x@y.z", b"nao sou um certificado"),
                "certificado invalido nao pode entrar no repositorio")
        igual(repositorio.listar(), [], "nada pode ter sido gravado")
