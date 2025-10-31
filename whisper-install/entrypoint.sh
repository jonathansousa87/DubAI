#!/bin/bash

# Mostrar ajuda apenas se não houver argumentos
if [ $# -eq 0 ]; then
    echo "WhisperX - Transcrição com GPU RTX 2080 Ti"
    echo ""
    echo "Uso: whisperx [opções] arquivo_audio"
    echo ""
    echo "📋 TODOS OS MODELOS DISPONÍVEIS:"
    echo ""
    echo "🔥 NOVOS (2024):"
    echo "  turbo           - Novo! Rápido como base, qualidade como large-v2"
    echo "  large-v3-turbo  - Novo! Mais rápido que large-v3, qualidade similar"
    echo ""
    echo "🌍 MULTILÍNGUES:"
    echo "  tiny           - 39M params, mais rápido, menos preciso"
    echo "  base           - 74M params, balanceado"
    echo "  small          - 244M params, boa qualidade"
    echo "  medium         - 769M params, alta qualidade"
    echo "  large          - 1550M params, muito alta qualidade"
    echo "  large-v1       - 1550M params, versão original"
    echo "  large-v2       - 1550M params, melhorado"
    echo "  large-v3       - 1550M params, máxima qualidade (padrão)"
    echo ""
    echo "🇺🇸 INGLÊS-APENAS (melhor para inglês):"
    echo "  tiny.en        - 39M params, inglês apenas"
    echo "  base.en        - 74M params, inglês apenas"
    echo "  small.en       - 244M params, inglês apenas"
    echo "  medium.en      - 769M params, inglês apenas"
    echo ""
    echo "⚡ RECOMENDAÇÕES POR USO:"
    echo "  • Tempo real: turbo ou tiny"
    echo "  • Balanceado: base ou small"
    echo "  • Máxima qualidade: large-v3"
    echo "  • Inglês puro: *.en"
    echo ""
    echo "📝 EXEMPLOS:"
    echo "  whisperx audio.mp3                              # large-v3 (padrão)"
    echo "  whisperx --model turbo audio.mp3                # novo modelo turbo"
    echo "  whisperx --model large-v3-turbo audio.mp3       # novo modelo v3-turbo"
    echo "  whisperx --model tiny --language pt audio.mp3   # rápido + português"
    echo "  whisperx --model base.en audio.mp3              # inglês otimizado"
    echo "  whisperx --output_format srt audio.mp3          # gerar legendas"
    echo ""
    echo "🎯 Formatos: txt, srt, vtt, tsv"
    echo "🌐 Idiomas: pt, en, es, fr, de, it, ja, ko, zh, ru, ar, etc."
    exit 0
fi

# Detectar se GPU está disponível
if nvidia-smi >/dev/null 2>&1; then
    DEVICE="cuda"
    echo "🚀 Usando GPU (RTX 2080 Ti)"
else
    DEVICE="cpu"
    echo "⚠️  GPU não detectada, usando CPU"
fi

# Se não tem --model nos argumentos, usar large-v3 como padrão
HAS_MODEL=false
for arg in "$@"; do
    if [[ "$arg" == "--model" ]]; then
        HAS_MODEL=true
        break
    fi
done

if [ "$HAS_MODEL" = false ]; then
    echo "📝 Modelo: large-v3 (padrão - máxima qualidade)"
    exec whisperx --model large-v3 --device "$DEVICE" "$@"
else
    # Extrair e mostrar o modelo especificado
    NEXT_IS_MODEL=false
    for arg in "$@"; do
        if [ "$NEXT_IS_MODEL" = true ]; then
            echo "📝 Modelo: $arg"
            break
        fi
        if [[ "$arg" == "--model" ]]; then
            NEXT_IS_MODEL=true
        fi
    done
    exec whisperx --device "$DEVICE" "$@"
fi
