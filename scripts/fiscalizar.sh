#!/bin/bash
# =====================================================================
# FISCALIZACAO AUTOMATICA — eXo Platform 365
# Executa a cada N segundos e registra no AUDIT.md
# Uso: ./scripts/fiscalizar.sh [intervalo_segundos]
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
INTERVALO="${1:-60}"
AUDIT="AUDIT.md"
DATA_INICIO=$(date '+%Y-%m-%d %H:%M:%S')

echo "=== FISCALIZACAO INICIADA em $DATA_INICIO ==="
echo "=== Intervalo: ${INTERVALO}s ==="
echo "=== Pressione Ctrl+C para parar ==="
echo ""

SEQ=$(grep -cP '^### \[FISCAL' "$AUDIT" 2>/dev/null || echo 0)
SEQ=$((SEQ + 1))

while true; do
    DATA=$(date '+%Y-%m-%d %H:%M:%S')
    TIMESTAMP=$(date +%s)
    echo "[$DATA] === RODADA $SEQ ==="

    # 1. Verificar containers
    CONTAINERS_UP=$(docker compose ps --services 2>/dev/null | wc -l)
    CONTAINERS_HEALTHY=$(docker compose ps 2>/dev/null | grep -c '(healthy)' || true)
    
    # 2. Verificar portal
    PORTAL_HTTP=$(curl -sk -o /dev/null -w "%{http_code}" https://192.168.1.59/portal/login 2>/dev/null || echo "FALHA")
    
    # 3. Verificar erros no exo
    ULTIMOS_ERROS=$(docker compose logs --tail=100 exo 2>/dev/null | grep -cE 'ERROR|FATAL|Exception' || true)
    
    # 4. Verificar se as extensoes estao ativas
    DLP_ATIVO=$(docker compose logs --tail=200 exo 2>/dev/null | grep -c 'DLP por padrao' || true)
    MFA_ATIVO=$(docker compose logs --tail=200 exo 2>/dev/null | grep -c '2FA por zona' || true)
    TRANSFERENCIA_ATIVO=$(docker compose logs --tail=200 exo 2>/dev/null | grep -c 'Transferencia:' || true)
    
    # 5. Verificar IA
    IA_OK=$(docker compose logs --tail=200 exo 2>/dev/null | grep -c "'ai-agent' initialized" || true)
    
    # 6. Verificar erros criticos
    JWT_ERRO=$(docker compose logs --tail=200 exo 2>/dev/null | grep -c 'Unable to load keystore.*jwt' || true)
    WEBCONF_ERRO=$(docker compose logs --tail=200 exo 2>/dev/null | grep -c 'webconfService.*null' || true)
    MATRIX_ERRO=$(docker compose logs --tail=200 exo 2>/dev/null | grep -c 'matrixService.*null' || true)
    
    # 7. Verificar sessao do copilot
    SESSOES_PARADAS=$(find /home/saexo/.vscode-server/data/User/workspaceStorage/61c7013bd81b3e89b32caffc91a7d93e/GitHub.copilot-chat/debug-logs/ -name "main.jsonl" -newer /tmp/fiscal-ultima -size -3c 2>/dev/null | wc -l || echo 0)
    
    echo "[$DATA] Containers: $CONTAINERS_HEALTHY/$CONTAINERS_UP healthy | Portal: $PORTAL_HTTP | Erros: $ULTIMOS_ERROS | DLP:$DLP_ATIVO MFA:$MFA_ATIVO TRANSF:$TRANSFERENCIA_ATIVO IA:$IA_OK"
    echo "[$DATA] JWT_ERRO:$JWT_ERRO WEBCONF:$WEBCONF_ERRO MATRIX:$MATRIX_ERRO | SessoesParadas:$SESSOES_PARADAS"
    
    # GERAR ALERTAS
    if [ "$PORTAL_HTTP" != "200" ]; then
        echo "  ** ALERTA CRITICO: Portal nao responde (HTTP $PORTAL_HTTP) **"
    fi
    if [ "$JWT_ERRO" -gt 0 ]; then
        echo "  ** ALERTA: JWT keystore ERROR ($JWT_ERRO ocorrencias) — nginx nao serve /jwt-public-key.pem **"
    fi
    if [ "$WEBCONF_ERRO" -gt 0 ]; then
        echo "  ** ALERTA: WebConferencingService NULL ($WEBCONF_ERRO ocorrencias) **"
    fi
    if [ "$MATRIX_ERRO" -gt 0 ]; then
        echo "  ** ALERTA: MatrixService NULL ($MATRIX_ERRO ocorrencias) **"
    fi
    if [ "$DLP_ATIVO" -eq 0 ]; then
        echo "  ** ALERTA: DLP NAO ATIVO **"
    fi
    if [ "$MFA_ATIVO" -eq 0 ]; then
        echo "  ** ALERTA: MFA NAO ATIVO **"
    fi
    if [ "$IA_OK" -eq 0 ]; then
        echo "  ** ALERTA: IA NAO INICIALIZADA **"
    fi
    if [ "$SESSOES_PARADAS" -gt 0 ]; then
        echo "  ** ALERTA: Ha sessoes do Copilot paradas sem produzir ($SESSOES_PARADAS) **"
    fi
    
    # REGISTRAR NO AUDIT a cada 10 rodadas
    if [ $((SEQ % 10)) -eq 0 ]; then
        echo -e "\n### [FISCAL-$(printf '%03d' $SEQ)] $DATA — FISCALIZACAO PERIODICA" >> "$AUDIT"
        echo "**Acao:** Verificacao automatica do estado do projeto." >> "$AUDIT"
        echo "**Resultado:** Containers $CONTAINERS_HEALTHY/$CONTAINERS_UP healthy, Portal HTTP $PORTAL_HTTP, Erros $ULTIMOS_ERROS, DLP:$DLP_ATIVO MFA:$MFA_ATIVO TRANSF:$TRANSFERENCIA_ATIVO IA:$IA_OK" >> "$AUDIT"
        echo "**Problemas:** JWT:$JWT_ERRO WebConf:$WEBCONF_ERRO Matrix:$MATRIX_ERRO" >> "$AUDIT"
        echo "**Status:** $( [ "$PORTAL_HTTP" = "200" ] && echo "OK" || echo "FALHA" )" >> "$AUDIT"
        echo "" >> "$AUDIT"
    fi
    
    touch /tmp/fiscal-ultima
    SEQ=$((SEQ + 1))
    sleep "$INTERVALO"
done