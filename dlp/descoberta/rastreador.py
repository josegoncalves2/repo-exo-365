# -*- coding: utf-8 -*-
"""Rastreador: varre o que esta' PARADO e classifica onde o dado mora.

DLP de saida responde "isto pode sair?". Descoberta responde a pergunta
anterior, que e' a que ninguem consegue responder numa prefeitura: "onde estao
os dados pessoais que eu tenho?". Sem ela, a politica de saida protege um
acervo que ninguem mapeou.

O QUE ESTE MODULO NAO FAZ, e por que:

  * NAO move, NAO renomeia e NAO apaga arquivo do acervo. Remediacao automatica
    sobre documento de trabalho, decidida por regra, apaga o acervo inteiro
    quando a regra esta' mal calibrada -- e o estrago so' aparece dias depois.
    A varredura CLASSIFICA (grava severidade e rotulos por recurso) e abre
    incidente. Retirar de circulacao e' decisao humana, tomada no console com o
    incidente a vista, pela quarentena.
  * NAO le arquivo acima do teto configurado, e diz isso no contador de
    ignorados em vez de fingir que varreu.

INCREMENTAL. Cada recurso tem uma assinatura (ETag no WebDAV, tamanho+mtime no
sistema de arquivos). A segunda passagem custa so' o que mudou. Uma varredura
completa de acervo municipal leva horas; repeti-la inteira toda noite fazia com
que ela fosse desligada na primeira semana.
"""
from __future__ import annotations

import secrets
import threading
import time
import traceback
from typing import Dict, List

from descoberta.origens import ErroDeOrigem, Recurso
from politica.modelo import Contexto

TETO_ARQUIVO_PADRAO = 32 * 1024 * 1024


