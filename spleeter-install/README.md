# Spleeter Docker - Guia de Instalação

Este guia documenta como instalar o container Spleeter em outra máquina com as mesmas configurações.

## 📋 O que é Spleeter?

Spleeter é uma ferramenta de separação de áudio desenvolvida pela Deezer que permite separar músicas em diferentes stems (faixas):
- **2 stems**: Vocais + Instrumental (acompanhamento)
- **4 stems**: Vocais + Bateria + Baixo + Outros
- **5 stems**: Vocais + Bateria + Baixo + Piano + Outros

## 📋 Pré-requisitos

### Hardware
- CPU: qualquer processador moderno (multi-core recomendado)
- RAM: Mínimo 4GB, recomendado 8GB
- Espaço em disco: ~5GB (imagem Docker + modelos pré-treinados)

### Software
- Docker Engine 20.10+
- Docker Compose 2.0+

**Nota**: Este container **NÃO requer GPU**, roda puramente em CPU usando TensorFlow CPU.

## 🔧 Instalação do Ambiente

### 1. Instalar Docker

```bash
# Ubuntu/Debian
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER

# Fazer logout e login novamente para aplicar permissões
```

### 2. Instalar Docker Compose

```bash
# Geralmente já vem com Docker, verificar versão
docker compose version

# Se não estiver instalado
sudo apt-get update
sudo apt-get install docker-compose-plugin
```

### 3. Verificar Instalação

```bash
docker --version
docker compose version
```

## 🐳 Instalação do Spleeter

### 1. Criar Estrutura de Diretórios

```bash
mkdir -p ~/.spleeter-docker
cd ~/.spleeter-docker
```

### 2. Criar Arquivos de Configuração

#### docker-compose.yml
```yaml
version: '3.8'
services:
  spleeter:
    build: .
    container_name: spleeter-container
    restart: unless-stopped
    volumes:
      - /tmp:/tmp
    environment:
      - PYTHONUNBUFFERED=1
    stdin_open: true
    tty: true
```

#### Dockerfile
```dockerfile
FROM python:3.8-slim

RUN apt-get update && apt-get install -y \
    ffmpeg \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install spleeter

WORKDIR /workdir

RUN spleeter separate -p spleeter:2stems-16kHz /dev/null || true
RUN spleeter separate -p spleeter:4stems-16kHz /dev/null || true
RUN spleeter separate -p spleeter:5stems-16kHz /dev/null || true

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

CMD ["tail", "-f", "/dev/null"]
```

**Importante**: As linhas `RUN spleeter separate` fazem o **download antecipado** dos modelos durante o build, evitando download durante o primeiro uso.

#### entrypoint.sh
```bash
#!/bin/bash

if [ $# -eq 0 ]; then
    echo "Spleeter - Separador de Áudio"
    echo "Uso: spleeter separate arquivo.mp3"
    echo "Modelos: 2stems (padrão), 4stems, 5stems"
    exit 0
fi

exec spleeter "$@"
```

### 3. Build e Inicialização

```bash
# Construir imagem (pode levar 10-15 minutos)
# Os modelos serão baixados durante o build
docker-compose build

# Iniciar container
docker-compose up -d

# Verificar status
docker ps | grep spleeter
```

## 🚀 Uso do Spleeter

### Criar Alias para Facilitar Uso

Adicione ao seu `~/.bashrc` ou `~/.zshrc`:

```bash
alias spleeter='docker exec -i spleeter-container spleeter'
```

Depois execute:
```bash
source ~/.bashrc  # ou source ~/.zshrc
```

### Exemplos de Uso

#### 1. Separar em 2 Stems (Vocais + Instrumental)
```bash
# Padrão - separa em vocals e accompaniment
spleeter separate -o /tmp/output /tmp/musica.mp3

# Resultado:
# /tmp/output/musica/vocals.wav
# /tmp/output/musica/accompaniment.wav
```

#### 2. Separar em 4 Stems (Vocais + Bateria + Baixo + Outros)
```bash
spleeter separate -p spleeter:4stems -o /tmp/output /tmp/musica.mp3

# Resultado:
# /tmp/output/musica/vocals.wav
# /tmp/output/musica/drums.wav
# /tmp/output/musica/bass.wav
# /tmp/output/musica/other.wav
```

