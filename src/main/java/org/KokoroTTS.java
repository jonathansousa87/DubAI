package org;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Integração com Kokoro TTS - Text-to-Speech GPU-accelerated
 *
 * Performance: ~6.7x mais rápido que Piper TTS
 * Sample Rate: 24000 Hz (vs 22050 Hz do Piper)
 * API: Compatível com OpenAI TTS
 */
public class KokoroTTS {

    private static final Logger logger = Logger.getLogger(KokoroTTS.class.getName());
    private static final String KOKORO_URL = "http://localhost:8880/v1/audio/speech";
    private static final String VOICES_URL = "http://localhost:8880/v1/audio/voices";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Semaphore para controlar concorrência (4 threads simultâneas, como o Piper)
    private static final Semaphore ttsSemaphore = new Semaphore(4);

    /**
     * Vozes disponíveis em Português Brasileiro
     * TODAS as vozes usam lang_code="p" para garantir pronúncia perfeita
     */
    public enum VozPTBR {
        // Vozes nativas PT-BR
        DORA_FEMININO("pf_dora", "Dora (Feminino - Nativa)", true),
        ALEX_MASCULINO("pm_alex", "Alex (Masculino - Nativo)", true),
        SANTA_MASCULINO("pm_santa", "Santa (Masculino Grave - Nativo)", true),

        // Vozes multilíngues femininas (requerem lang_code="p")
        ALLOY_FEMININO("af_alloy", "Alloy (Feminino - Alta Qualidade)", true),
        AOEDE_FEMININO("af_aoede", "Aoede (Feminino - Suave)", true),
        BELLA_FEMININO("af_bella", "Bella (Feminino - Elegante)", true),
        HEART_FEMININO("af_heart", "Heart (Feminino - Calorosa)", true),
        JESSICA_FEMININO("af_jessica", "Jessica (Feminino - Natural)", true),
        KORE_FEMININO("af_kore", "Kore (Feminino - Jovem)", true),
        NICOLE_FEMININO("af_nicole", "Nicole (Feminino - Profissional)", true),
        NOVA_FEMININO("af_nova", "Nova (Feminino - Moderna)", true),
        RIVER_FEMININO("af_river", "River (Feminino - Calma)", true),
        SARAH_FEMININO("af_sarah", "Sarah (Feminino - Amigável)", true),
        SKY_FEMININO("af_sky", "Sky (Feminino - Clara)", true),

        // Vozes multilíngues masculinas (requerem lang_code="p")
        ADAM_MASCULINO("am_adam", "Adam (Masculino - Profundo)", true),
        ECHO_MASCULINO("am_echo", "Echo (Masculino - Ressonante)", true),
        ERIC_MASCULINO("am_eric", "Eric (Masculino - Suave)", true),
        FENRIR_MASCULINO("am_fenrir", "Fenrir (Masculino - Forte)", true),
        LIAM_MASCULINO("am_liam", "Liam (Masculino - Natural)", true),
        MICHAEL_MASCULINO("am_michael", "Michael (Masculino - Confiante)", true),
        ONYX_MASCULINO("am_onyx", "Onyx (Masculino - Grave)", true),
        PUCK_MASCULINO("am_puck", "Puck (Masculino - Animado)", true);

        private final String code;
        private final String displayName;
        private final boolean requiresLangCode;

        VozPTBR(String code, String displayName, boolean requiresLangCode) {
            this.code = code;
            this.displayName = displayName;
            this.requiresLangCode = requiresLangCode;
        }

        public String getCode() {
            return code;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean requiresLangCode() {
            return requiresLangCode;
        }

        public static VozPTBR fromCode(String code) {
            for (VozPTBR voz : values()) {
                if (voz.code.equals(code)) {
                    return voz;
                }
            }
            return HEART_FEMININO; // Padrão - voz de alta qualidade
        }
    }

    /**
     * Gera áudio usando Kokoro TTS com voz única
     *
     * @param text Texto para sintetizar
     * @param outputFile Arquivo de saída
     * @param lengthScale Escala de duração (compatível com Piper: menor = mais rápido)
     * @param voice Voz PT-BR
     * @throws IOException Se falhar na geração
     * @throws InterruptedException Se thread for interrompida
     */
    public static void generateAudio(String text, Path outputFile, double lengthScale, VozPTBR voice)
            throws IOException, InterruptedException {
        generateAudioWithVoices(text, outputFile, lengthScale, voice.getCode(), voice.requiresLangCode());
    }

    /**
     * Gera áudio usando Kokoro TTS com múltiplas vozes combinadas
     *
     * @param text Texto para sintetizar
     * @param outputFile Arquivo de saída
     * @param lengthScale Escala de duração
     * @param voices Array de vozes para combinar (ex: [HEART_FEMININO, ADAM_MASCULINO])
     * @throws IOException Se falhar na geração
     * @throws InterruptedException Se thread for interrompida
     */
    public static void generateAudioWithCombinedVoices(String text, Path outputFile, double lengthScale, VozPTBR... voices)
            throws IOException, InterruptedException {

        if (voices == null || voices.length == 0) {
            throw new IllegalArgumentException("É necessário fornecer pelo menos uma voz");
        }

        if (voices.length == 1) {
            // Apenas uma voz, usar método normal
            generateAudio(text, outputFile, lengthScale, voices[0]);
            return;
        }

        // Combinar vozes com + (ex: "af_heart+am_adam")
        String combinedVoice = String.join("+",
            java.util.Arrays.stream(voices)
                .map(VozPTBR::getCode)
                .toArray(String[]::new)
        );

        // Todas as vozes combinadas requerem lang_code
        generateAudioWithVoices(text, outputFile, lengthScale, combinedVoice, true);
    }

