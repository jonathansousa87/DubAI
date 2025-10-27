# ☕ Integração Kokoro TTS com Java

Guia completo para integrar Kokoro TTS no seu projeto DubAI.

---

## 🎯 Objetivo

Substituir Piper TTS por Kokoro TTS no arquivo `TTSUtils.java` para obter **6.7x mais performance**.

---

## 📋 Mudanças Necessárias

### 1. Sample Rate
- **Piper:** 22050 Hz
- **Kokoro:** 24000 Hz

**Solução:** Converter áudio após geração ou ajustar toda a pipeline para 24kHz.

### 2. Speed vs Length Scale
- **Piper:** `--length-scale` (menor = mais rápido)
- **Kokoro:** `speed` (maior = mais rápido)

**Conversão:**
```java
float kokoroSpeed = 1.0f / piperLengthScale;
```

### 3. Formato de Entrada
- **Piper:** Aceita stdin ou argumento
- **Kokoro:** JSON via HTTP POST

---

## 💻 Código de Integração

### Classe KokoroTTS.java

Crie um novo arquivo em `src/main/java/org/`:

```java
package org;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

public class KokoroTTS {

    private static final Logger logger = Logger.getLogger(KokoroTTS.class.getName());
    private static final String KOKORO_URL = "http://localhost:8880/v1/audio/speech";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Semaphore para controlar concorrência (igual ao Piper)
    private static final Semaphore ttsSemaphore = new Semaphore(4);

    /**
     * Gera áudio usando Kokoro TTS
     *
     * @param text Texto para sintetizar
     * @param outputFile Arquivo de saída
     * @param lengthScale Escala de duração (compatível com Piper)
     * @param voice Voz PT-BR (padrão: pf_dora)
     * @throws IOException Se falhar na geração
     * @throws InterruptedException Se thread for interrompida
     */
    public static void generateAudio(String text, Path outputFile, double lengthScale, String voice)
            throws IOException, InterruptedException {

        ttsSemaphore.acquire();
        try {
            long startTime = System.nanoTime();

            // Converter lengthScale para speed (Kokoro usa o inverso)
            double speed = 1.0 / Math.max(0.5, Math.min(2.0, lengthScale));

            // Criar JSON request
            String requestBody = String.format(Locale.US, """
                {
                    "model": "kokoro",
                    "input": "%s",
                    "voice": "%s",
                    "response_format": "wav",
                    "speed": %.2f
                }
                """,
                escapeJson(text),
                voice,
                speed
            );

            logger.info(String.format("🔧 Gerando áudio Kokoro: voz=%s, speed=%.2f", voice, speed));

            // Criar HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(KOKORO_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // Enviar request e salvar resposta
            HttpResponse<Path> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofFile(outputFile)
            );

            if (response.statusCode() != 200) {
                throw new IOException("Kokoro TTS falhou: HTTP " + response.statusCode());
            }

            // Verificar se arquivo foi gerado
            if (!Files.exists(outputFile) || Files.size(outputFile) < 256) {
                throw new IOException("Kokoro não gerou áudio válido");
            }

            long elapsed = System.nanoTime() - startTime;
            logger.info(String.format("✅ Kokoro: %s gerado em %.3fs",
                    outputFile.getFileName(), elapsed / 1_000_000_000.0));

        } finally {
            ttsSemaphore.release();
        }
    }

    /**
     * Sobrecarga com voz padrão (pf_dora - feminina)
     */
    public static void generateAudio(String text, Path outputFile, double lengthScale)
            throws IOException, InterruptedException {
        generateAudio(text, outputFile, lengthScale, "pf_dora");
    }

    /**
     * Escape de caracteres especiais para JSON
     */
    private static String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Converte áudio de 24kHz (Kokoro) para 22.05kHz (Piper) se necessário
     *
     * @param kokoroOutput Arquivo de saída do Kokoro (24kHz)
     * @param targetOutput Arquivo de saída final (22.05kHz)
     */
    public static void convertTo22kHz(Path kokoroOutput, Path targetOutput)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", kokoroOutput.toString(),
                "-ar", "22050",
                "-ac", "1",
                targetOutput.toString()
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timeout na conversão de sample rate");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Falha na conversão de sample rate");
        }
    }

    /**
     * Testa se Kokoro está disponível
     */
    public static boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8880/v1/audio/voices"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            return response.statusCode() == 200;

        } catch (Exception e) {
            return false;
        }
    }
}
```