#### 3. Separar em 5 Stems (Vocais + Bateria + Baixo + Piano + Outros)
```bash
spleeter separate -p spleeter:5stems -o /tmp/output /tmp/musica.mp3

# Resultado:
# /tmp/output/musica/vocals.wav
# /tmp/output/musica/drums.wav
# /tmp/output/musica/bass.wav
# /tmp/output/musica/piano.wav
# /tmp/output/musica/other.wav
```

#### 4. Especificar Formato de Saída
```bash
# Salvar como MP3 em vez de WAV
spleeter separate -o /tmp/output -c mp3 /tmp/musica.mp3

# Salvar como FLAC
spleeter separate -o /tmp/output -c flac /tmp/musica.mp3
```

#### 5. Processar Múltiplos Arquivos
```bash
# Separar vários arquivos de uma vez
spleeter separate -o /tmp/output /tmp/musica1.mp3 /tmp/musica2.mp3 /tmp/musica3.mp3
```

### Opções Avançadas

```bash
# Alta qualidade (16kHz - padrão)
spleeter separate -p spleeter:2stems-16kHz -o /tmp/output /tmp/musica.mp3

# Usar CPU (já é padrão neste container)
spleeter separate -d cpu -o /tmp/output /tmp/musica.mp3

# Especificar bitrate para MP3
spleeter separate -o /tmp/output -c mp3 -b 320k /tmp/musica.mp3
```

## 📊 Configurações do Container

### Características
- **Python**: 3.8-slim (imagem leve)
- **Spleeter**: Versão 2.4.2
- **TensorFlow**: CPU only (sem necessidade de GPU)
- **FFmpeg**: Para conversão de formatos
- **Modelos**: Pré-baixados durante build (2stems, 4stems, 5stems)

### Volumes
- `/tmp`: Compartilhado com host para I/O de arquivos

### Variáveis de Ambiente
```bash
PYTHONUNBUFFERED=1    # Logs em tempo real
```

### Restart Policy
- `unless-stopped`: Container inicia automaticamente após reboot

## 🎯 Modelos Disponíveis

### 2 Stems (Padrão)
- **Saída**: vocals.wav + accompaniment.wav
- **Uso**: Karaokê, remover vocais, isolar instrumental
- **Velocidade**: ⚡⚡⚡ Rápida
- **Tamanho modelo**: ~33MB

### 4 Stems
- **Saída**: vocals.wav + drums.wav + bass.wav + other.wav
- **Uso**: Remixagem, isolamento de instrumentos
- **Velocidade**: ⚡⚡ Média
- **Tamanho modelo**: ~110MB

### 5 Stems
- **Saída**: vocals.wav + drums.wav + bass.wav + piano.wav + other.wav
- **Uso**: Produção musical profissional, remixagem avançada
- **Velocidade**: ⚡ Lenta
- **Tamanho modelo**: ~140MB

## 🔍 Verificação e Troubleshooting

### Verificar Versão do Spleeter

```bash
docker exec spleeter-container spleeter --version
```

### Verificar Modelos Instalados

```bash
docker exec spleeter-container ls -lh /usr/local/lib/python3.8/site-packages/spleeter/resources/
```

### Logs do Container

```bash
docker logs spleeter-container
```

### Problemas Comuns

#### Erro: "No such file or directory"
- Certifique-se que os arquivos de entrada estão em `/tmp`
- O container só tem acesso ao diretório `/tmp` mapeado

```bash
# ✅ Correto
spleeter separate -o /tmp/output /tmp/musica.mp3

# ❌ Errado
spleeter separate -o /home/user/output /home/user/musica.mp3
```

#### Processamento muito lento
- Normal em CPU, especialmente para arquivos longos
- 4stems e 5stems são mais lentos que 2stems
- Considere usar arquivos menores ou dividir o áudio

#### Erro de memória (OOM)
```bash
# Aumentar limite de memória do container
docker-compose down
```

Edite `docker-compose.yml`:
```yaml
services:
  spleeter:
    # ... configurações existentes ...
    mem_limit: 8g  # Limite de 8GB
```

```bash
docker-compose up -d
```

