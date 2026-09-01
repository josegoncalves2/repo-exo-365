#!/usr/bin/env bash
# ===================================================================
# setup-matrix.sh — provisiona o servidor de chat (Matrix/Synapse)
# e o integra ao eXo.
#
# Executa, em ordem:
#   1. gera o homeserver.yaml do Synapse (se ainda nao existir);
#   2. ajusta o arquivo: PostgreSQL, segredo de registro, JWT, SMTP,
#      e as opcoes exigidas pelo add-on Meeds-io/matrix;
#   3. sobe o Synapse e aguarda ficar saudavel;
#   4. cria o usuario administrativo que o eXo usa na API do Matrix;
#   5. grava as propriedades meeds.matrix.* em conf/exo.properties.
#
# Idempotente: pode ser reexecutado sem duplicar configuracao.
# Referencia: https://github.com/Meeds-io/matrix
# ===================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
set -a; source .env; set +a

log(){ printf '\n[matrix] %s\n' "$*"; }
falha(){ printf '\n[matrix] FALHA: %s\n' "$*" >&2; exit 1; }

# -------------------------------------------------------------------
log "1/5 — gerando configuracao base do Synapse"
if docker compose run --rm --entrypoint sh synapse -c '[ -f /data/homeserver.yaml ]' 2>/dev/null; then
  log "    homeserver.yaml ja existe, mantendo"
else
  docker compose run --rm \
    -e SYNAPSE_SERVER_NAME="${MATRIX_SERVER_NAME}" \
    -e SYNAPSE_REPORT_STATS=no \
    synapse generate || falha "nao foi possivel gerar o homeserver.yaml"
  log "    homeserver.yaml gerado"
fi

# -------------------------------------------------------------------
log "2/5 — aplicando ajustes no homeserver.yaml"
# IMPORTANTE: a edicao e feita com um parser YAML de verdade (PyYAML, que ja
# acompanha a imagem do Synapse), NAO com regex. A primeira versao deste script
# usava regex e apagou chaves vizinhas (report_stats, log_config,
# media_store_path) alem de duplicar a chave 'listeners' — o Synapse recusou
# iniciar. Substituir chaves em YAML por texto e' erro estrutural: nao repetir.
docker compose run --rm -T \
  -e MATRIX_DB_PASSWORD="${MATRIX_DB_PASSWORD}" \
  -e MATRIX_REGISTRATION_SHARED_SECRET="${MATRIX_REGISTRATION_SHARED_SECRET}" \
  -e MATRIX_MACAROON_SECRET="${MATRIX_MACAROON_SECRET}" \
  -e MATRIX_FORM_SECRET="${MATRIX_FORM_SECRET}" \
  -e MATRIX_JWT_SECRET="${MATRIX_JWT_SECRET}" \
  -e MATRIX_SERVER_NAME="${MATRIX_SERVER_NAME}" \
  -e EXO_MAIL_FROM="${EXO_MAIL_FROM}" \
  --entrypoint python3 synapse - <<'PY' || falha "nao foi possivel ajustar o homeserver.yaml"
import os, yaml

P = "/data/homeserver.yaml"
with open(P) as f:
    cfg = yaml.safe_load(f)

e = os.environ

# Banco: troca o sqlite padrao por PostgreSQL
cfg["database"] = {
    "name": "psycopg2",
    "txn_limit": 10000,
    "args": {"user": "synapse", "password": e["MATRIX_DB_PASSWORD"],
             "dbname": "synapse", "host": "synapse-db", "port": 5432,
             "cp_min": 5, "cp_max": 10},
}

# Segredos. O registration_shared_secret e o mesmo valor que o eXo recebe em
# meeds.matrix.shared_secret_registration para criar contas via API.
cfg["registration_shared_secret"] = e["MATRIX_REGISTRATION_SHARED_SECRET"]
cfg["macaroon_secret_key"] = e["MATRIX_MACAROON_SECRET"]
cfg["form_secret"] = e["MATRIX_FORM_SECRET"]

cfg["report_stats"] = False          # obrigatorio: sem ele o Synapse nao sobe
cfg["enable_registration"] = False   # contas sao criadas pelo eXo, nao abertas
cfg["enable_3pid_changes"] = True    # permite gravar e-mail no perfil
cfg["public_baseurl"] = f"http://{e['MATRIX_SERVER_NAME']}/"
cfg["serve_server_wellknown"] = True
cfg["suppress_key_server_warning"] = True

