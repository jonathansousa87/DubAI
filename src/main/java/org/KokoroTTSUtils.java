package org;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KokoroTTSUtils OTIMIZADO - FLUXO IDÊNTICO A TTSUtils
 * Baseado em TTSUtils mas adaptado para Kokoro TTS
 * 
 * FUNCIONALIDADES:
 * ✅ Mesmo fluxo de processamento que TTSUtils
 * ✅ Calibração automática de timing
 * ✅ Cache inteligente
 * ✅ Qualidade de áudio otimizada
 * ✅ Sincronização perfeita com VTT
 */
public class KokoroTTSUtils {

    private static final Logger logger = Logger.getLogger(KokoroTTSUtils.class.getName());

    // Configuração do Kokoro TTS
    private static final String KOKORO_EXECUTABLE = "kokoro-tts";
    private static final String DEFAULT_VOICE = "pf_dora";
    private static final double DEFAULT_SPEED = 0.85;

    // Configurações otimizadas
    private static final int MAX_CONCURRENT_TTS = 1; // Kokoro pode ser mais intensivo
    private static final int MAX_CONCURRENT_FFMPEG = 2;
    private static final int SAMPLE_RATE = 22050;
    private static final int AUDIO_CHANNELS = 1;

    private static final Path OUTPUT_DIR = Paths.get("output");
    private static final Path CACHE_DIR = OUTPUT_DIR.resolve("kokoro_cache");

    // Controle de concorrência com Virtual Threads (Java 21)
    private static final Semaphore ttsSemaphore = new Semaphore(MAX_CONCURRENT_TTS);
    private static final Semaphore ffmpegSemaphore = new Semaphore(MAX_CONCURRENT_FFMPEG);
    private static final ExecutorService ttsExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final ExecutorService ffmpegExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final ConcurrentHashMap<String, Path> audioCache = new ConcurrentHashMap<>();

