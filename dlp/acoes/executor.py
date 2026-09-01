# -*- coding: utf-8 -*-
"""Executor: transforma a lista de acoes da regra em efeito real.

E' AQUI QUE A ENCENACAO ACABA. A politica sempre soube dizer "MASCARAR,
NOTIFICAR_ADMIN, ORIENTAR"; o que nao existia era alguem que fizesse. O motor
decidia, o incidente registrava a lista, e nada acontecia. Este modulo e' o
unico lugar do servico que PRODUZ efeito -- e por isso ele e' tambem o unico
lugar onde uma acao pode ser declarada NAO APLICAVEL, em voz alta.

ORDEM DE APLICACAO, e a razao de cada passo:

  1. IMPEDIMENTO primeiro (BLOQUEAR / QUARENTENAR / REVISAO_MANUAL). Se o dado
     nao sai, nao ha' o que mascarar nem cifrar. QUARENTENAR retem a copia no
     cofre ANTES de negar -- negar sem reter perderia o objeto do incidente.
  2. MASCARAR, se ainda vai sair. Mascara antes de cifrar: o envelope tem que
     guardar a versao ja' redigida, nunca a original.
  3. CRIPTOGRAFAR, por ultimo, sobre o que sobrou.
  4. AVISOS (usuario, administrador, orientacao) sobre o resultado FINAL, para
     que o texto diga o que de fato aconteceu, e nao o que a regra pedia.

APLICABILIDADE. MASCARAR so' faz sentido sobre texto: nao ha' como redigir um
CPF dentro de um PDF ou de um .docx e devolver o arquivo integro -- e devolver
"o texto extraido" no lugar do documento entregaria um arquivo corrompido ao
usuario. Quando a acao nao se aplica, ela NAO e' dada por cumprida: o executor
degrada para a acao configurada em `acao_nao_aplicavel` (padrao BLOQUEAR) e
grava o motivo no incidente. Falhar fechado e dizer por que e' a unica saida
honesta; deixar passar em claro seria mentir duas vezes -- na tela e no
relatorio de conformidade.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

from acoes import cripto, notificacao as correio
from acoes.liberacao import Liberacoes
from acoes.quarentena import Quarentena
from motor.mascara import redigir

# Formatos em que redigir o texto e devolver o arquivo continua produzindo um
# arquivo valido para quem recebe. Fora desta lista, mascarar corromperia.
FORMATOS_TEXTUAIS = {"txt", "csv", "html", "htm", "xml", "json", "md", "log",
                     "sql", "yaml", "yml", "ini", "conf", "vazio", ""}

MIME_POR_FORMATO = {"csv": "text/csv; charset=utf-8",
                    "html": "text/html; charset=utf-8",
                    "htm": "text/html; charset=utf-8",
                    "xml": "application/xml; charset=utf-8",
                    "json": "application/json; charset=utf-8"}


@dataclass
class Configuracao:
    """Como o executor se comporta quando a regra pede o impossivel."""
    acao_nao_aplicavel: str = "BLOQUEAR"      # BLOQUEAR | REVISAO_MANUAL | PERMITIR
    url_console: str = ""
    dominio_email: str = ""
    orientacao_padrao: str = (
        "Dados pessoais so' podem circular pelo caminho que a prefeitura "
        "controla. O portal registra quem acessou o que; copia por anexo, "
        "link publico ou nuvem pessoal nao registra nada.")


@dataclass
class Resultado:
    permitido: bool
    acoes_executadas: List[str] = field(default_factory=list)
    acoes_nao_aplicaveis: List[Dict[str, str]] = field(default_factory=list)
    conteudo: Optional[bytes] = None          # so' quando houve transformacao
    # CRIPTOGRAFAR que o CANAL vai cumprir, e nao o executor. E' o caso do
    # e-mail: S/MIME para o certificado do destinatario protege melhor que um
    # ZIP com senha, e cifrar duas vezes so' atrapalharia quem recebe. Quando
    # isto e' verdade, o canal E' OBRIGADO a cumprir -- se nao conseguir,
    # degrada como qualquer outra acao nao aplicavel.
    cifra_pendente: bool = False
    mime: str = ""
    nome_arquivo: str = ""
    texto_mascarado: str = ""
    quarentena: str = ""
    notificacoes: List[int] = field(default_factory=list)
    motivo: str = ""
    orientacao: str = ""

    def como_dicionario(self) -> Dict:
        return {"acoes_executadas": self.acoes_executadas,
                "acoes_nao_aplicaveis": self.acoes_nao_aplicaveis,
                "quarentena": self.quarentena,
                "notificacoes_enfileiradas": len(self.notificacoes),
                "transformacao": self.mime or "",
                "motivo_execucao": self.motivo,
                "orientacao": self.orientacao}


class Executor:
    def __init__(self, quarentena: Quarentena, liberacoes: Liberacoes,
                 notificador: correio.Notificador,
                 certificados: cripto.RepositorioCertificados,
                 configuracao: Optional[Configuracao] = None):
        self.quarentena = quarentena
        self.liberacoes = liberacoes
        self.notificador = notificador
        self.certificados = certificados
        self.conf = configuracao or Configuracao()

    # ------------------------------------------------------------------ util
    @staticmethod
    def _e_textual(formato: str, dados: Optional[bytes]) -> bool:
        return dados is None or (formato or "").lower() in FORMATOS_TEXTUAIS

    def _degradar(self, resultado: Resultado, acao: str, motivo: str) -> None:
        """Acao impossivel nao vira acao cumprida. Registra e fecha a porta."""
        resultado.acoes_nao_aplicaveis.append({"acao": acao, "motivo": motivo})
        destino = self.conf.acao_nao_aplicavel.upper()
        if destino == "PERMITIR":
            resultado.motivo = (f"{resultado.motivo} | {acao} nao aplicavel "
                                f"({motivo}); politica manda seguir mesmo assim").strip(" |")
            return
        resultado.permitido = False
        resultado.acoes_executadas.append(destino)
        resultado.motivo = (f"{resultado.motivo} | {acao} nao aplicavel "
                            f"({motivo}); degradado para {destino}").strip(" |")

    # ------------------------------------------------------------- principal
    def simular(self, acoes: Sequence[str], permitido: bool,
                dados: Optional[bytes], texto: str, ocorrencias: Sequence,
                incidente: Dict, contexto: Dict,
                cifra_delegada: bool = False) -> Resultado:
        """Mesma decisao, ZERO efeito colateral.

        Responde "o que aconteceria" sem reter nada no cofre e sem disparar
        e-mail para ninguem. As transformacoes (mascara e cifra) continuam
        sendo CALCULADAS, porque sao funcoes puras e sao justamente o que
        alguem quer conferir antes de ligar a regra em producao.
        """
        return self.aplicar(acoes, permitido, dados, texto, ocorrencias,
                            incidente, contexto, efeitos=False,
                            cifra_delegada=cifra_delegada)

    def aplicar(self, acoes: Sequence[str], permitido: bool,
                dados: Optional[bytes], texto: str, ocorrencias: Sequence,
                incidente: Dict, contexto: Dict,
                efeitos: bool = True,
                cifra_delegada: bool = False) -> Resultado:
        r = Resultado(permitido=permitido)
        acoes = [a.upper() for a in acoes]

        # ---------------------------------------------------- 1. impedimento
        if "QUARENTENAR" in acoes:
            r.permitido = False
            r.acoes_executadas.append("QUARENTENAR")
            conteudo = dados if dados is not None else texto.encode("utf-8")
            if efeitos:
                item = self.quarentena.reter(conteudo, {
                    "incidente": incidente.get("identificador", ""),
                    "usuario": contexto.get("usuario", ""),
                    "canal": contexto.get("canal", ""),
                    "recurso": contexto.get("recurso", ""),
                    "nome_arquivo": contexto.get("nome_arquivo", ""),
                    "mime": incidente.get("mime", ""),
                    "regra": incidente.get("regra", ""),
                    "regra_nome": incidente.get("regra_nome", ""),
                    "severidade": incidente.get("severidade", ""),
                    "motivo": incidente.get("motivo", "")})
                r.quarentena = item.identificador
                r.motivo = (f"conteudo retido em quarentena {item.identificador} "
                            f"({item.tamanho} bytes, sha256 {item.sha256[:16]}...)")
            else:
                r.motivo = (f"simulacao: reteria {len(conteudo)} byte(s) em "
                            "quarentena")

        if "BLOQUEAR" in acoes:
            r.permitido = False
            r.acoes_executadas.append("BLOQUEAR")

        if "REVISAO_MANUAL" in acoes:
            r.permitido = False
            r.acoes_executadas.append("REVISAO_MANUAL")

        # ------------------------------------------------------ 2. mascarar
        if "MASCARAR" in acoes and r.permitido:
            formato = incidente.get("tipo_arquivo", "")
            if not texto:
                self._degradar(r, "MASCARAR",
                               "nenhum texto foi extraido do conteudo")
            elif not self._e_textual(formato, dados):
                self._degradar(r, "MASCARAR",
                               f"formato '{formato}' nao aceita redacao sem "
                               "corromper o arquivo entregue")
            else:
                redigido = redigir(texto, ocorrencias)
                r.texto_mascarado = redigido
                r.conteudo = redigido.encode("utf-8")
                r.mime = MIME_POR_FORMATO.get((formato or "").lower(),
                                              "text/plain; charset=utf-8")
                r.nome_arquivo = contexto.get("nome_arquivo", "")
                r.acoes_executadas.append("MASCARAR")

        # --------------------------------------------------- 3. criptografar
        if "CRIPTOGRAFAR" in acoes and r.permitido and cifra_delegada:
            # O CANAL cifra. So' chega aqui quando o canal ja' CONFERIU que
            # consegue (no e-mail: existe certificado S/MIME para todos os
            # destinatarios externos) -- por isso a acao conta como executada.
            # Se o envelope falhar depois, o canal NAO entrega e anota o
            # incidente; nada sai em claro por causa desta linha.
            r.cifra_pendente = True
            r.acoes_executadas.append("CRIPTOGRAFAR")
            r.motivo = (f"{r.motivo} | cifra a cargo do canal "
                        f"{contexto.get('canal', '')}").strip(" |")
        elif "CRIPTOGRAFAR" in acoes and r.permitido:
            base = r.conteudo if r.conteudo is not None else dados
            if base is None:
                base = texto.encode("utf-8") if texto else None
            if base is None:
                self._degradar(r, "CRIPTOGRAFAR", "nao ha' conteudo a cifrar")
            elif not efeitos:
                # Em observacao o envelope NAO e' produzido: cifrar ate' 32 MiB
                # por download so' para descartar o resultado custa CPU real
                # num portal movimentado, e a resposta ja' nao carrega o
                # conteudo transformado.
                r.acoes_executadas.append("CRIPTOGRAFAR")
                r.motivo = (f"{r.motivo} | simulacao: cifraria "
                            f"{len(base)} byte(s)").strip(" |")
            else:
                nome = (r.nome_arquivo or contexto.get("nome_arquivo", "")
                        or "conteudo.bin")
                senha = cripto.gerar_senha()
                r.conteudo = cripto.zip_aes256(nome, base, senha)
                r.mime = "application/zip"
                r.nome_arquivo = nome + ".zip"
                r.acoes_executadas.append("CRIPTOGRAFAR")
                # A senha vai ao usuario por um canal SEPARADO (o aviso por
                # e-mail). Ela NUNCA entra na resposta ao portal, no incidente
                # nem no log: senha viajando junto do arquivo cifrado e' o
                # mesmo que nao cifrar.
                self._enviar_senha(senha, nome, incidente, contexto, r)

        # ------------------------------------------------------- 4. avisos
        acao_tomada = self._descrever(r)
        if not efeitos:
            for nome in ("NOTIFICAR_USUARIO", "NOTIFICAR_ADMIN", "ORIENTAR",
                         "REGISTRAR"):
                if nome in acoes:
                    r.acoes_executadas.append(nome)
            if "ORIENTAR" in acoes:
                r.orientacao = (incidente.get("orientacao")
                                or self.conf.orientacao_padrao)
            if "PERMITIR" in acoes and r.permitido:
                r.acoes_executadas.append("PERMITIR")
            return r

        if "NOTIFICAR_USUARIO" in acoes:
            destino = correio.endereco_de(contexto.get("usuario", ""),
                                          contexto.get("email", ""),
                                          self.conf.dominio_email)
            identificador = self.notificador.enfileirar(
                "USUARIO", destino,
                f"[DLP] {acao_tomada}: {incidente.get('nome_arquivo') or 'transferencia'}",
                correio.texto_usuario(incidente,
                                      incidente.get("mensagem_usuario", ""),
                                      self.conf.url_console, acao_tomada),
                incidente.get("identificador", ""))
            if identificador:
                r.notificacoes.append(identificador)
            r.acoes_executadas.append("NOTIFICAR_USUARIO")

        if "NOTIFICAR_ADMIN" in acoes:
            r.notificacoes += [i for i in self.notificador.avisar_administradores(
                f"[DLP] {incidente.get('severidade', '')} — "
                f"{incidente.get('regra_nome') or 'sem regra'} "
                f"({incidente.get('usuario') or 'usuario nao identificado'})",
                correio.texto_administrador(incidente, self.conf.url_console,
                                            acao_tomada),
                incidente.get("identificador", "")) if i]
            r.acoes_executadas.append("NOTIFICAR_ADMIN")

        if "ORIENTAR" in acoes:
            orientacao = (incidente.get("orientacao")
                          or self.conf.orientacao_padrao)
            r.orientacao = orientacao
            destino = correio.endereco_de(contexto.get("usuario", ""),
                                          contexto.get("email", ""),
                                          self.conf.dominio_email)
            identificador = self.notificador.enfileirar(
                "ORIENTACAO", destino,
                "[DLP] Como compartilhar este tipo de documento com seguranca",
                correio.texto_orientacao(incidente, orientacao,
                                         self.conf.url_console),
                incidente.get("identificador", ""))
            if identificador:
                r.notificacoes.append(identificador)
            r.acoes_executadas.append("ORIENTAR")

        if "REGISTRAR" in acoes:
            # REGISTRAR e' implicito -- todo incidente ja' e' gravado. Aparece
            # em `acoes_executadas` para que o console nao mostre uma acao da
            # regra sem correspondencia no que foi feito.
            r.acoes_executadas.append("REGISTRAR")
        if "PERMITIR" in acoes and r.permitido:
            r.acoes_executadas.append("PERMITIR")
        return r

    # ------------------------------------------------------------- auxiliares
    def _enviar_senha(self, senha: str, nome: str, incidente: Dict,
                      contexto: Dict, resultado: Resultado) -> None:
        destino = correio.endereco_de(contexto.get("usuario", ""),
                                      contexto.get("email", ""),
                                      self.conf.dominio_email)
        corpo = (
            f"O arquivo '{nome}' foi entregue cifrado porque continha dado que "
            f"a politica de protecao classifica como sensivel.\n"
            f"\n"
            f"Para abrir: use 7-Zip, WinRAR, Keka ou o descompactador do seu "
            f"sistema e informe a senha abaixo quando ele pedir.\n"
            f"\n"
            f"    {senha}\n"
            f"\n"
            f"A senha vale para este arquivo apenas. Nao a repasse junto com o "
            f"arquivo: e' justamente a separacao entre os dois que protege o "
            f"conteudo.\n"
            f"\n"
            f"Incidente: {incidente.get('identificador', '')}\n")
        identificador = self.notificador.enfileirar(
            "USUARIO", destino, f"[DLP] Senha do arquivo cifrado: {nome}",
            corpo, incidente.get("identificador", ""))
        if identificador:
            resultado.notificacoes.append(identificador)

    def confirmar_cifra_do_canal(self, r: Resultado, conseguiu: bool,
                                 detalhe: str) -> Resultado:
        """Fecha a delegacao: o canal diz se cifrou, e o resultado nao mente.

        Sem este passo, `cifra_pendente` seria mais uma acao declarada e nao
        cumprida -- exatamente o defeito que este pacote existe para acabar.
        """
        if not r.cifra_pendente:
            return r
        r.cifra_pendente = False
        if conseguiu:
            r.acoes_executadas.append("CRIPTOGRAFAR")
            r.motivo = f"{r.motivo} | cifrado pelo canal: {detalhe}".strip(" |")
        else:
            self._degradar(r, "CRIPTOGRAFAR", detalhe)
        return r

    @staticmethod
    def _descrever(r: Resultado) -> str:
        if r.quarentena:
            return "retido em quarentena"
        if not r.permitido:
            return "transferencia bloqueada"
        if "CRIPTOGRAFAR" in r.acoes_executadas:
            return "entregue cifrado"
        if "MASCARAR" in r.acoes_executadas:
            return "entregue com os dados mascarados"
        return "transferencia permitida e registrada"
