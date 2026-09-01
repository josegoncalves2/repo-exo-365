# -*- coding: utf-8 -*-
"""EDM e IDM -- os dois casamentos que regex nao alcanca.

EDM (Exact Data Matching): a prefeitura carrega a folha de pagamento, e o DLP
passa a reconhecer AQUELES servidores especificos. Nao "um CPF qualquer", mas
"o CPF da Maria, que esta na folha". Reduz falso positivo a quase zero e pega
combinacao (nome + matricula) que isolada nao seria sensivel.

IDM (Indexed Document Matching): impressao digital de documento. Registra-se o
edital sigiloso; o DLP reconhece o documento inteiro, um trecho colado num
e-mail, ou uma versao levemente editada.

NENHUM DOS DOIS GUARDA O DADO. EDM guarda HMAC-SHA256 com sal por instalacao;
IDM guarda hashes de janelas deslizantes. Vazar o indice nao vaza a folha.
"""
from __future__ import annotations

import hashlib
import hmac
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Sequence, Set, Tuple


def _norm(v: str) -> str:
    if v is None:
        return ""
    s = unicodedata.normalize("NFKD", str(v))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return re.sub(r"[^a-z0-9]", "", s)


class IndiceEdm:
    """Casamento exato contra um cadastro, sem guardar o cadastro."""

    def __init__(self, sal: bytes, nome: str = "edm"):
        self._sal = sal
        self.nome = nome
        self.colunas: List[str] = []
        self._celulas: Set[str] = set()
        # `minimo_colunas` nasce aqui, e nao so' dentro de `indexar`: um indice
        # RECARREGADO do banco nunca passa por `indexar`, e o atributo faltando
        # obrigava `casar` a um getattr com padrao -- que escondia a diferenca
        # entre "o indice pede 2 colunas" e "ninguem sabe quantas ele pede".
        self.minimo_colunas = 2
        self.total_registros = 0

    def _h(self, valor: str) -> str:
        return hmac.new(self._sal, _norm(valor).encode(), hashlib.sha256).hexdigest()

    def indexar(self, colunas: Sequence[str], linhas: Iterable[Sequence[str]],
                minimo_colunas: int = 2) -> None:
        self.colunas = list(colunas)
        self.minimo_colunas = minimo_colunas
        for linha in linhas:
            valores = [v for v in linha if _norm(v)]
            if not valores:
                continue
            self.total_registros += 1
            for v in valores:
                if len(_norm(v)) >= 4:
                    self._celulas.add(self._h(v))
        # NAO se guarda mais o hash do registro INTEIRO. Ele era calculado,
        # serializado no banco e nunca consultado: a decisao de "registro
        # completo" sempre saiu da CONTAGEM de celulas casadas, porque o texto
        # de um documento real nao traz os campos na ordem nem juntos. Era
        # espaco em disco e tempo de indexacao para nada (PENDENCIAS, item 4).

    def casar(self, texto: str) -> Dict[str, object]:
        """Devolve celulas casadas e se ha' registro inteiro no texto.

        Os candidatos incluem SEQUENCIAS de ate' 4 palavras, nao so' palavra
        isolada: valor de cadastro raramente e' uma palavra so'. "Maria Souza"
        e' uma celula, e procurar apenas por "Maria" e "Souza" separados nunca
        casaria o hash dela -- que e' o hash do valor inteiro normalizado.
        """
        palavras = re.findall(r"[A-Za-zÀ-ÿ0-9][A-Za-zÀ-ÿ0-9./-]*", texto or "")
        candidatos = set()
        for i in range(len(palavras)):
            for n in range(1, 5):
                if i + n > len(palavras):
                    break
                candidatos.add(" ".join(palavras[i:i + n]))
        casadas = {c for c in candidatos if len(_norm(c)) >= 4
                   and self._h(c) in self._celulas}
        return {"indice": self.nome, "celulas_casadas": len(casadas),
                "amostra": sorted(casadas)[:5],
                "minimo": self.minimo_colunas,
                "registro_completo": len(casadas) >= self.minimo_colunas}


@dataclass
class IndiceIdm:
    """Impressao digital por janela deslizante (winnowing simplificado)."""
    sal: bytes
    nome: str = "idm"
    tamanho_janela: int = 8            # palavras por janela
    documentos: Dict[str, Set[str]] = field(default_factory=dict)

    def _janelas(self, texto: str) -> Set[str]:
        palavras = [p for p in re.split(r"\W+", _desacentuar(texto).lower()) if p]
        if len(palavras) < self.tamanho_janela:
            if not palavras:
                return set()
            return {self._h(" ".join(palavras))}
        return {self._h(" ".join(palavras[i:i + self.tamanho_janela]))
                for i in range(len(palavras) - self.tamanho_janela + 1)}

    def _h(self, s: str) -> str:
        return hmac.new(self.sal, s.encode(), hashlib.sha256).hexdigest()[:16]

    def registrar(self, identificador: str, texto: str) -> int:
        j = self._janelas(texto)
        self.documentos[identificador] = j
        return len(j)

    def casar(self, texto: str, corte: float = 0.15) -> List[Tuple[str, float]]:
        alvo = self._janelas(texto)
        if not alvo:
            return []
        achados = []
        for ident, janelas in self.documentos.items():
            if not janelas:
                continue
            comuns = len(alvo & janelas)
            proporcao = comuns / min(len(alvo), len(janelas))
            if proporcao >= corte:
                achados.append((ident, round(proporcao, 3)))
        return sorted(achados, key=lambda x: -x[1])


def _desacentuar(s: str) -> str:
    n = unicodedata.normalize("NFKD", s or "")
    return "".join(c for c in n if not unicodedata.combining(c))
