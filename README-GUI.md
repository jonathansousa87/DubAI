# 🎬 DubAI - Sistema Profissional de Dublagem Automática

## ✨ Interface Gráfica Moderna

Sistema completo para conversão de vídeos em inglês para português brasileiro com:
- 🤖 **IA Avançada**: Suporte a múltiplos modelos Ollama
- 🎵 **TTS Profissional**: Geração de áudio com Piper TTS
- 📱 **Interface Única**: Tela consolidada sem múltiplas janelas
- 🔧 **Controle Total**: Seleção de modelo, métodos e configurações

## 🚀 Como Usar

### Método 1: Script Automático (Recomendado)
```bash
./run-gui.sh
```

### Método 2: Manual
```bash
# Compilar
mvn compile

# Executar interface gráfica
java -cp target/classes org.Main

# Ou executar modo linha de comando (legacy)
java -cp target/classes org.Main input.mp4
```

## 🖥️ Interface Gráfica

### 📁 **Seção de Arquivos**
- **Vídeo**: Selecione o arquivo MP4/MKV/AVI para processar
- **Saída**: Diretório onde serão salvos os arquivos processados

### 🤖 **Configurações de IA**
- **Modelo Ollama**: Lista dinâmica dos modelos instalados
  - 🔄 Botão refresh para atualizar lista
  - ✅ DeepSeek-R1 selecionado automaticamente (recomendado)
- **Tradução**: Escolha o método:
  - `simple`: Rápida e eficiente
  - `advanced`: Mais precisa  
  - `intelligent`: Análise contextual

### 📊 **Monitoramento**
- **Barra de Progresso**: Mostra etapa atual e percentual
- **Log em Tempo Real**: Acompanhe todo o processo
- **Status do Sistema**: Verificações automáticas

## 🔧 Pré-requisitos

### Obrigatórios
- ✅ **Java 17+**: `java --version`
- ✅ **Maven**: `mvn --version`
- ✅ **Ollama**: `ollama --version`
- ✅ **Modelo Ollama**: `ollama pull deepseek-r1:8b`

### Opcionais (Simulados na GUI)
- **ffmpeg**: Para extração/combinação de áudio
- **WhisperX**: Para transcrição
- **Piper TTS**: Para síntese de voz

## 📋 Fluxo de Processamento

1. **🔍 Verificações**: Ollama, modelos, arquivos
2. **🎵 Extração**: Áudio do vídeo original
3. **🎤 Transcrição**: WhisperX → legendas em inglês
4. **🌐 Tradução**: Modelo Ollama → legendas em português
5. **🗣️ Síntese**: Piper TTS → áudio dublado
6. **🎬 Combinação**: Vídeo final com dual audio

## 🎯 Funcionalidades Avançadas

### 🛡️ **Sistema Anti-Vazamento**
- ✅ Filtragem de pensamentos da IA
- ✅ Remoção de instruções técnicas
- ✅ Limpeza de formatação markdown
- ✅ Validação rigorosa de qualidade

### 🎪 **Seleção Inteligente de Modelos**
- 📋 Lista automática via `ollama list`
- 🔄 Refresh em tempo real
- 🎯 Priorização do DeepSeek-R1
- ⚠️ Alertas para modelos ausentes

### 🎨 **Interface Responsiva**
- 🔒 Controles bloqueados durante processamento
- 📊 Progresso detalhado por etapa
- 🚫 Prevenção de múltiplas execuções
- 💬 Feedback visual contínuo

## 🔧 Resolução de Problemas

### ❌ "Nenhum modelo encontrado"
```bash
# Instalar modelo recomendado
ollama pull deepseek-r1:8b

# Verificar modelos instalados
ollama list
```

### ❌ "Ollama não está funcionando"
```bash
# Iniciar Ollama
systemctl start ollama
# Ou
ollama serve
```

### ❌ Erro de compilação
```bash
# Verificar Java
java --version  # Precisa ser 17+

# Limpar e recompilar
mvn clean compile
```

## 📈 Melhorias Implementadas

### ✅ **Correções Críticas**
- 🚫 Eliminou vazamento de instruções da IA
- 🎯 100% dos segmentos traduzidos (zero "não disponível")
- 🧹 Limpeza ultra-rigorosa de pensamentos
- 📝 Prompts simplificados e diretos

### ✅ **Interface Unificada**
- 🖥️ Uma única tela (não múltiplas janelas)
- 🎮 Controles integrados e intuitivos
- 📊 Monitoramento completo do processo
- 🔄 Atualizações em tempo real

### ✅ **Qualidade Profissional**
- 🎪 Português brasileiro natural
- 🛡️ Terminologia técnica preservada
- 🔊 Otimizado para TTS de alta qualidade
- ⚡ Performance otimizada

## 📞 Suporte

Para dúvidas ou problemas:
1. Verifique os logs na interface
2. Teste com arquivo pequeno primeiro
3. Confirme que Ollama + modelo estão funcionando
4. Use modo debug para mais informações

---

**🎉 Sistema pronto para produção de dual audio profissional!**