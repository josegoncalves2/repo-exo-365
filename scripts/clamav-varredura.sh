#!/bin/sh
# ===================================================================
# clamav-varredura.sh — motor de varredura do add-on exo-anti-malware.
#
# POR QUE ESTE SCRIPT EXISTE
# --------------------------
# O exo-anti-malware 7.2.1 NAO tem motor proprio. Seu
# ClamAVMalwareDetectionConnector (classe @Component do Spring, registrada
# automaticamente, e nao pelo conf/portal/configuration.xml do add-on) faz
# exatamente tres coisas:
#
#   1. le o arquivo apontado por
#      exo.malwareDetection.connector.clamav.report.path;
#   2. fica com as linhas terminadas em "FOUND", partindo cada uma no
#      PRIMEIRO ":" e tomando a parte da esquerda como caminho do arquivo
#      infectado (medido nas constantes da classe);
#   3. TRUNCA o arquivo depois de processar.
#
# Ou seja: o eXo espera a saida crua do `clamscan`. Este script produz
# exatamente essa saida, no caminho combinado, e nada alem disso.
#
# POR QUE VARREDURA AGENDADA, E NAO clamd RESIDENTE
# -------------------------------------------------
# O `clamd` residente mantem a base de assinaturas em memoria: ~1,3 GiB
# permanentes. Medido no host em 2026-08-27: 11 GiB totais, 7,1 GiB em uso,
# 2,4 GiB JA em swap, com o exo-app a 92% do seu limite de 3,5 GiB. Um
# residente de 1,3 GiB empurraria a JVM do portal para swap, e pausa de GC
# no portal e' pior do que varredura noturna.
#
# Com varredura agendada o custo permanente cai para ~50 MiB (so' o
# freshclam entre janelas) e o pico de 1,3 GiB acontece na janela escolhida.
#
# ISSO NAO E' ANTIVIRUS EM TEMPO REAL, e o add-on tambem nao e': o
# MalwareDetectionJob apenas LE o relatorio a cada
# exo.antiMalware.MalwareDetectionJob.period. A deteccao e' assincrona por
# construcao da eXo, nao por escolha deste script.
#
# Variaveis (vem do compose):
#   CLAMAV_ALVO       diretorio varrido            (padrao /srv/exo/files)
#   CLAMAV_RELATORIO  arquivo lido pelo eXo        (padrao /srv/antimalware/clamav-report.txt)
#   CLAMAV_JANELA     hora da varredura diaria HH:MM (padrao 03:00)
#   CLAMAV_NA_PARTIDA varre ao subir? sim|nao      (padrao sim)
# ===================================================================
set -u

ALVO="${CLAMAV_ALVO:-/srv/exo/files}"
RELATORIO="${CLAMAV_RELATORIO:-/srv/antimalware/clamav-report.txt}"
JANELA="${CLAMAV_JANELA:-03:00}"
NA_PARTIDA="${CLAMAV_NA_PARTIDA:-sim}"

registra() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# O eXo (uid 999) precisa TRUNCAR o relatorio depois de processar, e este
# container nao roda como esse uid. Sem 0666 o eXo le para sempre o mesmo
# relatorio e reprocessa as mesmas deteccoes a cada 5 minutos.
prepara_relatorio() {
  mkdir -p "$(dirname "$RELATORIO")"
  [ -f "$RELATORIO" ] || : > "$RELATORIO"
  chmod 0666 "$RELATORIO" 2>/dev/null || true
}

atualiza_assinaturas() {
  registra "atualizando assinaturas (freshclam)"
  if freshclam --quiet --stdout; then
    registra "assinaturas atualizadas"
  else
    # Base velha ainda varre. Falhar a varredura porque o mirror caiu seria
    # trocar protecao parcial por protecao nenhuma.
    registra "AVISO: freshclam falhou; seguindo com a base local existente"
  fi
}

