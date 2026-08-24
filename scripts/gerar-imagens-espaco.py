#!/usr/bin/env python3
# ============================================================================
# gerar-imagens-espaco.py -- avatar e banner de um espaco, sem dependencia.
#
# A stack nao tem Pillow e nao vale instalar uma biblioteca de imagem so' para
# isto. PNG e' simples o bastante para escrever a mao: assinatura, IHDR, IDAT
# (linhas com filtro 0, comprimidas com zlib) e IEND.
#
# A cor sai do nome do nivel (hash), entao cada espaco tem sempre a mesma cor
# e niveis diferentes nao se confundem na tela.
#   ./gerar-imagens-espaco.py "Setor de Tecnologia" ST /tmp/saida
# ============================================================================
import hashlib, os, struct, sys, zlib


def _png(largura, altura, pixel):
    """pixel(x, y) -> (r, g, b). Devolve os bytes do PNG."""
    linhas = bytearray()
    for y in range(altura):
        linhas.append(0)                      # filtro 0 (None) na linha toda
        for x in range(largura):
            linhas.extend(pixel(x, y))

    def bloco(tipo, dados):
        c = struct.pack(">I", len(dados)) + tipo + dados
        return c + struct.pack(">I", zlib.crc32(tipo + dados) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n"
            + bloco(b"IHDR", struct.pack(">IIBBBBB", largura, altura, 8, 2, 0, 0, 0))
            + bloco(b"IDAT", zlib.compress(bytes(linhas), 9))
            + bloco(b"IEND", b""))


def cor_do_nome(nome):
    """Tom estavel por nome, com saturacao e luminosidade controladas para o
    texto branco do eXo continuar legivel por cima."""
    h = int(hashlib.sha256(nome.encode("utf-8")).hexdigest()[:8], 16)
    matiz = (h % 360) / 360.0
    return _hsl(matiz, 0.46, 0.38), _hsl(matiz, 0.52, 0.24)


def _hsl(h, s, l):
    def canal(p, q, t):
        t = t % 1
        if t < 1 / 6: return p + (q - p) * 6 * t
        if t < 1 / 2: return q
        if t < 2 / 3: return p + (q - p) * (2 / 3 - t) * 6
        return p
    q = l * (1 + s) if l < 0.5 else l + s - l * s
    p = 2 * l - q
    return tuple(max(0, min(255, int(canal(p, q, h + d) * 255)))
                 for d in (1 / 3, 0, -1 / 3))


def banner(nome, largura=1200, altura=280):
    c1, c2 = cor_do_nome(nome)
    def px(x, y):
        t = (x / largura) * 0.7 + (y / altura) * 0.3     # diagonal suave
        return bytes(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
    return _png(largura, altura, px)


def avatar(sigla, lado=256):
    """Quadrado com a cor do nivel e um recorte diagonal mais claro -- sem
    fonte embutida nao da' para desenhar texto, entao a identidade vem da cor
    e do recorte, nao de letras."""
    c1, c2 = cor_do_nome(sigla)
    def px(x, y):
        return bytes(c1) if (x + y) < lado else bytes(c2)
    return _png(lado, lado, px)


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit("uso: gerar-imagens-espaco.py <nome> <sigla> <dir_saida>")
    nome, sigla, saida = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(saida, exist_ok=True)
    for arq, dados in ((f"{sigla}-banner.png", banner(nome)),
                       (f"{sigla}-avatar.png", avatar(sigla))):
        caminho = os.path.join(saida, arq)
        with open(caminho, "wb") as fh:
            fh.write(dados)
        print(f"  {caminho}  {len(dados)} bytes")
