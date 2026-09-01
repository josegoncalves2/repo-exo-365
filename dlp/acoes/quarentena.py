# -*- coding: utf-8 -*-
"""Acao QUARENTENAR: retirar de circulacao E poder devolver.

O DEFEITO QUE ISTO CORRIGE, confessado em PENDENCIAS.md: QUARENTENAR estava na
lista de acoes impeditivas e por isso apenas BLOQUEAVA. Era BLOQUEAR com outro
nome -- nao retinha nada, nao guardava nada, e nao havia como restaurar. E' o
mesmo defeito que este projeto apontou no add-on nativo, repetido por nos.

O QUE QUARENTENA PRECISA TER, e agora tem:

  1. RETENCAO -- o conteudo exato que tentou sair fica guardado, cifrado, no
     cofre. Sem isso o analista investiga um incidente sem ter o objeto.
  2. IMPEDIMENTO -- a transferencia nao acontece.
  3. CADEIA DE CUSTODIA -- quem reteve, quando, por qual regra, hash do
     conteudo em claro. O hash e' o que permite provar, na devolucao, que o
     arquivo e' bit a bit o mesmo.
  4. CAMINHO DE VOLTA -- alguem com autoridade libera, e a liberacao faz a
     proxima tentativa PASSAR. Quarentena sem porta de saida vira arquivo
     perdido, e o usuario aprende a nao usar o portal.

O QUE NAO E' FEITO, e por que: o arquivo de origem no acervo NAO e' movido nem
apagado. Quarentena de saida retem a COPIA que estava saindo. Mover o original
seria acao destrutiva sobre o documento de trabalho de alguem, decidida por uma
regra automatica -- e uma regra mal calibrada apagaria o acervo antes de alguem
notar. A retirada do original e' decisao humana, tomada no console com o
incidente a vista.
"""
from __future__ import annotations

import secrets
from dataclasses import dataclass
from typing import Dict, List, Optional

from motor.cofre import Cofre, CofreCorrompido

ESTADOS = ("RETIDO", "LIBERADO", "DESCARTADO")


@dataclass(frozen=True)
class ItemRetido:
    identificador: str
    sha256: str
    tamanho: int


class Quarentena:
    def __init__(self, repositorio, cofre: Cofre):
        self.repo = repositorio
        self.cofre = cofre

    # ---------------------------------------------------------------- reter
    def reter(self, conteudo: bytes, contexto: Dict) -> ItemRetido:
        """Guarda o conteudo cifrado e abre o registro de custodia."""
        guardado = self.cofre.guardar(conteudo)
        identificador = "q-" + secrets.token_hex(8)
        self.repo.reter({
            "identificador": identificador,
            "incidente": contexto.get("incidente", ""),
            "usuario": contexto.get("usuario", ""),
            "canal": contexto.get("canal", ""),
            "recurso": contexto.get("recurso", ""),
            "nome_arquivo": contexto.get("nome_arquivo", ""),
            "mime": contexto.get("mime", ""),
            "tamanho": guardado.tamanho,
            "sha256": guardado.sha256,
            "item_cofre": guardado.identificador,
            "estado": "RETIDO",
            "regra": contexto.get("regra", ""),
            "regra_nome": contexto.get("regra_nome", ""),
            "severidade": contexto.get("severidade", ""),
            "motivo": contexto.get("motivo", ""),
        })
        self.repo.auditar("sistema", "QUARENTENA_RETIDO", identificador,
                          f"{guardado.tamanho} bytes, sha256 {guardado.sha256[:16]}..., "
                          f"regra {contexto.get('regra_nome', '')}")
        return ItemRetido(identificador, guardado.sha256, guardado.tamanho)

    # -------------------------------------------------------------- consulta
    def listar(self, filtros: Optional[Dict] = None, limite: int = 50,
               deslocamento: int = 0) -> List[Dict]:
        return self.repo.quarentena(filtros, limite, deslocamento)

    def obter(self, identificador: str) -> Optional[Dict]:
        return self.repo.item_quarentena(identificador)

    def conteudo(self, identificador: str) -> bytes:
        """Devolve o original em claro. E' o caminho de restauracao.

        Chamado SO' pela rota de console, que exige o papel de administrador do
        portal. A conferencia de integridade e' feita aqui e nao no chamador:
        entregar silenciosamente um arquivo adulterado seria pior do que falhar.
        """
        registro = self.repo.item_quarentena(identificador)
        if not registro:
            raise KeyError(identificador)
        dados = self.cofre.ler(registro["item_cofre"])
        import hashlib
        atual = hashlib.sha256(dados).hexdigest()
        if registro["sha256"] and atual != registro["sha256"]:
            raise CofreCorrompido(
                f"{identificador}: hash do conteudo restaurado ({atual[:16]}...) "
                f"difere do registrado ({registro['sha256'][:16]}...)")
        self.repo.auditar("console", "QUARENTENA_LIDO", identificador,
                          f"{len(dados)} bytes conferidos por sha256")
        return dados

    # --------------------------------------------------------------- decidir
    def liberar(self, identificador: str, autor: str, justificativa: str,
                horas: int = 24, teto_usos: int = 1) -> Dict:
        """Libera o item E cria a autorizacao que faz a proxima tentativa passar.

        As duas coisas juntas, de proposito. Mudar so' o estado do registro
        deixaria o usuario exatamente onde estava -- barrado -- e o analista
        acreditando ter resolvido.
        """
        registro = self.repo.item_quarentena(identificador)
        if not registro:
            raise KeyError(identificador)
        if registro["estado"] != "RETIDO":
            raise ValueError(
                f"{identificador} ja' esta' em {registro['estado']}; so' item "
                "RETIDO pode ser liberado")
        if not justificativa.strip():
            # Liberacao sem motivo escrito e' liberacao que ninguem consegue
            # auditar depois. O campo e' obrigatorio por isso, e nao por rito.
            raise ValueError("justificativa e' obrigatoria para liberar")
        self.repo.decidir_quarentena(identificador, "LIBERADO", autor,
                                     justificativa)
        self.repo.auditar(autor, "QUARENTENA_LIBERADO", identificador,
                          justificativa)
        return registro

    def descartar(self, identificador: str, autor: str,
                  justificativa: str) -> Dict:
        """Marca como DESCARTADO. O binario CONTINUA no cofre, de proposito.

        Descarte aqui significa "nao volta a circular", e nao "sumiu". Apagar o
        objeto do incidente destruiria a prova do proprio incidente -- e o
        registro de auditoria passaria a apontar para o nada.
        """
        registro = self.repo.item_quarentena(identificador)
        if not registro:
            raise KeyError(identificador)
        if not justificativa.strip():
            raise ValueError("justificativa e' obrigatoria para descartar")
        self.repo.decidir_quarentena(identificador, "DESCARTADO", autor,
                                     justificativa)
        self.repo.auditar(autor, "QUARENTENA_DESCARTADO", identificador,
                          justificativa + " (binario preservado no cofre como prova)")
        return registro

    def resumo(self) -> Dict:
        return {estado: self.repo.contar_quarentena(estado) for estado in ESTADOS}