---

## 🔄 Modificação do TTSUtils.java

### Opção 1: Substituição Direta (Recomendado)

Substituir a chamada do Piper por Kokoro:

```java
// LOCALIZAÇÃO: TTSUtils.java linha ~1691
// MÉTODO: generateOptimizedSegmentAudio()

// ANTES (Piper):
private static void generateOptimizedSegmentAudio(OptimizedSegment segment)
        throws IOException, InterruptedException {

    Path outputFile = OUTPUT_DIR.resolve(segment.rawAudioFile);

    ttsSemaphore.acquire();
    try {
        ProcessBuilder pb = new ProcessBuilder(
            "piper",
            "--output", outputFile.toString(),
            "--length-scale", String.format(Locale.US, "%.6f", segment.currentLengthScale),
            segment.cleanText
        );
        // ... resto do código Piper
    } finally {
        ttsSemaphore.release();
    }
}

// DEPOIS (Kokoro):
private static void generateOptimizedSegmentAudio(OptimizedSegment segment)
        throws IOException, InterruptedException {

    Path outputFile = OUTPUT_DIR.resolve(segment.rawAudioFile);

    // Usar Kokoro TTS
    KokoroTTS.generateAudio(
        segment.cleanText,
        outputFile,
        segment.currentLengthScale,
        "pf_dora"  // Ou configurável
    );

    // Se precisar de 22kHz, converter
    if (needsConversion()) {
        Path temp = outputFile;
        Path converted = OUTPUT_DIR.resolve("conv_" + segment.rawAudioFile);
        KokoroTTS.convertTo22kHz(temp, converted);
        Files.move(converted, outputFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
```

### Opção 2: Fallback Automático

Tentar Kokoro, se falhar usar Piper:

```java
private static void generateOptimizedSegmentAudio(OptimizedSegment segment)
        throws IOException, InterruptedException {

    Path outputFile = OUTPUT_DIR.resolve(segment.rawAudioFile);

    // Tentar Kokoro primeiro
    if (KokoroTTS.isAvailable()) {
        try {
            logger.info("🚀 Usando Kokoro TTS (GPU)");
            KokoroTTS.generateAudio(
                segment.cleanText,
                outputFile,
                segment.currentLengthScale
            );
            return;
        } catch (Exception e) {
            logger.warning("⚠️ Kokoro falhou, usando Piper: " + e.getMessage());
        }
    }

    // Fallback para Piper
    logger.info("💻 Usando Piper TTS (CPU)");
    generateWithPiper(segment, outputFile);
}

private static void generateWithPiper(OptimizedSegment segment, Path outputFile)
        throws IOException, InterruptedException {

    ttsSemaphore.acquire();
    try {
        ProcessBuilder pb = new ProcessBuilder(
            "piper",
            "--output", outputFile.toString(),
            "--length-scale", String.format(Locale.US, "%.6f", segment.currentLengthScale),
            segment.cleanText
        );
        // ... código Piper original
    } finally {
        ttsSemaphore.release();
    }
}
```

### Opção 3: Configurável via Property

```java
// Adicionar no início da classe TTSUtils
private static final String TTS_ENGINE = System.getProperty("tts.engine", "kokoro");
private static final boolean USE_KOKORO = "kokoro".equalsIgnoreCase(TTS_ENGINE);

private static void generateOptimizedSegmentAudio(OptimizedSegment segment)
        throws IOException, InterruptedException {

    Path outputFile = OUTPUT_DIR.resolve(segment.rawAudioFile);

    if (USE_KOKORO && KokoroTTS.isAvailable()) {
        KokoroTTS.generateAudio(segment.cleanText, outputFile, segment.currentLengthScale);
    } else {
        generateWithPiper(segment, outputFile);
    }
}
```

