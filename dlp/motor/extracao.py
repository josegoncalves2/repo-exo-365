# -*- coding: utf-8 -*-
"""Extracao de texto de multiplos formatos, com EXTRACAO PARCIAL declarada.

REGRA DE OURO: "nao consegui ler" NUNCA pode virar "esta limpo". Todo retorno
diz se a leitura foi completa e, se nao, por que. Um PDF digitalizado sem OCR
que retorne texto vazio e' classificado como NAO VARRIDO -- e nao como limpo.
E' a diferenca entre um DLP honesto e um que produz falsa cobertura.
"""
from __future__ import annotations

import io
import os
import re
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass, field
from typing import List, Optional

from . import tipos

TETO_PADRAO = 32 * 1024 * 1024          # 32 MiB por arquivo
TETO_ZIP_ITENS = 200
TETO_ZIP_PROFUNDIDADE = 3


@dataclass
class Texto:
    conteudo: str = ""
    completo: bool = True
    motivo_parcial: str = ""
    formato: str = ""
    mime: str = ""
    disfarcado: bool = False
    aninhados: List[str] = field(default_factory=list)   # nomes dentro de zip

    @property
    def vazio_e_ilegivel(self) -> bool:
        return not self.conteudo.strip() and not self.completo


def _ocr_disponivel() -> bool:
    return _qual("tesseract") is not None


def _qual(binario: str) -> Optional[str]:
    for caminho in os.environ.get("PATH", "").split(os.pathsep):
        alvo = os.path.join(caminho, binario)
        if os.path.isfile(alvo) and os.access(alvo, os.X_OK):
            return alvo
    return None


def _ocr(dados: bytes, idiomas: str = "por+eng") -> Texto:
    """OCR por tesseract. E' o item 'inspecao OCR' do escopo."""
    if not _ocr_disponivel():
        return Texto("", False, "OCR indisponivel no ambiente")
    with tempfile.NamedTemporaryFile(suffix=".img", delete=False) as f:
        f.write(dados)
        caminho = f.name
    try:
        p = subprocess.run(["tesseract", caminho, "stdout", "-l", idiomas],
                           capture_output=True, timeout=120)
        texto = p.stdout.decode("utf-8", "replace")
        if p.returncode != 0:
            return Texto(texto, False, f"OCR falhou (rc={p.returncode})")
        return Texto(texto, True, "", "ocr")
    except subprocess.TimeoutExpired:
        return Texto("", False, "OCR estourou o tempo")
    except Exception as e:                                  # noqa: BLE001
        return Texto("", False, f"OCR erro: {e}")
    finally:
        try:
            os.unlink(caminho)
        except OSError:
            pass


def _pdf(dados: bytes, ocr: bool) -> Texto:
    texto = ""
    try:
        from pdfminer.high_level import extract_text       # type: ignore
        texto = extract_text(io.BytesIO(dados)) or ""
    except ImportError:
        if _qual("pdftotext"):
            with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
                f.write(dados)
                c = f.name
            try:
                p = subprocess.run(["pdftotext", "-layout", c, "-"],
                                   capture_output=True, timeout=120)
                texto = p.stdout.decode("utf-8", "replace")
            finally:
                os.unlink(c)
        else:
            return Texto("", False, "sem extrator de PDF no ambiente", "pdf")
    except Exception as e:                                  # noqa: BLE001
        return Texto("", False, f"PDF ilegivel: {e}", "pdf")

    if texto.strip():
        return Texto(texto, True, "", "pdf")
    # Sem texto: quase sempre e' digitalizacao. NAO e' "limpo".
    if ocr:
        r = _ocr(dados)
        if r.conteudo.strip():
            r.formato = "pdf+ocr"
            return r
    return Texto("", False, "PDF sem camada de texto (provavel digitalizacao)", "pdf")


def _docx(dados: bytes) -> Texto:
    try:
        with zipfile.ZipFile(io.BytesIO(dados)) as z:
            partes = []
            for nome in z.namelist():
                if re.match(r"word/(document|header\d*|footer\d*|footnotes|endnotes|comments)\.xml", nome):
                    partes.append(z.read(nome).decode("utf-8", "replace"))
            bruto = " ".join(partes)
        limpo = re.sub(r"<[^>]+>", " ", bruto)
        return Texto(re.sub(r"\s+", " ", limpo), True, "", "docx")
    except Exception as e:                                  # noqa: BLE001
        return Texto("", False, f"docx ilegivel: {e}", "docx")


def _xlsx(dados: bytes) -> Texto:
    try:
        with zipfile.ZipFile(io.BytesIO(dados)) as z:
            partes = []
            if "xl/sharedStrings.xml" in z.namelist():
                partes.append(z.read("xl/sharedStrings.xml").decode("utf-8", "replace"))
            for nome in z.namelist():
                if nome.startswith("xl/worksheets/") and nome.endswith(".xml"):
                    partes.append(z.read(nome).decode("utf-8", "replace"))
            bruto = " ".join(partes)
        limpo = re.sub(r"<[^>]+>", " ", bruto)
        return Texto(re.sub(r"\s+", " ", limpo), True, "", "xlsx")
    except Exception as e:                                  # noqa: BLE001
        return Texto("", False, f"xlsx ilegivel: {e}", "xlsx")


