# -*- coding: utf-8 -*-
"""Gera os .properties de idioma com escapes \\uXXXX, para o arquivo nao
depender da codificacao com que for lido."""
import io, os

BASE = "/opt/projetos/exo/extensao/painel/web/WEB-INF/classes"

CABECA = (
    "# Rotulos da tela \"Conformidade DLP\".\n"
    "# NAO editar acentos direto: o arquivo usa escapes \\uXXXX de proposito,\n"
    "# para ser lido igual por qualquer JDK e qualquer codificacao de terminal.\n"
    "# Regenerar por construir.sh.\n"
)

PT = {
 "painel.titulo": "Conformidade DLP",
 "painel.abrangencia": "Os números abaixo cobrem as análises registradas por este painel desde a última reinicialização do portal. Não são o retrato do acervo inteiro.",
 "painel.situacao.titulo": "Situação",
 "painel.col.categoria": "Categoria",
 "painel.col.quantidade": "Quantidade",
 "painel.col.percentual": "Percentual",
 "painel.total": "Análises registradas: {0}",
 "painel.semDados": "Nenhuma análise registrada ainda. Use a ferramenta abaixo para analisar um trecho e o relatório passa a ter conteúdo.",
 "painel.alerta.naoVarridoComAchado": "Atenção: {0} item(ns) não foram lidos por inteiro e o trecho que se conseguiu ler já continha dado sensível. São os mais graves.",
 "painel.motivos.titulo": "Por que não foi varrido — e o que fazer com cada linha",
 "painel.col.motivo": "Motivo",
 "painel.col.encaminhamento": "Encaminhamento",
 "painel.motivoCru.titulo": "Motivos que o classificador não reconheceu. A classificação pode ter derivado; vale investigar.",
 "painel.tipos.titulo": "O que foi encontrado",
 "painel.col.tipo": "Tipo de dado",
 "painel.col.itens": "Itens",
 "painel.col.ocorrencias": "Ocorrências",
 "painel.tipos.nota": "Itens e ocorrências são números diferentes de propósito: 300 CPFs em UM contracheque é um arquivo para tratar; 1 CPF em 300 documentos é um hábito espalhado, e a resposta é treinamento, não quarentena.",
 "painel.classificacao.titulo": "Classificação atribuída (só do que foi lido por inteiro)",
 "painel.classificacao.sem": "(sem classificação — não varrido não tem classificação: tem pendência)",
 "painel.csv.baixar": "Baixar relatório em CSV",
 "painel.analise.titulo": "Analisar texto",
 "painel.analise.instrucao": "Cole um trecho e clique em Analisar. O texto é analisado pelo mesmo motor que varre o acervo. Limite desta tela: {0} caracteres.",
 "painel.analise.botao": "Analisar",
 "painel.analise.naoEcoa": "O texto enviado não é reexibido nesta tela, de propósito: ele pode conter justamente o dado que o painel se recusa a mostrar em claro.",
 "painel.analise.vazio": "A caixa estava em branco. Nada foi analisado e nada foi registrado no relatório.",
 "painel.analise.falhou": "Não foi possível analisar este texto.",
 "painel.analise.resultado": "Resultado da última análise",
 "painel.analise.limpo": "Nenhum dado pessoal ou sensível foi encontrado neste trecho.",
 "painel.analise.metrica": "{0} caracteres analisados em {1} ms.",
 "painel.analise.incompleta": "Análise incompleta:",
 "painel.analise.truncado": "O texto tinha {0} caracteres e foi cortado no limite desta tela; foram analisados os primeiros {1}. O resultado NÃO significa que o restante está limpo.",
 "painel.analise.classificacao": "Classificação:",
 "painel.analise.decisao": "Decisão da política:",
 "painel.analise.gatilhos": "Rótulos que dispararam:",
 "painel.col.rotulo": "Rótulo",
 "painel.col.severidade": "Severidade",
 "painel.col.amostras": "Amostras (mascaradas)",
 "painel.amostras.nota": "As amostras são exibidas já mascaradas pelo motor. O valor em claro nunca chega a esta tela.",
 "painel.acesso.negado": "Acesso negado",
 "painel.acesso.negadoDetalhe": "Esta tela é restrita aos administradores do portal. É preciso pertencer ao grupo {0}.",
 "painel.categoria.LIMPO": "Limpo (lido por inteiro, nada encontrado)",
 "painel.categoria.ACHADO": "Com achado",
 "painel.categoria.NAO_VARRIDO": "Não varrido",
 "painel.motivo.RECUSADO_POR_SEGURANCA": "Recusado por limite de segurança",
 "painel.motivo.PROVAVEL_DIGITALIZACAO": "Provável digitalização (sem camada de texto)",
 "painel.motivo.FORMATO_NAO_SUPORTADO": "Formato não suportado, corrompido ou cifrado",
 "painel.motivo.ACIMA_DO_TETO_DE_BYTES": "Acima do teto de bytes",
 "painel.motivo.ACIMA_DO_TETO_DE_CARACTERES": "Acima do teto de caracteres",
 "painel.motivo.ORCAMENTO_DE_TEMPO_ESGOTADO": "Orçamento de tempo esgotado",
 "painel.motivo.SEM_CONTEUDO_BINARIO": "Sem conteúdo binário",
 "painel.motivo.FALHA_NA_VARREDURA": "Falha na varredura (erro do DLP)",
 "painel.motivo.OUTRO": "Outro",
 "painel.encaminhamento.RECUSADO_POR_SEGURANCA": "investigar: possível ataque",
 "painel.encaminhamento.PROVAVEL_DIGITALIZACAO": "exige OCR",
 "painel.encaminhamento.FORMATO_NAO_SUPORTADO": "avaliar caso a caso",
 "painel.encaminhamento.ACIMA_DO_TETO_DE_BYTES": "revisar configuração",
 "painel.encaminhamento.ACIMA_DO_TETO_DE_CARACTERES": "revisar configuração",
 "painel.encaminhamento.ORCAMENTO_DE_TEMPO_ESGOTADO": "revisar configuração",
 "painel.encaminhamento.SEM_CONTEUDO_BINARIO": "normalmente inofensivo",
 "painel.encaminhamento.FALHA_NA_VARREDURA": "ler o log e corrigir",
 "painel.encaminhamento.OUTRO": "ver amostras no relatório",
 "painel.classificacao.PUBLICO": "Público",
 "painel.classificacao.INTERNO": "Interno",
 "painel.classificacao.RESTRITO": "Restrito",
 "painel.classificacao.SIGILOSO": "Sigiloso",
 "painel.severidade.BAIXA": "Baixa",
 "painel.severidade.MEDIA": "Média",
 "painel.severidade.ALTA": "Alta",
 "painel.acao.IGNORAR": "Ignorar",
 "painel.acao.REGISTRAR": "Registrar",
 "painel.acao.ALERTAR": "Alertar",
 "painel.acao.MASCARAR": "Mascarar",
 "painel.acao.BLOQUEAR": "Bloquear",
 "painel.acao.QUARENTENAR": "Quarentenar",
}