varre() {
  if [ ! -d "$ALVO" ]; then
    registra "ERRO: alvo $ALVO nao existe ou nao esta montado; varredura abortada"
    return 1
  fi

  prepara_relatorio

  # Escreve em arquivo proprio e so' depois ANEXA ao relatorio. Anexar em vez
  # de sobrescrever evita a corrida com o truncamento do eXo: se ele truncar
  # no meio, perde-se no maximo a deteccao desta rodada, que volta na proxima.
  parcial="$(mktemp)"
  registra "varrendo $ALVO"
  # -i        so' infectados
  # --stdout  saida em stdout (o connector le o arquivo, nao o stderr)
  # -r        recursivo
  clamscan -r -i --no-summary --stdout "$ALVO" > "$parcial" 2>/dev/null
  codigo=$?

  # `grep -c` JA imprime 0 quando nao casa nada, e ainda assim sai com codigo
  # 1. Um `|| echo 0` aqui produziria a string "0\n0", que quebra o `-gt`
  # adiante com "[: Illegal number". Medido em 2026-08-27, primeira execucao.
  achados=$(grep -c 'FOUND$' "$parcial" 2>/dev/null) || true
  achados="${achados:-0}"

  case "$codigo" in
    0) registra "varredura concluida: nenhum arquivo infectado" ;;
    1) registra "varredura concluida: $achados arquivo(s) infectado(s)" ;;
    *) registra "ERRO: clamscan saiu com codigo $codigo" ;;
  esac

  if [ "$achados" -gt 0 ]; then
    cat "$parcial" >> "$RELATORIO"
    chmod 0666 "$RELATORIO" 2>/dev/null || true
    registra "relatorio anexado em $RELATORIO ($achados linha(s))"
  fi

  rm -f "$parcial"
}

# Segundos ate' a proxima ocorrencia de JANELA. Feito com `date` puro para
# nao depender de cron dentro do container.
segundos_ate_janela() {
  agora=$(date +%s)
  hoje=$(date +%Y-%m-%d)
  alvo=$(date -d "${hoje} ${JANELA}" +%s 2>/dev/null) || return 1
  [ "$alvo" -le "$agora" ] && alvo=$((alvo + 86400))
  echo $((alvo - agora))
}

registra "motor de varredura iniciado — alvo=$ALVO relatorio=$RELATORIO janela=$JANELA"
prepara_relatorio

if [ "$NA_PARTIDA" = "sim" ]; then
  atualiza_assinaturas
  varre
fi

# GATILHO MANUAL. Sem ele, a unica forma de conferir a deteccao seria esperar
# ate' a janela — inviavel para quem esta validando a instalacao. Criar o
# arquivo abaixo dispara uma varredura na proxima checagem (<= 30s):
#
#     touch data/antimalware/varrer-agora
#
# O gatilho e' consumido (apagado) ao ser atendido, para nao varrer em laco.
GATILHO="$(dirname "$RELATORIO")/varrer-agora"
INTERVALO_CHECAGEM=30

registra "gatilho manual: crie $GATILHO para varrer sem esperar a janela"

while true; do
  espera=$(segundos_ate_janela) || { registra "ERRO: janela '$JANELA' invalida"; exit 2; }
  registra "proxima varredura em ${espera}s (janela ${JANELA})"

  # Dorme em fatias em vez de um `sleep` unico de horas, para poder atender o
  # gatilho manual e para o container responder a um `docker stop` sem esperar
  # o timeout de 10s do Docker virar SIGKILL no meio de nada.
  restante="$espera"
  while [ "$restante" -gt 0 ]; do
    if [ -e "$GATILHO" ]; then
      rm -f "$GATILHO"
      registra "gatilho manual recebido — varrendo fora da janela"
      atualiza_assinaturas
      varre
      break
    fi
    fatia=$INTERVALO_CHECAGEM
    [ "$restante" -lt "$fatia" ] && fatia="$restante"
    sleep "$fatia"
    restante=$((restante - fatia))
  done

  # Chegou ao fim da espera sem gatilho: e' a janela agendada.
  if [ "$restante" -le 0 ]; then
    atualiza_assinaturas
    varre
  fi
done
