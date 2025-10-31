# WhisperX Docker - Guia de Instalação

Este guia documenta como instalar o container WhisperX em outra máquina com as mesmas configurações.

## 📋 Pré-requisitos

### Hardware
- GPU NVIDIA compatível (testado com RTX 2080 Ti)
- Mínimo 8GB de VRAM recomendado
- Espaço em disco: ~35GB (imagem Docker + modelos)

### Software
- Docker Engine 20.10+
- Docker Compose 2.0+
- NVIDIA Docker Runtime (nvidia-docker2)
- Driver NVIDIA atualizado (versão 515+)
- CUDA 11.8 (instalado via container)

## 🔧 Instalação do Ambiente

### 1. Instalar Docker

```bash
# Ubuntu/Debian
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
```

### 2. Instalar NVIDIA Container Toolkit

```bash
# Adicionar repositório NVIDIA
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
curl -s -L https://nvidia.github.io/libnvidia-container/$distribution/libnvidia-container.list | \
  sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | \
  sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list

# Instalar toolkit
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit

# Configurar Docker daemon
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
```

### 3. Verificar Instalação

```bash
# Testar acesso à GPU pelo Docker
docker run --rm --runtime=nvidia --gpus all nvidia/cuda:11.8.0-base-ubuntu20.04 nvidia-smi
```

## 🐳 Instalação do WhisperX

### 1. Criar Estrutura de Diretórios

```bash
mkdir -p ~/.whisperx-docker
cd ~/.whisperx-docker
```

### 2. Criar Arquivos de Configuração

#### docker-compose.yml
```yaml
services:
  whisperx:
    build: .
    container_name: whisperx-container
    restart: unless-stopped
    runtime: nvidia
    environment:
      - NVIDIA_VISIBLE_DEVICES=all
      - TRANSFORMERS_CACHE=/tmp/transformers_cache
      - HF_HOME=/tmp/hf_home
      - PYTHONUNBUFFERED=1
    volumes:
      - /tmp:/tmp
      - whisperx_cache:/tmp/transformers_cache
      - whisperx_hf:/tmp/hf_home
    stdin_open: true
    tty: true

volumes:
  whisperx_cache:
  whisperx_hf:
```

#### Dockerfile
```dockerfile
# Usar imagem base Ubuntu 20.04
FROM ubuntu:20.04

# Evitar prompts interativos
ENV DEBIAN_FRONTEND=noninteractive

# Configurar timezone
RUN ln -snf /usr/share/zoneinfo/UTC /etc/localtime && echo UTC > /etc/timezone

# Adicionar repositórios NVIDIA para cuDNN
RUN apt-get update && apt-get install -y wget gnupg && \
    wget https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2004/x86_64/cuda-keyring_1.0-1_all.deb && \
    dpkg -i cuda-keyring_1.0-1_all.deb && \
    apt-get update

# Instalar dependências do sistema + CUDA + cuDNN
RUN apt-get install -y \
    python3 \
    python3-pip \
    python3-dev \
    python3-distutils \
    ffmpeg \
    git \
    build-essential \
    wget \
    curl \
    software-properties-common \
    cuda-toolkit-11-8 \
    libcudnn8=8.9.2.26-1+cuda11.8 \
    libcudnn8-dev=8.9.2.26-1+cuda11.8 \
    && rm -rf /var/lib/apt/lists/*

# Configurar PATH para CUDA
ENV PATH=/usr/local/cuda-11.8/bin:$PATH
ENV LD_LIBRARY_PATH=/usr/local/cuda-11.8/lib64:$LD_LIBRARY_PATH

# Atualizar para Python 3.9
RUN add-apt-repository ppa:deadsnakes/ppa && \
    apt-get update && \
    apt-get install -y python3.9 python3.9-dev python3.9-distutils && \
    rm -rf /var/lib/apt/lists/*

# Instalar pip para Python 3.9
RUN curl https://bootstrap.pypa.io/get-pip.py -o get-pip.py && \
    python3.9 get-pip.py && \
    rm get-pip.py

# Criar links para python
RUN update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.9 1
RUN update-alternatives --install /usr/bin/python python /usr/bin/python3.9 1

# Criar diretórios de cache com permissões
RUN mkdir -p /tmp/cache && chmod 777 /tmp/cache
RUN mkdir -p /tmp/matplotlib && chmod 777 /tmp/matplotlib

# Configurar variáveis de ambiente para cache
ENV TRANSFORMERS_CACHE=/tmp/cache
ENV HF_HOME=/tmp/cache
ENV MPLCONFIGDIR=/tmp/matplotlib
ENV TORCH_HOME=/tmp/cache
ENV PYANNOTE_CACHE=/tmp/cache

# Instalar PyTorch com CUDA
RUN python3.9 -m pip install torch==2.0.0 torchvision==0.15.0 torchaudio==2.0.0 --index-url https://download.pytorch.org/whl/cu118

# Instalar versões específicas compatíveis
RUN python3.9 -m pip install \
    ctranslate2==3.24.0 \
    faster-whisper==0.10.1 \
    transformers==4.32.1 \
    pyannote.audio==3.1.1

# Instalar WhisperX
RUN python3.9 -m pip install git+https://github.com/m-bain/whisperX.git

# Criar diretório de trabalho
WORKDIR /workdir

# Script de entrada
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

CMD ["tail", "-f", "/dev/null"]
```

