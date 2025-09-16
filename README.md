# 🎬 DubAI - Sistema Profissional de Dublagem Automática

<div align="center">

![Java](https://img.shields.io/badge/Java-17+-ED8B00?style=for-the-badge&logo=java&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apache-maven&logoColor=white)
![AI](https://img.shields.io/badge/Google_Gemma_3_27B-4285F4?style=for-the-badge&logo=google&logoColor=white)
![Ollama](https://img.shields.io/badge/Ollama-000000?style=for-the-badge&logo=ollama&logoColor=white)

**Sistema inteligente para conversão de vídeos em inglês para português brasileiro com IA de última geração**

[🚀 Começar](#-como-usar) • [⚙️ Instalação](#%EF%B8%8F-pré-requisitos) • [📖 Documentação](#-funcionalidades) • [🎯 Exemplos](#-pipeline)

</div>

---

## ✨ Características Principais

### 🤖 **IA Avançada de Tradução**
- **Google Gemma 3 27B** - Modelo de última geração via API
- **Ollama Local** - Modelos locais (DeepSeek R1, Llama 3.1, Gemma 2)
- **Sistema de Timing Dinâmico** - Adaptação automática para sincronização perfeita
- **Especializado em Cursos Técnicos** - Preserva termos como JavaScript, React, Angular, etc.

### 🎵 **Síntese de Voz Profissional**
- **Piper TTS** - Voz natural em português brasileiro
- **Calibração Automática** - Ajuste de velocidade e timing
- **Processamento Otimizado** - Geração de áudio em chunks para eficiência

### 🏗️ **Pipeline Integrado Completo**
- **Extração de Áudio** - FFmpeg para conversão de vídeo
- **Separação Vocal** - Spleeter para isolar narração  
- **Transcrição Inteligente** - Whisper com detecção de timestamps
- **Tradução Contextual** - IA especializada em conteúdo técnico
- **Dublagem Sincronizada** - TTS com timing perfeito

### 📱 **Interface Gráfica Moderna**
- Tela única consolidada
- Seleção de modelo de tradução
- Configuração de API keys
- Logs em tempo real
- Controle completo do processo

---

## 🚀 Como Usar

### Método Rápido (Recomendado)
```bash
# Clone o repositório
git clone https://github.com/jonathansousa87/DubAI.git
cd DubAI

# Execute o script automático
./run-gui.sh
```

### Método Manual
```bash
# Compilar o projeto
mvn compile

# Executar interface gráfica
java -cp target/classes org.Main

# Ou modo linha de comando (legacy)
java -cp target/classes org.Main input.mp4
```

---

## ⚙️ Pré-requisitos

### 🖥️ **Sistema**
- **Java 17+** (JDK)
- **Maven 3.6+**
- **Python 3.8+**
- **FFmpeg** (instalado no PATH)
- **Docker** (para Ollama)

### 🔧 **Dependências Python**
```bash
pip install whisper spleeter piper-tts torch
```

### 🤖 **Modelos de IA**

**Opção 1: Google Gemma 3 (Recomendado)**
- Obtenha sua API Key gratuita em: [Google AI Studio](https://aistudio.google.com/)
- Sem necessidade de GPU local
- Modelo mais poderoso disponível

**Opção 2: Ollama Local**
```bash
# Instalar Ollama
curl -fsSL https://ollama.ai/install.sh | sh

# Baixar modelos (escolha um ou mais)
ollama pull deepseek-r1:8b    # Mais rápido
ollama pull llama3.1:8b       # Equilibrado
ollama pull gemma2:9b         # Mais preciso
```

---

## 📖 Funcionalidades

### 🎯 **Sistema de Tradução Inteligente**

#### **Timing Dinâmico**
- **[SHORT]** - Segmentos curtos (< 2s): Tradução concisa
- **[NORMAL]** - Segmentos médios (2-8s): Tradução balanceada
- **[LONG]** - Segmentos longos (> 8s): Tradução detalhada

#### **Preservação de Termos Técnicos**
Mantém em inglês automaticamente:
```
JavaScript, TypeScript, React, Angular, Next.js, Java, Spring Boot,
API, component, props, state, hook, function, class, method, variable,
array, object, promise, async, await, import, export, npm, yarn, webpack
```

#### **Validação e Re-tradução**
- Detecção automática de traduções inadequadas
- Re-tradução usando o mesmo modelo selecionado
- Sistema de fallback para garantia de qualidade

### 🔧 **Otimizações Avançadas**

#### **Gerenciamento de Memória GPU**
- Limpeza automática entre processamentos
- Restart do Ollama apenas quando necessário
- Monitoramento de uso de VRAM em tempo real

#### **Processamento em Chunks**
- Divisão inteligente de áudio longo
- Processamento paralelo de segmentos
- Concatenação automática do resultado final

---

## 🎬 Pipeline

```mermaid
graph LR
    A[🎥 Vídeo MP4] --> B[🎵 Extração Áudio]
    B --> C[🎼 Separação Vocal]
    C --> D[🎤 Transcrição Whisper]
    D --> E[🤖 Tradução IA]
    E --> F[🔧 Ajuste Timing]
    F --> G[🎙️ Síntese TTS]
    G --> H[✅ Dublagem Pronta]
```

### 📋 **Fluxo Detalhado**

1. **📥 Input**: Vídeo em inglês (.mp4, .avi, .mkv)
2. **🎵 Extração**: FFmpeg converte para WAV
3. **🎼 Separação**: Spleeter isola vocal do instrumental
4. **🎤 Transcrição**: Whisper gera timestamps precisos
5. **🤖 Tradução**: IA traduz preservando contexto técnico
6. **⏱️ Sincronização**: Ajuste automático de timing
7. **🎙️ TTS**: Piper gera áudio em português
8. **✅ Output**: Dublagem sincronizada pronta

---

## 🎯 Casos de Uso

### 📚 **Cursos de Programação**
- Tutoriais de desenvolvimento web
- Cursos de framework (React, Angular, Vue)
- Bootcamps e certificações
- Documentários técnicos

### 🎥 **Conteúdo Educacional**
- Palestras e conferências
- Webinars técnicos
- Apresentações corporativas
- Material de treinamento

### 🎬 **Produção de Conteúdo**
- Canais do YouTube
- Cursos online
- Material institucional
- Documentários

---

## 🔧 Configurações Avançadas

### 🎛️ **Parâmetros de Timing**
```java
// Velocidade de fala em português (palavras/segundo)
PORTUGUESE_SPEAKING_SPEED = 2.5

// Threshold para extensão de texto (evita voz robótica)
EXTENSION_THRESHOLD = 0.2

// Margem de segurança para timing
TIMING_MARGIN = 0.85
```

### 🤖 **Modelos Suportados**

| Modelo | Tamanho | Velocidade | Qualidade | Uso Recomendado |
|--------|---------|------------|-----------|-----------------|
| **Google Gemma 3 27B** | API | ⚡⚡⚡ | 🌟🌟🌟🌟🌟 | **Produção** |
| DeepSeek R1 8B | 8GB | ⚡⚡⚡ | 🌟🌟🌟🌟 | Rápido |
| Llama 3.1 8B | 8GB | ⚡⚡ | 🌟🌟🌟🌟 | Equilibrado |
| Gemma 2 9B | 9GB | ⚡ | 🌟🌟🌟🌟🌟 | Máxima qualidade |

---

## 📊 Resultados

### ⚡ **Performance**
- **Tempo de processamento**: ~0.3x duração do vídeo
- **Precisão de timing**: 95%+ sincronização
- **Qualidade de tradução**: Nível profissional
- **Uso de recursos**: Otimizado para GPU/CPU

### 🎯 **Qualidade**
- Preservação completa de termos técnicos
- Tradução contextual especializada
- Voz natural sem robôs
- Sincronização labial adequada

---

## 🤝 Contribuição

Contribuições são bem-vindas! Por favor:

1. Faça fork do projeto
2. Crie sua feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit suas mudanças (`git commit -m 'Add some AmazingFeature'`)
4. Push para a branch (`git push origin feature/AmazingFeature`)
5. Abra um Pull Request

---

## 📄 Licença

Este projeto está sob a licença MIT. Veja o arquivo `LICENSE` para mais detalhes.

---

## 🙏 Agradecimentos

- **OpenAI** - Whisper para transcrição
- **Google** - Gemma 3 27B para tradução
- **Ollama** - Interface local para modelos
- **Piper TTS** - Síntese de voz natural
- **Spleeter** - Separação de áudio

---

<div align="center">

**⭐ Se este projeto foi útil, considere dar uma estrela!**

[🐛 Reportar Bug](https://github.com/jonathansousa87/DubAI/issues) • [💡 Sugerir Feature](https://github.com/jonathansousa87/DubAI/issues) • [❓ Fazer Pergunta](https://github.com/jonathansousa87/DubAI/discussions)

</div>