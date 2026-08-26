#!/bin/bash
# Script de validação de ativação de features
# Executa testes de backend para confirmar que Chat, Video, Docs e GLPI foram ativados

set -e

PORTAL_URL="http://localhost:8080/portal"
RESULTS_FILE="/tmp/ativacao-results-$(date +%s).txt"

echo "========================================" | tee $RESULTS_FILE
echo "VALIDAÇÃO DE ATIVAÇÃO — eXo Platform 7.2.1" | tee -a $RESULTS_FILE
echo "Data: $(date)" | tee -a $RESULTS_FILE
echo "========================================" | tee -a $RESULTS_FILE
echo "" | tee -a $RESULTS_FILE

# ============================================================
# 1. CHAT (Matrix/Synapse)
# ============================================================
echo "1️⃣  VALIDANDO CHAT (Matrix/Synapse)..." | tee -a $RESULTS_FILE
echo "---" | tee -a $RESULTS_FILE

# Verificar se Synapse está rodando
if docker ps | grep -q synapse && docker ps | grep -q "synapse-db"; then
    echo "✅ Containers Synapse + PostgreSQL rodando" | tee -a $RESULTS_FILE
else
    echo "❌ Containers Synapse não estão rodando" | tee -a $RESULTS_FILE
fi

# Verificar configuração
if grep -q "meeds.matrix.enabled=true" /opt/projetos/exo/conf/exo.properties; then
    echo "✅ Config: meeds.matrix.enabled=true" | tee -a $RESULTS_FILE
else
    echo "❌ Config: meeds.matrix.enabled não está ativa" | tee -a $RESULTS_FILE
fi

if grep -q "exo.chat.enabled=true" /opt/projetos/exo/conf/exo.properties; then
    echo "✅ Config: exo.chat.enabled=true" | tee -a $RESULTS_FILE
else
    echo "❌ Config: exo.chat.enabled não está ativa" | tee -a $RESULTS_FILE
fi

# Verificar nos logs
if docker logs exo-app | grep -q "Matrix service initialized successfully"; then
    echo "✅ Log: Matrix inicializado com sucesso" | tee -a $RESULTS_FILE
else
    echo "⚠️  Log: Nenhuma mensagem de inicialização Matrix" | tee -a $RESULTS_FILE
fi

# Verificar WAR deployado
if docker exec exo-app ls -la /opt/exo/webapps/matrix.war > /dev/null 2>&1; then
    MATRIX_SIZE=$(docker exec exo-app ls -lh /opt/exo/webapps/matrix.war | awk '{print $5}')
    echo "✅ WAR deployed: matrix.war ($MATRIX_SIZE)" | tee -a $RESULTS_FILE
else
    echo "❌ WAR não encontrado: matrix.war" | tee -a $RESULTS_FILE
fi

echo "" | tee -a $RESULTS_FILE

# ============================================================
# 2. VIDEOCONFERÊNCIA (Jitsi)
# ============================================================
echo "2️⃣  VALIDANDO VIDEOCONFERÊNCIA (Jitsi)..." | tee -a $RESULTS_FILE
echo "---" | tee -a $RESULTS_FILE

# Verificar containers Jitsi
JITSI_CONTAINERS=$(docker ps | grep -c "jitsi" || echo 0)
if [ "$JITSI_CONTAINERS" -ge 5 ]; then
    echo "✅ Todos 5+ containers Jitsi rodando ($JITSI_CONTAINERS)" | tee -a $RESULTS_FILE
else
    echo "⚠️  Apenas $JITSI_CONTAINERS containers Jitsi ativos" | tee -a $RESULTS_FILE
fi

# Verificar configuração
if grep -q "webconferencing.enabled=true" /opt/projetos/exo/conf/exo.properties; then
    echo "✅ Config: webconferencing.enabled=true" | tee -a $RESULTS_FILE
else
    echo "❌ Config: webconferencing.enabled não está ativa" | tee -a $RESULTS_FILE
fi

if grep -q "webconferencing.jitsi.active=true" /opt/projetos/exo/conf/exo.properties; then
    echo "✅ Config: webconferencing.jitsi.active=true" | tee -a $RESULTS_FILE
else
    echo "❌ Config: webconferencing.jitsi.active não está ativa" | tee -a $RESULTS_FILE
fi

# Verificar WAR
if docker exec exo-app ls -la /opt/exo/webapps/jitsi.war > /dev/null 2>&1; then
    JITSI_SIZE=$(docker exec exo-app ls -lh /opt/exo/webapps/jitsi.war | awk '{print $5}')
    echo "✅ WAR deployed: jitsi.war ($JITSI_SIZE)" | tee -a $RESULTS_FILE
else
    echo "❌ WAR não encontrado: jitsi.war" | tee -a $RESULTS_FILE