EN = {
 "painel.titulo": "DLP Compliance",
 "painel.abrangencia": "The figures below cover the analyses recorded by this panel since the portal was last restarted. They are not a picture of the whole repository.",
 "painel.situacao.titulo": "Status",
 "painel.col.categoria": "Category",
 "painel.col.quantidade": "Count",
 "painel.col.percentual": "Percentage",
 "painel.total": "Analyses recorded: {0}",
 "painel.semDados": "No analysis recorded yet. Use the tool below to analyse a snippet and the report will fill in.",
 "painel.alerta.naoVarridoComAchado": "Warning: {0} item(s) were not read in full and the part that could be read already contained sensitive data. These are the most serious ones.",
 "painel.motivos.titulo": "Why it was not scanned - and what to do with each line",
 "painel.col.motivo": "Reason",
 "painel.col.encaminhamento": "Next step",
 "painel.motivoCru.titulo": "Reasons the classifier did not recognise. Classification may have drifted; worth investigating.",
 "painel.tipos.titulo": "What was found",
 "painel.col.tipo": "Data type",
 "painel.col.itens": "Items",
 "painel.col.ocorrencias": "Occurrences",
 "painel.tipos.nota": "Items and occurrences are deliberately different numbers: 300 CPFs in ONE payslip is a single file to handle; 1 CPF in 300 documents is a widespread habit, and the answer is training, not quarantine.",
 "painel.classificacao.titulo": "Assigned classification (only for what was read in full)",
 "painel.classificacao.sem": "(unclassified - not scanned has no classification: it has a pending action)",
 "painel.csv.baixar": "Download report as CSV",
 "painel.analise.titulo": "Analyse text",
 "painel.analise.instrucao": "Paste a snippet and click Analyse. The text is analysed by the same engine that scans the repository. Limit for this screen: {0} characters.",
 "painel.analise.botao": "Analyse",
 "painel.analise.naoEcoa": "The submitted text is deliberately not shown back on this screen: it may contain exactly the data the panel refuses to display in the clear.",
 "painel.analise.vazio": "The box was empty. Nothing was analysed and nothing was recorded in the report.",
 "painel.analise.falhou": "This text could not be analysed.",
 "painel.analise.resultado": "Result of the last analysis",
 "painel.analise.limpo": "No personal or sensitive data was found in this snippet.",
 "painel.analise.metrica": "{0} characters analysed in {1} ms.",
 "painel.analise.incompleta": "Incomplete analysis:",
 "painel.analise.truncado": "The text had {0} characters and was cut at this screen's limit; the first {1} were analysed. The result does NOT mean the remainder is clean.",
 "painel.analise.classificacao": "Classification:",
 "painel.analise.decisao": "Policy decision:",
 "painel.analise.gatilhos": "Labels that triggered:",
 "painel.col.rotulo": "Label",
 "painel.col.severidade": "Severity",
 "painel.col.amostras": "Samples (masked)",
 "painel.amostras.nota": "Samples are shown already masked by the engine. The clear value never reaches this screen.",
 "painel.acesso.negado": "Access denied",
 "painel.acesso.negadoDetalhe": "This screen is restricted to portal administrators. Membership of group {0} is required.",
 "painel.categoria.LIMPO": "Clean (read in full, nothing found)",
 "painel.categoria.ACHADO": "With finding",
 "painel.categoria.NAO_VARRIDO": "Not scanned",
 "painel.motivo.RECUSADO_POR_SEGURANCA": "Refused by a security limit",
 "painel.motivo.PROVAVEL_DIGITALIZACAO": "Likely a scan (no text layer)",
 "painel.motivo.FORMATO_NAO_SUPORTADO": "Unsupported, corrupted or encrypted format",
 "painel.motivo.ACIMA_DO_TETO_DE_BYTES": "Above the byte ceiling",
 "painel.motivo.ACIMA_DO_TETO_DE_CARACTERES": "Above the character ceiling",
 "painel.motivo.ORCAMENTO_DE_TEMPO_ESGOTADO": "Time budget exhausted",
 "painel.motivo.SEM_CONTEUDO_BINARIO": "No binary content",
 "painel.motivo.FALHA_NA_VARREDURA": "Scan failure (DLP error)",
 "painel.motivo.OUTRO": "Other",
 "painel.encaminhamento.RECUSADO_POR_SEGURANCA": "investigate: possible attack",
 "painel.encaminhamento.PROVAVEL_DIGITALIZACAO": "requires OCR",
 "painel.encaminhamento.FORMATO_NAO_SUPORTADO": "assess case by case",
 "painel.encaminhamento.ACIMA_DO_TETO_DE_BYTES": "review configuration",
 "painel.encaminhamento.ACIMA_DO_TETO_DE_CARACTERES": "review configuration",
 "painel.encaminhamento.ORCAMENTO_DE_TEMPO_ESGOTADO": "review configuration",
 "painel.encaminhamento.SEM_CONTEUDO_BINARIO": "usually harmless",
 "painel.encaminhamento.FALHA_NA_VARREDURA": "read the log and fix",
 "painel.encaminhamento.OUTRO": "see samples in the report",
 "painel.classificacao.PUBLICO": "Public",
 "painel.classificacao.INTERNO": "Internal",
 "painel.classificacao.RESTRITO": "Restricted",
 "painel.classificacao.SIGILOSO": "Confidential",
 "painel.severidade.BAIXA": "Low",
 "painel.severidade.MEDIA": "Medium",
 "painel.severidade.ALTA": "High",
 "painel.acao.IGNORAR": "Ignore",
 "painel.acao.REGISTRAR": "Record",
 "painel.acao.ALERTAR": "Alert",
 "painel.acao.MASCARAR": "Mask",
 "painel.acao.BLOQUEAR": "Block",
 "painel.acao.QUARENTENAR": "Quarantine",
}