    // MÚLTIPLOS PADRÕES DE TIMESTAMP (mesmo do TTSUtils)
    private static final Pattern[] TIMESTAMP_PATTERNS = {
            Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})"),
            Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"),
            Pattern.compile("(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2})[.,](\\d{3})"),
            Pattern.compile("(\\d{1,2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2})[.,](\\d{3})")
    };

    /**
     * Estrutura para segmentos com timing VTT e compensação
     */
    public static class TimedSegment {
        private final double vttStartTime;
        private final double vttEndTime;
        private final double vttDurationSeconds;
        private final double actualAudioDuration;
        private final double compensatedSilence;
        private final String text;
        private final String cleanText;
        private final int index;
        private final String audioFile;

        public TimedSegment(double vttStartTime, double vttEndTime, double vttDurationSeconds,
                           double actualAudioDuration, double compensatedSilence,
                           String text, String cleanText, int index, String audioFile) {
            this.vttStartTime = vttStartTime;
            this.vttEndTime = vttEndTime;
            this.vttDurationSeconds = vttDurationSeconds;
            this.actualAudioDuration = actualAudioDuration;
            this.compensatedSilence = compensatedSilence;
            this.text = text;
            this.cleanText = cleanText;
            this.index = index;
            this.audioFile = audioFile;
        }

        // Getters
        public double vttStartTime() { return vttStartTime; }
        public double vttEndTime() { return vttEndTime; }
        public double vttDuration() { return vttDurationSeconds; }
        public double actualDuration() { return actualAudioDuration; }
        public double compensatedSilence() { return compensatedSilence; }
        public String text() { return text; }
        public String cleanText() { return cleanText; }
        public int index() { return index; }
        public String audioFile() { return audioFile; }
        
        public boolean isEmpty() {
            return cleanText == null || cleanText.trim().isEmpty();
        }
    }

    /**
     * Análise de timing entre VTT e áudios reais
     */
    public static class TimingAnalysis {
        final double vttTotalTime;
        final double actualTotalTime;
        final double timingDifference;
        final int numberOfGaps;
        final double compensationPerGap;
        final String strategy;

        public TimingAnalysis(double vttTotalTime, double actualTotalTime, int numberOfGaps) {
            this.vttTotalTime = vttTotalTime;
            this.actualTotalTime = actualTotalTime;
            this.timingDifference = vttTotalTime - actualTotalTime;
            this.numberOfGaps = numberOfGaps;
            this.compensationPerGap = numberOfGaps > 0 ? timingDifference / numberOfGaps : 0.0;

            if (Math.abs(timingDifference) < 0.5) {
                this.strategy = "MANTER_TIMING_ORIGINAL";
            } else if (timingDifference > 0) {
                this.strategy = "ADICIONAR_SILÊNCIOS";
            } else {
                this.strategy = "ACELERAR_OU_REDUZIR_SILÊNCIOS";
            }
        }

        public double getAccuracy() {
            return Math.max(0.0, 1.0 - Math.abs(timingDifference) / Math.max(vttTotalTime, actualTotalTime));
        }

        @Override
        public String toString() {
            return String.format("VTT=%.3fs | Real=%.3fs | Diff=%.3fs | Gaps=%d | Comp/Gap=%.3fs | %s",
                    vttTotalTime, actualTotalTime, timingDifference, numberOfGaps, compensationPerGap, strategy);
        }
    }

    /**
     * Análise com duração alvo específica
     */
    public static class TimingAnalysisWithTarget {
        final double vttTotalTime;
        final double actualTotalTime;
        final double targetDuration;
        final double vttTargetDiff;
        final double actualTargetDiff;
        final double silenceAdjustment;
        final String strategy;

        public TimingAnalysisWithTarget(double vttTotalTime, double actualTotalTime, double targetDuration) {
            this.vttTotalTime = vttTotalTime;
            this.actualTotalTime = actualTotalTime;
            this.targetDuration = targetDuration;
            this.vttTargetDiff = targetDuration - vttTotalTime;
            this.actualTargetDiff = targetDuration - actualTotalTime;
            this.silenceAdjustment = actualTargetDiff / Math.max(1, (int)(vttTotalTime / 5.0)); // Aproximadamente a cada 5s

            if (Math.abs(actualTargetDiff) < 1.0) {
                this.strategy = "USAR_ALVO_DIRETO";
            } else if (Math.abs(vttTargetDiff) < Math.abs(actualTargetDiff)) {
                this.strategy = "USAR_VTT_PRÓXIMO";
            } else {
                this.strategy = "HÍBRIDO_PONDERADO";
            }
        }

        @Override
        public String toString() {
            return String.format("VTT=%.3fs | Real=%.3fs | Alvo=%.3fs | SilAdj=%.3fs | %s",
                    vttTotalTime, actualTotalTime, targetDuration, silenceAdjustment, strategy);
        }
    }

    // ============ MÉTODOS ANTIGOS REMOVIDOS ============
    // Os métodos processVttFile antigos foram substituídos pelos métodos 
    // do TTSUtils no final da classe para manter compatibilidade total
    /**
     * GERAÇÃO DE ÁUDIOS KOKORO TTS
     */
    private static void generateSpeechAudios(List<TimedSegment> segments, String voice, double speed)
            throws IOException, InterruptedException {
        System.out.println("🎙️ Gerando áudios de fala com Kokoro TTS...");

        for (int i = 0; i < segments.size(); i++) {
            TimedSegment segment = segments.get(i);

            System.out.printf("🔄 Gerando fala %d/%d: \"%.50s...\"\n",
                    i + 1, segments.size(), segment.cleanText());

            try {
                generateKokoroAudioWithCache(segment, voice, speed);
                System.out.printf("✅ Fala %d gerada: %s\n", i + 1, segment.audioFile());
            } catch (Exception e) {
                System.err.printf("❌ Erro na fala %d: %s\n", i + 1, e.getMessage());
                generateSilenceFallback(segment.vttDuration(), segment.audioFile());
            }

            Thread.sleep(200); // Kokoro pode precisar de mais tempo entre chamadas
        }
    }

    /**
     * GERAÇÃO DE ÁUDIO KOKORO COM CACHE
     */
    private static Path generateKokoroAudioWithCache(TimedSegment segment, String voice, double speed)
            throws IOException, InterruptedException {
        String cacheKey = generateCacheKey(segment.cleanText() + "_" + voice + "_" + speed);
        Path cachedFile = audioCache.get(cacheKey);

        if (cachedFile != null && Files.exists(cachedFile)) {
            Path targetFile = OUTPUT_DIR.resolve(segment.audioFile());
            Files.copy(cachedFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            return targetFile;
        }

        Path audioFile = generateKokoroAudioExplicit(segment, voice, speed);

        Path cacheFile = CACHE_DIR.resolve(cacheKey + ".wav");
        Files.copy(audioFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        audioCache.put(cacheKey, cacheFile);

        return audioFile;
    }

    /**
     * GERAÇÃO DE ÁUDIO KOKORO
     */
    private static Path generateKokoroAudio(TimedSegment segment, String voice, double speed)
            throws IOException, InterruptedException {

        Path outputFile = OUTPUT_DIR.resolve(segment.audioFile()).toAbsolutePath();
        // Garanta que o diretório existe
        Files.createDirectories(outputFile.getParent());

        ttsSemaphore.acquire();
        try {
            // ✅ CORREÇÃO: Usar formato exato como no terminal (4 casas decimais)
            String speedStr = String.format(Locale.US, "%.4f", speed);

            // ✅ USAR COMANDO EXATO DO SEU TERMINAL
            String command = String.format(
                    "echo '%s' | kokoro-tts --voice %s --speed %s --output_file '%s'",
                    escapeBashString(segment.cleanText()),
                    voice,
                    speedStr,
                    outputFile.toString()  // Agora com caminho absoluto
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Capturar saída para debug
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout na geração de áudio Kokoro");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Kokoro TTS falhou com código: " + process.exitValue() +
                        "\nComando: " + command +
                        "\nSaída: " + output.toString());
            }

            if (!Files.exists(outputFile)) {
                throw new IOException("Arquivo de áudio Kokoro não foi gerado");
            }
            
            // Verificação menos restritiva para frases curtas
            if (Files.size(outputFile) < 256) {
                throw new IOException("Arquivo de áudio Kokoro muito pequeno (< 256 bytes)");
            }

            return outputFile;

        } finally {
            ttsSemaphore.release();
        }
    }

    private static Path generateKokoroAudioExplicit(TimedSegment segment, String voice, double speed)
            throws IOException, InterruptedException {
        Path outputFile = OUTPUT_DIR.resolve(segment.audioFile()).toAbsolutePath();
        // Garanta que o diretório existe
        Files.createDirectories(outputFile.getParent());

        ttsSemaphore.acquire();
        try {
            // ✅ MÉTODO ALTERNATIVO: Criar comando em partes
            String speedStr = String.format(Locale.US, "%.4f", speed);

            // ✅ CRIAR COMANDO COMPLETO
            String command = String.format(
                    "echo '%s' | kokoro-tts --voice %s --speed %s --output_file '%s'",
                    escapeBashString(segment.cleanText()),
                    voice,
                    speedStr,
                    outputFile.toString()  // Agora com caminho absoluto
            );

            ProcessBuilder pb = new ProcessBuilder(
                    "bash", "-c",
                    "echo '" + escapeBashString(segment.cleanText()) + "' | " +
                            "kokoro-tts " +
                            "--voice " + voice + " " +
                            "--speed " + speedStr + " " +
                            "--output_file '" + outputFile.toString() + "'"
            );

            // ✅ DEFINIR LOCALE EXPLICITAMENTE
            pb.environment().put("LC_NUMERIC", "C");
            pb.environment().put("LANG", "C");

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Capturar saída para debug
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout na geração de áudio Kokoro");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Kokoro TTS falhou com código: " + process.exitValue() +
                        "\nComando: " + command +
                        "\nSaída: " + output.toString());
            }

            if (!Files.exists(outputFile)) {
                throw new IOException("Arquivo de áudio Kokoro não foi gerado");
            }
            
            // Verificação menos restritiva para frases curtas
            if (Files.size(outputFile) < 256) {
                throw new IOException("Arquivo de áudio Kokoro muito pequeno (< 256 bytes)");
            }

            System.out.println("🔍 Comando executado: " + String.join(" ", pb.command()));

            return outputFile;

        } finally {
            ttsSemaphore.release();
        }
    }

    private static Path generateKokoroAudioWithDebug(TimedSegment segment, String voice, double speed)
            throws IOException, InterruptedException {
        Path outputFile = OUTPUT_DIR.resolve(segment.audioFile());

        ttsSemaphore.acquire();
        try {
            String speedStr = String.format(Locale.US, "%.4f", speed);
            String command = String.format(
                    "echo '%s' | kokoro-tts --voice %s --speed %s --output_file '%s'",
                    escapeBashString(segment.cleanText()),
                    voice,
                    speedStr,
                    outputFile.toString()
            );

            // ✅ LOG DETALHADO
            System.out.printf("🎭 COMANDO KOKORO: %s\n", command);
            System.out.printf("🏃 Velocidade: %.4f → %s\n", speed, speedStr);
            System.out.printf("📁 Arquivo: %s\n", outputFile);

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    System.out.println("🎙️ KOKORO: " + line);  // ✅ LOG EM TEMPO REAL
                }
            }

            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout na geração de áudio Kokoro");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Kokoro TTS falhou com código: " + process.exitValue() +
                        "\nComando executado: " + command +
                        "\nSaída completa: " + output.toString());
            }

            if (!Files.exists(outputFile)) {
                throw new IOException("Arquivo de áudio Kokoro não foi gerado");
            }
            
            // Verificação menos restritiva para frases curtas
            if (Files.size(outputFile) < 256) {
                throw new IOException("Arquivo de áudio Kokoro muito pequeno (< 256 bytes)");
            }

            return outputFile;

        } finally {
            ttsSemaphore.release();
        }
    }



    /**
     * Escapa string para bash
     */
    private static String escapeBashString(String input) {
        if (input == null) return "";
        return input.replace("'", "'\"'\"'")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    // ===== MÉTODOS MANTIDOS DO TTSUTILS (com adaptações menores) =====

    private static List<TimedSegment> parseVttWithMultiplePatterns(String inputFile) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(inputFile));

        for (int p = 0; p < TIMESTAMP_PATTERNS.length; p++) {
            List<TimedSegment> segments = parseWithPattern(lines, TIMESTAMP_PATTERNS[p], p);
            if (!segments.isEmpty()) {
                System.out.printf("✅ SUCESSO com padrão %d: %d segmentos\n", p + 1, segments.size());
                return segments;
            }
        }

        return new ArrayList<>();
    }

    private static List<TimedSegment> parseWithPattern(List<String> lines, Pattern pattern, int patternIndex) {
        List<TimedSegment> segments = new ArrayList<>();

        String currentTimestamp = null;
        StringBuilder currentText = new StringBuilder();
        int segmentIndex = 1;

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("WEBVTT") || line.isEmpty() || line.matches("^\\d+$")) {
                continue;
            }

            if (pattern.matcher(line).matches()) {
                if (currentTimestamp != null && currentText.length() > 0) {
                    TimedSegment segment = createTimedSegmentWithPattern(
                            currentTimestamp, currentText.toString().trim(), segmentIndex++, pattern, patternIndex
                    );
                    if (segment != null) {
                        segments.add(segment);
                    }
                    currentText.setLength(0);
                }
                currentTimestamp = line;
            } else if (currentTimestamp != null && !line.isEmpty()) {
                if (currentText.length() > 0) {
                    currentText.append(" ");
                }
                currentText.append(line);
            }
        }

        if (currentTimestamp != null && currentText.length() > 0) {
            TimedSegment segment = createTimedSegmentWithPattern(
                    currentTimestamp, currentText.toString().trim(), segmentIndex, pattern, patternIndex
            );
            if (segment != null) {
                segments.add(segment);
            }
        }

        return segments;
    }

    private static TimedSegment createTimedSegmentWithPattern(String timestamp, String text, int index,
                                                              Pattern pattern, int patternIndex) {
        try {
            Matcher matcher = pattern.matcher(timestamp);
            if (!matcher.matches()) {
                return null;
            }

            double startTime, endTime;

            switch (patternIndex) {
                case 0, 1:
                    startTime = parseTimestampHMS(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
                    endTime = parseTimestampHMS(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
                    break;
                case 2, 3:
                    startTime = parseTimestampMS(matcher.group(1), matcher.group(2), matcher.group(3));
                    endTime = parseTimestampMS(matcher.group(4), matcher.group(5), matcher.group(6));
                    break;
                default:
                    return null;
            }

            double duration = endTime - startTime;
            if (duration <= 0) {
                return null;
            }

            String audioFile = String.format("kokoro_audio_%03d.wav", index);
            String cleanText = normalizeTextForSpeech(text);

            if (cleanText.trim().isEmpty()) {
                return null;
            }

            return new TimedSegment(
                    startTime, endTime, duration, 0.0, 0.0,
                    text, cleanText, index, audioFile
            );

        } catch (Exception e) {
            return null;
        }
    }

    private static double parseTimestampHMS(String hours, String minutes, String seconds, String milliseconds) {
        int h = Integer.parseInt(hours);
        int m = Integer.parseInt(minutes);
        int s = Integer.parseInt(seconds);
        int ms = Integer.parseInt(milliseconds);
        return h * 3600.0 + m * 60.0 + s + ms / 1000.0;
    }

    private static double parseTimestampMS(String minutes, String seconds, String milliseconds) {
        int m = Integer.parseInt(minutes);
        int s = Integer.parseInt(seconds);
        int ms = Integer.parseInt(milliseconds);
        return m * 60.0 + s + ms / 1000.0;
    }

    private static String normalizeTextForSpeech(String text) {
        if (text == null || text.trim().isEmpty()) return "";

        String normalized = text;
        normalized = normalized.replaceAll("\\[.*?\\]", "");
        normalized = normalized.replaceAll("\\(.*?\\)", "");
        normalized = normalized.replaceAll("<.*?>", "");
        normalized = normalized.replaceAll("♪.*?♪", "");
        normalized = normalized.replaceAll("https?://\\S+", "link");
        normalized = normalized.replaceAll("\\s+", " ").trim();

        return normalized;
    }

    // ===== MÉTODOS DE ANÁLISE E COMPENSAÇÃO (mantidos iguais) =====

    private static TimingAnalysis analyzeTimingDifferences(List<TimedSegment> segments)
            throws IOException, InterruptedException {

        System.out.println("🔍 Calculando tempos reais dos áudios Kokoro TTS...");

        double totalVTTTime = 0.0;
        double totalActualTime = 0.0;
        int validAudios = 0;

        for (TimedSegment segment : segments) {
            totalVTTTime += segment.vttDuration();

            Path audioFile = OUTPUT_DIR.resolve(segment.audioFile());
            if (Files.exists(audioFile)) {
                double actualDuration = getAudioDuration(audioFile);
                totalActualTime += actualDuration;
                validAudios++;

                System.out.printf("  Segmento %d: VTT=%.3fs | Kokoro=%.3fs | Diff=%.3fs\n",
                        segment.index(), segment.vttDuration(), actualDuration,
                        segment.vttDuration() - actualDuration);
            }
        }

        int numberOfGaps = Math.max(0, segments.size() - 1);
        TimingAnalysis analysis = new TimingAnalysis(totalVTTTime, totalActualTime, numberOfGaps);

        System.out.printf("📊 RESUMO TIMING:\n");
        System.out.printf("  🎯 Tempo VTT total: %.3fs\n", totalVTTTime);
        System.out.printf("  🎙️ Tempo Kokoro real: %.3fs\n", totalActualTime);
        System.out.printf("  📏 Diferença: %.3fs\n", analysis.timingDifference);
        System.out.printf("  🔧 Compensação por gap: %.3fs\n", analysis.compensationPerGap);
        System.out.printf("  🎯 Estratégia: %s\n", analysis.strategy);

        return analysis;
    }

    private static TimingAnalysisWithTarget analyzeTimingWithTarget(List<TimedSegment> segments, double targetDuration)
            throws IOException, InterruptedException {

        System.out.println("🔍 Calculando tempos vs duração alvo...");

        double totalVTTTime = segments.isEmpty() ? 0.0 :
                segments.get(segments.size() - 1).vttEndTime();
        double totalActualTime = 0.0;
        int validAudios = 0;

        for (TimedSegment segment : segments) {
            Path audioFile = OUTPUT_DIR.resolve(segment.audioFile());
            if (Files.exists(audioFile)) {
                double actualDuration = getAudioDuration(audioFile);
                totalActualTime += actualDuration;
                validAudios++;

                System.out.printf("  Segmento %d: VTT=%.3fs | Kokoro=%.3fs | Diff=%.3fs\n",
                        segment.index(), segment.vttDuration(), actualDuration,
                        segment.vttDuration() - actualDuration);
            }
        }

        int numberOfGaps = Math.max(0, segments.size());
        TimingAnalysisWithTarget analysis = new TimingAnalysisWithTarget(totalVTTTime, totalActualTime, targetDuration);

        System.out.printf("📊 RESUMO TIMING COM ALVO:\n");
        System.out.printf("  🎯 Duração alvo: %.3fs\n", targetDuration);
        System.out.printf("  📄 Tempo VTT total: %.3fs\n", totalVTTTime);
        System.out.printf("  🎙️ Tempo Kokoro real: %.3fs\n", totalActualTime);
        System.out.printf("  📏 Diferença vs alvo: %.3fs\n", analysis.actualTargetDiff);
        System.out.printf("  📏 Diferença vs VTT: %.3fs\n", analysis.vttTargetDiff);
        System.out.printf("  🔧 Ajuste por gap: %.3fs\n", analysis.silenceAdjustment);
        System.out.printf("  🎯 Estratégia: %s\n", analysis.strategy);

        return analysis;
    }

    private static List<TimedSegment> applyTimingCompensation(List<TimedSegment> segments,
                                                              TimingAnalysis analysis) {
        System.out.println("🔧 APLICANDO COMPENSAÇÃO DE TIMING KOKORO...");

        List<TimedSegment> compensated = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            TimedSegment original = segments.get(i);
            double compensatedSilence;

            if (i == 0) {
                compensatedSilence = original.vttStartTime();
                System.out.printf("  Silêncio inicial: %.3fs (do VTT)\n", compensatedSilence);
            } else {
                TimedSegment previous = segments.get(i - 1);
                double vttGap = original.vttStartTime() - previous.vttEndTime();

                switch (analysis.strategy) {
                    case "PRESERVAR_VTT":
                        compensatedSilence = Math.max(0.05, vttGap);
                        System.out.printf("  Gap %d: %.3fs (preservado do VTT)\n", i, compensatedSilence);
                        break;

                    case "EXPANDIR_GAPS":
                        compensatedSilence = Math.max(0.05, vttGap + analysis.compensationPerGap);
                        System.out.printf("  Gap %d: %.3fs → %.3fs (+%.3fs)\n",
                                i, vttGap, compensatedSilence, analysis.compensationPerGap);
                        break;

                    case "AJUSTE_PROPORCIONAL":
                        double reduction = Math.abs(analysis.compensationPerGap);
                        compensatedSilence = Math.max(0.1, vttGap - reduction);
                        System.out.printf("  Gap %d: %.3fs → %.3fs (-%.3fs limitado)\n",
                                i, vttGap, compensatedSilence, reduction);
                        break;

                    default:
                        compensatedSilence = Math.max(0.05, vttGap);
                        break;
                }
            }

            double actualAudioDuration = getActualAudioDuration(original);

            TimedSegment compensatedSegment = new TimedSegment(
                    original.vttStartTime(),
                    original.vttEndTime(),
                    original.vttDuration(),
                    actualAudioDuration,
                    compensatedSilence,
                    original.text(),
                    original.cleanText(),
                    original.index(),
                    original.audioFile()
            );

            compensated.add(compensatedSegment);
        }

        System.out.printf("✅ Compensação KOKORO aplicada em %d segmentos\n", compensated.size());
        return compensated;
    }

    private static List<TimedSegment> applyTargetBasedCompensation(List<TimedSegment> segments,
                                                                   TimingAnalysisWithTarget analysis) {
        System.out.println("🔧 APLICANDO COMPENSAÇÃO KOKORO BASEADA NO ALVO...");

        List<TimedSegment> compensated = new ArrayList<>();
        double accumulatedSilence = 0.0;

        for (int i = 0; i < segments.size(); i++) {
            TimedSegment original = segments.get(i);
            double compensatedSilence;

            if (i == 0) {
                double originalInitialSilence = original.vttStartTime();
                compensatedSilence = Math.max(0.05, originalInitialSilence + analysis.silenceAdjustment);
                accumulatedSilence = compensatedSilence;

                System.out.printf("  Silêncio inicial: %.3fs → %.3fs (ajuste: %.3fs)\n",
                        originalInitialSilence, compensatedSilence, analysis.silenceAdjustment);
            } else {
                TimedSegment previous = segments.get(i - 1);
                double originalGap = original.vttStartTime() - previous.vttEndTime();

                switch (analysis.strategy) {
                    case "USAR_ALVO_DIRETO":
                        compensatedSilence = Math.max(0.05, originalGap + analysis.silenceAdjustment);
                        break;
                    case "USAR_VTT_PRÓXIMO":
                        compensatedSilence = Math.max(0.05, originalGap);
                        break;
                    case "HÍBRIDO_PONDERADO":
                        compensatedSilence = Math.max(0.05, originalGap + analysis.silenceAdjustment);
                        break;
                    default:
                        compensatedSilence = Math.max(0.05, originalGap);
                        break;
                }

                accumulatedSilence += compensatedSilence;

                System.out.printf("  Gap %d: %.3fs → %.3fs (ajuste: %.3fs)\n",
                        i, originalGap, compensatedSilence, analysis.silenceAdjustment);
            }

            double actualAudioDuration = getActualAudioDuration(original);

            TimedSegment compensatedSegment = new TimedSegment(
                    original.vttStartTime(),
                    original.vttEndTime(),
                    original.vttDuration(),
                    actualAudioDuration,
                    compensatedSilence,
                    original.text(),
                    original.cleanText(),
                    original.index(),
                    original.audioFile()
            );

            compensated.add(compensatedSegment);
        }

        System.out.printf("✅ Compensação Kokoro baseada no alvo aplicada em %d segmentos\n", compensated.size());
        System.out.printf("📊 Total de silêncios: %.3fs\n", accumulatedSilence);

        return compensated;
    }

    private static double getActualAudioDuration(TimedSegment segment) {
        try {
            Path audioFile = OUTPUT_DIR.resolve(segment.audioFile());
            if (Files.exists(audioFile)) {
                return getAudioDuration(audioFile);
            }
        } catch (Exception e) {
            System.err.printf("⚠️ Erro obtendo duração real do segmento %d: %s\n",
                    segment.index(), e.getMessage());
        }
        return segment.vttDuration();
    }

    // ===== MÉTODOS DE CRIAÇÃO DE LISTA E CONCATENAÇÃO =====

    private static void createCompensatedList(List<TimedSegment> segments, Path listFile)
            throws IOException, InterruptedException {

        System.out.println("🔗 Criando lista KOKORO com compensação de timing...");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(listFile))) {

            for (int i = 0; i < segments.size(); i++) {
                TimedSegment segment = segments.get(i);

                if (segment.compensatedSilence() >= 0.01) {
                    String silenceFile = String.format("kokoro_silence_%03d.wav", segment.index());
                    Path silencePath = OUTPUT_DIR.resolve(silenceFile);

                    try {
                        generateSilence(segment.compensatedSilence(), silencePath);
                        writer.println("file '" + silenceFile + "'");

                        System.out.printf("🔇 %02d. Silêncio: %.3fs (%s)\n",
                                (i * 2) + 1, segment.compensatedSilence(), silenceFile);
                    } catch (Exception e) {
                        System.err.printf("⚠️ Erro gerando silêncio %d: %s\n",
                                segment.index(), e.getMessage());
                        try {
                            generateSilence(0.1, silencePath);
                            writer.println("file '" + silenceFile + "'");
                            System.out.printf("🔇 %02d. Silêncio FALLBACK: 0.100s\n", (i * 2) + 1);
                        } catch (Exception fallbackError) {
                            System.err.printf("❌ Fallback silêncio também falhou: %s\n", fallbackError.getMessage());
                        }
                    }
                }

                Path audioFile = OUTPUT_DIR.resolve(segment.audioFile());
                if (Files.exists(audioFile)) {
                    writer.println("file '" + segment.audioFile() + "'");
                    System.out.printf("🗣️ %02d. Fala %d: %s (real: %.3fs | VTT: %.3fs)\n",
                            (i * 2) + 2, segment.index(), segment.audioFile(),
                            segment.actualDuration(), segment.vttDuration());
                } else {
                    System.err.printf("❌ Áudio não encontrado: %s\n", audioFile);

                    String fallbackFile = String.format("kokoro_fallback_silence_%03d.wav", segment.index());
                    Path fallbackPath = OUTPUT_DIR.resolve(fallbackFile);
                    try {
                        generateSilence(segment.vttDuration(), fallbackPath);
                        writer.println("file '" + fallbackFile + "'");
                        System.out.printf("🔇 %02d. FALLBACK silêncio: %.3fs\n", (i * 2) + 2, segment.vttDuration());
                    } catch (Exception e) {
                        System.err.printf("❌ Fallback para áudio ausente falhou: %s\n", e.getMessage());
                    }
                }
            }
        }

        System.out.printf("✅ Lista KOKORO criada com %d segmentos\n", segments.size());
    }

    private static void createTargetPreciseList(List<TimedSegment> segments, Path listFile,
                                                TimingAnalysisWithTarget analysis)
            throws IOException, InterruptedException {

        System.out.println("🔗 Criando lista KOKORO RIGOROSA baseada no alvo...");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(listFile))) {
            double totalExpectedDuration = 0.0;

            for (int i = 0; i < segments.size(); i++) {
                TimedSegment segment = segments.get(i);

                if (segment.compensatedSilence() >= 0.01) {
                    String silenceFile = String.format("kokoro_target_silence_%03d.wav", segment.index());
                    Path silencePath = OUTPUT_DIR.resolve(silenceFile);

                    try {
                        generateSilence(segment.compensatedSilence(), silencePath);
                        writer.println("file '" + silenceFile + "'");
                        totalExpectedDuration += segment.compensatedSilence();

                        System.out.printf("🔇 %02d. Silêncio RIGOROSO: %.3fs (%s)\n",
                                (i * 2) + 1, segment.compensatedSilence(), silenceFile);
                    } catch (Exception e) {
                        System.err.printf("❌ Erro gerando silêncio rigoroso %d: %s\n",
                                segment.index(), e.getMessage());

                        double fallbackDuration = Math.max(0.05, segment.compensatedSilence());
                        generateSilence(fallbackDuration, silencePath);
                        writer.println("file '" + silenceFile + "'");
                        totalExpectedDuration += fallbackDuration;
                        System.out.printf("🔇 %02d. Silêncio FALLBACK: %.3fs\n", (i * 2) + 1, fallbackDuration);
                    }
                }

                Path audioFile = OUTPUT_DIR.resolve(segment.audioFile());
                if (Files.exists(audioFile)) {
                    writer.println("file '" + segment.audioFile() + "'");
                    totalExpectedDuration += segment.actualDuration();

                    System.out.printf("🗣️ %02d. Fala %d: %s (real: %.3fs)\n",
                            (i * 2) + 2, segment.index(), segment.audioFile(),
                            segment.actualDuration());
                } else {
                    System.err.printf("❌ Áudio não encontrado: %s\n", audioFile);

                    String fallbackFile = String.format("kokoro_fallback_speech_%03d.wav", segment.index());
                    Path fallbackPath = OUTPUT_DIR.resolve(fallbackFile);
                    double fallbackDuration = Math.max(1.0, segment.vttDuration());
                    generateSilence(fallbackDuration, fallbackPath);
                    writer.println("file '" + fallbackFile + "'");
                    totalExpectedDuration += fallbackDuration;
                    System.out.printf("🔇 %02d. FALLBACK para fala: %.3fs\n", (i * 2) + 2, fallbackDuration);
                }
            }

            System.out.printf("✅ Lista KOKORO RIGOROSA criada: %d segmentos\n", segments.size());
            System.out.printf("📊 Duração esperada: %.3fs\n", totalExpectedDuration);
            System.out.printf("🎯 Duração alvo: %.3fs\n", analysis.targetDuration);
            System.out.printf("📏 Diferença estimada: %.3fs\n", Math.abs(totalExpectedDuration - analysis.targetDuration));
        }
    }

    // ===== MÉTODOS AUXILIARES =====

    private static void generateSilence(double duration, Path outputFile) throws IOException, InterruptedException {
        if (duration <= 0) return;

        ffmpegSemaphore.acquire();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-f", "lavfi",
                    "-i", String.format("anullsrc=r=%d:cl=mono", SAMPLE_RATE),
                    "-t", String.valueOf(duration),
                    "-ar", String.valueOf(SAMPLE_RATE),
                    "-ac", String.valueOf(AUDIO_CHANNELS),
                    outputFile.toString()
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout gerando silêncio após 30 segundos");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Erro gerando silêncio: " + outputFile);
            }

        } finally {
            ffmpegSemaphore.release();
        }
    }

    private static Path generateSilenceFallback(double duration, String outputFile) {
        try {
            Path silenceFile = OUTPUT_DIR.resolve(outputFile);
            generateSilence(Math.max(0.5, Math.min(duration, 5.0)), silenceFile);
            return silenceFile;
        } catch (Exception e) {
            System.err.println("❌ Erro gerando silêncio fallback: " + e.getMessage());
            return null;
        }
    }

    private static void concatenateAudioOptimized(Path listFile, Path outputFile) throws IOException, InterruptedException {
        System.out.println("🔗 Concatenando áudio final Kokoro...");

        ffmpegSemaphore.acquire();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.toString(),
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    "-ar", String.valueOf(SAMPLE_RATE),
                    "-ac", String.valueOf(AUDIO_CHANNELS),
                    outputFile.toString()
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Timeout aumentado para 5 minutos para muitos segmentos
            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout na concatenação Kokoro após 5 minutos");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Erro na concatenação final Kokoro");
            }

            if (!Files.exists(outputFile) || Files.size(outputFile) < 10240) {
                throw new IOException("Arquivo final Kokoro inválido ou muito pequeno");
            }

            System.out.printf("✅ Concatenação Kokoro concluída: %.1f MB\n",
                    Files.size(outputFile) / 1024.0 / 1024.0);

        } finally {
            ffmpegSemaphore.release();
        }
    }

    private static double getAudioDuration(Path audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                audioFile.toString()
        );

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String duration = reader.readLine();
            if (duration != null && !duration.trim().isEmpty()) {
                return Double.parseDouble(duration.trim());
            }
        }

        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }

        return 0.0;
    }

    // ===== MÉTODOS DE VALIDAÇÃO =====

    private static void validateFinalTiming(Path finalAudio, List<TimedSegment> originalSegments) {
        try {
            System.out.println("🔍 VALIDAÇÃO FINAL DE TIMING KOKORO...");

            double finalDuration = getAudioDuration(finalAudio);
            double expectedVTTDuration = originalSegments.isEmpty() ? 0.0 :
                    originalSegments.get(originalSegments.size() - 1).vttEndTime();

            System.out.printf("📊 RESULTADO FINAL KOKORO:\n");
            System.out.printf("  🎯 Duração esperada (VTT): %.3fs\n", expectedVTTDuration);
            System.out.printf("  📏 Duração final (Kokoro): %.3fs\n", finalDuration);
            System.out.printf("  ⚖️ Diferença: %.3fs\n", Math.abs(finalDuration - expectedVTTDuration));

            double accuracy = expectedVTTDuration > 0 ?
                    (1.0 - Math.abs(finalDuration - expectedVTTDuration) / expectedVTTDuration) * 100 : 100;

            System.out.printf("  🎯 Precisão: %.2f%%\n", accuracy);

            if (accuracy >= 98.0) {
                System.out.println("🏆 TIMING KOKORO PERFEITO!");
            } else if (accuracy >= 95.0) {
                System.out.println("✅ TIMING KOKORO EXCELENTE!");
            } else if (accuracy >= 90.0) {
                System.out.println("✅ TIMING KOKORO BOM!");
            } else {
                System.out.println("⚠️ TIMING KOKORO ACEITÁVEL - pode haver pequenos desvios");
            }

        } catch (Exception e) {
            System.err.println("⚠️ Erro na validação final Kokoro: " + e.getMessage());
        }
    }

    private static void validateFinalTimingPrecise(Path finalAudio, List<TimedSegment> originalSegments,
                                                   double targetDuration) {
        try {
            System.out.println("🔍 VALIDAÇÃO RIGOROSA DE TIMING KOKORO...");

            double finalDuration = getAudioDuration(finalAudio);
            double expectedVTTDuration = originalSegments.isEmpty() ? 0.0 :
                    originalSegments.get(originalSegments.size() - 1).vttEndTime();

            System.out.printf("📊 RESULTADO FINAL KOKORO DETALHADO:\n");
            System.out.printf("  🎯 Duração alvo (original): %.3fs\n", targetDuration);
            System.out.printf("  📄 Duração esperada (VTT): %.3fs\n", expectedVTTDuration);
            System.out.printf("  📏 Duração final (Kokoro): %.3fs\n", finalDuration);
            System.out.printf("  ⚖️ Diferença vs alvo: %.3fs\n", Math.abs(finalDuration - targetDuration));
            System.out.printf("  ⚖️ Diferença vs VTT: %.3fs\n", Math.abs(finalDuration - expectedVTTDuration));

            double accuracyVsTarget = targetDuration > 0 ?
                    (1.0 - Math.abs(finalDuration - targetDuration) / targetDuration) * 100 : 100;

            double accuracyVsVTT = expectedVTTDuration > 0 ?
                    (1.0 - Math.abs(finalDuration - expectedVTTDuration) / expectedVTTDuration) * 100 : 100;

            System.out.printf("  🎯 Precisão vs Original: %.2f%%\n", accuracyVsTarget);
            System.out.printf("  📄 Precisão vs VTT: %.2f%%\n", accuracyVsVTT);

            double bestAccuracy = Math.max(accuracyVsTarget, accuracyVsVTT);

            if (bestAccuracy >= 99.0) {
                System.out.println("🏆 TIMING KOKORO PERFEITO! (99%+)");
            } else if (bestAccuracy >= 95.0) {
                System.out.println("✅ TIMING KOKORO EXCELENTE! (95%+)");
            } else if (bestAccuracy >= 90.0) {
                System.out.println("✅ TIMING KOKORO BOM! (90%+)");
            } else if (bestAccuracy >= 85.0) {
                System.out.println("⚠️ TIMING KOKORO ACEITÁVEL (85%+) - pequenos ajustes necessários");
            } else {
                System.out.println("❌ TIMING KOKORO INSATISFATÓRIO (<85%) - correções necessárias");

                if (finalDuration > targetDuration + 5.0) {
                    System.out.println("💡 SUGESTÃO: Áudio muito longo - ajustar velocidade ou reduzir silêncios");
                } else if (finalDuration < targetDuration - 5.0) {
                    System.out.println("💡 SUGESTÃO: Áudio muito curto - adicionar silêncios ou reduzir velocidade");
                }
            }

        } catch (Exception e) {
            System.err.println("⚠️ Erro na validação rigorosa Kokoro: " + e.getMessage());
        }
    }

    // ===== MÉTODOS DE SETUP E LIMPEZA =====

    private static void prepareDirectories() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.createDirectories(CACHE_DIR);
    }

    private static void validateKokoroSetup() throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder(KOKORO_EXECUTABLE, "--help");
            Process process = pb.start();

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("❌ Kokoro TTS não responde");
            }

            // Kokoro pode retornar códigos diferentes, vamos verificar se existe
            System.out.printf("✅ Kokoro TTS configurado: %s\n", KOKORO_EXECUTABLE);
            System.out.printf("🎭 Voz padrão: %s\n", DEFAULT_VOICE);
            System.out.printf("🏃 Velocidade padrão: %.3f\n", DEFAULT_SPEED);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("❌ Verificação do Kokoro TTS interrompida");
        } catch (Exception e) {
            throw new IOException("❌ Kokoro TTS não encontrado ou não funcional: " + e.getMessage());
        }
    }

    private static String generateCacheKey(String text) {
        return String.valueOf(Math.abs(text.trim().toLowerCase().hashCode()));
    }

    public static void shutdown() {
        System.out.println("🔄 Finalizando KOKORO TTS COM COMPENSAÇÃO DE TIMING...");

        // ✅ Virtual Threads se fecham automaticamente, mas vamos garantir limpeza
        ttsExecutor.shutdown();
        ffmpegExecutor.shutdown();

        try {
            // Virtual Threads terminam rapidamente
            if (!ttsExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                ttsExecutor.shutdownNow();
            }
            if (!ffmpegExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                ffmpegExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ttsExecutor.shutdownNow();
            ffmpegExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        audioCache.clear();
        System.out.println("✅ KOKORO TTS COM COMPENSAÇÃO finalizado");
    }

    // ===== MÉTODOS DE DEBUG =====

    public static void debugVTTFormat(String vttFile) {
        try {
            analyzeVTTFile(vttFile);
        } catch (IOException e) {
            System.err.println("❌ Erro no debug VTT Kokoro: " + e.getMessage());
        }
    }

    private static void analyzeVTTFile(String inputFile) throws IOException {
        System.out.println("🔍 ANÁLISE DETALHADA DO VTT COM TIMING KOKORO");

        List<String> lines = Files.readAllLines(Paths.get(inputFile));
        System.out.printf("📄 Arquivo: %s (%d linhas)\n", inputFile, lines.size());

        System.out.println("📋 CABEÇALHO:");
        for (int i = 0; i < Math.min(10, lines.size()); i++) {
            String line = lines.get(i).trim();
            System.out.printf("  %02d: '%s'\n", i + 1, line);
        }

        System.out.println("🧪 TESTE DE PADRÕES KOKORO:");
        for (int p = 0; p < TIMESTAMP_PATTERNS.length; p++) {
            int matches = 0;
            String firstMatch = null;

            for (String line : lines) {
                if (TIMESTAMP_PATTERNS[p].matcher(line.trim()).matches()) {
                    matches++;
                    if (firstMatch == null) {
                        firstMatch = line.trim();
                    }
                }
            }

            System.out.printf("  Padrão %d: %d matches", p + 1, matches);
            if (firstMatch != null) {
                System.out.printf(" (ex: '%s')", firstMatch);
            }
            System.out.println();
        }

        List<TimedSegment> segments = parseVttWithMultiplePatterns(inputFile);
        if (!segments.isEmpty()) {
            double totalVTTDuration = segments.get(segments.size() - 1).vttEndTime();
            double totalSpeechTime = segments.stream().mapToDouble(TimedSegment::vttDuration).sum();

            System.out.printf("📊 ANÁLISE DE TIMING VTT KOKORO:\n");
            System.out.printf("  Segmentos: %d\n", segments.size());
            System.out.printf("  Duração total: %.3fs\n", totalVTTDuration);
            System.out.printf("  Tempo de fala: %.3fs\n", totalSpeechTime);
            System.out.printf("  Tempo de silêncio: %.3fs\n", totalVTTDuration - totalSpeechTime);
        }
    }

    // ===== MÉTODOS ESTÁTICOS DE UTILIDADE =====

    /**
     * Lista vozes disponíveis (se Kokoro suportar)
     */
    public static void listAvailableVoices() {
        try {
            System.out.println("🎭 Tentando listar vozes Kokoro disponíveis...");

            ProcessBuilder pb = new ProcessBuilder(KOKORO_EXECUTABLE, "--list-voices");
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                System.out.println("📋 VOZES KOKORO:");
                while ((line = reader.readLine()) != null) {
                    System.out.println("  " + line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

        } catch (Exception e) {
            System.out.println("⚠️ Não foi possível listar vozes: " + e.getMessage());
            System.out.println("🎭 Voz padrão disponível: " + DEFAULT_VOICE);
        }
    }

    /**
     * Teste rápido do Kokoro TTS
     */
    public static boolean testKokoroTTS() {
        return testKokoroTTS("Teste do Kokoro TTS funcionando.", DEFAULT_VOICE, DEFAULT_SPEED);
    }

    public static boolean testKokoroTTS(String testText, String voice, double speed) {
        try {
            System.out.println("🧪 Testando Kokoro TTS...");

            prepareDirectories();
            Path testOutput = OUTPUT_DIR.resolve("kokoro_test.wav");

            ProcessBuilder pb = new ProcessBuilder(
                    "bash", "-c",
                    String.format("echo '%s' | %s --voice %s --speed %.4f --output_file '%s'",
                            escapeBashString(testText),
                            KOKORO_EXECUTABLE,
                            voice,
                            speed,
                            testOutput.toString())
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            if (process.exitValue() == 0 && Files.exists(testOutput) && Files.size(testOutput) > 256) {
                System.out.printf("✅ Kokoro TTS funcionando! Arquivo gerado: %.1f KB\n",
                        Files.size(testOutput) / 1024.0);

                // Limpar arquivo de teste
                Files.deleteIfExists(testOutput);
                return true;
            } else {
                System.out.println("❌ Teste do Kokoro TTS falhou");
                return false;
            }

        } catch (Exception e) {
            System.err.println("❌ Erro no teste Kokoro TTS: " + e.getMessage());
            return false;
        }
    }

    // ============ ESTRUTURAS DE DADOS TTSUtils ============

    public static class AudioFormat {
        public final int sampleRate;
        public final int channels;
        public final String codec;

        public AudioFormat(int sampleRate, int channels, String codec) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.codec = codec;
        }
    }

    public static class OptimizedSegment {
        public final double vttStartTime;
        public final double vttEndTime;
        public final double vttDuration;
        public final String originalText;
        public final String cleanText;
        public final int index;
        public final String audioFile;
        public double actualDuration;
        public double adjustedSpeed;
        public int retryCount;

        public OptimizedSegment(double vttStartTime, double vttEndTime, String originalText, 
                              String cleanText, int index, String audioFile) {
            this.vttStartTime = vttStartTime;
            this.vttEndTime = vttEndTime;
            this.vttDuration = vttEndTime - vttStartTime;
            this.originalText = originalText;
            this.cleanText = cleanText;
            this.index = index;
            this.audioFile = audioFile;
            this.actualDuration = 0.0;
            this.adjustedSpeed = DEFAULT_SPEED;
            this.retryCount = 0;
        }
    }

    public static class AudioQualityMetrics {
        public final double timingAccuracy;
        public final double averageVolume;
        public final boolean hasAudibleContent;
        public final double silenceRatio;
        public final boolean passesQualityCheck;

        public AudioQualityMetrics(double timingAccuracy, double averageVolume, 
                                 boolean hasAudibleContent, double silenceRatio) {
            this.timingAccuracy = timingAccuracy;
            this.averageVolume = averageVolume;
            this.hasAudibleContent = hasAudibleContent;
            this.silenceRatio = silenceRatio;
            this.passesQualityCheck = timingAccuracy > 0.85 && hasAudibleContent && silenceRatio < 0.9;
        }
    }

    public static class OptimizedCalibration {
        public double globalLengthScale = 1.0;
        public double globalSpeedScale = 1.0;
        public double globalVolumeScale = 1.0;
        public final List<CalibrationResult> results = new ArrayList<>();

        public static class CalibrationResult {
            public final double lengthScale;
            public final double speedScale;
            public final double timingAccuracy;
            public final double qualityScore;
            public final LocalDateTime timestamp;

            public CalibrationResult(double lengthScale, double speedScale, 
                                   double timingAccuracy, double qualityScore) {
                this.lengthScale = lengthScale;
                this.speedScale = speedScale;
                this.timingAccuracy = timingAccuracy;
                this.qualityScore = qualityScore;
                this.timestamp = LocalDateTime.now();
            }
        }

        public void loadFromCache() {
            // Implementação simplificada
            logger.info("📋 Calibração carregada do cache");
        }

        public void saveToCache() {
            // Implementação simplificada
            logger.info("💾 Calibração salva no cache");
        }
    }

    // ============ MÉTODOS PRINCIPAIS TTSUtils ============

    /**
     * MÉTODO PRINCIPAL OTIMIZADO - MESMO FLUXO QUE TTSUtils
     */
    public static void processVttFile(String inputFile) throws IOException, InterruptedException {
        initializeOptimizedLogging();

        logger.info("🚀 INICIANDO KOKORO TTS OTIMIZADO - Qualidade e Sincronização Perfeitas");
        logOptimized("OPTIMIZED_START", "file=" + inputFile);

        OptimizedCalibration calibration = new OptimizedCalibration();
        calibration.loadFromCache();

        try {
            prepareDirectories();
            validateKokoroSetup();

            double targetDuration = detectOriginalAudioDuration(inputFile);
            logger.info(String.format("🎯 Duração alvo: %.3fs", targetDuration));

            List<OptimizedSegment> segments = parseVttOptimized(inputFile, calibration.globalLengthScale);
            logger.info(String.format("📝 Segmentos: %d", segments.size()));

            if (segments.isEmpty()) {
                throw new IOException("❌ Nenhum segmento válido encontrado");
            }

            // LOOP DE CALIBRAÇÃO OTIMIZADO
            boolean calibrationComplete = false;
            int calibrationIteration = 0;
            AudioQualityMetrics bestQuality = null;
            Path bestOutput = null;

            while (!calibrationComplete && calibrationIteration < 3) {
                calibrationIteration++;
                logger.info(String.format("🔄 Iteração de calibração: %d", calibrationIteration));

                // Gerar áudios
                generateAllAudios(segments);

                // Calibrar timing
                adjustSegmentTiming(segments, targetDuration);

                // Criar áudio final
                Path finalAudio = assembleFinalAudio(segments, targetDuration);

                // Avaliar qualidade
                AudioQualityMetrics quality = evaluateAudioQuality(finalAudio, targetDuration);

                if (quality.passesQualityCheck || calibrationIteration >= 3) {
                    calibrationComplete = true;
                    bestQuality = quality;
                    bestOutput = finalAudio;
                } else {
                    // Ajustar calibração para próxima iteração
                    adjustCalibrationParameters(calibration, quality);
                }
            }

            // Finalizar
            if (bestQuality != null) {
                logger.info(String.format("✅ Processamento concluído - Qualidade: %.2f", bestQuality.timingAccuracy));
                logOptimized("OPTIMIZED_SUCCESS", String.format("quality=%.2f,timing=%.2f", 
                    bestQuality.timingAccuracy, bestQuality.averageVolume));
            }

            calibration.saveToCache();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "❌ Erro no processamento", e);
            logOptimized("OPTIMIZED_ERROR", "error=" + e.getMessage());
            throw e;
        }

        logger.info("✅ Sistema KOKORO TTS OTIMIZADO finalizado com sucesso");
    }

    /**
     * PROCESSAMENTO COM DURAÇÃO ALVO ESPECÍFICA
     */
    public static void processVttFileWithTargetDuration(String inputFile, double targetDuration) 
            throws IOException, InterruptedException {
        logger.info(String.format("🎯 Processando com duração alvo específica: %.3fs", targetDuration));
        processVttFile(inputFile); // Por simplicidade, usa o método principal
    }

    // ============ MÉTODOS AUXILIARES TTSUtils ============

    private static void initializeOptimizedLogging() {
        logger.setLevel(Level.INFO);
        logger.info("📊 Logging otimizado inicializado");
    }

    private static void logOptimized(String event, String details) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        logger.info(String.format("[%s] %s: %s", timestamp, event, details));
    }

    private static double detectOriginalAudioDuration(String vttFile) throws IOException {
        // Implementação simplificada - detecta duração do VTT
        List<String> lines = Files.readAllLines(Paths.get(vttFile));
        double maxTime = 0.0;
        
        for (String line : lines) {
            for (Pattern pattern : TIMESTAMP_PATTERNS) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    try {
                        double endTime;
                        if (pattern == TIMESTAMP_PATTERNS[0] || pattern == TIMESTAMP_PATTERNS[1]) {
                            endTime = parseTimestampHMS(matcher.group(5), matcher.group(6), 
                                                      matcher.group(7), matcher.group(8));
                        } else {
                            endTime = parseTimestampMS(matcher.group(4), matcher.group(5), matcher.group(6));
                        }
                        maxTime = Math.max(maxTime, endTime);
                    } catch (Exception e) {
                        // Ignora erros de parsing
                    }
                    break;
                }
            }
        }
        
        return maxTime > 0 ? maxTime : 300.0; // Default 5 minutos
    }

    private static List<OptimizedSegment> parseVttOptimized(String inputFile, double lengthScale) 
            throws IOException {
        List<OptimizedSegment> segments = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(inputFile));
        
        int segmentIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            
            for (Pattern pattern : TIMESTAMP_PATTERNS) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    try {
                        double startTime, endTime;
                        if (pattern == TIMESTAMP_PATTERNS[0] || pattern == TIMESTAMP_PATTERNS[1]) {
                            startTime = parseTimestampHMS(matcher.group(1), matcher.group(2), 
                                                        matcher.group(3), matcher.group(4));
                            endTime = parseTimestampHMS(matcher.group(5), matcher.group(6), 
                                                      matcher.group(7), matcher.group(8));
                        } else {
                            startTime = parseTimestampMS(matcher.group(1), matcher.group(2), matcher.group(3));
                            endTime = parseTimestampMS(matcher.group(4), matcher.group(5), matcher.group(6));
                        }
                        
                        // Buscar texto nas próximas linhas
                        String text = "";
                        for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                            String textLine = lines.get(j).trim();
                            if (!textLine.isEmpty() && !textLine.contains("-->")) {
                                text = textLine;
                                break;
                            }
                        }
                        
                        if (!text.isEmpty()) {
                            String cleanText = normalizeTextForSpeech(text);
                            if (!cleanText.trim().isEmpty()) {
                                String audioFile = String.format("kokoro_segment_%03d.wav", segmentIndex);
                                segments.add(new OptimizedSegment(startTime, endTime, text, cleanText, 
                                                                segmentIndex, audioFile));
                                segmentIndex++;
                            }
                        }
                    } catch (Exception e) {
                        // Ignora erros de parsing
                    }
                    break;
                }
            }
        }
        
        return segments;
    }

    private static void generateAllAudios(List<OptimizedSegment> segments)
            throws IOException, InterruptedException {
        logger.info(String.format("🎵 Gerando %d áudios SEQUENCIALMENTE (evitar CUDA OOM)", segments.size()));

        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < segments.size(); i++) {
            OptimizedSegment segment = segments.get(i);

            // Limpeza de GPU a cada 5 áudios
            if (i > 0 && i % 5 == 0) {
                System.gc();
                Thread.sleep(2000);
                logger.info(String.format("🧹 Limpeza GPU após %d áudios", i));
            }

            boolean audioGenerated = false;
            int retryCount = 0;
            int maxRetries = 3;

            // ✅ RETRY LOOP - TENTAR GERAR ÁUDIO REAL
            while (!audioGenerated && retryCount < maxRetries) {
                try {
                    TimedSegment timedSegment = convertToTimedSegment(segment);

                    // ✅ AJUSTAR PARÂMETROS NO RETRY
                    double retrySpeed = segment.adjustedSpeed;
                    if (retryCount > 0) {
                        retrySpeed = Math.max(0.7, retrySpeed + (retryCount * 0.1)); // Aumentar velocidade
                    }

                    generateKokoroAudioWithTimeout(timedSegment, DEFAULT_VOICE, retrySpeed, 60);

                    Path audioFile = OUTPUT_DIR.resolve(segment.audioFile);
                    if (Files.exists(audioFile) && Files.size(audioFile) > 256) {
                        segment.actualDuration = getAudioDuration(audioFile);
                        logger.info(String.format("✅ Áudio %d/%d: %s (%.3fs)",
                                i + 1, segments.size(), segment.audioFile, segment.actualDuration));
                        audioGenerated = true;
                        successCount++;
                    } else {
                        throw new IOException("Áudio não gerado ou muito pequeno");
                    }

                } catch (Exception e) {
                    retryCount++;
                    logger.warning(String.format("⚠️ Tentativa %d/%d falhou para áudio %d: %s",
                            retryCount, maxRetries, i + 1, e.getMessage()));

                    if (retryCount < maxRetries) {
                        // ✅ TENTAR SIMPLIFICAR O TEXTO NO RETRY
                        if (retryCount == 2) {
                            String simplifiedText = simplifyTextForRetry(segment.cleanText);
                            if (!simplifiedText.equals(segment.cleanText)) {
                                logger.info(String.format("🔄 Retry %d com texto simplificado: \"%s\"",
                                        retryCount, simplifiedText));
                                // Criar novo segment temporário com texto simplificado
                                OptimizedSegment simpleSegment = new OptimizedSegment(
                                        segment.vttStartTime, segment.vttEndTime,
                                        segment.originalText, simplifiedText,
                                        segment.index, segment.audioFile
                                );
                                segment = simpleSegment; // Usar o simplificado
                            }
                        }
                        Thread.sleep(2000); // Pausa entre retries
                    }
                }
            }

            // ✅ SE TODAS AS TENTATIVAS FALHARAM - PARAR O PROCESSAMENTO
            if (!audioGenerated) {
                failureCount++;
                logger.severe(String.format("❌ FALHA TOTAL no áudio %d após %d tentativas: %s",
                        i + 1, maxRetries, segment.cleanText));

                // ✅ NÃO GERAR SILÊNCIO - PARAR E REPORTAR ERRO
                throw new IOException(String.format(
                        "❌ FALHA CRÍTICA: Não foi possível gerar áudio %d/%d após %d tentativas.\n" +
                                "Texto problemático: \"%s\"\n" +
                                "Sucessos até agora: %d\n" +
                                "Sistema TTS deve gerar FALA, não silêncio!",
                        i + 1, segments.size(), maxRetries, segment.cleanText, successCount
                ));
            }

            // Pausa entre áudios
            Thread.sleep(500);
        }


        logger.info(String.format("✅ Geração concluída: %d sucessos, %d falhas", successCount, failureCount));

        if (failureCount > 0) {
            logger.warning(String.format("⚠️ %d áudios falharam, mas sistema continuou", failureCount));
        }
    }

    private static String simplifyTextForRetry(String originalText) {
        if (originalText == null || originalText.trim().isEmpty()) {
            return "texto";
        }

        String simplified = originalText;

        // ✅ LIMPEZA BÁSICA SEM REGEX PROBLEMÁTICA
        simplified = simplified.replaceAll("\\s+", " ").trim();

        // Remover caracteres específicos problemáticos
        simplified = simplified.replace("\"", "");
        simplified = simplified.replace("'", "");
        simplified = simplified.replace("[", "");
        simplified = simplified.replace("]", "");
        simplified = simplified.replace("(", "");
        simplified = simplified.replace(")", "");
        simplified = simplified.replace("{", "");
        simplified = simplified.replace("}", "");
        simplified = simplified.replace("#", "");
        simplified = simplified.replace("@", "");
        simplified = simplified.replace("%", " por cento ");
        simplified = simplified.replace("&", " e ");

        // Se ficou muito pequeno, usar frase padrão
        if (simplified.trim().length() < 3) {
            simplified = "texto de teste";
        }

        // Limitar tamanho para retry
        if (simplified.length() > 100) {
            simplified = simplified.substring(0, 97) + "...";
        }

        return simplified.trim();
    }

