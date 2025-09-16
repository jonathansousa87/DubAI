#!/bin/bash

echo "🧪 EXECUTANDO TESTE PARA CAPTURAR LOGS COMPLETOS"
echo "=============================================="

# Criar diretório de teste com vídeo simulado
TEST_DIR="/tmp/test_video_processing"
mkdir -p "$TEST_DIR"
cd "$TEST_DIR"

# Criar um arquivo de vídeo simulado para teste
echo "📁 Criando arquivo de teste..."
touch "test_video.mp4"
echo "fake mp4 content for testing" > "test_video.mp4"

# Voltar ao diretório do projeto
cd /home/kadabra/Documentos/projetos/back/DubAI

echo "🎬 Executando processamento com logs capturados..."
LOG_FILE="full_execution_$(date +%Y%m%d_%H%M%S).log"

# Executar o Main com o diretório de teste
java -cp "src/main/java" org.Main "$TEST_DIR" > "$LOG_FILE" 2>&1

echo "✅ Execução finalizada - logs salvos em: $LOG_FILE"
echo ""
echo "🔍 ANÁLISE DOS LOGS:"
echo "==================="

if [ -f "$LOG_FILE" ]; then
    echo "📊 Total de linhas: $(wc -l < "$LOG_FILE")"
    echo ""
    
    echo "🎬 Início do processamento:"
    head -20 "$LOG_FILE"
    echo ""
    
    echo "❌ Erros encontrados:"
    grep -i "erro\|exception\|falha\|failed" "$LOG_FILE" | head -10
    echo ""
    
    echo "🎬 Tentativas de criação de vídeo:"
    grep -i "criando.*vídeo\|vídeo.*final\|dual.*áudio\|outputVideo" "$LOG_FILE" | head -10
    echo ""
    
    echo "📄 Para ver o log completo: cat $LOG_FILE"
else
    echo "❌ Arquivo de log não foi criado"
fi

# Limpar diretório de teste
rm -rf "$TEST_DIR"
echo "🧹 Diretório de teste removido"