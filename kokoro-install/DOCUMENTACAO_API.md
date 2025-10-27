# 📡 Kokoro TTS - Documentação da API

API REST compatível com OpenAI para geração de áudio via Text-to-Speech.

---

## 🌐 Base URL

```
http://localhost:8880
```

---

## 📍 Endpoints

### 1. POST `/v1/audio/speech` - Gerar Áudio

Converte texto em áudio.

#### Request:

**Headers:**
```
Content-Type: application/json
```

**Body:**
```json
{
  "model": "kokoro",
  "input": "Texto para sintetizar",
  "voice": "pf_dora",
  "response_format": "wav",
  "speed": 1.0
}
```

#### Parâmetros:

| Campo | Tipo | Obrigatório | Valores | Descrição |
|-------|------|-------------|---------|-----------|
| `model` | string | ✅ | `"kokoro"` | Sempre "kokoro" |
| `input` | string | ✅ | Qualquer texto | Texto para sintetizar (max ~500 chars) |
| `voice` | string | ✅ | Ver lista de vozes | Código da voz |
| `response_format` | string | ❌ | `wav`, `mp3`, `opus`, `flac` | Formato de saída (padrão: mp3) |
| `speed` | float | ❌ | `0.5` - `2.0` | Velocidade de fala (padrão: 1.0) |

#### Response:

**Success (200):**
- Content-Type: `audio/wav` ou `audio/mpeg`
- Body: Binary audio data

**Error (400):**
```json
{
  "detail": "Mensagem de erro"
}
```

#### Exemplo cURL:

```bash
curl -X POST http://localhost:8880/v1/audio/speech \
  -H "Content-Type: application/json" \
  -d '{
    "model": "kokoro",
    "input": "Olá, este é um teste do Kokoro TTS",
    "voice": "pf_dora",
    "response_format": "wav"
  }' \
  --output output.wav
```

---

### 2. GET `/v1/audio/voices` - Listar Vozes

Lista todas as vozes disponíveis.

#### Request:
```bash
curl http://localhost:8880/v1/audio/voices
```

#### Response:
```json
{
  "voices": [
    "pf_dora",
    "pm_alex",
    "pm_santa",
    "af_bella",
    "am_adam",
    ...
  ]
}
```

---

## 🎤 Vozes Disponíveis

### Português Brasileiro (PT-BR):

| Código | Gênero | Características |
|--------|--------|-----------------|
| **pf_dora** | Feminino | Natural, clara, recomendada |
| **pm_alex** | Masculino | Clara, neutra |
| **pm_santa** | Masculino | Grave, profunda |

### Outras Línguas (Inglês, Espanhol, etc):

Acesse `/v1/audio/voices` ou visite http://localhost:8880/docs para lista completa.

---

## 📊 Especificações Técnicas

### Formato de Áudio Gerado:

#### WAV (Recomendado):
- Sample Rate: 24000 Hz
- Channels: 1 (mono)
- Bit Depth: 16-bit PCM
- Codec: pcm_s16le

#### MP3:
- Bitrate: 128 kbps
- Sample Rate: 24000 Hz

#### OPUS:
- Bitrate: 64 kbps
- Sample Rate: 24000 Hz

#### FLAC:
- Lossless compression
- Sample Rate: 24000 Hz

---

## ⚡ Performance

### Métricas Reais (RTX 2080 Ti):

| Tamanho do Texto | Duração Áudio | Tempo Geração | RTF |
|------------------|---------------|---------------|-----|
| 50 caracteres | 2.5s | 60ms | 41.7x |
| 100 caracteres | 4.2s | 89ms | 47.2x |
| 200 caracteres | 8.0s | 150ms | 53.3x |

**RTF (Real-Time Factor):** Quanto maior, mais rápido que tempo real.

---

## 🔒 Segurança

### API Key:
- **Não é necessária** para uso local
- Se houver autenticação configurada:
  ```bash
  curl -H "Authorization: Bearer YOUR_API_KEY" ...
  ```

### Rate Limiting:
- Padrão: Sem limite
- Configurável no container

---

## 🧪 Exemplos de Uso

### Python:
```python
import requests

url = "http://localhost:8880/v1/audio/speech"
data = {
    "model": "kokoro",
    "input": "Olá mundo",
    "voice": "pf_dora",
    "response_format": "wav"
}

response = requests.post(url, json=data)

with open("output.wav", "wb") as f:
    f.write(response.content)
```

### JavaScript (Node.js):
```javascript
const fs = require('fs');
const fetch = require('node-fetch');

async function generateSpeech() {
    const response = await fetch('http://localhost:8880/v1/audio/speech', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            model: 'kokoro',
            input: 'Olá mundo',
            voice: 'pf_dora',
            response_format: 'wav'
        })
    });

    const buffer = await response.arrayBuffer();
    fs.writeFileSync('output.wav', Buffer.from(buffer));
}
```

### Java (HTTP Client):
```java
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

HttpClient client = HttpClient.newHttpClient();

String json = """
    {
        "model": "kokoro",
        "input": "Olá mundo",
        "voice": "pf_dora",
        "response_format": "wav"
    }
    """;

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8880/v1/audio/speech"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(json))
    .build();

HttpResponse<Path> response = client.send(
    request,
    HttpResponse.BodyHandlers.ofFile(Paths.get("output.wav"))
);
```

---

## 🐛 Códigos de Erro

| Status | Descrição | Solução |
|--------|-----------|---------|
| 200 | Sucesso | - |
| 400 | Parâmetros inválidos | Verificar JSON |
| 404 | Voz não encontrada | Usar `/v1/audio/voices` |
| 422 | Validação falhou | Verificar tipos de dados |
| 500 | Erro interno | Verificar logs do container |

---

## 📖 Documentação Interativa

### Swagger UI:
Acesse: **http://localhost:8880/docs**

Interface web com:
- ✅ Todos os endpoints documentados
- ✅ Teste direto pelo navegador
- ✅ Exemplos de request/response
- ✅ Validação em tempo real

### OpenAPI Spec:
```bash
curl http://localhost:8880/openapi.json
```

---

## 💡 Dicas de Otimização

### 1. Reutilizar Conexões HTTP:
```java
// Criar client uma vez
private static final HttpClient client = HttpClient.newHttpClient();

// Reutilizar para múltiplas requests
```

### 2. Processar em Lote:
```java
// Gerar múltiplos áudios em paralelo
List<CompletableFuture<Path>> futures = texts.stream()
    .map(text -> generateAudioAsync(text))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

### 3. Usar WAV para Melhor Performance:
- WAV não precisa de encoding adicional
- MP3/OPUS adicionam ~10-20ms de latência

### 4. Timeout Adequado:
```java
HttpRequest request = HttpRequest.newBuilder()
    .timeout(Duration.ofSeconds(30))  // Ajustar conforme tamanho do texto
    .build();
```

---

## 🔗 Links Úteis

- **Swagger UI:** http://localhost:8880/docs
- **Health Check:** http://localhost:8880/health (se disponível)
- **GitHub do Projeto:** https://github.com/remsky/Kokoro-FastAPI
- **Modelo Base:** https://huggingface.co/hexgrad/Kokoro-82M

---

**✨ API pronta para uso em produção!**