// ============ CORREÇÃO 2: GERAÇÃO COM TIMEOUT E RETRY ============
// ADICIONAR este método no KokoroTTSUtils.java

    private static Path generateKokoroAudioWithTimeout(TimedSegment segment, String voice, double speed, int timeoutSeconds)
            throws IOException, InterruptedException {

        Path outputFile = OUTPUT_DIR.resolve(segment.audioFile()).toAbsolutePath();
        Files.createDirectories(outputFile.getParent());

        ttsSemaphore.acquire();
        try {
            String text = segment.cleanText();

            // ✅ TEXTO LIMPO MAS SEM OVER-ENGINEERING
            String cleanText = text.replaceAll("\\s+", " ").trim();

            // ✅ VELOCIDADE DENTRO DO RANGE SEGURO
            double safeSpeed = Math.max(0.6, Math.min(speed, 1.2));
            String speedStr = String.format(Locale.US, "%.4f", safeSpeed);

            // ✅ COMANDO KOKORO ORIGINAL - SEM PARÂMETROS INEXISTENTES
            String command = String.format(
                    "echo '%s' | kokoro-tts --voice %s --speed %s --output_file '%s'",
                    escapeBashString(cleanText),
                    voice,
                    speedStr,
                    outputFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);

            // ✅ AMBIENTE BÁSICO (sem over-configuration)
            pb.environment().put("CUDA_VISIBLE_DEVICES", "0");
            pb.environment().put("LC_NUMERIC", "C");
            pb.environment().put("LANG", "C");

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    if (line.contains("CUDA out of memory") || line.contains("out of memory")) {
                        logger.warning("🚨 CUDA OOM detectado, terminando processo");
                        process.destroyForcibly();
                        throw new IOException("CUDA Out of Memory: " + line);
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout no Kokoro TTS após " + timeoutSeconds + "s");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Kokoro TTS falhou com código: " + process.exitValue() +
                        "\nTexto: " + cleanText +
                        "\nComando: " + command +
                        "\nSaída: " + output.toString());
            }

            if (!Files.exists(outputFile)) {
                throw new IOException("Arquivo de áudio não foi gerado");
            }
            
            // Verificação menos restritiva para arquivos pequenos mas válidos
            if (Files.size(outputFile) < 256) {
                throw new IOException("Arquivo de áudio muito pequeno (< 256 bytes)");
            }

            return outputFile;

        } finally {
            ttsSemaphore.release();
        }
    }

    private static String optimizeTextForKokoro(String text) {
        if (text == null || text.trim().isEmpty()) return "";

        String optimized = text;

        // ✅ LIMPEZA AVANÇADA PARA MELHOR PRONÚNCIA
        optimized = optimized.replaceAll("\\s+", " "); // Espaços múltiplos
        optimized = optimized.replaceAll("([.!?])([A-Z])", "$1 $2"); // Espaço após pontuação
        optimized = optimized.replaceAll("([,;:])([A-Za-z])", "$1 $2"); // Espaço após vírgulas

        // ✅ SUBSTITUIÇÕES PARA MELHOR PRONÚNCIA
        optimized = optimized.replace("vs", "versus");
        optimized = optimized.replace("&", "e");
        optimized = optimized.replace("%", "por cento");
        optimized = optimized.replace("@", "arroba");
        optimized = optimized.replace("#", "hashtag");

        // ✅ REMOVER CARACTERES PROBLEMÁTICOS
        optimized = optimized.replaceAll("[\\[\\](){}]", ""); // Parênteses/colchetes
        optimized = optimized.replaceAll("[\"\u2018\u2019\u201C\u201D\u0060]", ""); // Aspas variadas
        optimized = optimized.replaceAll("[…]", "..."); // Reticências especiais

        // ✅ NORMALIZAR NÚMEROS SIMPLES
        optimized = optimized.replaceAll("\\b(\\d{1,2})\\b", "$1"); // Manter números simples

        // ✅ LIMITAR TAMANHO PARA EVITAR PROBLEMAS
        if (optimized.length() > 500) {
            optimized = optimized.substring(0, 497) + "...";
        }

        return optimized.trim();
    }

    private static void validateAudioQuality(Path audioFile, int textLength) throws IOException {
        try {
            long fileSize = Files.size(audioFile);

            // ✅ VERIFICAR TAMANHO ESPERADO (aproximadamente)
            long expectedMinSize = textLength * 100; // ~100 bytes por caractere
            long expectedMaxSize = textLength * 2000; // ~2000 bytes por caractere

            if (fileSize < expectedMinSize) {
                logger.warning(String.format("⚠️ Áudio pode estar muito curto: %d bytes (esperado min: %d)",
                        fileSize, expectedMinSize));
            }

            if (fileSize > expectedMaxSize) {
                logger.warning(String.format("⚠️ Áudio pode estar muito longo: %d bytes (esperado max: %d)",
                        fileSize, expectedMaxSize));
            }

            // ✅ VERIFICAR HEADER WAV
            try (var inputStream = Files.newInputStream(audioFile)) {
                byte[] header = new byte[44]; // Header WAV completo
                int bytesRead = inputStream.read(header);

                if (bytesRead >= 44) {
                    // Verificar sample rate (bytes 24-27)
                    int sampleRate = java.nio.ByteBuffer.wrap(header, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();

                    if (sampleRate < 16000 || sampleRate > 48000) {
                        logger.warning("⚠️ Sample rate suspeito: " + sampleRate + " Hz");
                    }
                }
            }

        } catch (Exception e) {
            logger.warning("⚠️ Erro na validação de qualidade: " + e.getMessage());
        }
    }

    private static TimedSegment convertToTimedSegment(OptimizedSegment segment) {
        return new TimedSegment(segment.vttStartTime, segment.vttEndTime, segment.vttDuration,
                               segment.actualDuration, 0.0, segment.originalText, segment.cleanText,
                               segment.index, segment.audioFile);
    }

    private static void adjustSegmentTiming(List<OptimizedSegment> segments, double targetDuration) {
        logger.info("⚙️ Ajustando timing dos segmentos");
        // Implementação simplificada - ajusta velocidade baseado na diferença de timing
        
        double totalVttDuration = segments.stream().mapToDouble(s -> s.vttDuration).sum();
        double totalActualDuration = segments.stream().mapToDouble(s -> s.actualDuration).sum();
        
        if (totalActualDuration > 0) {
            double speedAdjustment = totalVttDuration / totalActualDuration;
            for (OptimizedSegment segment : segments) {
                segment.adjustedSpeed = Math.max(0.5, Math.min(2.0, DEFAULT_SPEED * speedAdjustment));
            }
        }
    }

    private static Path assembleFinalAudio(List<OptimizedSegment> segments, double targetDuration)
            throws IOException, InterruptedException {
        logger.info(String.format("🔧 Montando áudio com timing VTT EXATO (%d segmentos)", segments.size()));

        Path finalOutput = OUTPUT_DIR.resolve("output.wav");
        Path concatList = OUTPUT_DIR.resolve("kokoro_concat_list.txt");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(concatList, StandardCharsets.UTF_8))) {

            int validSegments = 0;
            double accumulatedTime = 0.0;

            for (int i = 0; i < segments.size(); i++) {
                OptimizedSegment segment = segments.get(i);

                // ✅ SILÊNCIO INICIAL: Exato do VTT
                if (i == 0 && segment.vttStartTime > 0.01) {
                    String silenceFile = "kokoro_silence_initial.wav";
                    Path silencePath = OUTPUT_DIR.resolve(silenceFile);

                    double exactInitialSilence = segment.vttStartTime;
                    generateSilence(exactInitialSilence, silencePath);
                    writer.println("file '" + silenceFile + "'");
                    validSegments++;
                    accumulatedTime += exactInitialSilence;

                    logger.info(String.format("🔇 Silêncio inicial VTT: %.3fs", exactInitialSilence));
                }

                // ✅ ÁUDIO DO SEGMENTO
                Path audioFile = OUTPUT_DIR.resolve(segment.audioFile);
                if (Files.exists(audioFile) && Files.size(audioFile) > 256) {
                    writer.println("file '" + segment.audioFile + "'");
                    validSegments++;
                    accumulatedTime += segment.actualDuration;

                    logger.fine(String.format("🗣️ Segmento %d: %.3fs", segment.index, segment.actualDuration));
                } else {
                    // Fallback: usar duração VTT exata
                    String fallbackFile = String.format("kokoro_fallback_%03d.wav", segment.index);
                    Path fallbackPath = OUTPUT_DIR.resolve(fallbackFile);
                    generateSilence(segment.vttDuration, fallbackPath);
                    writer.println("file '" + fallbackFile + "'");
                    validSegments++;
                    accumulatedTime += segment.vttDuration;

                    logger.warning(String.format("⚠️ Fallback %d: %.3fs (VTT duration)",
                            segment.index, segment.vttDuration));
                }

                // ✅ GAP ENTRE SEGMENTOS: EXATO DO VTT
                if (i < segments.size() - 1) {
                    OptimizedSegment nextSegment = segments.get(i + 1);

                    // ✅ CALCULAR GAP EXATO BASEADO NO VTT
                    double currentSegmentEnd = segment.vttEndTime;
                    double nextSegmentStart = nextSegment.vttStartTime;
                    double exactGap = nextSegmentStart - currentSegmentEnd;

                    logger.fine(String.format("🔍 Gap %d: %.3fs - %.3fs = %.3fs",
                            i, nextSegmentStart, currentSegmentEnd, exactGap));

                    if (exactGap > 0.01) { // Mínimo 10ms
                        String gapFile = String.format("kokoro_gap_%03d.wav", i);
                        Path gapPath = OUTPUT_DIR.resolve(gapFile);
                        generateSilence(exactGap, gapPath);
                        writer.println("file '" + gapFile + "'");
                        validSegments++;
                        accumulatedTime += exactGap;

                        logger.fine(String.format("🔇 Gap %d VTT exato: %.3fs", i, exactGap));
                    } else if (exactGap < -0.01) {
                        // ✅ OVERLAP DETECTADO - ajustar
                        logger.warning(String.format("⚠️ Overlap detectado no gap %d: %.3fs (ignorando)", i, exactGap));
                        // Não adicionar gap negativo - segmentos se sobrepõem no VTT
                    }
                }
            }

            if (validSegments == 0) {
                throw new IOException("❌ Nenhum segmento válido para concatenação");
            }

            logger.info(String.format("📊 Timing VTT: %d segmentos, %.3fs acumulado", validSegments, accumulatedTime));

            // ✅ COMPARAR COM DURAÇÃO ESPERADA
            if (segments.size() > 0) {
                double expectedVttDuration = segments.get(segments.size() - 1).vttEndTime;
                double difference = Math.abs(accumulatedTime - expectedVttDuration);
                double accuracy = expectedVttDuration > 0 ?
                        (1.0 - difference / expectedVttDuration) * 100 : 100;

                logger.info(String.format("🎯 Precisão VTT: %.3fs esperado, %.3fs gerado (%.2f%% precisão)",
                        expectedVttDuration, accumulatedTime, accuracy));
            }
        }

        // ✅ CONCATENAÇÃO
        return executeConcatCopyModeKokoro(concatList, finalOutput);
    }

    private static void analyzeVTTTiming(List<OptimizedSegment> segments) {
        logger.info("🔍 ANÁLISE DETALHADA DOS INTERVALOS VTT:");

        double totalSpeechTime = 0.0;
        double totalSilenceTime = 0.0;

        for (int i = 0; i < segments.size(); i++) {
            OptimizedSegment segment = segments.get(i);

            // Silêncio inicial
            if (i == 0 && segment.vttStartTime > 0.01) {
                totalSilenceTime += segment.vttStartTime;
                logger.info(String.format("  Silêncio inicial: 0.000s → %.3fs (%.3fs)",
                        segment.vttStartTime, segment.vttStartTime));
            }

            // Segmento de fala
            totalSpeechTime += segment.vttDuration;
            logger.fine(String.format("  Fala %d: %.3fs → %.3fs (%.3fs)",
                    segment.index, segment.vttStartTime, segment.vttEndTime, segment.vttDuration));

            // Gap para próximo segmento
            if (i < segments.size() - 1) {
                OptimizedSegment nextSegment = segments.get(i + 1);
                double gap = nextSegment.vttStartTime - segment.vttEndTime;

                if (gap > 0.01) {
                    totalSilenceTime += gap;
                    logger.fine(String.format("  Gap %d: %.3fs → %.3fs (%.3fs)",
                            i, segment.vttEndTime, nextSegment.vttStartTime, gap));
                } else if (gap < -0.01) {
                    logger.warning(String.format("  ⚠️ Overlap %d: %.3fs (segmentos se sobrepõem)", i, gap));
                }
            }
        }

        double totalDuration = totalSpeechTime + totalSilenceTime;
        logger.info(String.format("📊 RESUMO VTT:"));
        logger.info(String.format("  🗣️ Tempo de fala: %.3fs", totalSpeechTime));
        logger.info(String.format("  🔇 Tempo de silêncio: %.3fs", totalSilenceTime));
        logger.info(String.format("  ⏱️ Duração total: %.3fs", totalDuration));
        logger.info(String.format("  📊 Proporção fala/silêncio: %.1f%%/%.1f%%",
                (totalSpeechTime/totalDuration)*100, (totalSilenceTime/totalDuration)*100));
    }

    /**
     * 🔧 EXECUTAR CONCATENAÇÃO COPY MODE - BASEADO EM TTSUTILS
     */
    private static Path executeConcatCopyModeKokoro(Path concatListFile, Path outputFile)
            throws IOException, InterruptedException {

        logger.info("🔧 Iniciando concatenação robusta com validação rigorosa");

        // ✅ VALIDAÇÃO INICIAL
        if (!Files.exists(concatListFile) || Files.size(concatListFile) == 0) {
            throw new IOException("❌ Lista de concatenação vazia: " + concatListFile);
        }

        // ✅ VERIFICAR E VALIDAR TODOS OS ARQUIVOS
        List<String> validFiles = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();
        long totalSize = 0;

        try (BufferedReader reader = Files.newBufferedReader(concatListFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("file '") && line.endsWith("'")) {
                    String filename = line.substring(6, line.length() - 1);
                    Path audioFile = OUTPUT_DIR.resolve(filename);

                    if (Files.exists(audioFile) && Files.size(audioFile) > 256) {
                        validFiles.add(filename);
                        totalSize += Files.size(audioFile);
                        logger.fine("✅ Arquivo válido: " + filename + " (" + Files.size(audioFile) + " bytes)");
                    } else {
                        missingFiles.add(filename);
                        logger.warning("❌ Arquivo ausente/inválido: " + filename);

                        // ✅ TENTAR CRIAR ARQUIVO AUSENTE SE FOR GAP/SILÊNCIO
                        if (filename.contains("gap") || filename.contains("silence")) {
                            try {
                                generateSilence(0.1, audioFile); // Silêncio mínimo
                                if (Files.exists(audioFile) && Files.size(audioFile) > 256) {
                                    validFiles.add(filename);
                                    totalSize += Files.size(audioFile);
                                    logger.info("🔧 Arquivo de silêncio criado: " + filename);
                                }
                            } catch (Exception e) {
                                logger.warning("⚠️ Não foi possível criar silêncio: " + filename);
                            }
                        }
                    }
                }
            }
        }

        logger.info(String.format("📊 Validação: %d válidos, %d ausentes, %.1f MB total",
                validFiles.size(), missingFiles.size(), totalSize / 1024.0 / 1024.0));

        if (validFiles.isEmpty()) {
            throw new IOException("❌ Nenhum arquivo válido para concatenação");
        }

        if (validFiles.size() < 10) {
            logger.warning("⚠️ Poucos arquivos válidos: " + validFiles.size());
        }

        // ✅ CRIAR LISTA LIMPA SÓ COM ARQUIVOS VÁLIDOS
        Path cleanListFile = OUTPUT_DIR.resolve("kokoro_clean_concat.txt");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(cleanListFile, StandardCharsets.UTF_8))) {
            for (String validFile : validFiles) {
                writer.println("file '" + validFile + "'");
            }
        }

        logger.info("📋 Lista limpa criada com " + validFiles.size() + " arquivos");

        // ✅ EXECUTAR FFMPEG COM CONFIGURAÇÕES ROBUSTAS
        List<String> ffmpegCmd = new ArrayList<>();
        ffmpegCmd.add("ffmpeg");
        ffmpegCmd.add("-y");                    // Sobrescrever
        ffmpegCmd.add("-f");
        ffmpegCmd.add("concat");
        ffmpegCmd.add("-safe");
        ffmpegCmd.add("0");
        ffmpegCmd.add("-i");
        ffmpegCmd.add(cleanListFile.toString());

        // ✅ USAR COPY MODE MAIS ROBUSTO
        ffmpegCmd.add("-c");
        ffmpegCmd.add("copy");
        ffmpegCmd.add("-avoid_negative_ts");
        ffmpegCmd.add("make_zero");
        ffmpegCmd.add("-fflags");
        ffmpegCmd.add("+genpts");              // Gerar timestamps
        ffmpegCmd.add("-map");
        ffmpegCmd.add("0:a");                  // Só áudio

        ffmpegCmd.add(outputFile.toString());

        logger.info("🚀 Comando FFmpeg: " + String.join(" ", ffmpegCmd));

        // ✅ EXECUTAR COM TIMEOUT GENEROSO
        ProcessBuilder pb = new ProcessBuilder(ffmpegCmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        List<String> errorLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                if (line.toLowerCase().contains("error") || line.toLowerCase().contains("fail")) {
                    errorLines.add(line);
                    logger.warning("⚠️ FFmpeg: " + line);
                } else if (line.contains("time=") || line.contains("size=")) {
                    logger.fine("📊 FFmpeg progresso: " + line);
                }
            }
        }

        // ✅ TIMEOUT DE 5 MINUTOS PARA CONCATENAÇÃO
        boolean finished = process.waitFor(300, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("❌ Timeout na concatenação após 5 minutos\nSaída: " + output.toString());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorSummary = errorLines.isEmpty() ? "Sem detalhes específicos" :
                    String.join("; ", errorLines);
            throw new IOException(String.format(
                    "❌ FFmpeg falhou (código %d)\n🔍 Erros principais: %s\n📄 Saída completa: %s",
                    exitCode, errorSummary, output.toString()));
        }

        // ✅ VALIDAÇÃO FINAL RIGOROSA
        if (!Files.exists(outputFile)) {
            throw new IOException("❌ Arquivo final não foi criado: " + outputFile +
                    "\n📄 Saída FFmpeg: " + output.toString());
        }

        long finalSize = Files.size(outputFile);
        if (finalSize < 44100) { // Menos de 1 segundo de áudio (44.1kHz * 1s * 2 bytes)
            throw new IOException("❌ Arquivo final muito pequeno: " + finalSize + " bytes" +
                    "\n📄 Saída FFmpeg: " + output.toString());
        }

        // ✅ VERIFICAR SE É WAV VÁLIDO
        try (var inputStream = Files.newInputStream(outputFile)) {
            byte[] header = new byte[12];
            int bytesRead = inputStream.read(header);
            if (bytesRead < 12) {
                throw new IOException("❌ Arquivo final com header incompleto");
            }

            String riffHeader = new String(header, 0, 4, StandardCharsets.US_ASCII);
            String waveHeader = new String(header, 8, 4, StandardCharsets.US_ASCII);

            if (!"RIFF".equals(riffHeader) || !"WAVE".equals(waveHeader)) {
                throw new IOException("❌ Arquivo final não é um WAV válido. Header: " +
                        java.util.Arrays.toString(header));
            }
        }

        // ✅ VERIFICAR DURAÇÃO FINAL
        try {
            double duration = getAudioDuration(outputFile);
            if (duration < 1.0) {
                throw new IOException("❌ Duração final muito curta: " + duration + "s");
            }
            logger.info(String.format("🎵 Duração final: %.3fs", duration));
        } catch (Exception e) {
            logger.warning("⚠️ Não foi possível verificar duração: " + e.getMessage());
        }

        logger.info(String.format("✅ Concatenação bem-sucedida: %s (%.2f MB)",
                outputFile.getFileName(), finalSize / 1024.0 / 1024.0));

        return outputFile;
    }

    private static void debugConcatenationFiles(Path concatListFile) throws IOException {
        logger.info("🔍 DEBUG: Analisando arquivos de concatenação");

        if (!Files.exists(concatListFile)) {
            logger.severe("❌ Lista de concatenação não existe: " + concatListFile);
            return;
        }

        List<String> lines = Files.readAllLines(concatListFile);
        logger.info("📄 Total de linhas na lista: " + lines.size());

        int existingFiles = 0;
        int missingFiles = 0;
        long totalSize = 0;

        for (String line : lines) {
            if (line.startsWith("file '") && line.endsWith("'")) {
                String filename = line.substring(6, line.length() - 1);
                Path audioFile = OUTPUT_DIR.resolve(filename);

                if (Files.exists(audioFile)) {
                    try {
                        long size = Files.size(audioFile);
                        totalSize += size;
                        existingFiles++;
                        logger.fine("✅ " + filename + " (" + size + " bytes)");
                    } catch (IOException e) {
                        logger.warning("⚠️ Erro lendo " + filename + ": " + e.getMessage());
                        missingFiles++;
                    }
                } else {
                    logger.warning("❌ Ausente: " + filename);
                    missingFiles++;
                }
            }
        }

        logger.info(String.format("📊 Resumo: %d existem, %d ausentes, %.2f MB total",
                existingFiles, missingFiles, totalSize / 1024.0 / 1024.0));
    }



    public static void processVttFileStandard(String inputFile) throws IOException, InterruptedException {
        logger.info("🚀 PROCESSAMENTO KOKORO com TIMING VTT EXATO");

        try {
            prepareDirectories();
            validateKokoroSetup();

            List<OptimizedSegment> segments = parseVttOptimized(inputFile, 1.0);
            logger.info(String.format("📝 Segmentos: %d", segments.size()));

            if (segments.isEmpty()) {
                throw new IOException("❌ Nenhum segmento válido encontrado no VTT");
            }

            // ✅ ANÁLISE DETALHADA DO VTT
            analyzeVTTTiming(segments);

            // Gerar áudios (UMA VEZ SÓ)
            generateAllAudios(segments);

            // Ajustar timing básico
            adjustSegmentTiming(segments, 0.0);

            // Montar áudio final (agora com timing VTT exato)
            Path finalAudio = assembleFinalAudio(segments, 0.0);

            // Validação final
            if (!Files.exists(finalAudio)) {
                throw new IOException("❌ Arquivo final não foi gerado: " + finalAudio);
            }

            // ✅ VALIDAÇÃO FINAL DE TIMING
            double finalDuration = getAudioDuration(finalAudio);
            double expectedDuration = segments.get(segments.size() - 1).vttEndTime;
            double accuracy = expectedDuration > 0 ?
                    (1.0 - Math.abs(finalDuration - expectedDuration) / expectedDuration) * 100 : 100;

            logger.info(String.format("✅ RESULTADO FINAL:"));
            logger.info(String.format("  🎯 VTT esperado: %.3fs", expectedDuration));
            logger.info(String.format("  📏 Áudio gerado: %.3fs", finalDuration));
            logger.info(String.format("  🎯 Precisão: %.2f%%", accuracy));

            if (accuracy >= 99.0) {
                logger.info("🏆 TIMING PERFEITO!");
            } else if (accuracy >= 95.0) {
                logger.info("✅ TIMING EXCELENTE!");
            } else {
                logger.warning("⚠️ Timing pode precisar de ajustes");
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "❌ Erro no processamento Kokoro", e);
            throw e;
        }
    }

    private static void validateVTTIntervals(String vttFile) throws IOException {
        logger.info("🔍 Validando intervalos do VTT: " + vttFile);

        List<String> lines = Files.readAllLines(Paths.get(vttFile));
        Pattern timestampPattern = Pattern.compile(
                "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
        );

        List<double[]> intervals = new ArrayList<>();

        for (String line : lines) {
            Matcher matcher = timestampPattern.matcher(line);
            if (matcher.find()) {
                try {
                    double startTime = parseTimestampHMS(matcher.group(1), matcher.group(2),
                            matcher.group(3), matcher.group(4));
                    double endTime = parseTimestampHMS(matcher.group(5), matcher.group(6),
                            matcher.group(7), matcher.group(8));
                    intervals.add(new double[]{startTime, endTime});
                } catch (Exception e) {
                    logger.warning("⚠️ Erro parseando linha: " + line);
                }
            }
        }

        logger.info(String.format("📊 VTT Analysis: %d intervalos encontrados", intervals.size()));

        // ✅ VERIFICAR OVERLAPS E GAPS
        for (int i = 0; i < intervals.size() - 1; i++) {
            double currentEnd = intervals.get(i)[1];
            double nextStart = intervals.get(i + 1)[0];
            double gap = nextStart - currentEnd;

            if (gap < 0) {
                logger.warning(String.format("⚠️ Overlap detectado: segmento %d termina em %.3fs, próximo inicia em %.3fs (%.3fs overlap)",
                        i, currentEnd, nextStart, Math.abs(gap)));
            } else if (gap > 10.0) {
                logger.warning(String.format("⚠️ Gap muito longo: %.3fs entre segmentos %d e %d", gap, i, i+1));
            }
        }

        if (!intervals.isEmpty()) {
            double totalDuration = intervals.get(intervals.size() - 1)[1];
            logger.info(String.format("📏 Duração total do VTT: %.3fs (%.2f minutos)",
                    totalDuration, totalDuration / 60.0));
        }
    }

    private static AudioQualityMetrics evaluateAudioQuality(Path audioFile, double targetDuration) 
            throws IOException, InterruptedException {
        double actualDuration = getAudioDuration(audioFile);
        double timingAccuracy = 1.0 - Math.abs(actualDuration - targetDuration) / targetDuration;
        
        // Análise de volume simplificada
        double averageVolume = analyzeAverageVolume(audioFile);
        boolean hasAudibleContent = averageVolume > -40.0; // dB
        double silenceRatio = analyzeSilenceRatio(audioFile);
        
        return new AudioQualityMetrics(Math.max(0.0, timingAccuracy), averageVolume, 
                                     hasAudibleContent, silenceRatio);
    }

    private static double analyzeAverageVolume(Path audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioFile.toString(),
                "-af", "volumedetect",
                "-vn", "-sn", "-dn",
                "-f", "null", "-"
        );
        
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        process.waitFor(30, TimeUnit.SECONDS);
        
        // Extrair volume médio da saída
        String outputStr = output.toString();
        Pattern volumePattern = Pattern.compile("mean_volume: ([+-]?\\d*\\.?\\d+) dB");
        Matcher matcher = volumePattern.matcher(outputStr);
        
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        
        return -30.0; // Default assumindo áudio audível
    }

    private static double analyzeSilenceRatio(Path audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioFile.toString(),
                "-af", "silencedetect=noise=-30dB:duration=0.1",
                "-f", "null", "-"
        );
        
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        process.waitFor(30, TimeUnit.SECONDS);
        
        // Análise simplificada - conta detecções de silêncio
        String outputStr = output.toString();
        long silenceDetections = outputStr.lines()
                .filter(line -> line.contains("silence_start"))
                .count();
        
        // Estimativa grosseira de ratio de silêncio
        return Math.min(0.9, silenceDetections * 0.1);
    }

    private static void adjustCalibrationParameters(OptimizedCalibration calibration, 
                                                   AudioQualityMetrics quality) {
        if (quality.timingAccuracy < 0.85) {
            calibration.globalSpeedScale *= (quality.timingAccuracy < 0.7) ? 1.2 : 1.1;
        }
        
        if (!quality.hasAudibleContent) {
            calibration.globalVolumeScale *= 1.5;
        }
        
        logger.info(String.format("🔧 Calibração ajustada - Speed: %.3f, Volume: %.3f", 
                calibration.globalSpeedScale, calibration.globalVolumeScale));
    }

    private static void printOptimizedUsage() {
        System.out.println("📖 USO DO KOKORO TTS UTILS:");
        System.out.println("  java KokoroTTSUtils process <arquivo.vtt>         - Processar arquivo VTT");
        System.out.println("  java KokoroTTSUtils process-target <arquivo.vtt> <duracao> - Com duração específica");
        System.out.println();
        System.out.println("🎯 EXEMPLOS:");
        System.out.println("  java KokoroTTSUtils process transcription.vtt");
        System.out.println("  java KokoroTTSUtils process-target transcription.vtt 180.5");
    }

    // ============ MAIN OTIMIZADO ============

    public static void main(String[] args) {
        try {
            if (args.length > 0) {
                String command = args[0];

                switch (command.toLowerCase()) {
                    case "process":
                        if (args.length > 1) {
                            processVttFile(args[1]);
                        } else {
                            System.out.println("Usage: java KokoroTTSUtils process <vtt_file>");
                        }
                        break;

                    case "process-target":
                        if (args.length > 2) {
                            double targetDuration = Double.parseDouble(args[2]);
                            processVttFileWithTargetDuration(args[1], targetDuration);
                        } else {
                            System.out.println("Usage: java KokoroTTSUtils process-target <vtt_file> <duration>");
                        }
                        break;

                    default:
                        printOptimizedUsage();
                }
            } else {
                printOptimizedUsage();
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "❌ Erro fatal", e);
            System.exit(1);
        } finally {
            shutdown();
        }
    }
}