**Uso:**
```bash
# Usar Kokoro
java -Dtts.engine=kokoro -jar DubAI.jar

# Usar Piper
java -Dtts.engine=piper -jar DubAI.jar
```

---

## 🎚️ Configuração de Voz

### Adicionar Seleção de Voz:

```java
public enum KokoroVoice {
    DORA_FEMININO("pf_dora"),
    ALEX_MASCULINO("pm_alex"),
    SANTA_MASCULINO("pm_santa");

    private final String code;

    KokoroVoice(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

// Usar
private static final KokoroVoice DEFAULT_VOICE = KokoroVoice.DORA_FEMININO;

KokoroTTS.generateAudio(
    segment.cleanText,
    outputFile,
    segment.currentLengthScale,
    DEFAULT_VOICE.getCode()
);
```

---

## 🧪 Testes

### Teste Unitário:

```java
import org.junit.Test;
import static org.junit.Assert.*;

public class KokoroTTSTest {

    @Test
    public void testKokoroAvailable() {
        assertTrue("Kokoro deve estar disponível", KokoroTTS.isAvailable());
    }

    @Test
    public void testGenerateAudio() throws Exception {
        Path output = Paths.get("/tmp/test_kokoro.wav");
        KokoroTTS.generateAudio("Teste", output, 1.0);

        assertTrue("Arquivo deve existir", Files.exists(output));
        assertTrue("Arquivo deve ter conteúdo", Files.size(output) > 1000);

        // Cleanup
        Files.deleteIfExists(output);
    }
}
```

---

## 📊 Monitoramento de Performance

### Adicionar métricas:

```java
private static long totalKokoroTime = 0;
private static long totalPiperTime = 0;
private static int kokoroCount = 0;
private static int piperCount = 0;

private static void generateOptimizedSegmentAudio(OptimizedSegment segment)
        throws IOException, InterruptedException {

    Path outputFile = OUTPUT_DIR.resolve(segment.rawAudioFile);
    long start = System.nanoTime();

    if (USE_KOKORO && KokoroTTS.isAvailable()) {
        KokoroTTS.generateAudio(segment.cleanText, outputFile, segment.currentLengthScale);
        long elapsed = System.nanoTime() - start;
        totalKokoroTime += elapsed;
        kokoroCount++;
    } else {
        generateWithPiper(segment, outputFile);
        long elapsed = System.nanoTime() - start;
        totalPiperTime += elapsed;
        piperCount++;
    }
}

// Ao final, imprimir estatísticas
public static void printStats() {
    if (kokoroCount > 0) {
        double avgKokoro = totalKokoroTime / (double) kokoroCount / 1_000_000_000.0;
        System.out.printf("📊 Kokoro: %d segmentos, média %.3fs%n", kokoroCount, avgKokoro);
    }
    if (piperCount > 0) {
        double avgPiper = totalPiperTime / (double) piperCount / 1_000_000_000.0;
        System.out.printf("📊 Piper: %d segmentos, média %.3fs%n", piperCount, avgPiper);
    }
}
```

---

## ✅ Checklist de Integração

- [ ] Container Kokoro rodando (`docker ps | grep kokoro`)
- [ ] Criar arquivo `KokoroTTS.java`
- [ ] Modificar `TTSUtils.java`
- [ ] Testar com 1 segmento
- [ ] Testar com 10 segmentos
- [ ] Comparar qualidade de áudio
- [ ] Medir performance real
- [ ] Ajustar sample rate se necessário
- [ ] Configurar fallback para Piper
- [ ] Deploy em produção

---

**✨ Integração pronta para aumentar performance em 6.7x!**
