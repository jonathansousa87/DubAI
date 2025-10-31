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
