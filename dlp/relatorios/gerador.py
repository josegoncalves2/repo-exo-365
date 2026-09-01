# -*- coding: utf-8 -*-
"""Relatorios de conformidade e exportacao. CSV e HTML, sem dependencia."""
from __future__ import annotations

import csv
import html
import io
from datetime import datetime, timedelta, timezone
from typing import Dict, List

COLUNAS_CSV = ("identificador", "momento", "canal", "origem", "usuario", "ip",
               "destino", "nome_arquivo", "tipo_arquivo", "severidade",
               "classificacao", "regra_nome", "acoes", "permitido",
               "conformidade", "classificadores", "ocorrencias", "estado",
               "responsavel", "extracao_completa")


def _linha(inc: Dict) -> Dict:
    ev = inc.get("evidencia", [])
    return {
        "identificador": inc.get("identificador", ""),
        "momento": inc.get("momento", ""),
        "canal": inc.get("canal", ""),
        "origem": inc.get("origem", ""),
        "usuario": inc.get("usuario", ""),
        "ip": inc.get("ip", ""),
        "destino": inc.get("destino", ""),
        "nome_arquivo": inc.get("nome_arquivo", ""),
        "tipo_arquivo": inc.get("tipo_arquivo", ""),
        "severidade": inc.get("severidade", ""),
        "classificacao": inc.get("classificacao", ""),
        "regra_nome": inc.get("regra_nome", ""),
        "acoes": ";".join(inc.get("acoes", [])),
        "permitido": "sim" if inc.get("permitido", True) else "nao",
        "conformidade": ";".join(inc.get("conformidade", [])),
        "classificadores": ";".join(e.get("rotulo", "") for e in ev),
        "ocorrencias": sum(e.get("quantidade", 0) for e in ev),
        "estado": inc.get("estado", ""),
        "responsavel": inc.get("responsavel", ""),
        "extracao_completa": "sim" if inc.get("extracao_completa", True) else "nao",
    }


def csv_incidentes(incidentes: List[Dict]) -> str:
    saida = io.StringIO()
    w = csv.DictWriter(saida, fieldnames=COLUNAS_CSV, delimiter=";")
    w.writeheader()
    for inc in incidentes:
        w.writerow(_linha(inc))
    return saida.getvalue()


def painel(repo, dias: int = 30) -> Dict:
    desde = (datetime.now(timezone.utc) - timedelta(days=dias)).isoformat(timespec="seconds")
    return {
        "periodo_dias": dias,
        "desde": desde,
        "total": repo.contar(),
        "por_canal": repo.agregar("canal", desde),
        "por_severidade": repo.agregar("severidade", desde),
        "por_usuario": repo.agregar("usuario", desde)[:10],
        "por_regra": repo.agregar("regra_nome", desde)[:10],
        "por_estado": repo.agregar("estado", desde),
        "por_origem": repo.agregar("origem", desde),
        "por_classificacao": repo.agregar("classificacao", desde),
        "por_tipo_arquivo": repo.agregar("tipo_arquivo", desde)[:10],
    }


def conformidade(repo, norma: str, dias: int = 90) -> Dict:
    desde = (datetime.now(timezone.utc) - timedelta(days=dias)).isoformat(timespec="seconds")
    todos = repo.listar({"desde": desde}, limite=100000)
    alvo = [i for i in todos if norma.upper() in
            [c.upper() for c in i.conformidade]]
    bloqueados = [i for i in alvo if not i.permitido]
    return {
        "norma": norma, "periodo_dias": dias,
        "incidentes": len(alvo),
        "bloqueados": len(bloqueados),
        "permitidos_com_registro": len(alvo) - len(bloqueados),
        "por_canal": _contar([i.canal for i in alvo]),
        "por_severidade": _contar([i.severidade for i in alvo]),
        "confirmados": len([i for i in alvo if i.estado == "CONFIRMADO"]),
        "falsos_positivos": len([i for i in alvo if i.estado == "FALSO_POSITIVO"]),
        "em_aberto": len([i for i in alvo if i.estado in ("NOVO", "EM_ANALISE",
                                                          "ESCALADO")]),
    }


def _contar(valores) -> List[Dict]:
    d: Dict[str, int] = {}
    for v in valores:
        d[v or "(vazio)"] = d.get(v or "(vazio)", 0) + 1
    return [{"chave": k, "total": n} for k, n in
            sorted(d.items(), key=lambda x: -x[1])]


def html_conformidade(dados: Dict) -> str:
    def tabela(titulo, linhas):
        corpo = "".join(
            f"<tr><td>{html.escape(str(l['chave']))}</td>"
            f"<td style='text-align:right'>{l['total']}</td></tr>"
            for l in linhas)
        return (f"<h3>{html.escape(titulo)}</h3><table border=1 cellpadding=6 "
                f"cellspacing=0>{corpo}</table>")
    return (
        "<!doctype html><meta charset='utf-8'>"
        f"<title>Conformidade {html.escape(dados['norma'])}</title>"
        "<body style=\"font-family:system-ui,sans-serif;max-width:52rem;margin:2rem auto\">"
        f"<h1>Relatorio de conformidade — {html.escape(dados['norma'])}</h1>"
        f"<p>Periodo: ultimos {dados['periodo_dias']} dias.</p>"
        f"<ul><li>Incidentes: <b>{dados['incidentes']}</b></li>"
        f"<li>Bloqueados: <b>{dados['bloqueados']}</b></li>"
        f"<li>Permitidos com registro: {dados['permitidos_com_registro']}</li>"
        f"<li>Confirmados: {dados['confirmados']} | "
        f"Falsos positivos: {dados['falsos_positivos']} | "
        f"Em aberto: {dados['em_aberto']}</li></ul>"
        + tabela("Por canal", dados["por_canal"])
        + tabela("Por severidade", dados["por_severidade"])
        + "</body>")
