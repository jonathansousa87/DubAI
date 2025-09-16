#!/bin/bash

echo "🎬 DubAI - Sistema Profissional de Dublagem Automática"
echo "======================================================="
echo ""

# Verificar se o Maven está instalado
if ! command -v mvn &> /dev/null; then
    echo "❌ ERRO: Maven não está instalado"
    echo "   Instale com: sudo pacman -S maven (Arch/CachyOS)"
    exit 1
fi

# Verificar se o Ollama está instalado e rodando
if ! command -v ollama &> /dev/null; then
    echo "❌ ERRO: Ollama não está instalado"
    echo "   Instale em: https://ollama.ai"
    exit 1
fi

# Verificar se o Ollama está rodando
if ! ollama list &> /dev/null; then
    echo "❌ ERRO: Ollama não está rodando"
    echo "   Execute: systemctl start ollama"
    echo "   Ou: ollama serve"
    exit 1
fi

echo "✅ Verificações passaram - iniciando interface gráfica..."
echo ""

# Compilar e executar
mvn compile -q && java -cp target/classes org.Main

echo ""
echo "🎉 DubAI finalizado!"