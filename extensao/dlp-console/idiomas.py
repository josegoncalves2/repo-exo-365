# -*- coding: utf-8 -*-
"""Gera os bundles do console a partir das chaves REALMENTE usadas no JS.

Gerar em vez de manter a mao e' de proposito: um rotulo faltante so' aparece
como a propria chave na tela, e ninguem le' 103 chaves conferindo. Aqui a
falta vira erro de build.
"""
import re, pathlib, sys

RAIZ = pathlib.Path(sys.argv[1])
js = (RAIZ / "web/js/consoleDlp.bundle.js").read_text(encoding="utf-8")

chaves = set(re.findall(r"\$t\('([^']+)'\)", js))
chaves |= {"dlp.aba." + c for c in re.findall(r"codigo: '([a-z]+)'", js)}
chaves |= set(re.findall(r"rotulo: '([^']+)'", js))
chaves |= set(re.findall(r"nota: '([^']+)'", js))
# `titulo:` alimenta $t(a.titulo) nos agregados do painel. Faltava aqui, e
# duas chaves passavam sem traducao — o portao mentia por omissao.
chaves |= set(re.findall(r"titulo: '([^']+)'", js))
chaves = {k for k in chaves if "'" not in k and " " not in k and "+" not in k}

PT = {
 "console.titulo":"Prevenção de Perda de Dados",
 "dlp.aba.painel":"Painel","dlp.aba.incidentes":"Incidentes","dlp.aba.revisao":"Revisão",
 "dlp.aba.quarentena":"Quarentena","dlp.aba.politica":"Política","dlp.aba.indices":"Índices",
 "dlp.aba.dicionarios":"Dicionários","dlp.aba.descoberta":"Descoberta","dlp.aba.avisos":"Avisos",
 "dlp.aba.agentes":"Agentes","dlp.aba.auditoria":"Auditoria",
 "dlp.buscar":"Buscar","dlp.vazio":"Nada a mostrar","dlp.filaVazia":"Nenhum item aguardando decisão",
 "dlp.recarregar":"Recarregar","dlp.tentarDeNovo":"Tentar de novo",
 "dlp.falhaServico":"O serviço de DLP não respondeu:",
 "dlp.incidentes":"Incidentes","dlp.noTotal":"no total",
 "dlp.esperandoRevisao":"Esperando revisão","dlp.decisaoHumana":"decisão humana pendente",
 "dlp.emQuarentena":"Em quarentena","dlp.retidosNoCofre":"retidos no cofre",
 "dlp.avisosNaFila":"Avisos na fila","dlp.emFalha":"em falha",
 "dlp.regrasAtivas":"Regras ativas","dlp.naPolitica":"na política vigente",
 "dlp.componentes":"Componentes","dlp.ativo":"ativo","dlp.inativo":"inativo",
 "dlp.porSeveridade":"Por severidade","dlp.porCanal":"Por canal",
 "dlp.quando":"Quando","dlp.severidade":"Severidade","dlp.canal":"Canal",
 "dlp.regra":"Regra","dlp.usuario":"Usuário","dlp.estado":"Estado","dlp.abrir":"Abrir",
 "dlp.csv":"CSV","dlp.incidente":"Incidente","dlp.responsavel":"Responsável",
 "dlp.atribuir":"Atribuir","dlp.anotacao":"Anotação","dlp.anotar":"Anotar",
 "dlp.emAnalise":"Em análise","dlp.confirmar":"Confirmar","dlp.falsoPositivo":"Falso positivo",
 "dlp.encerrar":"Encerrar","dlp.estadoAlterado":"Estado alterado",
 "dlp.atribuido":"Responsável atribuído","dlp.anotado":"Anotação registrada",
 "dlp.revisaoExplica":"Aprovar cria uma liberação com prazo, escopo nominal e contagem de usos. A justificativa é obrigatória e vai para a trilha de auditoria.",
 "dlp.justificativa":"Justificativa","dlp.horas":"Horas","dlp.tetoUsos":"Teto de usos",
 "dlp.aprovar":"Aprovar","dlp.reprovar":"Reprovar","dlp.aprovado":"Liberação criada",
 "dlp.reprovado":"Pedido reprovado","dlp.arquivo":"Arquivo",
 "dlp.cofreProprio":"Retidos por este motor","dlp.cofreNativo":"Retidos pelo add-on da plataforma",
 "dlp.addonExo":"nativo eXo","dlp.nativoIndisponivel":"Cofre nativo indisponível:",
 "dlp.liberacoesVigentes":"Liberações vigentes","dlp.baixar":"Baixar","dlp.liberar":"Liberar",
 "dlp.liberado":"Item liberado","dlp.revogar":"Revogar","dlp.revogado":"Liberação revogada",
 "dlp.expiraEm":"Expira em","dlp.usos":"Usos",
 "dlp.acao":"Ação","dlp.ativa":"Ativa","dlp.editar":"Editar","dlp.cancelar":"Cancelar",
 "dlp.gravar":"Gravar","dlp.jsonInvalido":"O texto não é um JSON válido",
 "dlp.politicaGravada":"Política gravada",
 "dlp.indicesExplica":"Desativar um índice preserva o registro e a trilha. Esta tela não apaga índice.",
 "dlp.nome":"Nome","dlp.tipo":"Tipo","dlp.registros":"Registros","dlp.atualizado":"Atualizado",
 "dlp.termos":"Termos","dlp.termosAjuda":"Termos, um por linha ou separados por vírgula",
 "dlp.novoDicionario":"Novo dicionário","dlp.dicionarioGravado":"Dicionário gravado",
 "dlp.origem":"Origem","dlp.lidos":"Lidos","dlp.varreduraCompleta":"Varredura completa",
 "dlp.iniciarVarredura":"Iniciar varredura","dlp.varreduraIniciada":"Varredura iniciada",
 "dlp.varreduraCancelada":"Varredura cancelada",
 "dlp.semOrigem":"Nenhuma origem de descoberta configurada. Defina DLP_DESCOBERTA_URL para habilitar a varredura em repouso.",
 "dlp.destinatario":"Destinatário","dlp.tentativas":"Tentativas","dlp.reenviar":"Reenviar",
 "dlp.reenviado":"Aviso reenfileirado",
 "dlp.maquina":"Máquina","dlp.versao":"Versão","dlp.ultimoContato":"Último contato",
 
 "dlp.correio":"Correio de avisos",
 "dlp.correioDesligado":"DLP_NOTIFICA_SMTP_HOST vazio: as ações NOTIFICAR_* ficam em FALHA na fila",
 "dlp.siem":"Envio a SIEM",
 "dlp.siemDesligado":"DLP_SIEM_HOST vazio",
 "dlp.descobertaRepouso":"Descoberta em repouso",
 "dlp.indicesEdmIdm":"Índices EDM/IDM",
 "dlp.porRegra":"Por regra",
 "dlp.porUsuario":"Por usuário",
 "dlp.prioridade":"Prioridade",
 "dlp.identificador":"Identificador",
 "dlp.autor":"Autor","dlp.alvo":"Alvo","dlp.detalhe":"Detalhe",
}
EN = {
 "console.titulo":"Data Loss Prevention",
 "dlp.aba.painel":"Dashboard","dlp.aba.incidentes":"Incidents","dlp.aba.revisao":"Review",
 "dlp.aba.quarentena":"Quarantine","dlp.aba.politica":"Policy","dlp.aba.indices":"Indexes",
 "dlp.aba.dicionarios":"Dictionaries","dlp.aba.descoberta":"Discovery","dlp.aba.avisos":"Notices",
 "dlp.aba.agentes":"Agents","dlp.aba.auditoria":"Audit",
 "dlp.buscar":"Search","dlp.vazio":"Nothing to show","dlp.filaVazia":"No item awaiting decision",
 "dlp.recarregar":"Reload","dlp.tentarDeNovo":"Try again",
 "dlp.falhaServico":"The DLP service did not answer:",
 "dlp.incidentes":"Incidents","dlp.noTotal":"in total",
 "dlp.esperandoRevisao":"Awaiting review","dlp.decisaoHumana":"human decision pending",
 "dlp.emQuarentena":"In quarantine","dlp.retidosNoCofre":"held in the vault",
 "dlp.avisosNaFila":"Notices queued","dlp.emFalha":"failed",
 "dlp.regrasAtivas":"Active rules","dlp.naPolitica":"in the current policy",
 "dlp.componentes":"Components","dlp.ativo":"up","dlp.inativo":"down",
 "dlp.porSeveridade":"By severity","dlp.porCanal":"By channel",
 "dlp.quando":"When","dlp.severidade":"Severity","dlp.canal":"Channel",
 "dlp.regra":"Rule","dlp.usuario":"User","dlp.estado":"State","dlp.abrir":"Open",
 "dlp.csv":"CSV","dlp.incidente":"Incident","dlp.responsavel":"Assignee",
 "dlp.atribuir":"Assign","dlp.anotacao":"Note","dlp.anotar":"Add note",
 "dlp.emAnalise":"Under analysis","dlp.confirmar":"Confirm","dlp.falsoPositivo":"False positive",
 "dlp.encerrar":"Close","dlp.estadoAlterado":"State changed",
 "dlp.atribuido":"Assignee set","dlp.anotado":"Note recorded",
 "dlp.revisaoExplica":"Approving creates a waiver with an expiry, a named scope and a use count. The justification is required and goes to the audit trail.",
 "dlp.justificativa":"Justification","dlp.horas":"Hours","dlp.tetoUsos":"Use cap",
 "dlp.aprovar":"Approve","dlp.reprovar":"Reject","dlp.aprovado":"Waiver created",
 "dlp.reprovado":"Request rejected","dlp.arquivo":"File",
 "dlp.cofreProprio":"Held by this engine","dlp.cofreNativo":"Held by the platform add-on",
 "dlp.addonExo":"eXo native","dlp.nativoIndisponivel":"Native vault unavailable:",
 "dlp.liberacoesVigentes":"Active waivers","dlp.baixar":"Download","dlp.liberar":"Release",
 "dlp.liberado":"Item released","dlp.revogar":"Revoke","dlp.revogado":"Waiver revoked",
 "dlp.expiraEm":"Expires at","dlp.usos":"Uses",
 "dlp.acao":"Action","dlp.ativa":"Active","dlp.editar":"Edit","dlp.cancelar":"Cancel",
 "dlp.gravar":"Save","dlp.jsonInvalido":"The text is not valid JSON",
 "dlp.politicaGravada":"Policy saved",
 "dlp.indicesExplica":"Deactivating an index preserves the record and the trail. This screen never deletes an index.",
 "dlp.nome":"Name","dlp.tipo":"Type","dlp.registros":"Records","dlp.atualizado":"Updated",
 "dlp.termos":"Terms","dlp.termosAjuda":"Terms, one per line or comma separated",
 "dlp.novoDicionario":"New dictionary","dlp.dicionarioGravado":"Dictionary saved",
 "dlp.origem":"Source","dlp.lidos":"Read","dlp.varreduraCompleta":"Full scan",
 "dlp.iniciarVarredura":"Start scan","dlp.varreduraIniciada":"Scan started",
 "dlp.varreduraCancelada":"Scan cancelled",
 "dlp.semOrigem":"No discovery source configured. Set DLP_DESCOBERTA_URL to enable data-at-rest scanning.",
 "dlp.destinatario":"Recipient","dlp.tentativas":"Attempts","dlp.reenviar":"Resend",
 "dlp.reenviado":"Notice re-queued",
 "dlp.maquina":"Host","dlp.versao":"Version","dlp.ultimoContato":"Last seen",
 
 "dlp.correio":"Notice mail relay",
 "dlp.correioDesligado":"DLP_NOTIFICA_SMTP_HOST empty: NOTIFICAR_* actions stay FAILED in the queue",
 "dlp.siem":"SIEM forwarding",
 "dlp.siemDesligado":"DLP_SIEM_HOST empty",
 "dlp.descobertaRepouso":"Data-at-rest discovery",
 "dlp.indicesEdmIdm":"EDM/IDM indexes",
 "dlp.porRegra":"By rule",
 "dlp.porUsuario":"By user",
 "dlp.prioridade":"Priority",
 "dlp.identificador":"Identifier",
 "dlp.autor":"Author","dlp.alvo":"Target","dlp.detalhe":"Detail",
}

def confere(nome, mapa):
    faltam = sorted(chaves - set(mapa))
    if faltam:
        raise SystemExit("ERRO: %s sem traducao para: %s" % (nome, ", ".join(faltam)))
    sobram = sorted(set(mapa) - chaves - {"console.titulo"})
    if sobram:
        raise SystemExit("ERRO: %s traduz chave que o JS nao usa: %s" % (nome, ", ".join(sobram)))

confere("pt_BR", PT)
confere("en", EN)

def escapa(t):
    return "".join(c if ord(c) < 128 else "\\u%04x" % ord(c) for c in t)

destino = RAIZ / "web/WEB-INF/classes/locale/portlet/dlpconsole"
for arquivo, mapa in (("Console_pt_BR.properties", PT),
                      ("Console_en.properties", EN),
                      ("Console.properties", EN)):
    corpo = "".join("%s=%s\n" % (k, escapa(mapa[k])) for k in sorted(mapa))
    (destino / arquivo).write_text(corpo, encoding="ascii")

print("bundles gravados: %d chaves usadas no JS, todas traduzidas em pt_BR e en"
      % len(chaves))