class Rastreador:
    def __init__(self, repositorio, servico, origens: Dict[str, object],
                 teto_arquivo: int = TETO_ARQUIVO_PADRAO):
        self.repo = repositorio
        self.servico = servico
        self.origens = origens
        self.teto_arquivo = teto_arquivo
        self._em_curso: Dict[str, threading.Thread] = {}
        self._trava = threading.Lock()
        self._cancelar: Dict[str, threading.Event] = {}

    # ------------------------------------------------------------- controle
    def origens_disponiveis(self) -> List[Dict[str, str]]:
        return [{"nome": nome, "tipo": getattr(o, "tipo", "?"),
                 "descricao": o.descricao()} for nome, o in self.origens.items()]

    def iniciar(self, origem: str, alvo: str = "", modo: str = "COMPLETA",
                autor: str = "console", usuario_atribuido: str = "") -> Dict:
        """Abre a varredura e devolve na hora. O trabalho corre em thread."""
        if origem not in self.origens:
            raise KeyError(f"origem desconhecida: {origem}. "
                           f"Disponiveis: {', '.join(self.origens) or '(nenhuma)'}")
        if modo not in ("COMPLETA", "INCREMENTAL"):
            raise ValueError("modo deve ser COMPLETA ou INCREMENTAL")
        with self._trava:
            vivas = [i for i, t in self._em_curso.items() if t.is_alive()]
            if vivas:
                # Uma varredura por vez, de proposito: duas em paralelo
                # disputariam CPU com o caminho de decisao do portal, que e'
                # sincrono com o clique do usuario. Descoberta pode esperar;
                # download nao.
                raise RuntimeError(
                    f"ja' ha' varredura em andamento ({vivas[0]}); "
                    "aguarde o termino ou cancele")
            identificador = "v-" + secrets.token_hex(8)
            self.repo.abrir_varredura({
                "identificador": identificador, "autor": autor,
                "origem": origem, "alvo": alvo or "/", "modo": modo})
            self._cancelar[identificador] = threading.Event()
            tarefa = threading.Thread(
                target=self._executar,
                args=(identificador, origem, alvo, modo, usuario_atribuido),
                name=f"varredura {identificador}", daemon=True)
            self._em_curso[identificador] = tarefa
            tarefa.start()
        self.repo.auditar(autor, "VARREDURA_INICIADA", identificador,
                          f"origem={origem} alvo={alvo or '/'} modo={modo}")
        return {"varredura": identificador, "origem": origem,
                "alvo": alvo or "/", "modo": modo, "estado": "EM_ANDAMENTO"}

    def cancelar(self, identificador: str, autor: str = "console") -> bool:
        evento = self._cancelar.get(identificador)
        if evento is None or evento.is_set():
            return False
        evento.set()
        self.repo.auditar(autor, "VARREDURA_CANCELADA", identificador, "")
        return True

    def em_andamento(self) -> List[str]:
        return [i for i, t in self._em_curso.items() if t.is_alive()]

    # ------------------------------------------------------------- execucao
    def _executar(self, identificador: str, nome_origem: str, alvo: str,
                  modo: str, usuario_atribuido: str) -> None:
        origem = self.origens[nome_origem]
        cancelar = self._cancelar[identificador]
        contadores = {"arquivos": 0, "inspecionados": 0, "ignorados": 0,
                      "com_achado": 0, "erros": 0}
        problemas: List[str] = []
        inicio = time.time()
        try:
            for recurso in origem.listar(alvo):
                if cancelar.is_set():
                    problemas.append("cancelada pelo operador")
                    break
                contadores["arquivos"] += 1
                try:
                    self._inspecionar(recurso, nome_origem, origem, modo,
                                      usuario_atribuido, contadores)
                except ErroDeOrigem as e:
                    contadores["erros"] += 1
                    problemas.append(str(e)[:200])
                except Exception as e:                      # noqa: BLE001
                    contadores["erros"] += 1
                    problemas.append(f"{recurso.caminho}: {e}"[:200])
                    traceback.print_exc()
                if contadores["arquivos"] % 50 == 0:
                    self.repo.atualizar_varredura(identificador, **contadores)
            estado = "CANCELADA" if cancelar.is_set() else "CONCLUIDA"
        except ErroDeOrigem as e:
            estado = "FALHA"
            problemas.append(str(e))
        except Exception as e:                              # noqa: BLE001
            estado = "FALHA"
            problemas.append(str(e))
            traceback.print_exc()

        duracao = int(time.time() - inicio)
        # So' os 20 primeiros problemas ficam no registro: um acervo com
        # permissao errada produz milhares de linhas identicas, e um campo de
        # texto gigante torna a tela de varreduras inutilizavel. A contagem
        # completa continua em `erros`.
        detalhe = (f"{duracao}s. " + ("; ".join(problemas[:20])
                                      if problemas else "sem ocorrencias"))
        if len(problemas) > 20:
            detalhe += f" (+{len(problemas) - 20} outras)"
        from datetime import datetime, timezone
        self.repo.atualizar_varredura(
            identificador, estado=estado,
            terminada_em=datetime.now(timezone.utc).isoformat(timespec="seconds"),
            detalhe=detalhe[:4000], **contadores)
        print(f"[dlp] varredura {identificador} {estado}: "
              f"{contadores['inspecionados']} inspecionado(s), "
              f"{contadores['com_achado']} com achado, "
              f"{contadores['ignorados']} ignorado(s), "
              f"{contadores['erros']} erro(s) em {duracao}s", flush=True)

    def _inspecionar(self, recurso: Recurso, nome_origem: str, origem,
                     modo: str, usuario_atribuido: str,
                     contadores: Dict[str, int]) -> None:
        chave = f"{nome_origem}:{recurso.caminho}"
        if modo == "INCREMENTAL" and self.repo.ja_visto(chave, recurso.assinatura):
            contadores["ignorados"] += 1
            return
        if recurso.tamanho > self.teto_arquivo:
            contadores["ignorados"] += 1
            # Registra a classificacao como NAO VARRIDO. "Grande demais" nao
            # pode virar "limpo" -- e' a mesma regra de ouro da extracao.
            self.repo.classificar(chave, "MEDIA", "NAO_CLASSIFICADO", [], False,
                                  f"arquivo de {recurso.tamanho} bytes acima do "
                                  f"teto de {self.teto_arquivo}")
            return

        dados = origem.ler(recurso)
        contexto = Contexto(canal="DESCOBERTA",
                            usuario=usuario_atribuido or "varredura",
                            destino=nome_origem, nome_arquivo=recurso.nome)
        # UMA passagem so'. A primeira versao chamava `analisar` duas vezes --
        # uma para classificar, outra para registrar o incidente -- e isso
        # significava extrair, OCRar e casar EDM/IDM DUAS vezes por arquivo.
        # Num acervo de dezenas de milhares de itens e' o dobro do tempo de
        # varredura por nada: o proprio `analisar` ja' classifica quando recebe
        # `recurso` e so' abre incidente quando ha' o que registrar.
        resultado = self.servico.analisar(dados, contexto, recurso=chave,
                                          registrar=True)
        contadores["inspecionados"] += 1
        if resultado.get("evidencia"):
            contadores["com_achado"] += 1
        self.repo.marcar_visto(chave, nome_origem, recurso.assinatura,
                               resultado["severidade"], resultado["classificacao"])


class Agendador(threading.Thread):
    """Dispara a varredura incremental de tempos em tempos.

    Intervalo, e nao expressao de cron: a unica pergunta que importa aqui e'
    "de quanto em quanto tempo", e um analisador de cron seria mais codigo para
    manter do que o problema pede. Zero desliga o agendamento -- e a varredura
    continua disponivel sob demanda pelo console.
    """

    def __init__(self, rastreador: Rastreador, origem: str, alvo: str,
                 intervalo_segundos: int, espera_inicial: int = 300):
        super().__init__(name="Agendador de varredura", daemon=True)
        self.rastreador = rastreador
        self.origem = origem
        self.alvo = alvo
        self.intervalo = intervalo_segundos
        self.espera_inicial = espera_inicial
        self._parar = threading.Event()

    def parar(self) -> None:
        self._parar.set()

    def run(self) -> None:                                  # noqa: D102
        # A espera inicial existe para nao competir com o arranque do portal:
        # subir a stack inteira e comecar a ler o acervo no mesmo minuto e' a
        # receita para um arranque lento que parece defeito.
        if self._parar.wait(self.espera_inicial):
            return
        while not self._parar.is_set():
            try:
                self.rastreador.iniciar(self.origem, self.alvo, "INCREMENTAL",
                                        autor="agendador")
            except RuntimeError as e:
                print(f"[dlp] agendador: {e}", flush=True)
            except Exception as e:                          # noqa: BLE001
                print(f"[dlp] agendador: falha ao iniciar varredura: {e}",
                      flush=True)
            self._parar.wait(self.intervalo)