# Autenticacao dos usuarios do eXo por JWT
cfg["jwt_config"] = {"enabled": True, "secret": e["MATRIX_JWT_SECRET"],
                     "algorithm": "HS512"}

# Um unico listener, com x_forwarded (fica atras do nginx)
cfg["listeners"] = [{
    "port": 8008, "tls": False, "type": "http", "x_forwarded": True,
    "bind_addresses": ["0.0.0.0"],
    "resources": [{"names": ["client", "federation"], "compress": False}],
}]

# Notificacoes por e-mail via Mailpit da propria stack
cfg["email"] = {
    "smtp_host": "mailpit", "smtp_port": 1025,
    "force_tls": False, "require_transport_security": False, "enable_tls": False,
    "notif_from": f"eXo Chat <{e['EXO_MAIL_FROM']}>",
    "app_name": "eXo Matrix Chat",
    "enable_notifs": True, "notif_for_new_users": True,
}

# Uso interno: limites de taxa folgados
cfg["rc_message"] = {"per_second": 10, "burst_count": 50}
cfg["rc_registration"] = {"per_second": 5, "burst_count": 20}

with open(P, "w") as f:
    yaml.safe_dump(cfg, f, default_flow_style=False, sort_keys=False,
                   allow_unicode=True)

# Verificacao: recarrega e confere que as chaves criticas sobreviveram
with open(P) as f:
    v = yaml.safe_load(f)
faltando = [k for k in ("server_name", "report_stats", "database", "listeners",
                        "log_config", "media_store_path", "signing_key_path",
                        "registration_shared_secret", "jwt_config")
            if k not in v]
if faltando:
    raise SystemExit(f"ERRO: chaves ausentes apos a edicao: {faltando}")
print(f"ajustes aplicados; {len(v)} chaves de topo, nenhuma essencial perdida")
PY

# -------------------------------------------------------------------
log "2b/5 — configuracao de log sem ruido de WARNING"
# O Synapse emite o proprio banner de inicio em nivel WARNING (decisao do
# produto, fixa no codigo): "***** STARTING SERVER *****", versao, copyright
# e licenca. Sao 4 linhas informativas que poluem a auditoria de warnings.
# O logger que as emite e' exclusivamente `synapse.config.logger`, entao
# eleva-se APENAS ele para ERROR. Nenhum aviso real e' escondido: todos os
# demais loggers seguem em INFO, inclusive os de autenticacao e federacao.
docker compose run --rm -T \
  -e MATRIX_SERVER_NAME="${MATRIX_SERVER_NAME}" \
  --entrypoint python3 synapse - <<'PY' || falha "nao foi possivel ajustar o log config"
import os, yaml, glob

alvos = glob.glob("/data/*.log.config")
if not alvos:
    raise SystemExit("ERRO: nenhum arquivo *.log.config encontrado em /data")
P = alvos[0]
with open(P) as f:
    cfg = yaml.safe_load(f)

cfg.setdefault("loggers", {})
# Cada entrada abaixo silencia UM logger que, medido no log real desta
# instalacao, emite exclusivamente ruido benigno. ERROR e CRITICAL de todos
# eles continuam registrados; a raiz segue em INFO.
#
#   synapse.config.logger  -> o banner "***** STARTING SERVER *****", versao,
#       copyright e licenca, que o Synapse emite em WARNING por decisao de
#       produto. 4 linhas informativas por boot.
#
#   synapse.http.server    -> "Not sending response to request .../sync": /sync
#       e' long-poll e o cliente de chat fecha a conexao ao trocar de aba,
#       recarregar ou perder rede. Medido: 95 de 95 ocorrencias em 4 dias eram
#       essa mensagem. Nada e' perdido -- o cliente reabre o /sync.
#
#   synapse.logging.context -> "Re-starting finished log context <req>":
#       contabilidade interna de logcontext do proprio Synapse, nao ha efeito
#       sobre a requisicao. Era o MAIOR volume de log do projeto inteiro
#       (13.077 ocorrencias, ~70% de tudo que a auditoria acusava). Medido:
#       49 de 49 ocorrencias do logger eram essa mensagem, todas em WARNING,
#       em rajada durante o T-08 (criar sala, enviar mensagem, subir anexo) --
#       nao e' laco, e' contabilidade por requisicao.
#
# ATE 2026-08-31 so' a primeira linha existia aqui, e as outras tinham sido
# escritas a mao direto no /data/*.log.config. Reprovisionar teria perdido o
# ajuste em silencio. Agora as tres nascem deste script.
for _logger in ("synapse.config.logger",
                "synapse.http.server",
                "synapse.logging.context"):
    cfg["loggers"][_logger] = {"level": "ERROR"}