#### Arquivos de saída não aparecem
```bash
# Verificar permissões do /tmp
ls -ld /tmp

# Deve mostrar: drwxrwxrwt
# Se não, corrigir permissões
sudo chmod 1777 /tmp
```

## ⚡ Performance

### Tempo de Processamento Estimado (CPU)

Para uma música de **3 minutos** em processador moderno (quad-core):

| Modelo | Tempo | Uso CPU | RAM |
|--------|-------|---------|-----|
| 2stems | ~30-60s | 100% | ~2GB |
| 4stems | ~1-2min | 100% | ~3GB |
| 5stems | ~2-4min | 100% | ~4GB |

**Dica**: Para processamento em lote, considere usar múltiplos containers ou processar em paralelo.

## 📦 Formatos Suportados

### Entrada
- MP3, WAV, OGG, M4A, WMA, FLAC
- Qualquer formato suportado por FFmpeg

### Saída
- **WAV** (padrão) - sem perda de qualidade
- **MP3** - use `-c mp3`
- **OGG** - use `-c ogg`
- **FLAC** - use `-c flac`

## 🔄 Manutenção

### Atualizar Spleeter

```bash
cd ~/.spleeter-docker
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

### Limpar Cache e Downloads

```bash
# Limpar arquivos temporários
docker exec spleeter-container rm -rf /tmp/output/*
```

### Backup da Configuração

```bash
# Fazer backup dos arquivos de configuração
tar czf spleeter-docker-backup.tar.gz ~/.spleeter-docker/
```

## 🎵 Casos de Uso

### 1. Criar Karaokê
```bash
# Remover vocais para criar versão instrumental
spleeter separate -o /tmp/karaoke /tmp/musica.mp3
# Use o arquivo accompaniment.wav
```

### 2. Isolar Vocais (Acapella)
```bash
# Extrair apenas os vocais
spleeter separate -o /tmp/acapella /tmp/musica.mp3
# Use o arquivo vocals.wav
```

### 3. Remixagem
```bash
# Separar todos os instrumentos
spleeter separate -p spleeter:5stems -o /tmp/remix /tmp/musica.mp3
# Reimporte as faixas em sua DAW (Ableton, FL Studio, etc.)
```

### 4. Sampling para Produção Musical
```bash
# Isolar bateria e baixo
spleeter separate -p spleeter:4stems -o /tmp/samples /tmp/musica.mp3
# Use drums.wav e bass.wav como samples
```

### 5. Análise e Educação Musical
```bash
# Separar instrumentos para estudo
spleeter separate -p spleeter:5stems -o /tmp/estudo /tmp/musica.mp3
# Ouça cada instrumento separadamente
```

## 📝 Notas Importantes

1. **Qualidade**: Spleeter usa aprendizado de máquina, mas não é perfeito. Pode haver artefatos em situações complexas.
2. **Copyright**: Respeite direitos autorais ao usar músicas separadas.
3. **Arquivos temporários**: Use `/tmp` para entrada/saída - único diretório mapeado.
4. **Primeira execução**: Mesmo com modelos pré-baixados, a primeira separação pode ser um pouco mais lenta.
5. **CPU Only**: Este container não usa GPU. Para processamento mais rápido, considere a versão GPU do Spleeter.

## 🆘 Suporte

- Spleeter GitHub: https://github.com/deezer/spleeter
- Documentação oficial: https://github.com/deezer/spleeter/wiki
- Issues: Verificar logs com `docker logs spleeter-container -f`

## 📚 Recursos Adicionais

### Integração com Python

```bash
# Executar Python dentro do container
docker exec -it spleeter-container python3
```

```python
from spleeter.separator import Separator

# Criar separador
separator = Separator('spleeter:2stems')

# Separar arquivo
separator.separate_to_file('/tmp/input.mp3', '/tmp/output/')
```

### Scripts de Automação

Criar um script `batch_separate.sh`:

```bash
#!/bin/bash
for file in /tmp/input/*.mp3; do
    echo "Processando: $file"
    docker exec -i spleeter-container spleeter separate -o /tmp/output "$file"
done
```

## 📄 Licença

Spleeter está sob licença MIT desenvolvido pela Deezer Research.
