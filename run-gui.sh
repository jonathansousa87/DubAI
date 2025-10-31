#!/bin/bash

echo "🎬 DubAI - Sistema Profissional de Dublagem Automática"
echo "======================================================="
echo "🤖 Powered by Google Gemma 3 27B API + Sistema Inteligente de Pronunciação"
echo ""

# Verificar se o Maven está instalado
if ! command -v mvn &> /dev/null; then
    echo "❌ ERRO: Maven não está instalado"
    echo "   Instale com: sudo pacman -S maven (Arch/CachyOS)"
    exit 1
fi

# Verificar dependências essenciais
echo "🔍 Verificando dependências..."

# FFmpeg
if ! command -v ffmpeg &> /dev/null; then
    echo "⚠️  FFmpeg não encontrado - necessário para processamento de vídeo"
fi

# Piper TTS
if [ ! -f "/home/kadabra/.local/bin/piper" ]; then
    echo "⚠️  Piper TTS não encontrado em ~/.local/bin/piper - necessário para síntese de voz"
fi

echo ""
echo "✅ Verificações concluídas - iniciando interface gráfica..."
echo "🎯 Funcionalidades ativas:"
echo "   - Tradução com Google Gemma 3 27B API"
echo "   - Sistema inteligente de pronunciação (200+ termos técnicos)"
echo "   - Timestamps precisos para dublagem natural"
echo "   - Suporte completo: Java, Spring, React, Next.js, TypeScript, n8n, MCP, IA"
echo ""

# Compilar e executar a interface gráfica usando Maven exec
echo "🚀 Iniciando DubAI..."
mvn compile exec:java -Dexec.mainClass="org.DubAIGUI" -q

echo ""
echo "🎉 DubAI finalizado!"