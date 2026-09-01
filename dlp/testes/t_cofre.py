# -*- coding: utf-8 -*-
"""Cofre: ida e volta, deteccao de adulteracao e isolamento entre itens."""
from __future__ import annotations

import os

from testes.apoio import Temporario, caso, certo, igual, levanta, nao_contem


@caso("cofre: guarda, le de volta e confere o hash do claro")
def _():
    from motor.cofre import Cofre, chave_persistente
    with Temporario() as dir_:
        cofre = Cofre(os.path.join(dir_, "cofre"),
                      chave_persistente(os.path.join(dir_, "chave.bin")))
        conteudo = "Ficha do servidor. CPF 529.982.247-25.".encode("utf-8")
        item = cofre.guardar(conteudo)
        igual(cofre.ler(item.identificador), conteudo,
              "o conteudo lido tem de ser identico ao guardado")
        igual(item.tamanho, len(conteudo), "tamanho registrado")
        import hashlib
        igual(item.sha256, hashlib.sha256(conteudo).hexdigest(),
              "o sha256 e' do conteudo em CLARO, para o analista conferir a "
              "restauracao")


@caso("cofre: o arquivo em disco NAO contem o texto em claro")
def _():
    from motor.cofre import Cofre, chave_persistente
    with Temporario() as dir_:
        caminho_cofre = os.path.join(dir_, "cofre")
        cofre = Cofre(caminho_cofre,
                      chave_persistente(os.path.join(dir_, "chave.bin")))
        item = cofre.guardar(b"segredo-que-nao-pode-vazar-529.982.247-25")
        with open(os.path.join(caminho_cofre, item.identificador + ".cofre"),
                  "rb") as f:
            bruto = f.read()
        nao_contem(b"529.982.247-25", bruto,
                   "o cofre estaria guardando dado pessoal em claro")
        nao_contem(b"segredo", bruto, "conteudo legivel no disco")


@caso("cofre: adulteracao no disco e' detectada, nao ignorada")
def _():
    from motor.cofre import Cofre, CofreCorrompido, chave_persistente
    with Temporario() as dir_:
        caminho_cofre = os.path.join(dir_, "cofre")
        cofre = Cofre(caminho_cofre,
                      chave_persistente(os.path.join(dir_, "chave.bin")))
        item = cofre.guardar(b"conteudo original do incidente")
        alvo = os.path.join(caminho_cofre, item.identificador + ".cofre")
        with open(alvo, "r+b") as f:
            f.seek(20)
            byte = f.read(1)
            f.seek(20)
            f.write(bytes([byte[0] ^ 0xFF]))
        levanta(CofreCorrompido, lambda: cofre.ler(item.identificador),
                "GCM tem de recusar conteudo adulterado")


@caso("cofre: chave mestra diferente nao abre o item")
def _():
    from motor.cofre import Cofre, CofreCorrompido
    with Temporario() as dir_:
        caminho = os.path.join(dir_, "cofre")
        item = Cofre(caminho, b"A" * 32).guardar(b"material retido")
        outro = Cofre(caminho, b"B" * 32)
        levanta(CofreCorrompido, lambda: outro.ler(item.identificador),
                "outra chave mestra nao pode decifrar")


@caso("cofre: renomear o arquivo no disco impede a leitura (chave por item)")
def _():
    from motor.cofre import Cofre, CofreCorrompido, chave_persistente
    with Temporario() as dir_:
        caminho_cofre = os.path.join(dir_, "cofre")
        cofre = Cofre(caminho_cofre,
                      chave_persistente(os.path.join(dir_, "chave.bin")))
        item = cofre.guardar(b"prova do incidente")
        novo = "0" * 32
        os.rename(os.path.join(caminho_cofre, item.identificador + ".cofre"),
                  os.path.join(caminho_cofre, novo + ".cofre"))
        # A chave e' derivada do identificador: mover de gaveta quebra a
        # autenticacao. E' o que impede trocar um item retido por outro.
        levanta(CofreCorrompido, lambda: cofre.ler(novo),
                "item movido de nome nao pode ser lido com a chave do novo nome")


@caso("cofre: identificador fora do formato e' recusado (nao vira caminho)")
def _():
    from motor.cofre import Cofre
    with Temporario() as dir_:
        cofre = Cofre(os.path.join(dir_, "cofre"), b"C" * 32)
        levanta(ValueError, lambda: cofre.ler("../../etc/passwd"),
                "identificador com caminho tem de ser recusado")
        certo(not cofre.existe("../../etc/passwd"),
              "existe() nao pode aceitar caminho relativo")


@caso("cofre: a chave persistente e' reusada entre arranques")
def _():
    from motor.cofre import chave_persistente
    with Temporario() as dir_:
        caminho = os.path.join(dir_, "chave.bin")
        primeira = chave_persistente(caminho)
        igual(chave_persistente(caminho), primeira,
              "trocar a chave entre arranques tornaria ilegivel tudo o que ja' "
              "esta' retido")
        igual(oct(os.stat(caminho).st_mode & 0o777), oct(0o600),
              "a chave mestra precisa de modo 600")