    /**
     * Método interno para gerar áudio com qualquer nome de voz
     */
    private static void generateAudioWithVoices(String text, Path outputFile, double lengthScale,
                                                 String voiceCode, boolean requiresLangCode)
            throws IOException, InterruptedException {

        ttsSemaphore.acquire();
        try {
            long startTime = System.nanoTime();

            // Remover marcadores de diarização [SPEAKER_XX]: antes de sintetizar
            text = text.replaceAll("\\[SPEAKER_\\d+\\]:\\s*", "");

            // Converter lengthScale do Piper para speed do Kokoro (inverso)
            // Piper: lengthScale < 1.0 = mais rápido | lengthScale > 1.0 = mais lento
            // Kokoro: speed > 1.0 = mais rápido | speed < 1.0 = mais lento
            double speed = 1.0 / Math.max(0.5, Math.min(2.0, lengthScale));

            // Criar JSON request com lang_code e normalization_options
            String langCodeParam = requiresLangCode ? "\"lang_code\": \"p\"," : "";

            String requestBody = String.format(Locale.US, """
                {
                    "model": "kokoro",
                    "input": "%s",
                    "voice": "%s",
                    %s
                    "response_format": "wav",
                    "speed": %.2f,
                    "stream": false,
                    "normalization_options": {
                        "normalize": true,
                        "unit_normalization": false,
                        "url_normalization": true,
                        "email_normalization": true,
                        "optional_pluralization_normalization": true
                    }
                }
                """,
                escapeJson(text),
                voiceCode,
                langCodeParam,
                speed
            );

            logger.fine(String.format("🎤 Kokoro TTS: voz=%s, speed=%.2f, lengthScale=%.2f",
                voiceCode, speed, lengthScale));

            // Criar HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(KOKORO_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // Enviar request e salvar resposta diretamente no arquivo
            HttpResponse<Path> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofFile(outputFile)
            );

            if (response.statusCode() != 200) {
                throw new IOException("Kokoro TTS falhou com HTTP " + response.statusCode());
            }

            // Verificar se arquivo foi gerado corretamente
            if (!Files.exists(outputFile) || Files.size(outputFile) < 256) {
                throw new IOException("Kokoro não gerou áudio válido para: " + text.substring(0, Math.min(50, text.length())));
            }

            long elapsed = System.nanoTime() - startTime;
            logger.fine(String.format("✅ Kokoro: %s gerado em %.3fs",
                    outputFile.getFileName(), elapsed / 1_000_000_000.0));

        } catch (IOException | InterruptedException e) {
            // Deletar arquivo parcial em caso de erro
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {}
            throw e;
        } finally {
            ttsSemaphore.release();
        }
    }

    /**
     * Sobrecarga com voz padrão (Heart - feminina alta qualidade)
     */
    public static void generateAudio(String text, Path outputFile, double lengthScale)
            throws IOException, InterruptedException {
        generateAudio(text, outputFile, lengthScale, VozPTBR.HEART_FEMININO);
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
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    /**
     * Testa se Kokoro está disponível e respondendo
     *
     * @return true se Kokoro está rodando, false caso contrário
     */
    public static boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VOICES_URL))
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

    /**
     * Lista todas as vozes PT-BR disponíveis no servidor
     *
     * @return Lista de códigos de voz PT-BR
     */
    public static List<String> listVoicesPTBR() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VOICES_URL))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException("Falha ao listar vozes: HTTP " + response.statusCode());
        }

        // Parse simples do JSON (buscar padrão pf_ ou pm_)
        String body = response.body();
        return java.util.Arrays.stream(body.split("\""))
                .filter(s -> s.matches("p[fm]_[a-z]+"))
                .collect(Collectors.toList());
    }

    /**
     * Converte áudio de 24kHz (Kokoro) para 22.05kHz (compatibilidade com Piper)
     *
     * NOTA: Geralmente não é necessário se toda a pipeline usar Kokoro
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
                "-acodec", "pcm_s16le",
                targetOutput.toString()
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Consumir output para evitar bloqueio
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            reader.lines().forEach(line -> logger.finest("ffmpeg: " + line));
        }

        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timeout na conversão de sample rate");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Falha na conversão de sample rate");
        }

        logger.fine("✅ Convertido de 24kHz para 22.05kHz: " + targetOutput.getFileName());
    }

    /**
     * Obtém informações sobre o servidor Kokoro
     */
    public static String getServerInfo() {
        if (!isAvailable()) {
            return "❌ Kokoro TTS não está disponível (http://localhost:8880)";
        }

        try {
            List<String> voices = listVoicesPTBR();
            return String.format("✅ Kokoro TTS disponível | %d vozes PT-BR", voices.size());
        } catch (Exception e) {
            return "⚠️ Kokoro disponível mas falhou ao listar vozes";
        }
    }
}
