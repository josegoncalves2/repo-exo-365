# -*- coding: utf-8 -*-
"""Deteccao de dado sensivel: padrao + dicionario + validacao + contexto.

TRES CAMADAS, e a ordem importa:
  1. FORMA      -- a expressao regular acha candidatos baratos;
  2. VALIDACAO  -- o digito verificador descarta o que so' parece;
  3. CONTEXTO   -- palavra proxima confirma ou nega (proximity matching).

A camada 3 existe porque numero valido nem sempre e' dado pessoal, e porque
sem ela o DLP gera tanto falso positivo que o operador desliga -- e ai nao ha'
DLP nenhum. Foi exatamente o defeito da instalacao anterior, ao contrario:
casava a PALAVRA "CPF" e deixava passar o NUMERO.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from typing import Callable, List, Optional, Sequence

from . import validadores


def normalizar(texto: str) -> str:
    """Minusculas sem acento. Usado so' para casar CONTEXTO, nunca o valor."""
    if not texto:
        return ""
    sem = unicodedata.normalize("NFKD", texto)
    return "".join(c for c in sem if not unicodedata.combining(c)).lower()


@dataclass(frozen=True)
class Detector:
    rotulo: str
    descricao: str
    padrao: re.Pattern
    validador: Callable[[str], bool]
    severidade: str                      # BAIXA | MEDIA | ALTA | CRITICA
    categorias: Sequence[str]            # PII, PCI-DSS, PHI, LGPD, ...
    contexto: Sequence[str] = ()
    contexto_obrigatorio: bool = False
    janela_contexto: int = 60

    def confirmar_contexto(self, texto_norm: str, inicio: int, fim: int) -> bool:
        if not self.contexto:
            return True
        ini = max(0, inicio - self.janela_contexto)
        f = min(len(texto_norm), fim + self.janela_contexto)
        vizinhanca = texto_norm[ini:f]
        return any(p in vizinhanca for p in self.contexto)


@dataclass
class Ocorrencia:
    rotulo: str
    bruto: str
    inicio: int
    fim: int
    severidade: str
    categorias: Sequence[str]
    com_contexto: bool = True


@dataclass
class Achado:
    rotulo: str
    severidade: str
    categorias: Sequence[str]
    ocorrencias: List[Ocorrencia] = field(default_factory=list)

    @property
    def quantidade(self) -> int:
        return len(self.ocorrencias)


def _p(expr: str) -> re.Pattern:
    return re.compile(expr, re.IGNORECASE | re.MULTILINE)


