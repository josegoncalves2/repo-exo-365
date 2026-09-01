# -*- coding: utf-8 -*-
"""Persistencia de incidente, indice EDM/IDM, politica e classificacao.

SQLite com WAL. Escolha deliberada: o volume de incidente de um portal
municipal cabe folgado, o arquivo e' um so' (backup trivial), e nao acrescenta
mais um servico a uma stack que ja' tem MySQL, Postgres e Elasticsearch. A
camada e' isolada -- trocar por Postgres e' reescrever este arquivo, so'.
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
from typing import Dict, List, Optional, Sequence

from incidentes.modelo import Incidente

ESQUEMA = """
CREATE TABLE IF NOT EXISTS incidente (
  identificador TEXT PRIMARY KEY,
  momento TEXT NOT NULL,
  canal TEXT, usuario TEXT, ip TEXT, destino TEXT,
  recurso TEXT, nome_arquivo TEXT, tipo_arquivo TEXT,
  severidade TEXT, classificacao TEXT,
  regra TEXT, regra_nome TEXT, permitido INTEGER,
  estado TEXT, responsavel TEXT, origem TEXT,
  documento TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_inc_momento    ON incidente(momento DESC);
CREATE INDEX IF NOT EXISTS ix_inc_estado     ON incidente(estado);
CREATE INDEX IF NOT EXISTS ix_inc_usuario    ON incidente(usuario);
CREATE INDEX IF NOT EXISTS ix_inc_canal      ON incidente(canal);
CREATE INDEX IF NOT EXISTS ix_inc_severidade ON incidente(severidade);

CREATE TABLE IF NOT EXISTS politica (
  identificador TEXT PRIMARY KEY, documento TEXT NOT NULL,
  atualizado_em TEXT NOT NULL, atualizado_por TEXT
);
-- indice_edm, politica e modelo_estatistico usam a MESMA forma
-- (chave + documento JSON + atualizado_em) porque sao lidos e gravados pelo
-- par generico guardar_json/ler_json. Colunas especificas aqui obrigariam um
-- gravador proprio para cada tabela, e foi exatamente o que quebrou o
-- indexador de EDM: "table indice_edm has no column named documento".
CREATE TABLE IF NOT EXISTS indice_edm (
  nome TEXT PRIMARY KEY, documento TEXT NOT NULL, atualizado_em TEXT
);
CREATE TABLE IF NOT EXISTS indice_idm (
  nome TEXT NOT NULL, documento TEXT NOT NULL, janelas TEXT NOT NULL,
  atualizado_em TEXT, PRIMARY KEY (nome, documento)
);
CREATE TABLE IF NOT EXISTS classificacao (
  recurso TEXT PRIMARY KEY, severidade TEXT, classificacao TEXT,
  rotulos TEXT, momento TEXT, extracao_completa INTEGER, motivo_parcial TEXT
);
CREATE TABLE IF NOT EXISTS agente (
  identificador TEXT PRIMARY KEY, nome TEXT, sistema TEXT, versao TEXT,
  usuario TEXT, ip TEXT, visto_em TEXT, politica_versao TEXT, estado TEXT
);
CREATE TABLE IF NOT EXISTS modelo_estatistico (
  nome TEXT PRIMARY KEY, documento TEXT NOT NULL, atualizado_em TEXT
);
CREATE TABLE IF NOT EXISTS auditoria (
  id INTEGER PRIMARY KEY AUTOINCREMENT, momento TEXT, autor TEXT,
  acao TEXT, alvo TEXT, detalhe TEXT
);

-- A partir daqui: tabelas das ACOES QUE EXECUTAM. Antes de existirem, a
-- politica declarava dez acoes e o codigo honrava duas; QUARENTENAR era
-- BLOQUEAR com outro nome e NOTIFICAR nao notificava ninguem. Cada tabela
-- abaixo e' o registro durável de uma acao que agora tem efeito.

-- Conteudo RETIDO. O binario NAO fica aqui: fica no cofre, cifrado. Esta
-- tabela guarda o ponteiro, o hash do claro (para o analista conferir a
-- restauracao) e a decisao humana.
CREATE TABLE IF NOT EXISTS quarentena (
  identificador TEXT PRIMARY KEY, momento TEXT NOT NULL, incidente TEXT,
  usuario TEXT, canal TEXT, recurso TEXT, nome_arquivo TEXT, mime TEXT,
  tamanho INTEGER, sha256 TEXT, item_cofre TEXT, estado TEXT,
  regra TEXT, regra_nome TEXT, severidade TEXT, motivo TEXT,
  decidido_por TEXT, decidido_em TEXT, justificativa TEXT
);
CREATE INDEX IF NOT EXISTS ix_quar_estado  ON quarentena(estado);
CREATE INDEX IF NOT EXISTS ix_quar_momento ON quarentena(momento DESC);

-- LIBERACAO: e' o que faz REVISAO_MANUAL terminar em algum lugar. Sem ela,
-- "encaminhado para revisao" era um bloqueio permanente com nome simpatico:
-- ninguem revisava e nada voltava a passar.
CREATE TABLE IF NOT EXISTS liberacao (
  identificador TEXT PRIMARY KEY, momento TEXT NOT NULL, autor TEXT,
  incidente TEXT, usuario TEXT, recurso TEXT, canal TEXT,
  expira_em TEXT, teto_usos INTEGER, usos INTEGER DEFAULT 0,
  estado TEXT, justificativa TEXT
);
CREATE INDEX IF NOT EXISTS ix_lib_alvo ON liberacao(usuario, recurso, estado);

-- Fila de notificacao PERSISTENTE. Em memoria, um reinicio engoliria o aviso
-- justamente do incidente que motivou o reinicio.
CREATE TABLE IF NOT EXISTS notificacao (
  id INTEGER PRIMARY KEY AUTOINCREMENT, momento TEXT NOT NULL, tipo TEXT,
  destinatario TEXT, incidente TEXT, assunto TEXT, corpo TEXT,
  estado TEXT, tentativas INTEGER DEFAULT 0, ultimo_erro TEXT,
  enviada_em TEXT, proxima_em TEXT
);
CREATE INDEX IF NOT EXISTS ix_not_estado ON notificacao(estado, proxima_em);

-- Dicionarios customizados. Antes so' entravam por um arquivo largado no
-- volume, o que exigia acesso ao servidor para uma tarefa de administracao.
CREATE TABLE IF NOT EXISTS dicionario (
  nome TEXT PRIMARY KEY, documento TEXT NOT NULL, atualizado_em TEXT,
  atualizado_por TEXT
);

-- Varreduras de dados em REPOUSO (canal DESCOBERTA).
CREATE TABLE IF NOT EXISTS varredura (
  identificador TEXT PRIMARY KEY, momento TEXT NOT NULL, autor TEXT,
  origem TEXT, alvo TEXT, modo TEXT, estado TEXT,
  iniciada_em TEXT, terminada_em TEXT,
  arquivos INTEGER DEFAULT 0, inspecionados INTEGER DEFAULT 0,
  ignorados INTEGER DEFAULT 0, com_achado INTEGER DEFAULT 0,
  erros INTEGER DEFAULT 0, detalhe TEXT
);
CREATE INDEX IF NOT EXISTS ix_varredura_momento ON varredura(momento DESC);

-- Memoria da varredura INCREMENTAL: assinatura do que ja' foi lido, para a
-- segunda passagem custar so' o que mudou.
CREATE TABLE IF NOT EXISTS descoberta_visto (
  chave TEXT PRIMARY KEY, alvo TEXT, assinatura TEXT, visto_em TEXT,
  severidade TEXT, classificacao TEXT
);
"""

# Colunas acrescentadas depois da primeira versao do banco. SQLite nao tem
# "ADD COLUMN IF NOT EXISTS", entao a migracao confere o que ja' existe. Sem
# isto, uma instalacao em producao (que ja' tem incidentes gravados) quebraria
# no arranque -- e nenhum dado pode ser perdido para acrescentar uma coluna.
MIGRACOES = (
    ("indice_edm", "ativo", "INTEGER DEFAULT 1"),
    ("indice_idm", "ativo", "INTEGER DEFAULT 1"),
)


class Repositorio:
    def __init__(self, caminho: str):
        os.makedirs(os.path.dirname(caminho) or ".", exist_ok=True)
        self._caminho = caminho
        self._local = threading.local()
        with self._conexao() as c:
            c.executescript(ESQUEMA)
            self._migrar(c)

    @staticmethod
    def _migrar(c: sqlite3.Connection) -> None:
        for tabela, coluna, tipo in MIGRACOES:
            existentes = {linha["name"] for linha in
                          c.execute(f"PRAGMA table_info({tabela})")}
            if not existentes:
                continue                    # tabela ainda nao criada
            if coluna not in existentes:
                c.execute(f"ALTER TABLE {tabela} ADD COLUMN {coluna} {tipo}")

    def _conexao(self) -> sqlite3.Connection:
        c = getattr(self._local, "conexao", None)
        if c is None:
            c = sqlite3.connect(self._caminho, check_same_thread=False, timeout=30)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA journal_mode=WAL")
            c.execute("PRAGMA synchronous=NORMAL")
            c.execute("PRAGMA foreign_keys=ON")
            self._local.conexao = c
        return c

    # ------------------------------------------------------------ incidentes
    def salvar(self, inc: Incidente) -> None:
        c = self._conexao()
        with c:
            c.execute(
                "INSERT OR REPLACE INTO incidente (identificador,momento,canal,"
                "usuario,ip,destino,recurso,nome_arquivo,tipo_arquivo,severidade,"
                "classificacao,regra,regra_nome,permitido,estado,responsavel,"
                "origem,documento) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (inc.identificador, inc.momento, inc.canal, inc.usuario, inc.ip,
                 inc.destino, inc.recurso, inc.nome_arquivo, inc.tipo_arquivo,
                 inc.severidade, inc.classificacao, inc.regra, inc.regra_nome,
                 1 if inc.permitido else 0, inc.estado, inc.responsavel,
                 inc.origem, inc.como_json()))

    def obter(self, identificador: str) -> Optional[Incidente]:
        r = self._conexao().execute(
            "SELECT documento FROM incidente WHERE identificador=?",
            (identificador,)).fetchone()
        return Incidente.de_dicionario(json.loads(r["documento"])) if r else None

    def listar(self, filtros: Optional[Dict] = None, limite: int = 50,
               deslocamento: int = 0) -> List[Incidente]:
        f = filtros or {}
        onde, args = [], []
        for coluna in ("estado", "canal", "usuario", "severidade", "origem",
                       "regra", "responsavel"):
            if f.get(coluna):
                onde.append(f"{coluna}=?")
                args.append(f[coluna])
        if f.get("permitido") is not None:
            onde.append("permitido=?")
            args.append(1 if f["permitido"] else 0)
        if f.get("desde"):
            onde.append("momento>=?")
            args.append(f["desde"])
        if f.get("ate"):
            onde.append("momento<=?")
            args.append(f["ate"])
        if f.get("busca"):
            onde.append("(nome_arquivo LIKE ? OR recurso LIKE ? OR destino LIKE ?)")
            alvo = f"%{f['busca']}%"
            args += [alvo, alvo, alvo]
        sql = "SELECT documento FROM incidente"
        if onde:
            sql += " WHERE " + " AND ".join(onde)
        sql += " ORDER BY momento DESC LIMIT ? OFFSET ?"
        args += [limite, deslocamento]
        return [Incidente.de_dicionario(json.loads(r["documento"]))
                for r in self._conexao().execute(sql, args)]

    def contar(self, filtros: Optional[Dict] = None) -> int:
        f = filtros or {}
        onde, args = [], []
        for coluna in ("estado", "canal", "usuario", "severidade", "origem"):
            if f.get(coluna):
                onde.append(f"{coluna}=?")
                args.append(f[coluna])
        sql = "SELECT COUNT(*) n FROM incidente"
        if onde:
            sql += " WHERE " + " AND ".join(onde)
        return self._conexao().execute(sql, args).fetchone()["n"]

    def agregar(self, campo: str, desde: Optional[str] = None) -> List[Dict]:
        permitidos = {"canal", "usuario", "severidade", "regra_nome", "estado",
                      "origem", "classificacao", "tipo_arquivo"}
        if campo not in permitidos:
            raise ValueError(f"campo nao agregavel: {campo}")
        sql = f"SELECT {campo} chave, COUNT(*) n FROM incidente"
        args: list = []
        if desde:
            sql += " WHERE momento>=?"
            args.append(desde)
        sql += f" GROUP BY {campo} ORDER BY n DESC"
        return [{"chave": r["chave"] or "(vazio)", "total": r["n"]}
                for r in self._conexao().execute(sql, args)]

    # ------------------------------------------------------------- auxiliares
    def guardar_json(self, tabela: str, chave_coluna: str, chave: str,
                     documento: dict, extra: Optional[Dict] = None) -> None:
        colunas = [chave_coluna, "documento", "atualizado_em"]
        valores = [chave, json.dumps(documento, ensure_ascii=False),
                   _agora_iso()]
        for k, v in (extra or {}).items():
            colunas.append(k)
            valores.append(v)
        marcas = ",".join("?" * len(valores))
        c = self._conexao()
        with c:
            c.execute(f"INSERT OR REPLACE INTO {tabela} ({','.join(colunas)}) "
                      f"VALUES ({marcas})", valores)

    def ler_json(self, tabela: str, chave_coluna: str, chave: str) -> Optional[dict]:
        r = self._conexao().execute(
            f"SELECT documento FROM {tabela} WHERE {chave_coluna}=?",
            (chave,)).fetchone()
        return json.loads(r["documento"]) if r else None

    def classificar(self, recurso: str, severidade: str, classificacao: str,
                    rotulos: Sequence[str], completa: bool, motivo: str) -> None:
        c = self._conexao()
        with c:
            c.execute("INSERT OR REPLACE INTO classificacao (recurso,severidade,"
                      "classificacao,rotulos,momento,extracao_completa,"
                      "motivo_parcial) VALUES (?,?,?,?,?,?,?)",
                      (recurso, severidade, classificacao,
                       json.dumps(list(rotulos)), _agora_iso(),
                       1 if completa else 0, motivo))

    def classificacao_de(self, recurso: str) -> Optional[Dict]:
        r = self._conexao().execute(
            "SELECT * FROM classificacao WHERE recurso=?", (recurso,)).fetchone()
        if not r:
            return None
        d = dict(r)
        d["rotulos"] = json.loads(d["rotulos"] or "[]")
        d["extracao_completa"] = bool(d["extracao_completa"])
        return d

    def registrar_agente(self, dados: Dict) -> None:
        c = self._conexao()
        with c:
            c.execute("INSERT OR REPLACE INTO agente (identificador,nome,sistema,"
                      "versao,usuario,ip,visto_em,politica_versao,estado) "
                      "VALUES (?,?,?,?,?,?,?,?,?)",
                      (dados["identificador"], dados.get("nome", ""),
                       dados.get("sistema", ""), dados.get("versao", ""),
                       dados.get("usuario", ""), dados.get("ip", ""),
                       _agora_iso(), dados.get("politica_versao", ""),
                       dados.get("estado", "ATIVO")))

    def agentes(self) -> List[Dict]:
        return [dict(r) for r in self._conexao().execute(
            "SELECT * FROM agente ORDER BY visto_em DESC")]

    def auditar(self, autor: str, acao: str, alvo: str, detalhe: str = "") -> None:
        c = self._conexao()
        with c:
            c.execute("INSERT INTO auditoria (momento,autor,acao,alvo,detalhe) "
                      "VALUES (?,?,?,?,?)", (_agora_iso(), autor, acao, alvo, detalhe))

    def auditoria(self, limite: int = 100) -> List[Dict]:
        return [dict(r) for r in self._conexao().execute(
            "SELECT * FROM auditoria ORDER BY id DESC LIMIT ?", (limite,))]

    # ------------------------------------------------------------- quarentena
    def reter(self, dados: Dict) -> None:
        c = self._conexao()
        with c:
            c.execute(
                "INSERT INTO quarentena (identificador,momento,incidente,usuario,"
                "canal,recurso,nome_arquivo,mime,tamanho,sha256,item_cofre,estado,"
                "regra,regra_nome,severidade,motivo) "
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (dados["identificador"], _agora_iso(), dados.get("incidente", ""),
                 dados.get("usuario", ""), dados.get("canal", ""),
                 dados.get("recurso", ""), dados.get("nome_arquivo", ""),
                 dados.get("mime", ""), int(dados.get("tamanho", 0)),
                 dados.get("sha256", ""), dados.get("item_cofre", ""),
                 dados.get("estado", "RETIDO"), dados.get("regra", ""),
                 dados.get("regra_nome", ""), dados.get("severidade", ""),
                 dados.get("motivo", "")))

    def quarentena(self, filtros: Optional[Dict] = None, limite: int = 50,
                   deslocamento: int = 0) -> List[Dict]:
        f = filtros or {}
        onde, args = [], []
        for coluna in ("estado", "usuario", "canal", "incidente", "severidade"):
            if f.get(coluna):
                onde.append(f"{coluna}=?")
                args.append(f[coluna])
        if f.get("busca"):
            onde.append("(nome_arquivo LIKE ? OR recurso LIKE ?)")
            args += [f"%{f['busca']}%"] * 2
        sql = "SELECT * FROM quarentena"
        if onde:
            sql += " WHERE " + " AND ".join(onde)
        sql += " ORDER BY momento DESC LIMIT ? OFFSET ?"
        args += [limite, deslocamento]
        return [dict(r) for r in self._conexao().execute(sql, args)]

    def item_quarentena(self, identificador: str) -> Optional[Dict]:
        r = self._conexao().execute(
            "SELECT * FROM quarentena WHERE identificador=?",
            (identificador,)).fetchone()
        return dict(r) if r else None

    def decidir_quarentena(self, identificador: str, estado: str, autor: str,
                           justificativa: str) -> bool:
        c = self._conexao()
        with c:
            cur = c.execute(
                "UPDATE quarentena SET estado=?, decidido_por=?, decidido_em=?, "
                "justificativa=? WHERE identificador=?",
                (estado, autor, _agora_iso(), justificativa, identificador))
        return cur.rowcount > 0

    def contar_quarentena(self, estado: Optional[str] = None) -> int:
        if estado:
            return self._conexao().execute(
                "SELECT COUNT(*) n FROM quarentena WHERE estado=?",
                (estado,)).fetchone()["n"]
        return self._conexao().execute(
            "SELECT COUNT(*) n FROM quarentena").fetchone()["n"]

    # -------------------------------------------------------------- liberacao
    def criar_liberacao(self, dados: Dict) -> None:
        c = self._conexao()
        with c:
            c.execute(
                "INSERT INTO liberacao (identificador,momento,autor,incidente,"
                "usuario,recurso,canal,expira_em,teto_usos,usos,estado,"
                "justificativa) VALUES (?,?,?,?,?,?,?,?,?,0,?,?)",
                (dados["identificador"], _agora_iso(), dados.get("autor", ""),
                 dados.get("incidente", ""), dados.get("usuario", ""),
                 dados.get("recurso", ""), dados.get("canal", ""),
                 dados.get("expira_em", ""), int(dados.get("teto_usos", 1)),
                 dados.get("estado", "ATIVA"), dados.get("justificativa", "")))

    def liberacao_valida(self, usuario: str, recurso: str, canal: str,
                         agora: str) -> Optional[Dict]:
        """Liberacao ATIVA, nao expirada e com uso restante.

        A busca casa o recurso EXATO ou a liberacao curinga (recurso vazio),
        que e' a do incidente inteiro. Nao ha' casamento por prefixo: liberar
        '/documentos' liberaria o acervo inteiro sem que ninguem percebesse.
        """
        r = self._conexao().execute(
            "SELECT * FROM liberacao WHERE estado='ATIVA' AND usuario=? "
            "AND (recurso=? OR recurso='') "
            "AND (canal=? OR canal='') "
            "AND (expira_em='' OR expira_em>=?) "
            "AND (teto_usos<=0 OR usos<teto_usos) "
            "ORDER BY momento DESC LIMIT 1",
            (usuario, recurso, canal, agora)).fetchone()
        return dict(r) if r else None

    def consumir_liberacao(self, identificador: str) -> None:
        c = self._conexao()
        with c:
            c.execute("UPDATE liberacao SET usos=usos+1 WHERE identificador=?",
                      (identificador,))
            c.execute("UPDATE liberacao SET estado='CONSUMIDA' "
                      "WHERE identificador=? AND teto_usos>0 AND usos>=teto_usos",
                      (identificador,))

    def liberacoes(self, filtros: Optional[Dict] = None,
                   limite: int = 100) -> List[Dict]:
        f = filtros or {}
        onde, args = [], []
        for coluna in ("estado", "usuario", "incidente"):
            if f.get(coluna):
                onde.append(f"{coluna}=?")
                args.append(f[coluna])
        sql = "SELECT * FROM liberacao"
        if onde:
            sql += " WHERE " + " AND ".join(onde)
        sql += " ORDER BY momento DESC LIMIT ?"
        args.append(limite)
        return [dict(r) for r in self._conexao().execute(sql, args)]

    def revogar_liberacao(self, identificador: str, autor: str) -> bool:
        c = self._conexao()
        with c:
            cur = c.execute(
                "UPDATE liberacao SET estado='REVOGADA', "
                "justificativa=COALESCE(justificativa,'')||' | revogada por '||? "
                "WHERE identificador=? AND estado='ATIVA'", (autor, identificador))
        return cur.rowcount > 0

    # ------------------------------------------------------------ notificacao
    def enfileirar_notificacao(self, dados: Dict) -> int:
        c = self._conexao()
        with c:
            cur = c.execute(
                "INSERT INTO notificacao (momento,tipo,destinatario,incidente,"
                "assunto,corpo,estado,tentativas,proxima_em) "
                "VALUES (?,?,?,?,?,?, 'PENDENTE', 0, ?)",
                (_agora_iso(), dados.get("tipo", ""), dados.get("destinatario", ""),
                 dados.get("incidente", ""), dados.get("assunto", ""),
                 dados.get("corpo", ""), _agora_iso()))
        return int(cur.lastrowid)

    def notificacoes_pendentes(self, agora: str, limite: int = 20) -> List[Dict]:
        return [dict(r) for r in self._conexao().execute(
            "SELECT * FROM notificacao WHERE estado='PENDENTE' AND proxima_em<=? "
            "ORDER BY id LIMIT ?", (agora, limite))]

    def marcar_notificacao(self, identificador: int, estado: str,
                           erro: str = "", proxima_em: str = "") -> None:
        c = self._conexao()
        with c:
            c.execute(
                "UPDATE notificacao SET estado=?, ultimo_erro=?, "
                "tentativas=tentativas+1, "
                "enviada_em=CASE WHEN ?='ENVIADA' THEN ? ELSE enviada_em END, "
                "proxima_em=? WHERE id=?",
                (estado, erro, estado, _agora_iso(), proxima_em, identificador))

    def notificacoes(self, filtros: Optional[Dict] = None,
                     limite: int = 100) -> List[Dict]:
        f = filtros or {}
        onde, args = [], []
        for coluna in ("estado", "tipo", "destinatario", "incidente"):
            if f.get(coluna):
                onde.append(f"{coluna}=?")
                args.append(f[coluna])
        sql = "SELECT * FROM notificacao"
        if onde:
            sql += " WHERE " + " AND ".join(onde)
        sql += " ORDER BY id DESC LIMIT ?"
        args.append(limite)
        return [dict(r) for r in self._conexao().execute(sql, args)]

    def contar_notificacoes(self, estado: str) -> int:
        return self._conexao().execute(
            "SELECT COUNT(*) n FROM notificacao WHERE estado=?",
            (estado,)).fetchone()["n"]

    # ------------------------------------------------------------ dicionarios
    def guardar_dicionario(self, nome: str, termos: Sequence[str], severidade: str,
                           categorias: Sequence[str], autor: str) -> None:
        c = self._conexao()
        with c:
            c.execute(
                "INSERT OR REPLACE INTO dicionario (nome,documento,atualizado_em,"
                "atualizado_por) VALUES (?,?,?,?)",
                (nome, json.dumps({"termos": list(termos), "severidade": severidade,
                                   "categorias": list(categorias)},
                                  ensure_ascii=False), _agora_iso(), autor))

    def dicionarios(self) -> Dict[str, Dict]:
        saida: Dict[str, Dict] = {}
        for r in self._conexao().execute(
                "SELECT nome, documento, atualizado_em, atualizado_por "
                "FROM dicionario ORDER BY nome"):
            d = json.loads(r["documento"])
            d["atualizado_em"] = r["atualizado_em"]
            d["atualizado_por"] = r["atualizado_por"]
            saida[r["nome"]] = d
        return saida

    def remover_dicionario(self, nome: str) -> bool:
        c = self._conexao()
        with c:
            cur = c.execute("DELETE FROM dicionario WHERE nome=?", (nome,))
        return cur.rowcount > 0

    # --------------------------------------------------------------- indices
    def ativar_indice(self, tabela: str, nome: str, ativo: bool) -> bool:
        if tabela not in ("indice_edm", "indice_idm"):
            raise ValueError(f"tabela de indice desconhecida: {tabela}")
        c = self._conexao()
        with c:
            cur = c.execute(f"UPDATE {tabela} SET ativo=? WHERE nome=?",
                            (1 if ativo else 0, nome))
        return cur.rowcount > 0

    def indices(self) -> Dict[str, List[Dict]]:
        edm = [{"nome": r["nome"], "ativo": bool(r["ativo"]),
                "atualizado_em": r["atualizado_em"],
                **{k: v for k, v in json.loads(r["documento"]).items()
                   if k not in ("celulas", "registros")}}
               for r in self._conexao().execute(
                   "SELECT nome,documento,atualizado_em,ativo FROM indice_edm "
                   "ORDER BY nome")]
        idm: Dict[str, Dict] = {}
        for r in self._conexao().execute(
                "SELECT nome,documento,janelas,atualizado_em,ativo FROM indice_idm "
                "ORDER BY nome,documento"):
            item = idm.setdefault(r["nome"], {"nome": r["nome"], "ativo": bool(r["ativo"]),
                                              "documentos": [],
                                              "atualizado_em": r["atualizado_em"]})
            item["documentos"].append({"documento": r["documento"],
                                       "janelas": len(json.loads(r["janelas"]))})
            item["ativo"] = item["ativo"] and bool(r["ativo"])
        return {"edm": edm, "idm": list(idm.values())}

    def remover_indice_edm(self, nome: str) -> bool:
        c = self._conexao()
        with c:
            cur = c.execute("DELETE FROM indice_edm WHERE nome=?", (nome,))
        return cur.rowcount > 0

    def remover_indice_idm(self, nome: str, documento: str = "") -> int:
        c = self._conexao()
        with c:
            if documento:
                cur = c.execute("DELETE FROM indice_idm WHERE nome=? AND documento=?",
                                (nome, documento))
            else:
                cur = c.execute("DELETE FROM indice_idm WHERE nome=?", (nome,))
        return cur.rowcount

    # -------------------------------------------------------------- varredura
    def abrir_varredura(self, dados: Dict) -> None:
        c = self._conexao()
        with c:
            c.execute(
                "INSERT INTO varredura (identificador,momento,autor,origem,alvo,"
                "modo,estado,iniciada_em) VALUES (?,?,?,?,?,?,?,?)",
                (dados["identificador"], _agora_iso(), dados.get("autor", ""),
                 dados.get("origem", ""), dados.get("alvo", ""),
                 dados.get("modo", "COMPLETA"), "EM_ANDAMENTO", _agora_iso()))

    def atualizar_varredura(self, identificador: str, **campos) -> None:
        permitidos = {"estado", "terminada_em", "arquivos", "inspecionados",
                      "ignorados", "com_achado", "erros", "detalhe"}
        usados = {k: v for k, v in campos.items() if k in permitidos}
        if not usados:
            return
        atribuicoes = ", ".join(f"{k}=?" for k in usados)
        c = self._conexao()
        with c:
            c.execute(f"UPDATE varredura SET {atribuicoes} WHERE identificador=?",
                      list(usados.values()) + [identificador])

    def varreduras(self, limite: int = 50) -> List[Dict]:
        return [dict(r) for r in self._conexao().execute(
            "SELECT * FROM varredura ORDER BY momento DESC LIMIT ?", (limite,))]

    def varredura(self, identificador: str) -> Optional[Dict]:
        r = self._conexao().execute(
            "SELECT * FROM varredura WHERE identificador=?",
            (identificador,)).fetchone()
        return dict(r) if r else None

    def ja_visto(self, chave: str, assinatura: str) -> bool:
        r = self._conexao().execute(
            "SELECT assinatura FROM descoberta_visto WHERE chave=?",
            (chave,)).fetchone()
        return bool(r) and r["assinatura"] == assinatura

    def marcar_visto(self, chave: str, alvo: str, assinatura: str,
                     severidade: str, classificacao: str) -> None:
        c = self._conexao()
        with c:
            c.execute("INSERT OR REPLACE INTO descoberta_visto (chave,alvo,"
                      "assinatura,visto_em,severidade,classificacao) "
                      "VALUES (?,?,?,?,?,?)",
                      (chave, alvo, assinatura, _agora_iso(), severidade,
                       classificacao))


def _agora_iso() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat(timespec="seconds")
