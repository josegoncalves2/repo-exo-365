# -*- coding: utf-8 -*-
"""Exportacao de incidente para SIEM: syslog CEF e LEEF.

O SIEM da prefeitura e' o lugar onde o incidente de DLP encontra o incidente de
rede, de antivirus e de autenticacao. Um DLP que so' fala consigo mesmo obriga
o analista a olhar duas telas -- e ele vai olhar uma so'.

O que sai daqui NUNCA carrega valor sensivel: so' o rotulo, a quantidade e a
severidade. A evidencia mascarada fica no console.
"""
from __future__ import annotations

import socket
from typing import Dict

FORNECEDOR = "PMO"
PRODUTO = "DLP eXo"
VERSAO = "1.0"

_GRAVIDADE = {"BAIXA": 3, "MEDIA": 5, "ALTA": 8, "CRITICA": 10, "NENHUMA": 0}


def _escapar_cef(valor: str) -> str:
    return (str(valor or "").replace("\\", "\\\\").replace("|", "\\|")
            .replace("=", "\\=").replace("\n", " "))


def para_cef(inc: Dict) -> str:
    """ArcSight Common Event Format."""
    rotulos = ",".join(e.get("rotulo", "") for e in inc.get("evidencia", []))
    total = sum(e.get("quantidade", 0) for e in inc.get("evidencia", []))
    cabecalho = (f"CEF:0|{FORNECEDOR}|{PRODUTO}|{VERSAO}|"
                 f"{_escapar_cef(inc.get('regra') or 'DLP')}|"
                 f"{_escapar_cef(inc.get('regra_nome') or 'Deteccao DLP')}|"
                 f"{_GRAVIDADE.get(inc.get('severidade', ''), 0)}|")
    ext = {
        "externalId": inc.get("identificador", ""),
        "rt": inc.get("momento", ""),
        "suser": inc.get("usuario", ""),
        "src": inc.get("ip", ""),
        "destinationDnsDomain": inc.get("destino", ""),
        "fname": inc.get("nome_arquivo", ""),
        "fileType": inc.get("tipo_arquivo", ""),
        "fsize": inc.get("tamanho", 0),
        "act": ",".join(inc.get("acoes", [])),
        "outcome": "blocked" if not inc.get("permitido", True) else "allowed",
        "cs1Label": "canal", "cs1": inc.get("canal", ""),
        "cs2Label": "classificadores", "cs2": rotulos,
        "cs3Label": "classificacao", "cs3": inc.get("classificacao", ""),
        "cs4Label": "conformidade", "cs4": ",".join(inc.get("conformidade", [])),
        "cn1Label": "ocorrencias", "cn1": total,
        "cs5Label": "origem", "cs5": inc.get("origem", ""),
        "cs6Label": "extracaoCompleta",
        "cs6": "sim" if inc.get("extracao_completa", True) else "nao",
    }
    corpo = " ".join(f"{k}={_escapar_cef(v)}" for k, v in ext.items() if v != "")
    return cabecalho + corpo


def para_leef(inc: Dict) -> str:
    """IBM QRadar Log Event Extended Format."""
    rotulos = ",".join(e.get("rotulo", "") for e in inc.get("evidencia", []))
    campos = {
        "devTime": inc.get("momento", ""),
        "usrName": inc.get("usuario", ""),
        "src": inc.get("ip", ""),
        "identSrc": inc.get("origem", ""),
        "fileName": inc.get("nome_arquivo", ""),
        "sev": _GRAVIDADE.get(inc.get("severidade", ""), 0),
        "action": ",".join(inc.get("acoes", [])),
        "outcome": "blocked" if not inc.get("permitido", True) else "allowed",
        "canal": inc.get("canal", ""),
        "classificadores": rotulos,
        "regra": inc.get("regra_nome", ""),
        "destino": inc.get("destino", ""),
    }
    corpo = "\t".join(f"{k}={v}" for k, v in campos.items())
    return (f"LEEF:2.0|{FORNECEDOR}|{PRODUTO}|{VERSAO}|"
            f"{inc.get('regra') or 'DLP'}|\t{corpo}")


class EnvioSyslog:
    """Envia por UDP ou TCP. Falha de SIEM NUNCA derruba a decisao de DLP."""

    def __init__(self, host: str = "", porta: int = 514, protocolo: str = "udp",
                 formato: str = "cef", facilidade: int = 13):
        self.host, self.porta = host, porta
        self.protocolo = (protocolo or "udp").lower()
        self.formato = (formato or "cef").lower()
        self.facilidade = facilidade
        self.ativo = bool(host)
        self.ultimo_erro = ""

    def _prioridade(self, severidade: str) -> int:
        nivel = 4 if severidade in ("ALTA", "CRITICA") else 6
        return self.facilidade * 8 + nivel

    def enviar(self, inc: Dict) -> bool:
        if not self.ativo:
            return False
        corpo = para_cef(inc) if self.formato == "cef" else para_leef(inc)
        pri = self._prioridade(inc.get("severidade", ""))
        mensagem = f"<{pri}>{corpo}".encode("utf-8", "replace")
        try:
            if self.protocolo == "tcp":
                with socket.create_connection((self.host, self.porta), timeout=5) as s:
                    s.sendall(mensagem + b"\n")
            else:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.settimeout(5)
                    s.sendto(mensagem, (self.host, self.porta))
            self.ultimo_erro = ""
            return True
        except Exception as e:                              # noqa: BLE001
            self.ultimo_erro = str(e)
            return False
