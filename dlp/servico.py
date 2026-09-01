# -*- coding: utf-8 -*-
"""Servico de analise: amarra extracao, deteccao, EDM/IDM, estatistica,
politica, ACAO e incidente. E' o unico lugar onde a decisao acontece.

Todo canal (download, e-mail, ICAP, endpoint, nuvem, descoberta) entra por
`analisar` e sai com o mesmo veredito. Um caminho de decisao so' -- porque
politica que vale para o download e nao vale para o e-mail nao e' politica.

MUDANCA ESTRUTURAL DE 2026-08-31: entre decidir e registrar passou a existir um
terceiro passo -- EXECUTAR. Antes, o veredito ia direto para o incidente e a
lista de acoes era gravada como se tivesse acontecido. QUARENTENAR nao retinha,
NOTIFICAR nao avisava, MASCARAR devolvia um texto que ninguem usava. Agora o
`acoes.executor` produz o efeito e devolve o que REALMENTE foi feito, incluindo
o que nao foi possivel fazer e por que. O incidente guarda os dois: o que a
regra pediu (`acoes`) e o que aconteceu (`acoes_executadas`).
"""
from __future__ import annotations

import json
import threading
from typing import Dict, List, Optional, Sequence

from acoes.executor import Executor
from incidentes.modelo import Incidente
from incidentes.repositorio import Repositorio
from motor import extracao
from motor.deteccao import Varredura, severidade_maxima
from motor.estatistica import ClassificadorBayes
from motor.impressao import IndiceEdm, IndiceIdm
from politica.modelo import ACOES_IMPEDITIVAS, Condicao, Contexto, Excecao, Regra
from politica.motor import MotorPolitica
from politica.modelos_prontos import catalogo

CLASSIFICACAO_POR_SEVERIDADE = {
    "CRITICA": "SIGILOSO", "ALTA": "SIGILOSO", "MEDIA": "INTERNO",
    "BAIXA": "INTERNO", "NENHUMA": "PUBLICO",
}


