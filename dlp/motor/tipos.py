# -*- coding: utf-8 -*-
"""Tipo REAL do arquivo, por assinatura -- nunca pela extensao.

Renomear segredos.xlsx para foto.jpg e' o desvio mais barato que existe. Quem
confia na extensao ja' perdeu. Aqui o tipo sai dos bytes iniciais (magic
number) e, quando ha' libmagic no ambiente, dela tambem.
"""
from __future__ import annotations

from typing import Optional, Tuple

try:                                    # opcional: enriquece, nao e' requisito
    import magic as _libmagic
except Exception:                       # noqa: BLE001
    _libmagic = None

ASSINATURAS: Tuple[Tuple[bytes, int, str, str], ...] = (
    (b"%PDF-", 0, "application/pdf", "pdf"),
    (b"\x50\x4b\x03\x04", 0, "application/zip", "zip"),   # tambem docx/xlsx/odt
    (b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1", 0, "application/x-ole-storage", "doc"),
    (b"\x7fELF", 0, "application/x-executable", "elf"),
    (b"MZ", 0, "application/x-dosexec", "exe"),
    (b"\x89PNG\r\n\x1a\n", 0, "image/png", "png"),
    (b"\xff\xd8\xff", 0, "image/jpeg", "jpg"),
    (b"GIF8", 0, "image/gif", "gif"),
    (b"BM", 0, "image/bmp", "bmp"),
    (b"II*\x00", 0, "image/tiff", "tiff"),
    (b"MM\x00*", 0, "image/tiff", "tiff"),
    (b"RIFF", 0, "application/octet-stream", "riff"),
    (b"\x1f\x8b", 0, "application/gzip", "gz"),
    (b"7z\xbc\xaf\x27\x1c", 0, "application/x-7z-compressed", "7z"),
    (b"Rar!\x1a\x07", 0, "application/vnd.rar", "rar"),
    (b"SQLite format 3\x00", 0, "application/vnd.sqlite3", "sqlite"),
    (b"{\\rtf", 0, "application/rtf", "rtf"),
)

# Dentro de um zip, o que distingue docx/xlsx/pptx/odt e' o primeiro nome.
ZIP_INTERNO = (
    (b"word/", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    (b"xl/", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    (b"ppt/", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    (b"mimetypeapplication/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text", "odt"),
    (b"mimetypeapplication/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet", "ods"),
)


def detectar(dados: bytes, nome: Optional[str] = None) -> dict:
    """Devolve {mime, extensao_real, extensao_declarada, disfarcado}."""
    mime, ext = "application/octet-stream", "bin"
    if dados:
        for assinatura, desloc, m, e in ASSINATURAS:
            if dados[desloc:desloc + len(assinatura)] == assinatura:
                mime, ext = m, e
                break
        if ext == "zip":
            # ABRIR o zip e olhar os NOMES DE ENTRADA -- nao os bytes crus.
            # Procurar b"word/" no buffer casava tambem um zip que CONTEM um
            # docx (os nomes internos do docx aparecem nos bytes armazenados),
            # e ai o arquivo era tratado como docx: a extracao recursiva nao
            # rodava e o conteudo dos aninhados passava sem varredura. Era
            # caminho de evasao -- basta compactar o documento.
            mime, ext = _classificar_zip(dados) or (mime, ext)
        if mime == "application/octet-stream" and _texto(dados):
            mime, ext = "text/plain", "txt"
    if _libmagic is not None:
        try:
            mime = _libmagic.from_buffer(dados[:8192], mime=True) or mime
        except Exception:               # noqa: BLE001
            pass
    declarada = ""
    if nome and "." in nome:
        declarada = nome.rsplit(".", 1)[-1].lower()
    disfarcado = bool(declarada) and declarada != ext and not _equivalente(declarada, ext)
    return {"mime": mime, "extensao_real": ext, "extensao_declarada": declarada,
            "disfarcado": disfarcado}


_FAMILIAS = (
    {"zip", "docx", "xlsx", "pptx", "odt", "ods"},
    {"jpg", "jpeg"},
    {"tif", "tiff"},
    {"txt", "csv", "md", "json", "xml", "log", "html", "htm"},
    {"doc", "xls", "ppt"},
)


def _equivalente(a: str, b: str) -> bool:
    return any(a in f and b in f for f in _FAMILIAS)


def _texto(dados: bytes) -> bool:
    amostra = dados[:2048]
    if not amostra:
        return False
    if b"\x00" in amostra:
        return False
    try:
        amostra.decode("utf-8")
        return True
    except UnicodeDecodeError:
        try:
            amostra.decode("latin-1")
            return True
        except Exception:               # noqa: BLE001
            return False


def _classificar_zip(dados: bytes):
    """docx/xlsx/pptx/odt sao zips com estrutura propria. Zip comum nao tem."""
    import io
    import zipfile
    try:
        with zipfile.ZipFile(io.BytesIO(dados)) as z:
            nomes = z.namelist()
    except Exception:                   # noqa: BLE001
        return None
    if not nomes:
        return None
    if "mimetype" in nomes:
        try:
            with zipfile.ZipFile(io.BytesIO(dados)) as z:
                declarado = z.read("mimetype").decode("ascii", "replace").strip()
            for _marca, m, e in ZIP_INTERNO:
                if declarado and declarado == m:
                    return m, e
        except Exception:               # noqa: BLE001
            pass
    tem = lambda pref: any(n.startswith(pref) for n in nomes)   # noqa: E731
    if tem("word/") and "[Content_Types].xml" in nomes:
        return ZIP_INTERNO[0][1], ZIP_INTERNO[0][2]
    if tem("xl/") and "[Content_Types].xml" in nomes:
        return ZIP_INTERNO[1][1], ZIP_INTERNO[1][2]
    if tem("ppt/") and "[Content_Types].xml" in nomes:
        return ZIP_INTERNO[2][1], ZIP_INTERNO[2][2]
    return None
