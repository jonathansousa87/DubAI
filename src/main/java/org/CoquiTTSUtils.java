package org;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * CoquiTTSUtils CORRIGIDO - Silêncios inseridos ENTRE segmentos
 * Seguindo o padrão correto do código Python
 */
public class CoquiTTSUtils {

    private static final Path OUTPUT_DIR = Paths.get("output");
    private static final Path SPEAKER_WAV = OUTPUT_DIR.resolve("vocals.wav");
    private static final String COQUI_MODEL = "tts_models/multilingual/multi-dataset/xtts_v2";
    private static final Path COQUI_EXECUTABLE = Paths.get("/home/kadabra/miniconda3/envs/coqui_tts_env/bin/tts");

    // Configurações de qualidade
    private static final int SAMPLE_RATE = 22050; // Padrão do CoquiTTS
    private static final double SILENCE_BETWEEN_SENTENCES = 0.4; // ENTRE segmentos
    private static final double FINAL_SILENCE = 0.3;             // Apenas no final

    // Controle de concorrência (CRÍTICO: apenas 1 processo por vez)
    private static final Semaphore coquiSemaphore = new Semaphore(1);
    private static final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();

    // Cache para evitar reprocessamento
    private static final Map<String, Path> audioCache = new ConcurrentHashMap<>();

    // Padrões para limpeza de texto
    private static final Pattern CLEANUP_PATTERNS = Pattern.compile(
            "\\[.*?\\]|\\(.*?\\)|<.*?>|[♪♫🎵🎶]|\\.\\.+", Pattern.DOTALL);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    // Configurações de timeout
    private static final int TTS_TIMEOUT_SECONDS = 120;
    private static final int FFMPEG_TIMEOUT_SECONDS = 30;

    private record AudioSegment(String text, double duration, int index, String outputFile) {}

    /**
     * Método principal CORRIGIDO para processar arquivo VTT
     */
    public static void processVttFile(String inputFile) throws IOException, InterruptedException {
        System.out.println("🗣️ Iniciando processamento TTS CoquiTTS CORRIGIDO...");

        if (Files.notExists(OUTPUT_DIR)) {
            Files.createDirectories(OUTPUT_DIR);
        }

        // Valida e prepara áudio de referência
        prepareReferenceAudio();

        // Parse do arquivo VTT
        List<AudioSegment> segments = parseVttFile(inputFile);
        System.out.printf("📝 Encontrados %d segmentos para síntese\n", segments.size());

        if (segments.isEmpty()) {
            throw new IOException("❌ Nenhum segmento válido encontrado no arquivo VTT");
        }

        // Processa segmentos sequencialmente para evitar problemas
        processAudioSegments(segments);

        // CORREÇÃO PRINCIPAL: Cria lista com silêncios ENTRE segmentos
        Path listFile = OUTPUT_DIR.resolve("concat_list.txt");
        createConcatenationListWithInterleavedSilences(segments, listFile);

        // Concatena áudio final
        Path finalOutput = OUTPUT_DIR.resolve("output.wav");
        concatenateAudioFiles(listFile, finalOutput);

        System.out.println("✅ Processamento TTS CoquiTTS corrigido concluído: " + finalOutput);
    }

    /**
     * Prepara e valida o áudio de referência
     */
    private static void prepareReferenceAudio() throws IOException, InterruptedException {
        if (!Files.exists(SPEAKER_WAV)) {
            throw new IOException("❌ Arquivo de referência não encontrado: " + SPEAKER_WAV);
        }

        // Valida qualidade do áudio de referência
        AudioInfo audioInfo = getAudioInfo(SPEAKER_WAV);
        System.out.printf("🎤 Áudio de referência: %.1fs, %dHz, %d canais\n",
                audioInfo.duration, audioInfo.sampleRate, audioInfo.channels);

        if (audioInfo.duration < 3.0) {
            System.err.println("⚠️ Áudio de referência muito curto (< 3s), qualidade pode ser prejudicada");
        }

        // Otimiza áudio de referência se necessário
        if (audioInfo.sampleRate != SAMPLE_RATE || audioInfo.channels != 1) {
            optimizeReferenceAudio();
        }
    }

    /**
     * Otimiza o áudio de referência para melhor qualidade
     */
    private static void optimizeReferenceAudio() throws IOException, InterruptedException {
        Path optimizedSpeaker = OUTPUT_DIR.resolve("speaker_optimized.wav");

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", SPEAKER_WAV.toString(),
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", "1",
                "-af", "highpass=f=80,lowpass=f=8000,volume=1.0",
                optimizedSpeaker.toString()
        );

