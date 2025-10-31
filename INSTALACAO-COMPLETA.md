# 🚀 Guia Completo de Instalação em Nova Máquina

Este guia documenta como configurar todo o ambiente DubAI em uma máquina nova do zero.

## 📋 Índice
1. [Pré-requisitos](#pré-requisitos)
2. [Instalação do Docker](#instalação-do-docker)
3. [Instalação NVIDIA (para GPU)](#instalação-nvidia)
4. [Transferir o Projeto](#transferir-o-projeto)
5. [Configurar Permissões](#configurar-permissões)
6. [Instalar Containers](#instalar-containers)
7. [Verificação](#verificação)
8. [Configurar Aliases](#configurar-aliases)

---

## 📋 Pré-requisitos

### Hardware Necessário

#### Para WhisperX (GPU):
- GPU NVIDIA com mínimo 8GB VRAM
- Testado com: RTX 2080 Ti, RTX 3060+, RTX 4000 series
- CPU: Qualquer processador moderno
- RAM: Mínimo 16GB
- Disco: ~50GB livres

#### Para Spleeter (CPU):
- CPU: Qualquer processador multi-core
- RAM: Mínimo 8GB
- Disco: ~10GB livres

### Sistema Operacional
- Linux (Ubuntu 20.04+, Debian 11+, CachyOS, Arch, etc.)
- Acesso sudo/root

---

## 🐳 Instalação do Docker

### 1. Instalar Docker Engine

```bash
# Remover versões antigas (se existirem)
sudo apt-get remove docker docker-engine docker.io containerd runc

# Atualizar sistema
sudo apt-get update

# Instalar dependências
sudo apt-get install -y \
    ca-certificates \
    curl \
    gnupg \
    lsb-release

# Adicionar chave GPG oficial do Docker
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg

# Adicionar repositório
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Instalar Docker
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# Adicionar usuário ao grupo docker (não precisa sudo sempre)
sudo usermod -aG docker $USER

# Aplicar mudança (fazer logout/login ou executar)
newgrp docker
```

### 2. Verificar Instalação do Docker

```bash
docker --version
docker compose version

# Testar Docker
docker run hello-world
```

**Esperado**: Deve aparecer mensagem "Hello from Docker!"

---

## 🎮 Instalação NVIDIA (Somente para GPU)

### 1. Verificar GPU NVIDIA

```bash
# Verificar se GPU está presente
lspci | grep -i nvidia

# Verificar driver NVIDIA
nvidia-smi
```

**Se `nvidia-smi` não funcionar**, instale o driver:

```bash
# Ubuntu/Debian
sudo apt-get install -y nvidia-driver-535

# Arch/Manjaro/CachyOS
sudo pacman -S nvidia nvidia-utils

# Reiniciar após instalação
sudo reboot
```

### 2. Instalar NVIDIA Container Toolkit

```bash
# Configurar repositório
distribution=$(. /etc/os-release;echo $ID$VERSION_ID)
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg

curl -s -L https://nvidia.github.io/libnvidia-container/$distribution/libnvidia-container.list | \
  sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | \
  sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list

# Instalar
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit

# Configurar Docker
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
```

### 3. Testar GPU no Docker

```bash
docker run --rm --runtime=nvidia --gpus all nvidia/cuda:11.8.0-base-ubuntu20.04 nvidia-smi
```

**Esperado**: Deve mostrar informações da sua GPU.

---

## 📦 Transferir o Projeto

### Opção 1: Via Git (Recomendado)

```bash
# Clonar repositório
cd ~/Documentos/projetos/back/
git clone <URL_DO_REPOSITORIO> DubAI
cd DubAI
```

### Opção 2: Via SCP/RSYNC

```bash
# Na máquina ORIGEM (atual)
cd ~/Documentos/projetos/back/
tar czf DubAI.tar.gz DubAI/

# Transferir para nova máquina
scp DubAI.tar.gz usuario@nova-maquina:/tmp/

# Na máquina DESTINO (nova)
mkdir -p ~/Documentos/projetos/back/
cd ~/Documentos/projetos/back/
tar xzf /tmp/DubAI.tar.gz
cd DubAI
```

### Opção 3: Via Pendrive/HD Externo

```bash
# Na máquina ORIGEM
cd ~/Documentos/projetos/back/
cp -r DubAI /media/pendrive/

# Na máquina DESTINO
mkdir -p ~/Documentos/projetos/back/
cp -r /media/pendrive/DubAI ~/Documentos/projetos/back/
cd ~/Documentos/projetos/back/DubAI
```

---

## 🔓 Configurar Permissões

### 1. Executar Script de Permissões

```bash
# Dentro da pasta DubAI
cd ~/Documentos/projetos/back/DubAI

# Tornar o script executável
chmod +x fix-permissions.sh

# Executar (vai pedir senha sudo)
./fix-permissions.sh
```

**O que o script faz:**
- ✅ Libera permissão 777 em todo o projeto
- ✅ Libera /tmp para os containers
- ✅ Configura ACL para novos arquivos
- ✅ Define você como dono dos arquivos
- ✅ Permite containers escreverem livremente

### 2. Criar Pastas Necessárias

```bash
# Criar pasta output se não existir
mkdir -p ~/Documentos/projetos/back/DubAI/output

# Liberar permissões
chmod 777 ~/Documentos/projetos/back/DubAI/output
```

---

## 🐋 Instalar Containers

### 1. WhisperX (Transcrição com GPU)

```bash
# Copiar arquivos para home
mkdir -p ~/.whisperx-docker
cp whisperx-install/* ~/.whisperx-docker/
cd ~/.whisperx-docker

# Build da imagem (20-30 minutos)
docker compose build

# Iniciar container
docker compose up -d

# Verificar
docker ps | grep whisperx
```

**Tempo de build**: ~20-30 minutos (depende da internet e CPU)

### 2. Spleeter (Separação de Áudio)

```bash
# Copiar arquivos para home
mkdir -p ~/.spleeter-docker
cp spleeter-install/* ~/.spleeter-docker/
cd ~/.spleeter-docker

# Build da imagem (10-15 minutos)
docker compose build

# Iniciar container
docker compose up -d

# Verificar
docker ps | grep spleeter
```

**Tempo de build**: ~10-15 minutos

### 3. Verificar Todos os Containers

```bash
docker ps
```

**Esperado**: Containers `whisperx-container` e `spleeter-container` com status "Up"

---

## ✅ Verificação

### 1. Testar WhisperX

```bash
# Verificar versão
docker exec whisperx-container python3 -c "import whisperx; print('WhisperX OK')"

# Verificar GPU
docker exec whisperx-container nvidia-smi
```

### 2. Testar Spleeter

```bash
# Verificar versão
docker exec spleeter-container spleeter --version
```

### 3. Teste Funcional WhisperX

```bash
# Criar arquivo de teste (silêncio de 1 segundo)
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 1 /tmp/test.wav

# Transcrever
docker exec -i whisperx-container /entrypoint.sh --model tiny /tmp/test.wav
```

### 4. Teste Funcional Spleeter

```bash
# Usar o mesmo arquivo de teste
docker exec -i spleeter-container spleeter separate -o /tmp/test-output /tmp/test.wav
```

---

## 🔧 Configurar Aliases

### 1. Adicionar Aliases ao Shell

```bash
# Editar bashrc
nano ~/.bashrc

# Adicionar no final do arquivo:
alias whisperx='docker exec -i whisperx-container /entrypoint.sh'
alias spleeter='docker exec -i spleeter-container spleeter'

# Salvar (Ctrl+O, Enter, Ctrl+X)

# Aplicar mudanças
source ~/.bashrc
```

### 2. Testar Aliases

```bash
# Testar WhisperX
whisperx

# Testar Spleeter
spleeter
```

**Esperado**: Deve mostrar a ajuda de cada ferramenta.

---

## 📝 Uso Diário

### WhisperX - Transcrição

```bash
# Transcrever áudio com modelo padrão
whisperx /tmp/audio.mp3

# Usar modelo rápido
whisperx --model tiny /tmp/audio.mp3

# Especificar idioma português
whisperx --model base --language pt /tmp/audio.mp3

# Gerar legendas SRT
whisperx --output_format srt /tmp/audio.mp3
```

### Spleeter - Separação de Áudio

```bash
# Separar em vocais + instrumental
spleeter separate -o /tmp/output /tmp/musica.mp3

# Separar em 4 stems
spleeter separate -p spleeter:4stems -o /tmp/output /tmp/musica.mp3

# Separar em 5 stems
spleeter separate -p spleeter:5stems -o /tmp/output /tmp/musica.mp3
```

---

## 🔄 Manutenção

### Iniciar Containers (se pararam)

```bash
docker start whisperx-container spleeter-container
```

### Parar Containers

```bash
docker stop whisperx-container spleeter-container
```

### Verificar Logs

```bash
# WhisperX
docker logs whisperx-container

# Spleeter
docker logs spleeter-container
```

### Atualizar Containers

```bash
# WhisperX
cd ~/.whisperx-docker
docker compose down
docker compose build --no-cache
docker compose up -d

# Spleeter
cd ~/.spleeter-docker
docker compose down
docker compose build --no-cache
docker compose up -d
```

### Limpar Espaço em Disco

```bash
# Remover imagens não usadas
docker system prune -a

# Remover volumes não usados
docker volume prune
```

---

## ❌ Solução de Problemas

### Container não inicia

```bash
# Ver logs detalhados
docker logs whisperx-container --tail 50

# Reconstruir container
cd ~/.whisperx-docker
docker compose down
docker compose up -d --force-recreate
```

### GPU não detectada

```bash
# Verificar NVIDIA runtime
docker info | grep -i runtime

# Deve mostrar: Runtimes: nvidia runc

# Se não mostrar, reinstalar nvidia-container-toolkit
sudo apt-get install --reinstall nvidia-container-toolkit
sudo systemctl restart docker
```

### Erro de permissão ao escrever

```bash
# Executar novamente o script de permissões
cd ~/Documentos/projetos/back/DubAI
./fix-permissions.sh

# Verificar permissões
ls -la output/
```

### Container usa muita memória

```bash
# Limitar memória no docker-compose.yml
# Adicionar em cada serviço:
mem_limit: 8g
```

### WhisperX muito lento

```bash
# Usar modelo menor
whisperx --model tiny /tmp/audio.mp3

# Verificar se está usando GPU
docker exec whisperx-container nvidia-smi
```

---

## 📊 Requisitos de Espaço

| Container | Imagem | Cache/Modelos | Total |
|-----------|--------|---------------|-------|
| WhisperX  | ~30GB  | ~5GB          | ~35GB |
| Spleeter  | ~3.4GB | ~0.5GB        | ~4GB  |
| **TOTAL** |        |               | **~40GB** |

---

## 🎯 Checklist Final

Antes de considerar a instalação completa, verifique:

- [ ] Docker instalado e funcionando
- [ ] NVIDIA Docker Toolkit instalado (se usar GPU)
- [ ] Projeto transferido para a nova máquina
- [ ] Script de permissões executado
- [ ] Container WhisperX buildado e rodando
- [ ] Container Spleeter buildado e rodando
- [ ] GPU detectada no WhisperX (nvidia-smi)
- [ ] Aliases configurados no shell
- [ ] Teste funcional WhisperX passou
- [ ] Teste funcional Spleeter passou
- [ ] Pasta output criada com permissões 777

---

## 📚 Documentação Adicional

- WhisperX detalhado: `whisperx-install/README.md`
- Spleeter detalhado: `spleeter-install/README.md`
- Script de permissões: `fix-permissions.sh`

---

## 🆘 Suporte

### Verificar Status Geral

```bash
# Ver todos containers
docker ps -a

# Ver uso de recursos
docker stats

# Ver espaço em disco
df -h
du -sh ~/.whisperx-docker ~/.spleeter-docker
```

### Logs Úteis

```bash
# Sistema Docker
sudo journalctl -u docker -n 50

# Container específico
docker logs whisperx-container --tail 100 -f
```

---

## 🎉 Pronto!

Sua instalação está completa. Agora você pode:

✅ Transcrever áudios com WhisperX
✅ Separar músicas com Spleeter
✅ Processar arquivos em /tmp
✅ Salvar resultados em output/

**Próximos passos**: Integrar com sua aplicação principal!