def _pptx(dados: bytes) -> Texto:
    try:
        with zipfile.ZipFile(io.BytesIO(dados)) as z:
            partes = [z.read(n).decode("utf-8", "replace") for n in z.namelist()
                      if n.startswith("ppt/slides/") and n.endswith(".xml")]
        limpo = re.sub(r"<[^>]+>", " ", " ".join(partes))
        return Texto(re.sub(r"\s+", " ", limpo), True, "", "pptx")
    except Exception as e:                                  # noqa: BLE001
        return Texto("", False, f"pptx ilegivel: {e}", "pptx")


def _odf(dados: bytes, formato: str) -> Texto:
    try:
        with zipfile.ZipFile(io.BytesIO(dados)) as z:
            bruto = z.read("content.xml").decode("utf-8", "replace")
        limpo = re.sub(r"<[^>]+>", " ", bruto)
        return Texto(re.sub(r"\s+", " ", limpo), True, "", formato)
    except Exception as e:                                  # noqa: BLE001
        return Texto("", False, f"{formato} ilegivel: {e}", formato)


def _texto_simples(dados: bytes) -> Texto:
    for cod in ("utf-8", "latin-1"):
        try:
            return Texto(dados.decode(cod), True, "", "txt")
        except UnicodeDecodeError:
            continue
    return Texto("", False, "codificacao desconhecida", "txt")


def _zip(dados: bytes, ocr: bool, profundidade: int) -> Texto:
    """Compactado: extrai RECURSIVAMENTE. E' o esconderijo mais obvio."""
    if profundidade > TETO_ZIP_PROFUNDIDADE:
        return Texto("", False, "zip aninhado alem do limite", "zip")
    partes, nomes, incompleto = [], [], []
    try:
        with zipfile.ZipFile(io.BytesIO(dados)) as z:
            itens = [i for i in z.infolist() if not i.is_dir()][:TETO_ZIP_ITENS]
            if len(z.infolist()) > TETO_ZIP_ITENS:
                incompleto.append(f"zip com mais de {TETO_ZIP_ITENS} itens")
            for info in itens:
                if info.file_size > TETO_PADRAO:
                    incompleto.append(f"{info.filename} acima do teto")
                    continue
                try:
                    interno = z.read(info)
                except RuntimeError:
                    # `zipfile` levanta RuntimeError para item cifrado com a
                    # senha classica do ZIP.
                    incompleto.append(f"{info.filename} protegido por senha")
                    continue
                except NotImplementedError as e:
                    # Metodo de compressao que a biblioteca padrao nao abre --
                    # o caso mais comum e' AES (metodo 99), que e' justamente o
                    # que se usa para esconder conteudo. Antes esta excecao
                    # escapava do laco e caia no `except Exception` de fora,
                    # descartando TAMBEM os itens legiveis do mesmo pacote: um
                    # zip com dez arquivos e um cifrado deixava de ser varrido
                    # inteiro. Agora o item vira motivo de parcialidade e a
                    # varredura continua nos outros nove.
                    incompleto.append(f"{info.filename}: {e}")
                    continue
                except Exception as e:                      # noqa: BLE001
                    incompleto.append(f"{info.filename} ilegivel: {e}")
                    continue
                nomes.append(info.filename)
                r = extrair(interno, info.filename, ocr=ocr, _profundidade=profundidade + 1)
                partes.append(r.conteudo)
                if not r.completo:
                    incompleto.append(f"{info.filename}: {r.motivo_parcial}")
    except Exception as e:                                  # noqa: BLE001
        return Texto("", False, f"zip ilegivel: {e}", "zip")
    return Texto(" ".join(partes), not incompleto, "; ".join(incompleto), "zip",
                 aninhados=nomes)


def extrair(dados: bytes, nome: str = "", ocr: bool = True,
            teto: int = TETO_PADRAO, _profundidade: int = 0) -> Texto:
    """Ponto de entrada. Devolve texto + se a leitura foi completa."""
    if not dados:
        return Texto("", True, "", "vazio")
    if len(dados) > teto:
        return Texto("", False, f"arquivo acima do teto de {teto} bytes")

    info = tipos.detectar(dados, nome)
    ext = info["extensao_real"]

    if ext == "pdf":
        r = _pdf(dados, ocr)
    elif ext == "docx":
        r = _docx(dados)
    elif ext == "xlsx":
        r = _xlsx(dados)
    elif ext == "pptx":
        r = _pptx(dados)
    elif ext in ("odt", "ods"):
        r = _odf(dados, ext)
    elif ext in ("png", "jpg", "gif", "bmp", "tiff"):
        r = _ocr(dados) if ocr else Texto("", False, "imagem sem OCR", ext)
    elif ext == "zip":
        r = _zip(dados, ocr, _profundidade)
    elif ext == "txt":
        r = _texto_simples(dados)
    elif ext in ("doc", "xls", "ppt"):
        r = Texto("", False, "formato OLE legado sem extrator", ext)
    elif ext in ("exe", "elf"):
        r = Texto("", False, "binario executavel: nao ha texto a extrair", ext)
    else:
        r = _texto_simples(dados) if tipos._texto(dados) else \
            Texto("", False, f"formato {ext} sem extrator", ext)

    r.mime = info["mime"]
    r.disfarcado = info["disfarcado"]
    if not r.formato:
        r.formato = ext
    if r.disfarcado:
        aviso = (f"extensao declarada '{info['extensao_declarada']}' difere do "
                 f"tipo real '{ext}'")
        r.motivo_parcial = f"{r.motivo_parcial}; {aviso}" if r.motivo_parcial else aviso
    return r
