# -*- coding: utf-8 -*-
"""Analise lexical e estatistica: Bayes ingenuo + n-gramas.

POR QUE EXISTE, E O QUE NAO E'. Nao substitui detector nem valida nada: serve
para o documento que NAO tem identificador nenhum e ainda assim e' sigiloso --
minuta de edital, parecer juridico, sindicancia. O classificador aprende com
exemplos que o proprio operador rotula, no console.

NAO E' machine learning de terceiro nem modelo baixado: e' contagem de termos,
determinista, treinada com dado da propria casa e auditavel -- da' para
perguntar POR QUE classificou e receber os termos que pesaram.
"""
from __future__ import annotations

import json
import math
import re
import unicodedata
from collections import Counter, defaultdict
from typing import Dict, List, Sequence, Tuple

_PARE = {
    "a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "em", "no", "na",
    "nos", "nas", "um", "uma", "para", "por", "com", "que", "se", "ao", "aos",
    "the", "of", "and", "to", "in", "is", "it", "this", "that",
}


def _tokens(texto: str) -> List[str]:
    n = unicodedata.normalize("NFKD", texto or "")
    n = "".join(c for c in n if not unicodedata.combining(c)).lower()
    return [t for t in re.findall(r"[a-z0-9]{3,}", n) if t not in _PARE]


def ngramas(tokens: Sequence[str], n: int = 2) -> List[str]:
    return [" ".join(tokens[i:i + n]) for i in range(len(tokens) - n + 1)]


class ClassificadorBayes:
    """Bayes ingenuo multinomial com suavizacao de Laplace."""

    def __init__(self, usar_bigramas: bool = True):
        self.usar_bigramas = usar_bigramas
        self.contagem: Dict[str, Counter] = defaultdict(Counter)
        self.total_por_classe: Dict[str, int] = defaultdict(int)
        self.documentos_por_classe: Dict[str, int] = defaultdict(int)
        self.vocabulario: set = set()

    def _caracteristicas(self, texto: str) -> List[str]:
        t = _tokens(texto)
        return t + ngramas(t, 2) if self.usar_bigramas else t

    def treinar(self, classe: str, texto: str) -> None:
        c = self._caracteristicas(texto)
        self.documentos_por_classe[classe] += 1
        for termo in c:
            self.contagem[classe][termo] += 1
            self.total_por_classe[classe] += 1
            self.vocabulario.add(termo)

    def classificar(self, texto: str, top: int = 6) -> Dict[str, object]:
        if not self.documentos_por_classe:
            return {"classe": None, "confianca": 0.0, "termos": [],
                    "motivo": "classificador sem treino"}
        c = self._caracteristicas(texto)
        if not c:
            return {"classe": None, "confianca": 0.0, "termos": [],
                    "motivo": "texto sem termos uteis"}
        v = max(len(self.vocabulario), 1)
        total_docs = sum(self.documentos_por_classe.values())
        pontos: Dict[str, float] = {}
        contrib: Dict[str, List[Tuple[str, float]]] = {}
        for classe in self.documentos_por_classe:
            p = math.log(self.documentos_por_classe[classe] / total_docs)
            detalhe = []
            for termo in c:
                cont = self.contagem[classe][termo]
                lp = math.log((cont + 1) / (self.total_por_classe[classe] + v))
                p += lp
                if cont:
                    detalhe.append((termo, round(lp, 3)))
            pontos[classe] = p
            contrib[classe] = sorted(detalhe, key=lambda x: -x[1])[:top]
        melhor = max(pontos, key=pontos.get)
        ordenados = sorted(pontos.values(), reverse=True)
        margem = (ordenados[0] - ordenados[1]) if len(ordenados) > 1 else abs(ordenados[0])
        confianca = 1 - math.exp(-abs(margem) / 25.0)
        return {"classe": melhor, "confianca": round(confianca, 3),
                "termos": contrib[melhor],
                "motivo": "termos que mais pesaram estao em 'termos'"}

    def exportar(self) -> str:
        return json.dumps({
            "usar_bigramas": self.usar_bigramas,
            "contagem": {k: dict(v) for k, v in self.contagem.items()},
            "total_por_classe": dict(self.total_por_classe),
            "documentos_por_classe": dict(self.documentos_por_classe),
        })

    @classmethod
    def importar(cls, bruto: str) -> "ClassificadorBayes":
        d = json.loads(bruto)
        c = cls(d.get("usar_bigramas", True))
        for classe, termos in d["contagem"].items():
            c.contagem[classe] = Counter(termos)
        c.total_por_classe = defaultdict(int, d["total_por_classe"])
        c.documentos_por_classe = defaultdict(int, d["documentos_por_classe"])
        for termos in d["contagem"].values():
            c.vocabulario.update(termos)
        return c
