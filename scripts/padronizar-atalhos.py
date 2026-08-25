#!/usr/bin/env python3
# ============================================================================
# padronizar-atalhos.py -- nomes dos atalhos do painel (App Center) sob UMA regra.
#
#   ./scripts/padronizar-atalhos.py            # mostra o que mudaria (padrao)
#   ./scripts/padronizar-atalhos.py --aplicar  # grava
#
# POR QUE ISTO EXISTE
# A caixa "Atalhos" do painel misturava tres origens e nenhuma combinava com a
# outra:
#   . os atalhos que a propria eXo cria vem com titulo em INGLES gravado no
#     banco ("Add a task", "List Spaces"). Nao e' chave de traducao -- e' dado,
#     entao a plataforma nunca traduz, em idioma nenhum.
#   . os atalhos criados aqui vieram com acento faltando ("Dispositivos
#     Moveis"), crase de markdown no meio do nome (`Secretarias`) e
#     maiusculas a esmo.
#   . a ordem era arbitraria: todos com APPLICATION_ORDER = 1.
#
# A REGRA, aplicada a todos sem excecao:
#   1. portugues, com a acentuacao correta;
#   2. primeira letra maiuscula, o resto minusculo -- exceto nome proprio e
#      sigla (GLPI, MDM, BI, Prefeitura);
#   3. sigla entre parenteses no fim, nunca colada com hifen solto:
#      "Dados abertos (BI)", nao "Dados Abertos - BI";
#   4. nada de crase, aspas ou markdown no nome;
#   5. verbo no infinitivo quando a acao e' o proprio atalho ("Criar tarefa").
#
# IDEMPOTENTE: so' escreve onde o valor difere. Rodar duas vezes nao muda nada.
# ============================================================================
import argparse, os, subprocess, sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# id -> (titulo, descricao|None para nao mexer, ordem|None)
PADRAO = {
    1:  ("Criar tarefa",            None,                                              40),
    2:  ("Revisão de contribuições", None,                                             None),
    3:  ("Criar publicação",        None,                                               20),
    4:  ("Espaços",                 None,                                               50),
    5:  ("Favoritos",               None,                                               10),
    6:  ("Dar um Kudos",            "Envie uma recomendação para felicitar os outros", None),
    7:  ("Chamados (GLPI)",         "Abertura e acompanhamento de chamados de suporte",  60),
    8:  ("Consulta de e-mail",      "Consulta de e-mails de servidores e assessores",    70),
    9:  ("Gerador de senhas",       "Gera senhas fortes para contas e sistemas",         80),
    10: ("Site da Prefeitura",      "Portal público da Prefeitura de Olímpia",           90),
    11: ("Dispositivos móveis (MDM)",
         "Gestão, inventário e suporte remoto de dispositivos móveis",                  100),
    12: ("Dados abertos (BI)",      "Dados públicos abertos e indicadores de BI",       110),
    13: ("Estrutura organizacional",
         "Cria e administra secretarias, divisões e setores",                           120),
}


def mysql(sql, tabela=False):
    senha = ""
    with open(os.path.join(RAIZ, ".env"), encoding="utf-8") as fh:
        for l in fh:
            if l.startswith("MYSQL_ROOT_PASSWORD="):
                senha = l.split("=", 1)[1].strip()
    cmd = ["docker", "exec", "-e", f"MYSQL_PWD={senha}", "exo-mysql", "mysql", "-uroot", "exo",
           "--default-character-set=utf8mb4"] + ([] if tabela else ["-N"]) + ["-e", sql]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if p.returncode:
        sys.exit("SQL falhou: " + p.stderr[-400:])
    return p.stdout


def escapa(v):
    return "NULL" if v is None else "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    a = argparse.ArgumentParser(description="Padroniza os nomes dos atalhos do painel")
    a.add_argument("--aplicar", action="store_true", help="grava (sem isto, so' mostra)")
    args = a.parse_args()

    atual = {}
    for linha in mysql("SELECT ID, TITLE, IFNULL(DESCRIPTION,''), IFNULL(APPLICATION_ORDER,-1) "
                       "FROM AC_APPLICATION ORDER BY ID;").splitlines():
        if not linha.strip():
            continue
        i, t, d, o = linha.split("\t")
        atual[int(i)] = (t, d, int(o))

    mudancas, reverter = [], []
    for i, (titulo, desc, ordem) in sorted(PADRAO.items()):
        if i not in atual:
            print(f"  id {i} nao existe neste servidor (atalho nao criado) -- pulado")
            continue
        t0, d0, o0 = atual[i]
        campos, volta = [], []
        if t0 != titulo:
            campos.append(f"TITLE={escapa(titulo)}"); volta.append(f"TITLE={escapa(t0)}")
        if desc is not None and d0 != desc:
            campos.append(f"DESCRIPTION={escapa(desc)}"); volta.append(f"DESCRIPTION={escapa(d0)}")
        if ordem is not None and o0 != ordem:
            campos.append(f"APPLICATION_ORDER={ordem}")
            volta.append(f"APPLICATION_ORDER={o0 if o0 >= 0 else 'NULL'}")
        if not campos:
            continue
        mudancas.append((i, t0, titulo, f"UPDATE AC_APPLICATION SET {', '.join(campos)} WHERE ID={i};"))
        reverter.append(f"UPDATE AC_APPLICATION SET {', '.join(volta)} WHERE ID={i};")

    if not mudancas:
        print("Nada a fazer: os atalhos ja estao no padrao.")
        return
    print(f"{'ID':>3}  {'ANTES':<45} ->  DEPOIS")
    print("-" * 96)
    for i, antes, depois, _ in mudancas:
        print(f"{i:>3}  {antes:<45} ->  {depois}")
    print("-" * 96)

    if not args.aplicar:
        print(f"\n{len(mudancas)} alteracao(oes). Rode com --aplicar para gravar.")
        return

    print("\n-- COMO DESFAZER (guarde estas linhas) --")
    for r in reverter:
        print("   " + r)
    mysql("\n".join(s for _, _, _, s in mudancas))
    print(f"\n{len(mudancas)} atalho(s) padronizado(s) NO BANCO.")
    print("ATENCAO -- o App Center guarda a lista em MEMORIA para os atalhos")
    print("nao-sistema (IS_SYSTEM=0). Medido: com o banco ja corrigido, tanto a")
    print("tela quanto a propria API /app-center/rest/applications continuavam")
    print("devolvendo o titulo antigo; um PUT na API respondeu 200 e tambem nao")
    print("refrescou. Os titulos novos aparecem no proximo start do exo-app:")
    print("    docker compose restart exo     (o boot do eXo leva de 10 a 20 min)")


if __name__ == "__main__":
    main()