class ServicoDlp:
    def __init__(self, repositorio: Repositorio, sal: bytes, executor: Executor,
                 dicionarios: Optional[Dict[str, List[str]]] = None):
        self.repo = repositorio
        self._sal = sal
        self.executor = executor
        self._trava = threading.RLock()
        self.dicionarios_arquivo = dicionarios or {}
        self.varredura = Varredura(dicionarios=self._dicionarios_efetivos())
        self.politica = MotorPolitica(self._carregar_politica())
        self.edm: Dict[str, IndiceEdm] = {}
        self.idm: Dict[str, IndiceIdm] = {}
        self.classificador: Optional[ClassificadorBayes] = None
        self._carregar_indices()

    # ------------------------------------------------------------- politica
    def _carregar_politica(self) -> List[Regra]:
        doc = self.repo.ler_json("politica", "identificador", "vigente")
        if not doc:
            regras = catalogo()
            self.salvar_politica(regras, "sistema", inicial=True)
            return regras
        return [_regra_de_dicionario(r) for r in doc.get("regras", [])]

    def salvar_politica(self, regras: Sequence[Regra], autor: str,
                        inicial: bool = False) -> None:
        doc = {"regras": [_regra_para_dicionario(r) for r in regras]}
        self.repo.guardar_json("politica", "identificador", "vigente", doc,
                               {"atualizado_por": autor})
        self.repo.auditar(autor, "POLITICA_INICIAL" if inicial else "POLITICA_ALTERADA",
                          "vigente", f"{len(regras)} regra(s)")
        # Na carga inicial o motor ainda nao existe -- e' ele que esta' sendo
        # construido a partir daqui. Guardar e seguir; quem chamou aplica.
        motor = getattr(self, "politica", None)
        if motor is not None:
            with self._trava:
                motor.substituir(list(regras))

    def regras(self) -> List[Regra]:
        return list(self.politica.regras)

    # ----------------------------------------------------------- dicionarios
    def _dicionarios_efetivos(self) -> Dict[str, List[str]]:
        """Une o que veio do arquivo no volume com o que foi cadastrado na API.

        O arquivo continua valendo porque instalacoes antigas dependem dele; o
        cadastro tem precedencia porque e' o caminho que o administrador
        enxerga e consegue corrigir sem acesso ao servidor.
        """
        efetivos = {nome: list(termos)
                    for nome, termos in self.dicionarios_arquivo.items()}
        for nome, doc in self.repo.dicionarios().items():
            efetivos[nome] = list(doc.get("termos", []))
        return efetivos

    def recarregar_dicionarios(self) -> Dict[str, int]:
        with self._trava:
            efetivos = self._dicionarios_efetivos()
            self.varredura = Varredura(dicionarios=efetivos)
        return {nome: len(termos) for nome, termos in efetivos.items()}

    # --------------------------------------------------------------- indices
    def _carregar_indices(self) -> None:
        # `ativo` chegou depois; instalacao antiga nao tem a coluna preenchida
        # em toda linha, e COALESCE evita que um indice legitimo desapareca do
        # motor so' por causa da migracao.
        for linha in self.repo._conexao().execute(
                "SELECT nome FROM indice_edm WHERE COALESCE(ativo,1)=1"):
            doc = self.repo.ler_json("indice_edm", "nome", linha["nome"])
            if doc:
                idx = IndiceEdm(self._sal, linha["nome"])
                idx.colunas = doc.get("colunas", [])
                idx.minimo_colunas = doc.get("minimo", 2)
                idx.total_registros = doc.get("total_registros", 0)
                idx._celulas = set(doc.get("celulas", []))
                self.edm[linha["nome"]] = idx
        for linha in self.repo._conexao().execute(
                "SELECT DISTINCT nome FROM indice_idm WHERE COALESCE(ativo,1)=1"):
            nome = linha["nome"]
            idx = IndiceIdm(self._sal, nome)
            for r in self.repo._conexao().execute(
                    "SELECT documento, janelas FROM indice_idm "
                    "WHERE nome=? AND COALESCE(ativo,1)=1", (nome,)):
                idx.documentos[r["documento"]] = set(json.loads(r["janelas"]))
            self.idm[nome] = idx
        modelo = self.repo.ler_json("modelo_estatistico", "nome", "padrao")
        if modelo:
            self.classificador = ClassificadorBayes.importar(json.dumps(modelo))

    def recarregar_indices(self) -> Dict[str, int]:
        with self._trava:
            self.edm, self.idm = {}, {}
            self._carregar_indices()
        return {"edm": len(self.edm), "idm": len(self.idm)}

    def indexar_edm(self, nome: str, colunas: Sequence[str],
                    linhas: Sequence[Sequence[str]], minimo: int, autor: str) -> Dict:
        idx = IndiceEdm(self._sal, nome)
        idx.indexar(colunas, linhas, minimo)
        self.edm[nome] = idx
        self.repo.guardar_json("indice_edm", "nome", nome, {
            "colunas": list(colunas), "minimo": minimo,
            "total_registros": idx.total_registros,
            "celulas": sorted(idx._celulas)})
        self.repo.ativar_indice("indice_edm", nome, True)
        self.repo.auditar(autor, "EDM_INDEXADO", nome,
                          f"{idx.total_registros} registro(s), nenhum valor guardado")
        return {"indice": nome, "registros": idx.total_registros,
                "celulas": len(idx._celulas)}

    def indexar_idm(self, nome: str, documento: str, texto: str, autor: str) -> Dict:
        idx = self.idm.setdefault(nome, IndiceIdm(self._sal, nome))
        total = idx.registrar(documento, texto)
        c = self.repo._conexao()
        with c:
            c.execute("INSERT OR REPLACE INTO indice_idm (nome,documento,janelas,"
                      "atualizado_em,ativo) VALUES (?,?,?,datetime('now'),1)",
                      (nome, documento, json.dumps(sorted(idx.documentos[documento]))))
        self.repo.auditar(autor, "IDM_INDEXADO", f"{nome}/{documento}",
                          f"{total} janela(s)")
        return {"indice": nome, "documento": documento, "janelas": total}

    def treinar_estatistico(self, classe: str, texto: str, autor: str) -> Dict:
        if self.classificador is None:
            self.classificador = ClassificadorBayes()
        self.classificador.treinar(classe, texto)
        self.repo.guardar_json("modelo_estatistico", "nome", "padrao",
                               json.loads(self.classificador.exportar()))
        self.repo.auditar(autor, "ESTATISTICO_TREINADO", classe, f"{len(texto)} bytes")
        return {"classe": classe,
                "documentos": dict(self.classificador.documentos_por_classe)}

    # --------------------------------------------------------------- analise
    def analisar(self, dados: Optional[bytes], ctx: Contexto,
                 texto_direto: str = "", recurso: str = "",
                 registrar: bool = True, efeitos: bool = True,
                 cifra_delegada: bool = False) -> Dict:
        """Analisa, DECIDE e EXECUTA.

        `dados` para arquivo; `texto_direto` para corpo de e-mail, mensagem de
        chat ou area de transferencia.

        `cifra_delegada=True` diz que o CANAL cumpre a acao CRIPTOGRAFAR --
        e' o caso do e-mail, onde S/MIME para o certificado do destinatario
        protege melhor que um ZIP com senha. So' o canal que JA' conferiu que
        consegue cifrar deve passar isto.

        `registrar` e `efeitos` sao INDEPENDENTES, e a diferenca importa:

          * `registrar=True, efeitos=True`  -- operacao normal.
          * `registrar=True, efeitos=False` -- MODO OBSERVACAO do portal: o
            incidente e' gravado, o que ACONTECERIA fica em `acoes_simuladas`,
            e nada com efeito colateral ocorre. Foi um defeito medido em
            producao: enquanto os dois estavam amarrados, a observacao retinha
            copia no cofre e mandava e-mail ao administrador -- ou seja, "nada
            muda para o usuario" nao era verdade.
          * `registrar=False, efeitos=False` -- simulacao pura ("o que
            aconteceria se"), sem sujar o acervo de incidentes.
        """
        if dados is not None:
            ex = extracao.extrair(dados, ctx.nome_arquivo or "")
            texto = ex.conteudo
            completa, motivo = ex.completo, ex.motivo_parcial
            ctx.tipo_arquivo = ctx.tipo_arquivo or ex.formato
            ctx.disfarcado = ex.disfarcado
            mime, tamanho = ex.mime, len(dados)
        else:
            texto, completa, motivo = texto_direto or "", True, ""
            mime, tamanho = "text/plain", len(texto.encode("utf-8"))

        achados = self.varredura.varrer(texto)

        casados_edm = [n for n, idx in self.edm.items()
                       if idx.casar(texto).get("registro_completo")]
        casados_idm = [n for n, idx in self.idm.items() if idx.casar(texto)]
        ctx.indices_edm = casados_edm
        ctx.indices_idm = casados_idm
        if self.classificador is not None:
            r = self.classificador.classificar(texto)
            ctx.classe_estatistica = r.get("classe") or ""

        with self._trava:
            veredito = self.politica.avaliar(achados, ctx, texto)

        severidade = severidade_maxima(achados)
        # Extracao parcial NUNCA vira "limpo": sobe para MEDIA no minimo.
        if not completa and severidade == "NENHUMA":
            severidade = "MEDIA"
        classificacao = CLASSIFICACAO_POR_SEVERIDADE.get(severidade, "INTERNO")

        acoes_pedidas = list(veredito.acoes)
        permitido = veredito.permitido
        liberacao_usada = ""
        motivo_final = veredito.motivo

        # ------------------------------------------------- liberacao previa
        # E' o que fecha o ciclo de REVISAO_MANUAL: o analista aprovou, e a
        # proxima tentativa do MESMO usuario sobre o MESMO recurso passa. Sem
        # isto, "encaminhado para revisao" seria bloqueio permanente.
        if not permitido and efeitos:
            liberacao = self.executor.liberacoes.valida_para(
                ctx.usuario, recurso, ctx.canal)
            if liberacao:
                liberacao_usada = liberacao["identificador"]
                permitido = True
                acoes_pedidas = [a for a in acoes_pedidas
                                 if a not in ACOES_IMPEDITIVAS]
                motivo_final = (
                    f"liberado por {liberacao['autor']} "
                    f"(liberacao {liberacao_usada}): {liberacao['justificativa']}")
                self.executor.liberacoes.consumir(liberacao_usada)

        inc = Incidente(
            canal=ctx.canal, usuario=ctx.usuario, grupos=list(ctx.grupos),
            ip=ctx.ip, destino=ctx.destino, recurso=recurso,
            nome_arquivo=ctx.nome_arquivo, tipo_arquivo=ctx.tipo_arquivo,
            mime=mime, disfarcado=ctx.disfarcado, tamanho=tamanho,
            severidade=severidade, classificacao=classificacao,
            regra=veredito.regra or "", regra_nome=veredito.regra_nome,
            acoes=acoes_pedidas, permitido=permitido, motivo=motivo_final,
            conformidade=list(veredito.conformidade),
            evidencia=veredito.evidencia, extracao_completa=completa,
            motivo_parcial=motivo, indices_edm=casados_edm,
            indices_idm=list(casados_idm),
            classe_estatistica=ctx.classe_estatistica,
            mensagem_usuario=veredito.mensagem,
            orientacao=self._orientacao_da_regra(veredito.regra),
            liberacao=liberacao_usada,
            origem=_origem(ctx.canal))

        # ----------------------------------------------------- 2. EXECUCAO
        todas_ocorrencias = [o for a in achados for o in a.ocorrencias]
        contexto_execucao = {
            "usuario": ctx.usuario, "email": ctx.email, "canal": ctx.canal,
            "recurso": recurso, "nome_arquivo": ctx.nome_arquivo,
            "destino": ctx.destino}
        if efeitos:
            execucao = self.executor.aplicar(
                acoes_pedidas, permitido, dados, texto, todas_ocorrencias,
                inc.como_dicionario(), contexto_execucao,
                cifra_delegada=cifra_delegada)
        else:
            execucao = self.executor.simular(
                acoes_pedidas, permitido, dados, texto, todas_ocorrencias,
                inc.como_dicionario(), contexto_execucao,
                cifra_delegada=cifra_delegada)

        inc.modo = "APLICADO" if efeitos else "OBSERVACAO"
        inc.permitido = execucao.permitido
        if efeitos:
            inc.acoes_executadas = execucao.acoes_executadas
        else:
            inc.acoes_simuladas = execucao.acoes_executadas
        inc.acoes_nao_aplicaveis = execucao.acoes_nao_aplicaveis
        inc.quarentena = execucao.quarentena
        inc.notificacoes = len(execucao.notificacoes)
        if execucao.orientacao:
            inc.orientacao = execucao.orientacao
        if execucao.motivo:
            inc.motivo = f"{inc.motivo}; {execucao.motivo}"

        resposta = {
            "permitido": execucao.permitido,
            "acoes": acoes_pedidas,
            "acoes_executadas": execucao.acoes_executadas,
            "acoes_nao_aplicaveis": execucao.acoes_nao_aplicaveis,
            "regra": veredito.regra,
            "regra_nome": veredito.regra_nome,
            "severidade": severidade,
            "classificacao": classificacao,
            "mensagem": veredito.mensagem,
            "motivo": inc.motivo,
            "conformidade": veredito.conformidade,
            "evidencia": veredito.evidencia,
            "extracao_completa": completa,
            "motivo_parcial": motivo,
            "indices_edm": casados_edm,
            "indices_idm": list(casados_idm),
            "classe_estatistica": ctx.classe_estatistica,
            "disfarcado": ctx.disfarcado,
            "tipo_arquivo": ctx.tipo_arquivo,
            "liberacao": liberacao_usada,
            "quarentena": execucao.quarentena,
            "orientacao": execucao.orientacao,
            "cifra_pendente": execucao.cifra_pendente,
            "modo": inc.modo,
        }
        # O conteudo TRANSFORMADO volta ao portal em base64. E' o que faz
        # MASCARAR e CRIPTOGRAFAR existirem de verdade: antes, o servico
        # devolvia `texto_mascarado` e o filtro Java simplesmente nao o usava,
        # entao no download o conteudo passava inteiro ou era barrado.
        if execucao.conteudo is not None and efeitos:
            import base64
            resposta["conteudo_base64"] = base64.b64encode(
                execucao.conteudo).decode("ascii")
            resposta["mime_saida"] = execucao.mime
            resposta["nome_saida"] = execucao.nome_arquivo
        if execucao.texto_mascarado and efeitos:
            resposta["texto_mascarado"] = execucao.texto_mascarado

        # A classificacao do recurso e' o MAPA ("onde estao os dados"), e nao
        # uma acao: vale tambem em observacao.
        if recurso and registrar:
            self.repo.classificar(recurso, severidade, classificacao,
                                  [a.rotulo for a in achados], completa, motivo)

        deve_registrar = (registrar and
                          (achados or not completa or not execucao.permitido
                           or liberacao_usada))
        if deve_registrar:
            if efeitos and "REVISAO_MANUAL" in execucao.acoes_executadas:
                inc.estado = "EM_ANALISE"
            inc.registrar_trilha("sistema", "DETECCAO",
                                 f"{severidade} por {veredito.motivo}")
            if execucao.acoes_executadas:
                inc.registrar_trilha(
                    "sistema", "ACAO" if efeitos else "ACAO_SIMULADA",
                    ("" if efeitos else "em observacao, NADA foi feito: ")
                    + ", ".join(execucao.acoes_executadas))
            for nao in execucao.acoes_nao_aplicaveis:
                inc.registrar_trilha("sistema", "ACAO_NAO_APLICAVEL",
                                     f"{nao['acao']}: {nao['motivo']}")
            if liberacao_usada:
                inc.registrar_trilha("sistema", "LIBERACAO", liberacao_usada)
            self.repo.salvar(inc)
            resposta["incidente"] = inc.identificador
        return resposta

    def _orientacao_da_regra(self, identificador: Optional[str]) -> str:
        if not identificador:
            return ""
        for r in self.politica.regras:
            if r.identificador == identificador:
                return r.orientacao
        return ""


def _origem(canal: str) -> str:
    return {"ENDPOINT": "ENDPOINT", "USB": "ENDPOINT", "CLIPBOARD": "ENDPOINT",
            "IMPRESSAO": "ENDPOINT", "ICAP": "ICAP", "EMAIL": "EMAIL",
            "EMAIL_INTERNO": "EMAIL", "NUVEM": "NUVEM",
            "DESCOBERTA": "DESCOBERTA"}.get(canal, "PORTAL")


def _regra_para_dicionario(r: Regra) -> dict:
    from dataclasses import asdict
    return asdict(r)


def _regra_de_dicionario(d: dict) -> Regra:
    cond = Condicao(**d.get("condicao", {}))
    exc = [Excecao(**e) for e in d.get("excecoes", [])]
    campos = {k: v for k, v in d.items() if k not in ("condicao", "excecoes")}
    return Regra(condicao=cond, excecoes=exc, **campos)