CATALOGO: List[Detector] = [
    Detector("CPF", "Cadastro de Pessoa Fisica",
             _p(r"(?<!\d)\d{3}\.?\d{3}\.?\d{3}-?\d{2}(?!\d)"),
             validadores.cpf, "ALTA", ("PII", "LGPD")),
    Detector("CNPJ", "Cadastro Nacional de Pessoa Juridica",
             _p(r"(?<!\d)\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}(?!\d)"),
             validadores.cnpj, "MEDIA", ("PII", "LGPD")),
    Detector("CARTAO_CREDITO", "Numero de cartao de pagamento",
             _p(r"(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)"),
             validadores.luhn, "CRITICA", ("PCI-DSS",),
             contexto=("cartao", "credito", "debito", "visa", "master", "elo",
                       "amex", "bandeira", "validade", "cvv", "pagamento"),
             contexto_obrigatorio=True),
    Detector("PIS_PASEP", "PIS/PASEP/NIT",
             _p(r"(?<!\d)\d{3}\.?\d{4,5}\.?\d{2,3}-?\d{1}(?!\d)"),
             validadores.pis_pasep, "ALTA", ("PII", "LGPD"),
             contexto=("pis", "pasep", "nit", "inss", "previdencia", "fgts"),
             contexto_obrigatorio=True),
    Detector("TITULO_ELEITOR", "Titulo de eleitor",
             _p(r"(?<!\d)\d{12}(?!\d)"),
             validadores.titulo_eleitor, "ALTA", ("PII", "LGPD"),
             contexto=("titulo", "eleitor", "eleitoral", "zona", "secao", "urna"),
             contexto_obrigatorio=True),
    Detector("CNH", "Carteira Nacional de Habilitacao",
             _p(r"(?<!\d)\d{11}(?!\d)"),
             validadores.cnh, "ALTA", ("PII", "LGPD"),
             contexto=("cnh", "habilitacao", "motorista", "condutor", "detran"),
             contexto_obrigatorio=True),
    Detector("RENAVAM", "Registro de veiculo",
             _p(r"(?<!\d)\d{11}(?!\d)"),
             validadores.renavam, "BAIXA", ("PII",),
             contexto=("renavam", "veiculo", "placa", "licenciamento"),
             contexto_obrigatorio=True),
    Detector("CNS", "Cartao Nacional de Saude",
             _p(r"(?<!\d)\d{3}[ .]?\d{4}[ .]?\d{4}[ .]?\d{4}(?!\d)"),
             validadores.cns, "CRITICA", ("PHI", "HIPAA", "LGPD"),
             contexto=("cns", "cartao nacional", "sus", "saude", "paciente",
                       "prontuario"),
             contexto_obrigatorio=True),
    Detector("IBAN", "Conta bancaria internacional",
             _p(r"\b[A-Z]{2}\d{2}[A-Z0-9 ]{10,34}\b"),
             validadores.iban, "ALTA", ("PCI-DSS", "GLBA")),
    Detector("CHAVE_PIX_ALEATORIA", "Chave PIX aleatoria (UUID v4)",
             _p(r"\b[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b"),
             validadores.SEM_VALIDADOR, "ALTA", ("PCI-DSS", "LGPD"),
             contexto=("pix", "chave", "transferencia", "recebimento", "banco"),
             contexto_obrigatorio=True),
    Detector("EMAIL", "Endereco de e-mail",
             _p(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"),
             validadores.SEM_VALIDADOR, "BAIXA", ("PII", "LGPD")),
    Detector("TELEFONE", "Telefone brasileiro",
             _p(r"(?<!\d)(?:\+55[ -]?)?\(?\d{2}\)?[ -]?9?\d{4}[ -]?\d{4}(?!\d)"),
             validadores.SEM_VALIDADOR, "BAIXA", ("PII", "LGPD"),
             contexto=("telefone", "celular", "contato", "whatsapp", "fone", "tel"),
             contexto_obrigatorio=True),
    Detector("CEP", "Codigo de enderecamento postal",
             _p(r"(?<!\d)\d{5}-\d{3}(?!\d)"),
             validadores.cep, "BAIXA", ("PII",)),
    Detector("SEGREDO_EM_TEXTO_CLARO", "Credencial ou chave privada em texto claro",
             _p(r"(?:-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"
                r"|(?:senha|password|passwd|secret|token|api[_-]?key)"
                r"\s*[:=]\s*\S{6,}"
                r"|AKIA[0-9A-Z]{16}"
                r"|ghp_[A-Za-z0-9]{36}"
                r"|xox[baprs]-[A-Za-z0-9-]{10,}"
                r"|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})"),
             validadores.SEM_VALIDADOR, "CRITICA", ("SEGREDO", "SOX")),
    Detector("DADO_SAUDE", "Termo clinico associado a pessoa",
             _p(r"\b(?:cid[- ]?10|diagnostic[oa]|prontuario|laudo medico"
                r"|hiv|soropositiv[oa]|gestante|obito|internacao|receituario)\b"),
             validadores.SEM_VALIDADOR, "ALTA", ("PHI", "HIPAA", "LGPD")),
    Detector("ORIGEM_RACIAL_RELIGIAO", "Dado sensivel do art. 5, II da LGPD",
             _p(r"\b(?:orig[ei]m racial|etnia|conviccao religiosa|filiacao"
                r" sindical|opiniao politica|vida sexual|dado genetico"
                r"|dado biometrico)\b"),
             validadores.SEM_VALIDADOR, "CRITICA", ("LGPD",)),
]

POR_ROTULO = {d.rotulo: d for d in CATALOGO}
_PESO = {"CRITICA": 4, "ALTA": 3, "MEDIA": 2, "BAIXA": 1}


def _ordem(sev: str) -> int:
    return _PESO.get(sev, 0)


class Varredura:
    """Aplica o catalogo a um texto e devolve os achados agrupados."""

    def __init__(self, detectores: Optional[Sequence[Detector]] = None,
                 dicionarios: Optional[dict] = None):
        self.detectores = list(detectores or CATALOGO)
        self.dicionarios = dicionarios or {}

    def varrer(self, texto: str) -> List[Achado]:
        if not texto:
            return []
        norm = normalizar(texto)
        por_rotulo: dict = {}

        for det in self.detectores:
            for m in det.padrao.finditer(texto):
                bruto = m.group(0)
                if not det.validador(bruto):
                    continue
                tem_ctx = det.confirmar_contexto(norm, m.start(), m.end())
                if det.contexto_obrigatorio and not tem_ctx:
                    continue
                ach = por_rotulo.setdefault(
                    det.rotulo, Achado(det.rotulo, det.severidade, det.categorias))
                ach.ocorrencias.append(Ocorrencia(
                    det.rotulo, bruto, m.start(), m.end(),
                    det.severidade, det.categorias, tem_ctx))

        for rotulo, termos in self.dicionarios.items():
            for termo in termos:
                t = normalizar(termo)
                if not t:
                    continue
                for m in re.finditer(re.escape(t), norm):
                    ach = por_rotulo.setdefault(
                        rotulo, Achado(rotulo, "MEDIA", ("DICIONARIO",)))
                    ach.ocorrencias.append(Ocorrencia(
                        rotulo, texto[m.start():m.end()], m.start(), m.end(),
                        "MEDIA", ("DICIONARIO",), True))

        return sorted(por_rotulo.values(), key=lambda a: (-_ordem(a.severidade), a.rotulo))


def severidade_maxima(achados: Sequence[Achado]) -> str:
    if not achados:
        return "NENHUMA"
    return max(achados, key=lambda a: _ordem(a.severidade)).severidade
