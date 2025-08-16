package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KokoroTTSUtils COM COMPENSAÇÃO INTELIGENTE DE TIMING - VERSÃO INICIAL
 * Baseado em TTSUtils mas adaptado para Kokoro TTS
 */
public class KokoroTTSUtilsBKP {

    // Configuração do Kokoro TTS
    private static final String KOKORO_EXECUTABLE = "kokoro-tts";
    private static final String DEFAULT_VOICE = "pf_dora";
    private static final double DEFAULT_SPEED = 0.7995;

    // Configurações otimizadas
    private static final int MAX_CONCURRENT_TTS = 4; // Kokoro pode ser mais intensivo
    private static final int MAX_CONCURRENT_FFMPEG = 4;
    private static final int SAMPLE_RATE = 22050;
    private static final int AUDIO_CHANNELS = 1;

    private static final Path OUTPUT_DIR = Paths.get("output");
    private static final Path CACHE_DIR = OUTPUT_DIR.resolve("kokoro_cache");

    // Controle de concorrência
    private static final Semaphore ttsSemaphore = new Semaphore(MAX_CONCURRENT_TTS);
    private static final Semaphore ffmpegSemaphore = new Semaphore(MAX_CONCURRENT_FFMPEG);
    private static final ExecutorService ttsExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_TTS);
    private static final ExecutorService ffmpegExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_FFMPEG);
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
    private static class TimedSegment {
        final double vttStartTime;
        final double vttEndTime;
        final double vttDuration;
        final double actualAudioDuration;
        final double compensatedSilenceBefore;
        final String text;
        final String cleanText;
        final int index;
        final String audioFile;

        TimedSegment(double vttStartTime, double vttEndTime, double vttDuration,
                     double actualAudioDuration, double compensatedSilenceBefore,
                     String text, String cleanText, int index, String audioFile) {
            this.vttStartTime = vttStartTime;
            this.vttEndTime = vttEndTime;
            this.vttDuration = vttDuration;
            this.actualAudioDuration = actualAudioDuration;
            this.compensatedSilenceBefore = compensatedSilenceBefore;
            this.text = text;
            this.cleanText = cleanText;
            this.index = index;
            this.audioFile = audioFile;
        }

        // Getters
        double vttStartTime() { return vttStartTime; }
        double vttEndTime() { return vttEndTime; }
        double vttDuration() { return vttDuration; }
        double actualAudioDuration() { return actualAudioDuration; }
        double compensatedSilenceBefore() { return compensatedSilenceBefore; }
        String text() { return text; }
        String cleanText() { return cleanText; }
        int index() { return index; }
        String audioFile() { return audioFile; }
    }

    /**
     * Análise de timing para compensação
     */
    private static class TimingAnalysis {
        final double totalVTTSpeechTime;
        final double totalActualSpeechTime;
        final double timingDifference;
        final double compensationPerGap;
        final int numberOfGaps;
        final String strategy;

        TimingAnalysis(double vttTime, double actualTime, int gaps) {
            this.totalVTTSpeechTime = vttTime;
            this.totalActualSpeechTime = actualTime;
            this.timingDifference = vttTime - actualTime;
            this.numberOfGaps = gaps;

            if (Math.abs(timingDifference) < 2.0) {
                this.strategy = "PRESERVAR_VTT";
                this.compensationPerGap = 0.0;
            } else if (timingDifference > 0) {
                this.strategy = "EXPANDIR_GAPS";
                this.compensationPerGap = gaps > 0 ? Math.min(timingDifference / gaps, 0.5) : 0.0;
            } else {
                this.strategy = "AJUSTE_PROPORCIONAL";
                this.compensationPerGap = gaps > 0 ? Math.max(timingDifference / gaps, -0.2) : 0.0;
            }
        }

        @Override
        public String toString() {
            return String.format("VTT: %.3fs | Kokoro: %.3fs | Diff: %.3fs | Estratégia: %s | Compensação: %.3fs/gap",
                    totalVTTSpeechTime, totalActualSpeechTime, timingDifference, strategy, compensationPerGap);
        }
    }

    /**
     * Análise com duração alvo
     */
    private static class TimingAnalysisWithTarget {
        final double totalVTTTime;
        final double totalActualTTSTime;
        final double targetDuration;
        final double vttDifference;
        final double targetDifference;
        final String strategy;
        final double silenceAdjustment;
        final int numberOfGaps;

        TimingAnalysisWithTarget(double vttTime, double actualTime, double target, int gaps) {
            this.totalVTTTime = vttTime;
            this.totalActualTTSTime = actualTime;
            this.targetDuration = target;
            this.vttDifference = vttTime - actualTime;
            this.targetDifference = target - actualTime;
            this.numberOfGaps = Math.max(1, gaps);

            double targetErrorPercent = Math.abs(targetDifference / target) * 100;
            double vttErrorPercent = Math.abs(vttDifference / vttTime) * 100;

            double tempSilenceAdjustment;

            if (targetErrorPercent <= 2.0) {
                this.strategy = "USAR_ALVO_DIRETO";
                tempSilenceAdjustment = targetDifference / numberOfGaps;
            } else if (vttErrorPercent <= 5.0 && Math.abs(target - vttTime) <= 3.0) {
                this.strategy = "USAR_VTT_PRÓXIMO";
                tempSilenceAdjustment = vttDifference / numberOfGaps;
            } else {
                this.strategy = "HÍBRIDO_PONDERADO";
                double targetWeight = 0.7;
                double vttWeight = 0.3;
                tempSilenceAdjustment = (targetWeight * targetDifference + vttWeight * vttDifference) / numberOfGaps;
            }

            this.silenceAdjustment = Math.max(-0.5, Math.min(0.5, tempSilenceAdjustment));
        }

        @Override
        public String toString() {
            return String.format("VTT: %.3fs | Kokoro: %.3fs | Alvo: %.3fs | Diff VTT: %.3fs | Diff Alvo: %.3fs | Estratégia: %s | Ajuste: %.3fs/gap",
                    totalVTTTime, totalActualTTSTime, targetDuration, vttDifference, targetDifference, strategy, silenceAdjustment);
        }
    }

    /**
     * MÉTODO PRINCIPAL COM COMPENSAÇÃO DE TIMING
     */
    public static void processVttFile(String inputFile) throws IOException, InterruptedException {
        processVttFile(inputFile, DEFAULT_VOICE, DEFAULT_SPEED);
    }

    public static void processVttFile(String inputFile, String voice) throws IOException, InterruptedException {
        processVttFile(inputFile, voice, DEFAULT_SPEED);
    }

    public static void processVttFile(String inputFile, String voice, double speed) throws IOException, InterruptedException {
        System.out.println("🎙️ INICIANDO KOKORO TTS COM COMPENSAÇÃO INTELIGENTE DE TIMING");
        System.out.printf("🎭 Voz: %s | 🏃 Velocidade: %.3f\n", voice, speed);

        prepareDirectories();
        validateKokoroSetup();

        // 1. PARSE VTT e análise inicial
        List<TimedSegment> segments = parseVttWithMultiplePatterns(inputFile);
        System.out.printf("📝 Segmentos VTT encontrados: %d\n", segments.size());

        if (segments.isEmpty()) {
            throw new IOException("❌ Nenhum segmento válido encontrado no VTT");
        }

        // 2. GERAR ÁUDIOS KOKORO TTS
        System.out.println("🎙️ Gerando áudios Kokoro TTS...");
        generateSpeechAudios(segments, voice, speed);

        // 3. ANÁLISE DE TIMING REAL vs VTT
        System.out.println("🔍 ANALISANDO TIMING REAL vs VTT...");
        TimingAnalysis analysis = analyzeTimingDifferences(segments);
        System.out.println("📊 ANÁLISE: " + analysis);

        // 4. COMPENSAÇÃO DE TIMING
        List<TimedSegment> compensatedSegments = applyTimingCompensation(segments, analysis);

        // 5. CRIAR LISTA COMPENSADA
        Path listFile = OUTPUT_DIR.resolve("concat_list_kokoro_compensated.txt");
        createCompensatedList(compensatedSegments, listFile);

        // 6. CONCATENAÇÃO FINAL
        Path finalOutput = OUTPUT_DIR.resolve("output.wav");
        concatenateAudioOptimized(listFile, finalOutput);

        System.out.println("✅ KOKORO TTS COM COMPENSAÇÃO DE TIMING concluído: " + finalOutput);

        // 7. VALIDAÇÃO FINAL
        validateFinalTiming(finalOutput, segments);
    }

    /**
     * MÉTODO PRINCIPAL COM DURAÇÃO ALVO
     */
    public static void processVttFileWithTargetDuration(String inputFile, double targetDuration)
            throws IOException, InterruptedException {
        processVttFileWithTargetDuration(inputFile, targetDuration, DEFAULT_VOICE, DEFAULT_SPEED);
    }

    public static void processVttFileWithTargetDuration(String inputFile, double targetDuration, String voice, double speed)
            throws IOException, InterruptedException {

        System.out.println("🎙️ INICIANDO KOKORO TTS COM TIMING RIGOROSO E DURAÇÃO ALVO");
        System.out.printf("🎯 Duração alvo definida: %.3fs\n", targetDuration);
        System.out.printf("🎭 Voz: %s | 🏃 Velocidade: %.3f\n", voice, speed);

        prepareDirectories();
        validateKokoroSetup();

        // 1. PARSE VTT e análise inicial
        List<TimedSegment> segments = parseVttWithMultiplePatterns(inputFile);
        System.out.printf("📝 Segmentos VTT encontrados: %d\n", segments.size());

        if (segments.isEmpty()) {
            throw new IOException("❌ Nenhum segmento válido encontrado no VTT");
        }

        // 2. GERAR ÁUDIOS KOKORO TTS
        System.out.println("🎙️ Gerando áudios Kokoro TTS...");
        generateSpeechAudios(segments, voice, speed);

        // 3. ANÁLISE DE TIMING REAL vs VTT vs ALVO
        System.out.println("🔍 ANALISANDO TIMING vs DURAÇÃO ALVO...");
        TimingAnalysisWithTarget analysis = analyzeTimingWithTarget(segments, targetDuration);
        System.out.println("📊 ANÁLISE: " + analysis);

        // 4. COMPENSAÇÃO DE TIMING BASEADA NO ALVO
        List<TimedSegment> compensatedSegments = applyTargetBasedCompensation(segments, analysis);

        // 5. CRIAR LISTA RIGOROSA
        Path listFile = OUTPUT_DIR.resolve("concat_list_kokoro_target_precise.txt");
        createTargetPreciseList(compensatedSegments, listFile, analysis);

        // 6. CONCATENAÇÃO FINAL
        Path finalOutput = OUTPUT_DIR.resolve("output.wav");
        concatenateAudioOptimized(listFile, finalOutput);

        System.out.println("✅ KOKORO TTS COM TIMING RIGOROSO concluído: " + finalOutput);

        // 7. VALIDAÇÃO RIGOROSA FINAL
        validateFinalTimingPrecise(finalOutput, segments, targetDuration);
    }

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

            if (!Files.exists(outputFile) || Files.size(outputFile) < 1024) {
                throw new IOException("Arquivo de áudio Kokoro inválido ou muito pequeno");
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

            if (!Files.exists(outputFile) || Files.size(outputFile) < 1024) {
                throw new IOException("Arquivo de áudio Kokoro inválido ou muito pequeno");
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

            if (!Files.exists(outputFile) || Files.size(outputFile) < 1024) {
                throw new IOException("Arquivo de áudio Kokoro inválido ou muito pequeno");
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
        TimingAnalysisWithTarget analysis = new TimingAnalysisWithTarget(totalVTTTime, totalActualTime, targetDuration, numberOfGaps);

        System.out.printf("📊 RESUMO TIMING COM ALVO:\n");
        System.out.printf("  🎯 Duração alvo: %.3fs\n", targetDuration);
        System.out.printf("  📄 Tempo VTT total: %.3fs\n", totalVTTTime);
        System.out.printf("  🎙️ Tempo Kokoro real: %.3fs\n", totalActualTime);
        System.out.printf("  📏 Diferença vs alvo: %.3fs\n", analysis.targetDifference);
        System.out.printf("  📏 Diferença vs VTT: %.3fs\n", analysis.vttDifference);
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

                if (segment.compensatedSilenceBefore() >= 0.01) {
                    String silenceFile = String.format("kokoro_silence_%03d.wav", segment.index());
                    Path silencePath = OUTPUT_DIR.resolve(silenceFile);

                    try {
                        generateSilence(segment.compensatedSilenceBefore(), silencePath);
                        writer.println("file '" + silenceFile + "'");

                        System.out.printf("🔇 %02d. Silêncio: %.3fs (%s)\n",
                                (i * 2) + 1, segment.compensatedSilenceBefore(), silenceFile);
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
                            segment.actualAudioDuration(), segment.vttDuration());
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

                if (segment.compensatedSilenceBefore() >= 0.01) {
                    String silenceFile = String.format("kokoro_target_silence_%03d.wav", segment.index());
                    Path silencePath = OUTPUT_DIR.resolve(silenceFile);

                    try {
                        generateSilence(segment.compensatedSilenceBefore(), silencePath);
                        writer.println("file '" + silenceFile + "'");
                        totalExpectedDuration += segment.compensatedSilenceBefore();

                        System.out.printf("🔇 %02d. Silêncio RIGOROSO: %.3fs (%s)\n",
                                (i * 2) + 1, segment.compensatedSilenceBefore(), silenceFile);
                    } catch (Exception e) {
                        System.err.printf("❌ Erro gerando silêncio rigoroso %d: %s\n",
                                segment.index(), e.getMessage());

                        double fallbackDuration = Math.max(0.05, segment.compensatedSilenceBefore());
                        generateSilence(fallbackDuration, silencePath);
                        writer.println("file '" + silenceFile + "'");
                        totalExpectedDuration += fallbackDuration;
                        System.out.printf("🔇 %02d. Silêncio FALLBACK: %.3fs\n", (i * 2) + 1, fallbackDuration);
                    }
                }

                Path audioFile = OUTPUT_DIR.resolve(segment.audioFile());
                if (Files.exists(audioFile)) {
                    writer.println("file '" + segment.audioFile() + "'");
                    totalExpectedDuration += segment.actualAudioDuration();

                    System.out.printf("🗣️ %02d. Fala %d: %s (real: %.3fs)\n",
                            (i * 2) + 2, segment.index(), segment.audioFile(),
                            segment.actualAudioDuration());
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

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout gerando silêncio");
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
                    "-ar", String.valueOf(SAMPLE_RATE),
                    "-ac", String.valueOf(AUDIO_CHANNELS),
                    outputFile.toString()
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timeout na concatenação Kokoro");
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

        ttsExecutor.shutdown();
        ffmpegExecutor.shutdown();

        try {
            if (!ttsExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                ttsExecutor.shutdownNow();
            }
            if (!ffmpegExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
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

            if (process.exitValue() == 0 && Files.exists(testOutput) && Files.size(testOutput) > 1024) {
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
}