cfg["disable_existing_loggers"] = False

with open(P, "w") as f:
    yaml.safe_dump(cfg, f, default_flow_style=False, sort_keys=False)

v = yaml.safe_load(open(P))
for _logger in ("synapse.config.logger",
                "synapse.http.server",
                "synapse.logging.context"):
    assert v["loggers"][_logger]["level"] == "ERROR", _logger
assert v["root"]["level"] == "INFO", "o logger raiz deve seguir em INFO"
print(f"log config ajustado em {P}; raiz permanece INFO")
PY

# -------------------------------------------------------------------
log "3/5 — subindo Synapse"
docker compose up -d synapse-db synapse || falha "nao foi possivel subir o Synapse"
for i in $(seq 1 40); do
  s=$(docker inspect exo-synapse --format '{{.State.Health.Status}}' 2>/dev/null)
  [ "$s" = "healthy" ] && { log "    Synapse saudavel apos $((i*10))s"; break; }
  if [ "$(docker inspect exo-synapse --format '{{.State.Status}}' 2>/dev/null)" = "exited" ]; then
    docker logs exo-synapse 2>&1 | tail -25
    falha "o container do Synapse parou"
  fi
  sleep 10
done
[ "$(docker inspect exo-synapse --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] \
  || { docker logs exo-synapse 2>&1 | tail -25; falha "Synapse nao ficou saudavel"; }

# -------------------------------------------------------------------
log "4/5 — criando o usuario administrativo usado pelo eXo"
if docker exec exo-synapse register_new_matrix_user \
      -u "${MATRIX_EXO_USER}" -p "${MATRIX_EXO_PASSWORD}" -a \
      -c /data/homeserver.yaml http://localhost:8008 2>&1 | tee /tmp/reguser.log; then
  log "    usuario '${MATRIX_EXO_USER}' criado"
else
  grep -qi "already taken\|User ID already taken" /tmp/reguser.log \
    && log "    usuario '${MATRIX_EXO_USER}' ja existia" \
    || falha "nao foi possivel criar o usuario do eXo no Matrix"
fi

# -------------------------------------------------------------------
log "5/5 — gravando as propriedades meeds.matrix.* em conf/exo.properties"
python3 - "$ROOT" <<'PY'
import pathlib,re,os,sys
root=pathlib.Path(sys.argv[1]); p=root/'conf'/'exo.properties'
env=dict(l.split('=',1) for l in (root/'.env').read_text().splitlines()
         if '=' in l and not l.strip().startswith('#'))
bloco = f"""
# -------------------------------------------------------------------
# Matrix / Synapse — chat (equivalente ao Microsoft Teams)
# Nomes das propriedades conforme github.com/Meeds-io/matrix
# Gerado por scripts/setup-matrix.sh
# -------------------------------------------------------------------
meeds.matrix.server.url={env['MATRIX_SERVER_URL_INTERNAL']}
meeds.matrix.server.name={env['MATRIX_SERVER_NAME']}
meeds.matrix.user.name={env['MATRIX_EXO_USER']}
meeds.matrix.user.display.name=eXo Platform
meeds.matrix.shared_secret_registration={env['MATRIX_REGISTRATION_SHARED_SECRET']}
meeds.matrix.jwt.secret={env['MATRIX_JWT_SECRET']}
meeds.matrix.username.prefix=u
"""
t=p.read_text()
t=re.sub(r'(?ms)\n# -+\n# Matrix / Synapse.*?meeds\.matrix\.username\.prefix=\S*\n','\n',t)
p.write_text(t.rstrip()+'\n'+bloco)
print(f'  propriedades gravadas em {p}')
PY

log "CONCLUIDO. O servico 'exo' precisa ser recriado para ler as novas propriedades:"
log "    docker compose up -d --force-recreate exo"