#### entrypoint.sh
```bash
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
```

### 3. Build e Inicialização

```bash
# Construir imagem (pode levar 20-30 minutos)
docker-compose build

# Iniciar container
docker-compose up -d

# Verificar status
docker ps | grep whisperx
```

## 🚀 Uso do WhisperX

### Criar Alias para Facilitar Uso

Adicione ao seu `~/.bashrc` ou `~/.zshrc`:

```bash
alias whisperx='docker exec -it whisperx-container /entrypoint.sh'
```

Depois execute:
```bash
source ~/.bashrc  # ou source ~/.zshrc
```

### Exemplos de Uso

```bash
# Transcrever com modelo padrão (large-v3)
whisperx /tmp/audio.mp3

# Usar modelo turbo (mais rápido)
whisperx --model turbo /tmp/audio.mp3

# Especificar idioma português
whisperx --model base --language pt /tmp/audio.mp3

# Gerar legendas SRT
whisperx --output_format srt /tmp/audio.mp3

# Modelo otimizado para inglês
whisperx --model small.en /tmp/audio.mp3
```

### Formatos Suportados
- **Entrada**: mp3, wav, m4a, flac, ogg, webm, mp4
- **Saída**: txt, srt, vtt, tsv, json

## 📊 Configurações do Container

### Volumes Persistentes
- `whisperx_cache`: Cache de modelos Transformers
- `whisperx_hf`: Cache do Hugging Face
- `/tmp`: Compartilhado com host para I/O de arquivos

### Variáveis de Ambiente
```bash
NVIDIA_VISIBLE_DEVICES=all          # Acesso a todas GPUs
TRANSFORMERS_CACHE=/tmp/transformers_cache
HF_HOME=/tmp/hf_home
PYTHONUNBUFFERED=1                  # Logs em tempo real
```

### Runtime NVIDIA
- Runtime: `nvidia`
- CUDA: 11.8
- cuDNN: 8.9.2.26

## 🔍 Verificação e Troubleshooting

### Verificar GPU no Container

```bash
docker exec -it whisperx-container nvidia-smi
```

### Verificar Instalação do WhisperX

```bash
docker exec -it whisperx-container python3 -c "import whisperx; print(whisperx.__version__)"
```

### Logs do Container

```bash
docker logs whisperx-container
```

### Problemas Comuns

#### GPU não detectada
```bash
# Verificar NVIDIA Docker runtime
docker info | grep -i runtime

# Deve mostrar: Runtimes: nvidia runc
```

#### Erro de memória
- Reduzir tamanho do modelo (usar tiny, base ou small)
- Verificar VRAM disponível: `nvidia-smi`

#### Erro de permissão em /tmp
```bash
# Garantir permissões corretas
sudo chmod 1777 /tmp
```

## 📦 Modelos Disponíveis

| Modelo | Parâmetros | VRAM | Velocidade | Qualidade |
|--------|-----------|------|-----------|-----------|
| tiny | 39M | ~1GB | Muito rápida | Básica |
| base | 74M | ~1GB | Rápida | Boa |
| small | 244M | ~2GB | Média | Muito boa |
| medium | 769M | ~5GB | Lenta | Excelente |
| large-v3 | 1550M | ~10GB | Muito lenta | Máxima |
| turbo | - | ~6GB | Rápida | Excelente |

## 🔄 Manutenção

### Atualizar WhisperX

```bash
cd ~/.whisperx-docker
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

### Limpar Cache

```bash
docker exec -it whisperx-container rm -rf /tmp/transformers_cache/*
docker exec -it whisperx-container rm -rf /tmp/hf_home/*
```

### Backup de Modelos

```bash
# Listar volumes
docker volume ls | grep whisperx

# Backup
docker run --rm -v whisperx-docker_whisperx_cache:/data -v $(pwd):/backup ubuntu tar czf /backup/whisperx_cache_backup.tar.gz /data
```

## 📝 Notas Importantes

1. **Primeiro uso**: O primeiro processamento baixará os modelos (~2-5GB dependendo do modelo)
2. **Arquivos temporários**: Use `/tmp` para entrada/saída pois está mapeado entre host e container
3. **Restart policy**: Container configurado com `unless-stopped` para iniciar automaticamente
4. **Python**: Usa Python 3.9 para melhor compatibilidade com dependências

## 🆘 Suporte

- WhisperX GitHub: https://github.com/m-bain/whisperX
- NVIDIA Docker: https://github.com/NVIDIA/nvidia-docker
- Issues: Verificar logs com `docker logs whisperx-container -f`

## 📄 Licença

WhisperX é baseado no Whisper da OpenAI e está sob licença MIT.