NAV_PT = {"portal.administration.conformidade-dlp": "Conformidade DLP"}
NAV_EN = {"portal.administration.conformidade-dlp": "DLP Compliance"}


def escapar(v):
    saida = []
    for ch in v:
        o = ord(ch)
        if o < 128:
            saida.append(ch)
        else:
            saida.append("\\u%04x" % o)
    return "".join(saida)


def escrever(caminho, mapa):
    os.makedirs(os.path.dirname(caminho), exist_ok=True)
    with io.open(caminho, "w", encoding="ascii", newline="\n") as f:
        f.write(CABECA)
        for k in mapa:
            f.write("%s=%s\n" % (k, escapar(mapa[k])))
    print("   escrito %s (%d chaves)" % (caminho, len(mapa)))


P = BASE + "/locale/portlet/painel/"
N = BASE + "/locale/navigation/portal/"
# O arquivo sem sufixo e o ultimo recurso do ResourceBundle: sem ele, um usuario
# com idioma nao previsto levaria MissingResourceException e nenhum rotulo.
escrever(P + "Conformidade.properties", PT)
escrever(P + "Conformidade_pt_BR.properties", PT)
escrever(P + "Conformidade_en.properties", EN)
escrever(N + "administration.properties", NAV_PT)
escrever(N + "administration_pt_BR.properties", NAV_PT)
escrever(N + "administration_en.properties", NAV_EN)
