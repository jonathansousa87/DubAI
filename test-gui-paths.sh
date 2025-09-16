#!/bin/bash

echo "🧪 Teste de verificação de caminhos - DubAI GUI"
echo "==============================================="

# Criar diretório de teste com vídeo fake
mkdir -p test_video_folder
echo "📁 Pasta de teste criada: test_video_folder/"

# Criar arquivo de vídeo fake para teste
touch "test_video_folder/test_video.mp4"
echo "📹 Vídeo de teste criado: test_video_folder/test_video.mp4"

echo ""
echo "🔍 ANTES DO PROCESSAMENTO:"
echo "Arquivos na pasta de vídeo:"
ls -la test_video_folder/

echo ""
echo "Arquivos na pasta raiz (exclui output/):"
find . -maxdepth 1 -name "*.wav" -o -name "*.vtt" -o -name "chunk_*" | head -5

echo ""
echo "✅ Teste preparado. Execute o GUI e processe o vídeo de teste."
echo "📋 Depois do processamento, execute:"
echo "   find test_video_folder/ -name '*.wav' -o -name '*.vtt' -o -name 'chunk_*'"
echo "   (deve retornar apenas o vídeo final *_dub.mp4)"
echo ""
echo "   find . -maxdepth 1 -name '*.wav' -o -name '*.vtt' -o -name 'chunk_*'"
echo "   (deve estar vazio - nenhum arquivo de processamento na raiz)"