        executeProcess(pb, "Otimização do áudio de referência", FFMPEG_TIMEOUT_SECONDS);

        // Substitui o original pelo otimizado
        Files.move(optimizedSpeaker, SPEAKER_WAV, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("✅ Áudio de referência otimizado");
    }

    /**
     * Parse do arquivo VTT para extrair segmentos
     */
    private static List<AudioSegment> parseVttFile(String inputFile) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(inputFile));
        List<AudioSegment> segments = new ArrayList<>();

        String currentTimestamp = null;
        StringBuilder currentText = new StringBuilder();
        int segmentIndex = 1;

        for (String line : lines) {
            line = line.trim();

            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                continue;
            }

            // Pula números de sequência
            if (line.matches("^\\d+$")) {
                continue;
            }

            if (line.contains("-->")) {
                // Processa segmento anterior
                if (currentTimestamp != null && currentText.length() > 0) {
                    String cleanText = cleanTextForTTS(currentText.toString());
                    if (!cleanText.isEmpty()) {
                        double duration = calculateDuration(currentTimestamp);
                        String outputFile = String.format("audio_%03d.wav", segmentIndex);

                        segments.add(new AudioSegment(cleanText, duration, segmentIndex, outputFile));
                        segmentIndex++;
                    }
                    currentText.setLength(0);
                }

                currentTimestamp = line;
            } else if (currentTimestamp != null) {
                // Adiciona texto ao segmento atual
                if (currentText.length() > 0) {
                    currentText.append(" ");
                }
                currentText.append(line);
            }
        }

        // Processa último segmento
        if (currentTimestamp != null && currentText.length() > 0) {
            String cleanText = cleanTextForTTS(currentText.toString());
            if (!cleanText.isEmpty()) {
                double duration = calculateDuration(currentTimestamp);
                String outputFile = String.format("audio_%03d.wav", segmentIndex);

                segments.add(new AudioSegment(cleanText, duration, segmentIndex, outputFile));
            }
        }

        return segments;
    }

    /**
     * Limpa texto para síntese de fala
     */
    private static String cleanTextForTTS(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        String cleaned = text;

        // Remove elementos que não devem ser falados
        cleaned = CLEANUP_PATTERNS.matcher(cleaned).replaceAll("");

        // Normaliza caracteres problemáticos de forma mais robusta
        cleaned = cleanProblematicChars(cleaned);

        // Normaliza espaços
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");

        // Remove pausas desnecessárias no início/fim
        cleaned = cleaned.replaceAll("^[.,;:!?\\s]+|[.,;:!?\\s]+$", "");

        // Converte para formato amigável ao TTS
        cleaned = normalizeForSpeech(cleaned);

        return cleaned.trim();
    }

    /**
     * Limpa caracteres problemáticos de forma segura
     */
    private static String cleanProblematicChars(String text) {
        return text
                .replace("“", "\"")  // Aspas curvas esquerda
                .replace("”", "\"")  // Aspas curvas direita
                .replace("'", "'")   // Apóstrofe curvo esquerdo
                .replace("'", "'")   // Apóstrofe curvo direito
                .replace("„", "\"")  // Aspas baixas alemãs
                .replace("‚", "'")   // Vírgula baixa
                .replace("«", "\"")  // Aspas duplas francesa esquerda
                .replace("»", "\"")  // Aspas duplas francesa direita
                .replace("‹", "'")   // Aspas simples francesa esquerda
                .replace("›", "'")   // Aspas simples francesa direita
                .replace("`", "'")   // Acento grave
                .replaceAll("[\\[\\](){}]", ""); // Remove chaves e parênteses
    }

    /**
     * Normaliza texto para fala natural em português
     */
    private static String normalizeForSpeech(String text) {
        String normalized = text;

        // Se o texto ainda estiver em inglês, expande contrações comuns
        if (isTextInEnglish(text)) {
            normalized = normalized.replaceAll("\\bcan't\\b", "cannot");
            normalized = normalized.replaceAll("\\bwon't\\b", "will not");
            normalized = normalized.replaceAll("\\bdoesn't\\b", "does not");
            normalized = normalized.replaceAll("\\bdon't\\b", "do not");
            normalized = normalized.replaceAll("\\bI'm\\b", "I am");
            normalized = normalized.replaceAll("\\byou're\\b", "you are");
            normalized = normalized.replaceAll("\\bit's\\b", "it is");
            normalized = normalized.replaceAll("\\bthat's\\b", "that is");
        } else {
            // Normalização para português brasileiro
            normalized = normalized.replaceAll("\\bvc\\b", "você");
            normalized = normalized.replaceAll("\\bpq\\b", "porque");
            normalized = normalized.replaceAll("\\btb\\b", "também");
            normalized = normalized.replaceAll("\\bmto\\b", "muito");
            normalized = normalized.replaceAll("\\bqdo\\b", "quando");
        }

        // Normaliza números simples (funciona para ambos os idiomas)
        normalized = normalized.replaceAll("\\b1\\b", "um");
        normalized = normalized.replaceAll("\\b2\\b", "dois");
        normalized = normalized.replaceAll("\\b3\\b", "três");
        normalized = normalized.replaceAll("\\b4\\b", "quatro");
        normalized = normalized.replaceAll("\\b5\\b", "cinco");
        normalized = normalized.replaceAll("\\b6\\b", "seis");
        normalized = normalized.replaceAll("\\b7\\b", "sete");
        normalized = normalized.replaceAll("\\b8\\b", "oito");
        normalized = normalized.replaceAll("\\b9\\b", "nove");
        normalized = normalized.replaceAll("\\b10\\b", "dez");

        // Remove múltiplas pontuações
        normalized = normalized.replaceAll("[.!?]{2,}", ".");

        return normalized;
    }

    /**
     * Detecta se o texto está em inglês
     */
    private static boolean isTextInEnglish(String text) {
        if (text == null || text.length() < 10) return false;

        // Palavras comuns em inglês
        String[] englishWords = {"the", "and", "you", "that", "this", "with", "for", "are", "have", "will"};
        String lowerText = text.toLowerCase();

        int englishCount = 0;
        for (String word : englishWords) {
            if (lowerText.contains(" " + word + " ") ||
                    lowerText.startsWith(word + " ") ||
                    lowerText.endsWith(" " + word)) {
                englishCount++;
            }
        }

        return englishCount >= 2;
    }

    /**
     * Calcula duração do timestamp
     */
    private static double calculateDuration(String timestamp) {
        try {
            String[] parts = timestamp.split("\\s*-->\\s*");
            if (parts.length == 2) {
                double start = timestampToSeconds(parts[0]);
                double end = timestampToSeconds(parts[1]);
                return Math.max(0.5, end - start); // Mínimo 0.5s
            }
        } catch (Exception e) {
            System.err.println("⚠️ Erro calculando duração: " + timestamp);
        }
        return 3.0; // Fallback
    }

    /**
     * Converte timestamp para segundos
     */
    private static double timestampToSeconds(String timestamp) {
        String cleanTime = timestamp.replace(",", ".");
        String[] parts = cleanTime.split(":");

        if (parts.length == 3) {
            double h = Double.parseDouble(parts[0]);
            double m = Double.parseDouble(parts[1]);
            double s = Double.parseDouble(parts[2]);
            return h * 3600 + m * 60 + s;
        } else if (parts.length == 2) {
            double m = Double.parseDouble(parts[0]);
            double s = Double.parseDouble(parts[1]);
            return m * 60 + s;
        }

        throw new IllegalArgumentException("Formato de timestamp inválido: " + timestamp);
    }

    /**
     * Processa segmentos de áudio sequencialmente
     */
    private static void processAudioSegments(List<AudioSegment> segments) throws IOException, InterruptedException {
        System.out.println("🎙️ Iniciando síntese sequencial de áudio...");

        for (int i = 0; i < segments.size(); i++) {
            AudioSegment segment = segments.get(i);

            System.out.printf("🔄 Processando segmento %d/%d: \"%.50s...\"\n",
                    i + 1, segments.size(), segment.text);

            // Verifica cache primeiro
            String cacheKey = generateCacheKey(segment.text);
            Path cachedFile = audioCache.get(cacheKey);

            if (cachedFile != null && Files.exists(cachedFile)) {
                Path targetFile = OUTPUT_DIR.resolve(segment.outputFile);
                Files.copy(cachedFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                System.out.printf("📋 Usando cache para segmento %d\n", i + 1);
            } else {
                // Gera novo áudio
                Path audioFile = generateAudioWithRetry(segment);
                audioCache.put(cacheKey, audioFile);
                System.out.printf("✅ Segmento %d gerado com sucesso\n", i + 1);
            }

            // Pausa pequena entre gerações para estabilidade
            Thread.sleep(500);
        }

        System.out.println("✅ Todos os segmentos processados");
    }

    /**
     * Gera áudio com tentativas de retry
     */
    private static Path generateAudioWithRetry(AudioSegment segment) throws IOException, InterruptedException {
        Path outputFile = OUTPUT_DIR.resolve(segment.outputFile);
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                coquiSemaphore.acquire();

                try {
                    generateAudioWithCoqui(segment.text, outputFile);

                    // Valida o arquivo gerado
                    if (validateGeneratedAudio(outputFile)) {
                        return outputFile;
                    } else {
                        throw new IOException("Áudio gerado não passou na validação");
                    }

                } finally {
                    coquiSemaphore.release();
                }

            } catch (Exception e) {
                System.err.printf("⚠️ Tentativa %d/%d falhou para segmento %d: %s\n",
                        attempt, maxRetries, segment.index, e.getMessage());

                if (attempt == maxRetries) {
                    // Última tentativa: gera silêncio como fallback
                    System.err.printf("❌ Gerando silêncio como fallback para segmento %d\n", segment.index);
                    generateSilence(Math.max(1.0, segment.duration), outputFile);
                    return outputFile;
                }

                // Aguarda antes da próxima tentativa
                Thread.sleep(2000 * attempt);
            }
        }

        throw new IOException("Falha após " + maxRetries + " tentativas");
    }

    /**
     * Gera áudio usando CoquiTTS
     */
    private static void generateAudioWithCoqui(String text, Path outputFile) throws IOException, InterruptedException {
        // Limpa possíveis processos anteriores
        cleanupPreviousProcesses();

        // Valida o texto antes de gerar
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("Texto vazio para síntese TTS");
        }

        // Texto muito longo pode causar problemas
        if (text.length() > 500) {
            text = text.substring(0, 497) + "...";
        }

        ProcessBuilder pb = new ProcessBuilder(
                COQUI_EXECUTABLE.toString(),
                "--text", text.trim(),
                "--model_name", COQUI_MODEL,
                "--speaker_wav", SPEAKER_WAV.toString(),
                "--language_idx", "pt", // Força português
                "--out_path", outputFile.toString()
        );

        // Só adiciona CUDA se disponível
        if (isCudaAvailable()) {
            pb.command().add("--use_cuda");
        }

        // Configurações de ambiente para estabilidade
        Map<String, String> env = pb.environment();
        env.put("CUDA_VISIBLE_DEVICES", "0");
        env.put("OMP_NUM_THREADS", "2");
        env.put("CUDA_LAUNCH_BLOCKING", "1");
        env.put("PYTORCH_CUDA_ALLOC_CONF", "max_split_size_mb:512");

        executeProcess(pb, "Síntese TTS", TTS_TIMEOUT_SECONDS);
    }

    /**
     * CORREÇÃO PRINCIPAL: Cria lista de concatenação com silêncios ENTRE segmentos
     * Segue exatamente a lógica do código Python
     */
    private static void createConcatenationListWithInterleavedSilences(List<AudioSegment> segments, Path listFile)
            throws IOException, InterruptedException {

        System.out.println("🔗 Criando lista de concatenação com silêncios ENTRE segmentos...");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(listFile))) {
            for (int i = 0; i < segments.size(); i++) {
                AudioSegment segment = segments.get(i);

                // 1. PRIMEIRO: Adiciona o arquivo de áudio do segmento
                writer.println("file '" + segment.outputFile + "'");
                System.out.printf("📄 Adicionado áudio: %s\n", segment.outputFile);

                // 2. SEGUNDO: Adiciona silêncio ENTRE segmentos (não no último)
                if (i < segments.size() - 1) {
                    String silenceFile = String.format("silence_%03d.wav", i + 1);
                    Path silencePath = OUTPUT_DIR.resolve(silenceFile);

                    try {
                        generateSilence(SILENCE_BETWEEN_SENTENCES, silencePath);
                        writer.println("file '" + silenceFile + "'");
                        System.out.printf("🔇 Adicionado silêncio entre segmentos: %s (%.1fs)\n",
                                silenceFile, SILENCE_BETWEEN_SENTENCES);
                    } catch (IOException | InterruptedException e) {
                        System.err.println("⚠️ Erro gerando silêncio entre segmentos: " + e.getMessage());
                        // Continua sem o silêncio se houver erro
                    }
                }
            }

            // 3. TERCEIRO: Silêncio final (apenas no final de tudo)
            String finalSilenceFile = "silence_final.wav";
            Path finalSilencePath = OUTPUT_DIR.resolve(finalSilenceFile);
            try {
                generateSilence(FINAL_SILENCE, finalSilencePath);
                writer.println("file '" + finalSilenceFile + "'");
                System.out.printf("🔇 Adicionado silêncio final: %s (%.1fs)\n",
                        finalSilenceFile, FINAL_SILENCE);
            } catch (IOException | InterruptedException e) {
                System.err.println("⚠️ Erro gerando silêncio final: " + e.getMessage());
                // Continua sem o silêncio final se houver erro
            }
        }

        System.out.printf("✅ Lista de concatenação criada com %d segmentos\n", segments.size());
    }

    /**
     * Verifica se CUDA está disponível
     */
    private static boolean isCudaAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("nvidia-smi");
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Valida áudio gerado
     */
    private static boolean validateGeneratedAudio(Path audioFile) {
        try {
            if (!Files.exists(audioFile)) {
                return false;
            }

            long fileSize = Files.size(audioFile);
            if (fileSize < 1024) { // Menos que 1KB
                return false;
            }

            AudioInfo info = getAudioInfo(audioFile);
            return info.duration > 0.1 && info.sampleRate > 0;

        } catch (Exception e) {
            System.err.println("⚠️ Erro validando áudio: " + e.getMessage());
            return false;
        }
    }

    /**
     * Obtém informações do áudio
     */
    private static AudioInfo getAudioInfo(Path audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "quiet",
                "-select_streams", "a:0",
                "-show_entries", "stream=duration,sample_rate,channels",
                "-of", "csv=p=0",
                audioFile.toString()
        );

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timeout ao obter informações do áudio");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro ao analisar áudio: " + audioFile);
        }

        String[] parts = output.toString().split(",");
        if (parts.length >= 3) {
            return new AudioInfo(
                    Double.parseDouble(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            );
        }

        throw new IOException("Formato inesperado da saída do ffprobe");
    }

    private record AudioInfo(double duration, int sampleRate, int channels) {}

    /**
     * Gera silêncio
     */
    private static void generateSilence(double duration, Path outputFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi",
                "-i", "anullsrc=r=" + SAMPLE_RATE + ":cl=mono",
                "-t", String.valueOf(duration),
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", "1",
                outputFile.toString()
        );

        executeProcess(pb, "Geração de silêncio", FFMPEG_TIMEOUT_SECONDS);
    }

    /**
     * Concatena arquivos de áudio
     */
    private static void concatenateAudioFiles(Path listFile, Path outputFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                outputFile.toString()
        );

        executeProcess(pb, "Concatenação final", FFMPEG_TIMEOUT_SECONDS);
    }

    /**
     * Executa processo com timeout e logging
     */
    private static void executeProcess(ProcessBuilder pb, String description, int timeoutSeconds)
            throws IOException, InterruptedException {

        System.out.printf("⚡ Executando: %s\n", description);

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Monitor de saída em thread separada
        CompletableFuture<Void> outputMonitor = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Log apenas erros críticos para evitar spam
                    if (line.toLowerCase().contains("error") || line.toLowerCase().contains("fail")) {
                        System.err.println("❌ " + description + ": " + line);
                    }
                }
            } catch (IOException e) {
                // Ignora erros de leitura do stream
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        if (!finished) {
            System.err.printf("⏰ Timeout em %s após %ds\n", description, timeoutSeconds);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IOException("Timeout: " + description);
        }

        outputMonitor.cancel(true);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException(String.format("Falha em %s (código %d)", description, exitCode));
        }

        System.out.printf("✅ %s concluído\n", description);
    }

    /**
     * Limpa processos anteriores que podem estar travados
     */
    private static void cleanupPreviousProcesses() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pkill", "-f", "tts");
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);

            // Força garbage collection
            System.gc();
            Thread.sleep(100);

        } catch (Exception e) {
            // Ignora erros de limpeza
        }
    }

    /**
     * Gera chave de cache
     */
    private static String generateCacheKey(String text) {
        return String.valueOf(text.trim().toLowerCase().hashCode());
    }

    /**
     * Shutdown graceful do executor
     */
    public static void shutdown() {
        System.out.println("🔄 Finalizando CoquiTTS...");

        ttsExecutor.shutdown();
        try {
            if (!ttsExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                ttsExecutor.shutdownNow();
                if (!ttsExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    System.err.println("❌ Executor TTS não finalizou completamente");
                }
            }
        } catch (InterruptedException e) {
            ttsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Limpa cache e força garbage collection
        audioCache.clear();
        cleanupPreviousProcesses();
        System.gc();

        System.out.println("✅ CoquiTTS finalizado");
    }

    /**
     * Estatísticas do processamento
     */
    public static void printStats() {
        System.out.println("\n📊 ESTATÍSTICAS DO TTS:");
        System.out.println("Cache de áudios: " + audioCache.size() + " entradas");
        System.out.println("Sample rate: " + SAMPLE_RATE + " Hz");
        System.out.println("Modelo: " + COQUI_MODEL);
    }
}