#!/bin/bash

if [ $# -eq 0 ]; then
    echo "Spleeter - Separador de Áudio"
    echo "Uso: spleeter separate arquivo.mp3"
    echo "Modelos: 2stems (padrão), 4stems, 5stems"
    exit 0
fi

exec spleeter "$@"
