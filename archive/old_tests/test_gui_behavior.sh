#!/bin/bash

echo "🧪 TESTANDO COMPORTAMENTO DA GUI COM LOGS CAPTURADOS"
echo "=================================================="

# Criar diretório temporário com vídeos de teste
mkdir -p /tmp/test_videos
cd /tmp/test_videos

# Criar arquivos de teste simulando vídeos
echo "📁 Criando arquivos de teste..."
touch "video1.mp4"
touch "video2.mp4" 
touch "video3_dub.mp4"  # Este deve ser ignorado

# Executar o sistema em modo headless com logs capturados
echo "🎬 Executando processamento com captura de logs..."
cd /home/kadabra/Documentos/projetos/back/DubAI

# Simular processamento headless
DISPLAY="" java -Djava.awt.headless=true -cp "src/main/java" org.Main /tmp/test_videos > test_processing.log 2>&1

echo "✅ Log capturado em test_processing.log"
echo "📊 Primeiras 50 linhas do log:"
head -50 test_processing.log

echo ""
echo "🔍 ANÁLISE ESPECÍFICA:"
echo "1. Limpeza CUDA:"
grep -i "cuda\|limpeza\|gpu" test_processing.log | head -10

echo ""
echo "2. Exclusão de vídeos:"
grep -i "exclus\|remov\|delete" test_processing.log | head -5

echo ""
echo "3. Diagnóstico de arquivos:"
grep -i "diagnóstico\|existe\|pode.*ler" test_processing.log | head -5

# Limpar
rm -rf /tmp/test_videos