fi

echo "" | tee -a $RESULTS_FILE

# ============================================================
# 3. DOCUMENTOS ONLINE (ONLYOFFICE)
# ============================================================
echo "3️⃣  VALIDANDO DOCUMENTOS ONLINE (ONLYOFFICE)..." | tee -a $RESULTS_FILE
echo "---" | tee -a $RESULTS_FILE

# Verificar container
if docker ps | grep -q "onlyoffice.*healthy"; then
    echo "✅ Container ONLYOFFICE rodando e healthy" | tee -a $RESULTS_FILE
else
    echo "⚠️  Container ONLYOFFICE não healthy" | tee -a $RESULTS_FILE
fi

# Verificar configuração
if grep -q "onlyoffice.enabled=true" /opt/projetos/exo/conf/exo.properties; then
    echo "✅ Config: onlyoffice.enabled=true" | tee -a $RESULTS_FILE
else
    echo "❌ Config: onlyoffice.enabled não está ativa" | tee -a $RESULTS_FILE
fi

if grep -q "onlyoffice.documentserver.url" /opt/projetos/exo/conf/exo.properties; then
    echo "✅ Config: onlyoffice.documentserver.url configurada" | tee -a $RESULTS_FILE
else
    echo "❌ Config: onlyoffice.documentserver.url não configurada" | tee -a $RESULTS_FILE
fi

# Verificar WAR
if docker exec exo-app ls -la /opt/exo/webapps/onlyoffice.war > /dev/null 2>&1; then
    ONLYOFFICE_SIZE=$(docker exec exo-app ls -lh /opt/exo/webapps/onlyoffice.war | awk '{print $5}')
    echo "✅ WAR deployed: onlyoffice.war ($ONLYOFFICE_SIZE)" | tee -a $RESULTS_FILE
else
    echo "❌ WAR não encontrado: onlyoffice.war" | tee -a $RESULTS_FILE
fi

echo "" | tee -a $RESULTS_FILE

# ============================================================
# 4. GLPI INTEGRATION
# ============================================================
echo "4️⃣  VALIDANDO GLPI INTEGRATION..." | tee -a $RESULTS_FILE
echo "---" | tee -a $RESULTS_FILE

# Verificar configuração
if grep -q "glpi.integration.enabled=true" /opt/projetos/exo/conf/exo.properties; then
    echo "✅ Config: glpi.integration.enabled=true" | tee -a $RESULTS_FILE
else
    echo "⚠️  Config: glpi.integration.enabled não explicitamente ativa (pode ter padrão)" | tee -a $RESULTS_FILE
fi

# Verificar WAR
if docker exec exo-app ls -la /opt/exo/webapps/glpi-integration.war > /dev/null 2>&1; then
    GLPI_SIZE=$(docker exec exo-app ls -lh /opt/exo/webapps/glpi-integration.war | awk '{print $5}')
    echo "✅ WAR deployed: glpi-integration.war ($GLPI_SIZE)" | tee -a $RESULTS_FILE
else
    echo "⚠️  WAR não encontrado: glpi-integration.war (pode precisar instalação)" | tee -a $RESULTS_FILE
fi

echo "" | tee -a $RESULTS_FILE

# ============================================================
# 5. RESUMO GERAL
# ============================================================
echo "RESUMO FINAL" | tee -a $RESULTS_FILE
echo "========================================" | tee -a $RESULTS_FILE
echo "" | tee -a $RESULTS_FILE
echo "✅ = Ativado e confirmado" | tee -a $RESULTS_FILE
echo "⚠️  = Ativado mas requer validação manual" | tee -a $RESULTS_FILE
echo "❌ = Não ativado ou erro detectado" | tee -a $RESULTS_FILE
echo "" | tee -a $RESULTS_FILE
echo "📋 Resultado salvo em: $RESULTS_FILE" | tee -a $RESULTS_FILE
echo "" | tee -a $RESULTS_FILE
echo "PRÓXIMO PASSO:" | tee -a $RESULTS_FILE
echo "1. Abra http://192.168.1.59/portal/ no navegador" | tee -a $RESULTS_FILE
echo "2. Faça login como admin" | tee -a $RESULTS_FILE
echo "3. Verifique se você vê:" | tee -a $RESULTS_FILE
echo "   - Ícone 'Conversa' na navbar (Chat)" | tee -a $RESULTS_FILE
echo "   - Botão 'Videochamada' em eventos (Video)" | tee -a $RESULTS_FILE
echo "   - Editor online ao abrir .docx (Docs)" | tee -a $RESULTS_FILE
echo "   - Widget GLPI no dashboard (GLPI)" | tee -a $RESULTS_FILE

cat $RESULTS_FILE
