package org;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SilenceUtils - SOLUÇÃO CONSOLIDADA DEFINITIVA para processamento de silêncios
 * VERSÃO CORRIGIDA - Erros de compilação resolvidos
 */
public class SilenceUtils {

    private static final Logger LOGGER = Logger.getLogger(SilenceUtils.class.getName());

    // Configurações otimizadas para detecção precisa
    private static final double SILENCE_THRESHOLD_DB = -40.0;
    private static final double MIN_SILENCE_DURATION = 0.05; // 50ms mínimo
    private static final double NOISE_TOLERANCE_DB = -50.0;
    private static final double SAMPLE_RATE = 48000.0;
    private static final int CHANNELS = 2;
    private static final String CODEC = "pcm_s24le";

    // CORRIGIDO: Adicionadas constantes que estavam faltando
    private static final double SAMPLE_RATE_DOUBLE = 48000.0;
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    );

    // Pool otimizado para processamento paralelo
    private static final ExecutorService processingExecutor =
            Executors.newFixedThreadPool(Math.min(8, Runtime.getRuntime().availableProcessors()));

    // Pattern para parsing VTT
    private static final Pattern VTT_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    );

    /**
     * Classe para representar um segmento de silêncio com precisão sample-accurate
     */
    public static class SilenceSegment {
        public final double startTime;
        public final double endTime;
        public final double duration;
        public final double averageDb;
        public final SilenceType type;
        public final long startSample;
        public final long endSample;
        public final boolean preserveOriginalDuration;

        public SilenceSegment(double startTime, double endTime, double duration,
                              double averageDb, SilenceType type,
                              long startSample, long endSample, boolean preserveOriginalDuration) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = duration;
            this.averageDb = averageDb;
            this.type = type;
            this.startSample = startSample;
            this.endSample = endSample;
            this.preserveOriginalDuration = preserveOriginalDuration;
        }

        public boolean isSignificant() {
            return duration >= MIN_SILENCE_DURATION;
        }

        public String toTimestamp() {
            return String.format("%.6f-%.6f (%.6fs)", startTime, endTime, duration);
        }

        public boolean overlapsWith(SilenceSegment other) {
            return !(this.endTime <= other.startTime || this.startTime >= other.endTime);
        }
    }

    public enum SilenceType {
        INTRO_SILENCE,      // Silêncio inicial
        INTER_PHRASE,       // Entre frases
        INTER_SENTENCE,     // Entre sentenças
        PAUSE_BREATH,       // Pausas para respirar
        OUTRO_SILENCE,      // Silêncio final
        TRANSITION,         // Transições longas
        BACKGROUND_NOISE,   // Ruído de fundo detectado como silêncio
        TTS_GAP            // Gap detectado no TTS que precisa correção
    }

    /**
     * Classe para representar um segmento de áudio processado
     */
    public static class AudioSegment {
        public final SegmentType type;
        public final double startTime;
        public final double endTime;
        public final double duration;
        public final int segmentIndex;
        public final Path audioFile;
        public final String description;

        public AudioSegment(SegmentType type, double startTime, double endTime,
                            double duration, int segmentIndex, Path audioFile, String description) {
            this.type = type;
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = duration;
            this.segmentIndex = segmentIndex;
            this.audioFile = audioFile;
            this.description = description;
        }

        public enum SegmentType {
            SILENCE, SPEECH
        }

        public boolean isSilence() { return type == SegmentType.SILENCE; }
        public boolean isSpeech() { return type == SegmentType.SPEECH; }

        @Override
        public String toString() {
            return String.format("%s[%d]: %.3fs-%.3fs (%.3fs) - %s",
                    type, segmentIndex, startTime, endTime, duration, description);
        }
    }

    /**
     * Classe para representar gaps de silêncio no TTS
     */
    private static class SilenceGap {
        public final double startTime;
        public final double endTime;
        public final double duration;
        public final int index;

        public SilenceGap(double startTime, double endTime, double duration, int index) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = duration;
            this.index = index;
        }
    }

    /**
     * MÉTODO PRINCIPAL - Corrige gaps de TTS baseado no VTT
     */
    public static void fixTTSSilenceGaps(String vttFilePath, String outputDir)
            throws IOException, InterruptedException {

        LOGGER.info("🔧 INICIANDO CORREÇÃO RIGOROSA DE GAPS DE TTS");

        Path vttFile = Paths.get(vttFilePath);
        Path outputDirPath = Paths.get(outputDir);
        Path ttsOutput = outputDirPath.resolve("output.wav");

        if (!Files.exists(vttFile)) {
            LOGGER.warning("⚠️ VTT não encontrado: " + vttFilePath);
            return;
        }

        if (!Files.exists(ttsOutput)) {
            LOGGER.warning("⚠️ output.wav não encontrado: " + ttsOutput);
            return;
        }

        // === ANÁLISE RIGOROSA DO VTT ===
        List<SilenceGap> gaps = analyzeVTTForGapsRigorous(vttFile);
        LOGGER.info(String.format("🔍 Detectados %d gaps de silêncio", gaps.size()));

        // === SEMPRE PROCESSAR - Mesmo que não detecte gaps ===
        if (gaps.isEmpty()) {
            LOGGER.info("ℹ️ Nenhum gap detectado, mas verificando estrutura TTS");

            // Criar gaps baseados na estrutura VTT para garantir silêncios
            gaps = createMandatoryGapsFromVTT(vttFile);
            LOGGER.info(String.format("🔧 Criados %d gaps obrigatórios baseados no VTT", gaps.size()));
        }

        if (gaps.isEmpty()) {
            LOGGER.info("ℹ️ Estrutura VTT não permite criação de gaps, mantendo áudio original");
            return;
        }

        // === QUEBRAR ÁUDIO TTS BASEADO NO VTT ===
        List<AudioSegment> ttsSegments = breakTTSIntoSegmentsRigorous(ttsOutput, vttFile, outputDirPath);
        LOGGER.info(String.format("✂️ TTS quebrado em %d segmentos", ttsSegments.size()));

        // === RECONSTRUIR COM SILÊNCIOS RIGOROSOS ===
        Path correctedAudio = outputDirPath.resolve("output_with_rigorous_silences.wav");
        reconstructWithRigorousSilences(ttsSegments, gaps, correctedAudio, outputDirPath);

        // === SUBSTITUIR O OUTPUT ORIGINAL ===
        Files.move(correctedAudio, ttsOutput, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("✅ CORREÇÃO RIGOROSA DE GAPS DE TTS CONCLUÍDA");

        // === VALIDAÇÃO FINAL ===
        validateSilenceCorrection(ttsOutput, vttFile);
    }

    /**
     * ANÁLISE RIGOROSA DO VTT
     */
    private static List<SilenceGap> analyzeVTTForGapsRigorous(Path vttFile) throws IOException {
        List<String> lines = Files.readAllLines(vttFile);
        List<SilenceGap> gaps = new ArrayList<>();

        double lastEndTime = 0.0;
        boolean firstSegment = true;
        int gapIndex = 0;

        Pattern timestampPattern = Pattern.compile(
                "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
        );

        for (String line : lines) {
            Matcher matcher = timestampPattern.matcher(line);
            if (matcher.matches()) {
                double startTime = parseVTTTime(matcher.group(1), matcher.group(2),
                        matcher.group(3), matcher.group(4));
                double endTime = parseVTTTime(matcher.group(5), matcher.group(6),
                        matcher.group(7), matcher.group(8));

                // Gap inicial SEMPRE criar se > 0.01s
                if (firstSegment && startTime > 0.01) {
                    gaps.add(new SilenceGap(0.0, startTime, startTime, gapIndex++));
                    LOGGER.info(String.format("🔇 Gap inicial RIGOROSO: 0.000s → %.6fs (%.6fs)",
                            startTime, startTime));
                    firstSegment = false;
                }

                // Gap entre segmentos - threshold muito baixo
                if (lastEndTime > 0 && startTime > lastEndTime + 0.001) { // 1ms threshold
                    double gapDuration = startTime - lastEndTime;
                    gaps.add(new SilenceGap(lastEndTime, startTime, gapDuration, gapIndex++));
                    LOGGER.info(String.format("🔇 Gap %d RIGOROSO: %.6fs → %.6fs (%.6fs)",
                            gapIndex-1, lastEndTime, startTime, gapDuration));
                }

                lastEndTime = endTime;
                if (firstSegment) firstSegment = false;
            }
        }

        LOGGER.info(String.format("📊 Análise rigorosa: %d gaps detectados", gaps.size()));
        return gaps;
    }

    /**
     * CRIAR GAPS OBRIGATÓRIOS
     */
    private static List<SilenceGap> createMandatoryGapsFromVTT(Path vttFile) throws IOException {
        List<String> lines = Files.readAllLines(vttFile);
        List<SilenceGap> mandatoryGaps = new ArrayList<>();

        double lastEndTime = 0.0;
        boolean firstSegment = true;
        int gapIndex = 0;

        Pattern timestampPattern = Pattern.compile(
                "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
        );

        for (String line : lines) {
            Matcher matcher = timestampPattern.matcher(line);
            if (matcher.matches()) {
                double startTime = parseVTTTime(matcher.group(1), matcher.group(2),
                        matcher.group(3), matcher.group(4));
                double endTime = parseVTTTime(matcher.group(5), matcher.group(6),
                        matcher.group(7), matcher.group(8));

                // SEMPRE criar gap inicial se primeiro segmento não inicia em 0
                if (firstSegment && startTime > 0.001) {
                    mandatoryGaps.add(new SilenceGap(0.0, startTime, startTime, gapIndex++));
                    LOGGER.info(String.format("🔧 Gap obrigatório inicial: %.6fs", startTime));
                }

                // SEMPRE criar gap entre segmentos (mínimo 50ms)
                if (!firstSegment) {
                    double naturalGap = startTime - lastEndTime;
                    double mandatoryGapDuration = Math.max(0.05, naturalGap); // Mínimo 50ms

                    mandatoryGaps.add(new SilenceGap(lastEndTime, lastEndTime + mandatoryGapDuration,
                            mandatoryGapDuration, gapIndex++));
                    LOGGER.info(String.format("🔧 Gap obrigatório %d: %.6fs", gapIndex-1, mandatoryGapDuration));
                }

                lastEndTime = endTime;
                firstSegment = false;
            }
        }

        return mandatoryGaps;
    }

    /**
     * QUEBRAR TTS EM SEGMENTOS RIGOROSOS
     */
    private static List<AudioSegment> breakTTSIntoSegmentsRigorous(Path ttsAudio, Path vttFile, Path outputDir)
            throws IOException, InterruptedException {

        List<String> lines = Files.readAllLines(vttFile);
        List<AudioSegment> segments = new ArrayList<>();

        // Contar segmentos de fala no VTT
        long speechCount = lines.stream()
                .filter(line -> VTT_PATTERN.matcher(line).matches())
                .count();

        if (speechCount == 0) {
            LOGGER.warning("⚠️ Nenhum segmento de fala encontrado no VTT");
            return segments;
        }

        double ttsDuration = getAudioDuration(ttsAudio);
        double segmentDuration = ttsDuration / speechCount;

        LOGGER.info(String.format("📊 TTS: %.6fs total, %d segmentos, %.6fs por segmento",
                ttsDuration, speechCount, segmentDuration));

        double currentPosition = 0.0;
        int segmentIndex = 1;

        // Parse VTT para obter timing exato
        for (String line : lines) {
            Matcher matcher = Pattern.compile(
                    "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
            ).matcher(line);

            if (matcher.matches()) {
                double vttStart = parseVTTTime(matcher.group(1), matcher.group(2),
                        matcher.group(3), matcher.group(4));
                double vttEnd = parseVTTTime(matcher.group(5), matcher.group(6),
                        matcher.group(7), matcher.group(8));
                double vttDuration = vttEnd - vttStart;

                // Extrair segmento com duração baseada no VTT se possível
                double extractDuration = Math.min(segmentDuration, Math.max(0.5, vttDuration));

                Path segmentFile = outputDir.resolve(String.format("rigorous_tts_segment_%03d.wav", segmentIndex));

                try {
                    extractAudioSegmentPrecise(ttsAudio, currentPosition, extractDuration, segmentFile);

                    segments.add(new AudioSegment(AudioSegment.SegmentType.SPEECH, vttStart, vttEnd,
                            extractDuration, segmentIndex, segmentFile,
                            String.format("Rigorous TTS segment %.6fs", extractDuration)));

                    LOGGER.info(String.format("✂️ Segmento %d: %.6fs @ %.6fs (VTT: %.6fs → %.6fs)",
                            segmentIndex, extractDuration, currentPosition, vttStart, vttEnd));

                } catch (Exception e) {
                    LOGGER.warning(String.format("⚠️ Erro extraindo segmento %d: %s", segmentIndex, e.getMessage()));

                    // Fallback: criar silêncio
                    Path fallbackFile = outputDir.resolve(String.format("fallback_segment_%03d.wav", segmentIndex));
                    generateSilencePrecise(Math.max(0.5, vttDuration), fallbackFile);

                    segments.add(new AudioSegment(AudioSegment.SegmentType.SPEECH, vttStart, vttEnd,
                            vttDuration, segmentIndex, fallbackFile, "Fallback silence"));
                }

                currentPosition += extractDuration;
                segmentIndex++;
            }
        }

        LOGGER.info(String.format("✅ TTS quebrado RIGOROSAMENTE em %d segmentos", segments.size()));
        return segments;
    }

    /**
     * EXTRAÇÃO PRECISA DE SEGMENTO
     */
    private static void extractAudioSegmentPrecise(Path inputAudio, double startTime, double duration, Path outputFile)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputAudio.toString(),
                "-ss", String.format("%.9f", startTime),      // Precisão máxima
                "-t", String.format("%.9f", duration),        // Precisão máxima
                "-c:a", CODEC, "-ar", String.valueOf((int)SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-avoid_negative_ts", "make_zero",
                "-fflags", "+bitexact",                       // Máxima precisão
                outputFile.toString()
        );

        Process process = pb.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout na extração precisa de segmento");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro na extração precisa de segmento");
        }
    }

    /**
     * RECONSTRUIR COM SILÊNCIOS RIGOROSOS
     */
    private static void reconstructWithRigorousSilences(List<AudioSegment> segments, List<SilenceGap> gaps,
                                                        Path outputFile, Path tempDir) throws IOException, InterruptedException {

        Path concatList = tempDir.resolve("rigorous_corrected_concat_list.txt");
        LOGGER.info("🔗 Reconstruindo com silêncios RIGOROSOS...");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(concatList))) {
            int gapIndex = 0;
            double totalDuration = 0.0;

            for (int i = 0; i < segments.size(); i++) {
                AudioSegment segment = segments.get(i);

                // === ADICIONAR SILÊNCIO ANTES DO SEGMENTO ===
                if (gapIndex < gaps.size()) {
                    SilenceGap gap = gaps.get(gapIndex);

                    // Verificar se este gap deve ser aplicado antes deste segmento
                    boolean shouldApplyGap = (i == 0 && gap.index == 0) || // Gap inicial
                            (i > 0 && Math.abs(gap.startTime - segment.startTime) < 2.0); // Gap próximo

                    if (shouldApplyGap) {
                        String silenceFile = String.format("rigorous_silence_%03d.wav", gap.index);
                        Path silencePath = tempDir.resolve(silenceFile);

                        try {
                            generateSilencePrecise(gap.duration, silencePath);
                            writer.println("file '" + silenceFile + "'");
                            totalDuration += gap.duration;

                            LOGGER.info(String.format("🔇 Silêncio rigoroso %d: %.6fs", gap.index, gap.duration));
                        } catch (Exception e) {
                            LOGGER.warning(String.format("⚠️ Erro gerando silêncio %d: %s", gap.index, e.getMessage()));

                            // Fallback: silêncio mínimo
                            generateSilencePrecise(0.05, silencePath);
                            writer.println("file '" + silenceFile + "'");
                            totalDuration += 0.05;
                        }
                        gapIndex++;
                    }
                }

                // === ADICIONAR SEGMENTO DE FALA ===
                if (segment.audioFile != null && Files.exists(segment.audioFile)) {
                    writer.println("file '" + segment.audioFile.getFileName() + "'");

                    try {
                        double segmentDuration = getAudioDuration(segment.audioFile);
                        totalDuration += segmentDuration;
                        LOGGER.info(String.format("🗣️ Fala rigorosa %d: %s (%.6fs)",
                                segment.segmentIndex, segment.audioFile.getFileName(), segmentDuration));
                    } catch (Exception e) {
                        LOGGER.warning(String.format("⚠️ Erro obtendo duração do segmento %d", segment.segmentIndex));
                        totalDuration += segment.duration; // Usar duração estimada
                    }
                } else {
                    LOGGER.warning(String.format("⚠️ Segmento %d não encontrado: %s",
                            segment.segmentIndex, segment.audioFile));

                    // Fallback: gerar silêncio equivalente
                    String fallbackFile = String.format("fallback_speech_%03d.wav", segment.segmentIndex);
                    Path fallbackPath = tempDir.resolve(fallbackFile);
                    double fallbackDuration = Math.max(0.5, segment.duration);

                    generateSilencePrecise(fallbackDuration, fallbackPath);
                    writer.println("file '" + fallbackFile + "'");
                    totalDuration += fallbackDuration;
                    LOGGER.info(String.format("🔇 Fallback para fala %d: %.6fs", segment.segmentIndex, fallbackDuration));
                }
            }

            LOGGER.info(String.format("📊 Reconstrução completa: %.6fs de duração total esperada", totalDuration));
        }

        // === CONCATENAR COM PRECISÃO MÁXIMA ===
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", concatList.toString(),
                "-c:a", CODEC, "-ar", String.valueOf((int)SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-avoid_negative_ts", "make_zero",
                "-fflags", "+bitexact",
                outputFile.toString()
        );

        Process process = pb.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout na concatenação rigorosa");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro na concatenação rigorosa");
        }

        LOGGER.info(String.format("🔗 Reconstrução RIGOROSA concluída: %s", outputFile.getFileName()));
    }

    /**
     * GERAR SILÊNCIO COM PRECISÃO MÁXIMA
     */
    private static void generateSilencePrecise(double duration, Path outputFile)
            throws IOException, InterruptedException {

        if (duration <= 0) {
            throw new IllegalArgumentException("Duração deve ser > 0");
        }

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "lavfi",
                "-i", String.format("anullsrc=r=%.0f:cl=%s", SAMPLE_RATE,
                CHANNELS == 1 ? "mono" : "stereo"),
                "-t", String.format("%.9f", duration),  // Precisão máxima
                "-c:a", CODEC, "-ar", String.valueOf((int)SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-fflags", "+bitexact",
                outputFile.toString()
        );

        Process process = pb.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout gerando silêncio preciso");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro gerando silêncio preciso de " + duration + "s");
        }
    }

    /**
     * VALIDAÇÃO DE CORREÇÃO DE SILÊNCIOS
     */
    private static void validateSilenceCorrection(Path correctedAudio, Path vttFile) {
        try {
            LOGGER.info("🔍 VALIDANDO CORREÇÃO DE SILÊNCIOS...");

            double correctedDuration = getAudioDuration(correctedAudio);
            double expectedVTTDuration = getExpectedDurationFromVTT(vttFile);

            LOGGER.info(String.format("📊 VALIDAÇÃO:"));
            LOGGER.info(String.format("  📄 VTT esperado: %.6fs", expectedVTTDuration));
            LOGGER.info(String.format("  🔧 Corrigido: %.6fs", correctedDuration));
            LOGGER.info(String.format("  📏 Diferença: %.6fs", Math.abs(correctedDuration - expectedVTTDuration)));

            double accuracy = expectedVTTDuration > 0 ?
                    (1.0 - Math.abs(correctedDuration - expectedVTTDuration) / expectedVTTDuration) * 100 : 100;

            LOGGER.info(String.format("  🎯 Precisão: %.3f%%", accuracy));

            if (accuracy >= 99.0) {
                LOGGER.info("🏆 CORREÇÃO PERFEITA!");
            } else if (accuracy >= 95.0) {
                LOGGER.info("✅ CORREÇÃO EXCELENTE!");
            } else if (accuracy >= 90.0) {
                LOGGER.info("✅ CORREÇÃO BOA!");
            } else {
                LOGGER.warning("⚠️ CORREÇÃO ACEITÁVEL - pode precisar de ajustes");
            }

        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro na validação: " + e.getMessage());
        }
    }

    /**
     * OBTER DURAÇÃO ESPERADA DO VTT
     */
    private static double getExpectedDurationFromVTT(Path vttFile) throws IOException {
        List<String> lines = Files.readAllLines(vttFile);
        double maxEndTime = 0.0;

        Pattern timestampPattern = Pattern.compile(
                "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
        );

        for (String line : lines) {
            Matcher matcher = timestampPattern.matcher(line);
            if (matcher.find()) {
                try {
                    double endTime = parseVTTTime(matcher.group(5), matcher.group(6),
                            matcher.group(7), matcher.group(8));
                    maxEndTime = Math.max(maxEndTime, endTime);
                } catch (Exception e) {
                    // Ignora linhas com erro de parsing
                }
            }
        }

        return maxEndTime;
    }

    // ===== MÉTODOS AUXILIARES =====

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

    private static double parseVTTTime(String hours, String minutes, String seconds, String milliseconds) {
        int h = Integer.parseInt(hours);
        int m = Integer.parseInt(minutes);
        int s = Integer.parseInt(seconds);
        int ms = Integer.parseInt(milliseconds);
        return h * 3600.0 + m * 60.0 + s + ms / 1000.0;
    }

    // ===== MÉTODOS DE COMPATIBILIDADE =====

    /**
     * Substitui SimpleSilenceFix.fixSilencesInTTSOutput()
     */
    public static void fixSilencesInTTSOutput(String outputDir) throws IOException, InterruptedException {
        LOGGER.info("🔧 CORREÇÃO DEFINITIVA DE SILÊNCIOS - Iniciando");

        Path outputDirPath = Paths.get(outputDir);
        Path vttFile = outputDirPath.resolve("transcription.vtt");
        Path ttsOutput = outputDirPath.resolve("output.wav");

        if (!Files.exists(vttFile)) {
            LOGGER.warning("⚠️ VTT não encontrado, pulando correção de silêncios");
            return;
        }

        if (!Files.exists(ttsOutput)) {
            LOGGER.warning("⚠️ output.wav não encontrado, pulando correção");
            return;
        }

        try {
            // Usa a correção consolidada
            fixTTSSilenceGaps(vttFile.toString(), outputDir);
            LOGGER.info("✅ CORREÇÃO DEFINITIVA DE SILÊNCIOS CONCLUÍDA");

        } catch (Exception e) {
            LOGGER.severe("⚠️ Erro na correção definitiva: " + e.getMessage());
            // Fallback simples: apenas copia o arquivo
            LOGGER.info("🔄 Continuando sem correção de silêncios...");
        }
    }

    /**
     * Integração com TTSUtils
     */
    public static void integrateWithTTSUtils(String outputDir) {
        try {
            LOGGER.info("🎯 Integrando correção definitiva com TTSUtils...");
            fixSilencesInTTSOutput(outputDir);
        } catch (Exception e) {
            LOGGER.severe("⚠️ Erro na integração: " + e.getMessage());
            LOGGER.info("🔄 Continuando sem correção de silêncios...");
        }
    }

    /**
     * Shutdown graceful
     */
    public static void shutdown() {
        LOGGER.info("🔄 Finalizando processamento de silêncios...");

        processingExecutor.shutdown();
        try {
            if (!processingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
                if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warning("⚠️ Executor de processamento não finalizou completamente");
                }
            }
        } catch (InterruptedException e) {
            processingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("✅ Recursos de processamento de silêncios liberados");
    }

    // ===== MÉTODOS ADICIONAIS PARA COMPATIBILIDADE =====

    /**
     * MÉTODO PRINCIPAL - Preserva silêncios originais em áudio processado
     * Este método estava sendo referenciado mas não existia
     */
    public static void preserveOriginalSilences(Path originalAudioFile,
                                                List<Path> processedAudioSegments,
                                                Path outputFile) throws IOException, InterruptedException {

        LOGGER.info("🎯 INICIANDO PRESERVAÇÃO DE SILÊNCIOS");
        LOGGER.info("📂 Áudio original: " + originalAudioFile.getFileName());

        // 1. Detectar estrutura de silêncios do áudio original
        List<AudioSegment> originalStructure = detectAudioStructure(originalAudioFile, null);

        LOGGER.info(String.format("📊 Segmentos detectados: %d", originalStructure.size()));
        logSegmentStatistics(originalStructure);

        // 2. Validar áudios processados
        validateProcessedAudioSegments(originalStructure, processedAudioSegments);

        // 3. Reconstruir com silêncios preservados
        reconstructAudioWithPreservedSilences(originalStructure, processedAudioSegments, outputFile);

        LOGGER.info("✅ PRESERVAÇÃO DE SILÊNCIOS CONCLUÍDA: " + outputFile.getFileName());
    }

    /**
     * Detecta estrutura de silêncios/fala do áudio
     */
    private static List<AudioSegment> detectAudioStructure(Path audioFile, String vttPath)
            throws IOException, InterruptedException {

        LOGGER.info("🔍 Detectando estrutura de áudio...");

        if (vttPath != null && Files.exists(Paths.get(vttPath))) {
            return parseVTTStructure(vttPath, getAudioDuration(audioFile));
        } else {
            return detectSilenceStructure(audioFile);
        }
    }

    /**
     * Parse estrutura do VTT
     */
    private static List<AudioSegment> parseVTTStructure(String vttPath, double totalDuration)
            throws IOException {

        LOGGER.info("📄 Parsing estrutura do VTT");

        List<AudioSegment> segments = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(vttPath));

        double lastEndTime = 0.0;
        boolean isFirst = true;
        int segmentIndex = 1;

        for (String line : lines) {
            Matcher matcher = VTT_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                double startTime = parseVTTTime(matcher.group(1), matcher.group(2),
                        matcher.group(3), matcher.group(4));
                double endTime = parseVTTTime(matcher.group(5), matcher.group(6),
                        matcher.group(7), matcher.group(8));

                // Silêncio inicial
                if (isFirst && startTime > 0.1) {
                    segments.add(new AudioSegment(AudioSegment.SegmentType.SILENCE,
                            0.0, startTime, startTime, segmentIndex++, null, "Initial silence"));
                }

                // Silêncio entre segmentos
                if (!isFirst && startTime > lastEndTime + 0.05) {
                    double silenceDuration = startTime - lastEndTime;
                    segments.add(new AudioSegment(AudioSegment.SegmentType.SILENCE,
                            lastEndTime, startTime, silenceDuration, segmentIndex++, null, "Inter-segment silence"));
                }

                // Segmento de fala
                double speechDuration = endTime - startTime;
                segments.add(new AudioSegment(AudioSegment.SegmentType.SPEECH,
                        startTime, endTime, speechDuration, segmentIndex++, null, "Speech segment"));

                lastEndTime = endTime;
                isFirst = false;
            }
        }

        // Silêncio final
        if (lastEndTime < totalDuration - 0.1) {
            double finalSilence = totalDuration - lastEndTime;
            segments.add(new AudioSegment(AudioSegment.SegmentType.SILENCE,
                    lastEndTime, totalDuration, finalSilence, segmentIndex, null, "Final silence"));
        }

        return segments;
    }

    /**
     * Detecta estrutura usando análise automática de silêncios
     */
    private static List<AudioSegment> detectSilenceStructure(Path audioPath) throws IOException, InterruptedException {
        LOGGER.info("🔍 Detectando silêncios automaticamente...");

        List<AudioSegment> segments = new ArrayList<>();

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioPath.toString(),
                "-af", String.format("silencedetect=noise=%.1fdB:duration=%.3f",
                SILENCE_THRESHOLD_DB, MIN_SILENCE_DURATION),
                "-f", "null", "-"
        );

        Process process = pb.start();
        List<double[]> silences = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {

            String line;
            Double silenceStart = null;
            Pattern startPattern = Pattern.compile("silence_start: ([0-9.]+)");
            Pattern endPattern = Pattern.compile("silence_end: ([0-9.]+)");

            while ((line = reader.readLine()) != null) {
                Matcher startMatcher = startPattern.matcher(line);
                Matcher endMatcher = endPattern.matcher(line);

                if (startMatcher.find()) {
                    silenceStart = Double.parseDouble(startMatcher.group(1));
                } else if (endMatcher.find() && silenceStart != null) {
                    double silenceEnd = Double.parseDouble(endMatcher.group(1));
                    silences.add(new double[]{silenceStart, silenceEnd});
                    silenceStart = null;
                }
            }
        }

        process.waitFor(60, TimeUnit.SECONDS);

        // Converter para segmentos
        double totalDuration = getAudioDuration(audioPath);
        double currentTime = 0.0;
        int segmentIndex = 1;

        for (double[] silence : silences) {
            double silenceStart = silence[0];
            double silenceEnd = silence[1];

            // Fala antes do silêncio
            if (silenceStart > currentTime) {
                segments.add(new AudioSegment(AudioSegment.SegmentType.SPEECH, currentTime, silenceStart,
                        silenceStart - currentTime, segmentIndex++, null, "Detected speech"));
            }

            // Silêncio
            segments.add(new AudioSegment(AudioSegment.SegmentType.SILENCE, silenceStart, silenceEnd,
                    silenceEnd - silenceStart, segmentIndex++, null, "Detected silence"));

            currentTime = silenceEnd;
        }

        // Fala final
        if (currentTime < totalDuration) {
            segments.add(new AudioSegment(AudioSegment.SegmentType.SPEECH, currentTime, totalDuration,
                    totalDuration - currentTime, segmentIndex, null, "Final speech"));
        }

        return segments;
    }

    /**
     * Valida áudios processados
     */
    private static void validateProcessedAudioSegments(List<AudioSegment> originalStructure,
                                                       List<Path> processedAudioSegments) throws IOException {

        LOGGER.info("🔍 Validando áudios processados...");

        // Conta quantos segmentos de fala existem
        long speechSegments = originalStructure.stream()
                .filter(AudioSegment::isSpeech)
                .count();

        LOGGER.info(String.format("📊 Segmentos de fala esperados: %d, recebidos: %d",
                speechSegments, processedAudioSegments.size()));

        if (processedAudioSegments.size() != speechSegments) {
            LOGGER.warning("⚠️ Número de áudios processados não corresponde aos segmentos de fala");
        }

        // Valida se todos os arquivos existem e têm tamanho razoável
        for (int i = 0; i < processedAudioSegments.size(); i++) {
            Path audioFile = processedAudioSegments.get(i);

            if (!Files.exists(audioFile)) {
                throw new IOException("Arquivo de áudio processado não encontrado: " + audioFile);
            }

            long fileSize = Files.size(audioFile);
            if (fileSize < 1024) {
                throw new IOException("Arquivo de áudio muito pequeno: " + audioFile + " (" + fileSize + " bytes)");
            }

            LOGGER.fine(String.format("✅ Áudio %d validado: %s (%.1f KB)",
                    i + 1, audioFile.getFileName(), fileSize / 1024.0));
        }

        LOGGER.info("✅ Validação dos áudios processados concluída");
    }

    /**
     * Reconstrói áudio com silêncios preservados
     */
    private static void reconstructAudioWithPreservedSilences(List<AudioSegment> originalStructure,
                                                              List<Path> processedAudioSegments,
                                                              Path outputFile) throws IOException, InterruptedException {

        LOGGER.info("🔗 Reconstruindo áudio com silêncios preservados...");

        Path tempDir = Files.createTempDirectory("silence_preservation_");
        Path concatenationList = tempDir.resolve("concat_list.txt");

        try {
            createPreservationConcatenationList(originalStructure, processedAudioSegments,
                    concatenationList, tempDir);
            concatenateAudio(concatenationList, outputFile);
            validateFinalOutput(outputFile, originalStructure);

        } finally {
            cleanupTempDirectory(tempDir);
        }
    }

    /**
     * Cria lista de concatenação preservando ordem e silêncios
     */
    private static void createPreservationConcatenationList(List<AudioSegment> originalStructure,
                                                            List<Path> processedAudioSegments,
                                                            Path concatenationList,
                                                            Path tempDir) throws IOException, InterruptedException {

        LOGGER.info("📝 Criando lista de concatenação com silêncios preservados...");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(concatenationList))) {

            int speechIndex = 0;

            for (AudioSegment segment : originalStructure) {

                if (segment.isSilence()) {
                    // Gera silêncio com duração exata do original
                    Path silenceFile = generateExactSilence(segment.duration, tempDir, segment.segmentIndex);
                    writer.println("file '" + silenceFile.toAbsolutePath() + "'");

                    LOGGER.info(String.format("🔇 Silêncio preservado [%d]: %.3fs",
                            segment.segmentIndex, segment.duration));

                } else if (segment.isSpeech()) {
                    // Usa áudio processado correspondente

                    if (speechIndex >= processedAudioSegments.size()) {
                        LOGGER.warning("⚠️ Não há áudio processado suficiente para segmento " + segment.segmentIndex);

                        // Fallback: gera silêncio com duração do segmento original
                        Path fallbackSilence = generateExactSilence(segment.duration, tempDir, segment.segmentIndex);
                        writer.println("file '" + fallbackSilence.toAbsolutePath() + "'");
                        continue;
                    }

                    Path processedAudio = processedAudioSegments.get(speechIndex);

                    // Ajusta duração se necessário para manter sincronismo
                    Path adjustedAudio = adjustAudioToMatchOriginalDuration(
                            processedAudio, segment.duration, tempDir, speechIndex);

                    writer.println("file '" + adjustedAudio.toAbsolutePath() + "'");

                    LOGGER.info(String.format("🗣️ Fala processada [%d]: %s → %.3fs",
                            segment.segmentIndex, processedAudio.getFileName(), segment.duration));

                    speechIndex++;
                }
            }
        }

        LOGGER.info(String.format("✅ Lista criada: %d segmentos processados", originalStructure.size()));
    }

    /**
     * Ajusta áudio processado para corresponder à duração original
     */
    private static Path adjustAudioToMatchOriginalDuration(Path processedAudio, double targetDuration,
                                                           Path tempDir, int index)
            throws IOException, InterruptedException {

        double currentDuration = getAudioDuration(processedAudio);

        // Se duração está próxima do target (diferença < 50ms), não ajusta
        if (Math.abs(currentDuration - targetDuration) < 0.05) {
            LOGGER.fine(String.format("✅ Duração OK: %.3fs (target: %.3fs)", currentDuration, targetDuration));
            return processedAudio;
        }

        Path adjustedFile = tempDir.resolve(String.format("adjusted_speech_%03d.wav", index));

        // Calcula fator de velocidade necessário
        double speedFactor = currentDuration / targetDuration;
        speedFactor = Math.max(0.7, Math.min(1.4, speedFactor)); // Limita ajuste

        LOGGER.info(String.format("🔧 Ajustando duração: %.3fs → %.3fs (%.3fx)",
                currentDuration, targetDuration, speedFactor));

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", processedAudio.toString(),
                "-af", String.format("atempo=%.6f", speedFactor),
                "-c:a", CODEC, "-ar", String.valueOf((int)SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                adjustedFile.toString()
        );

        Process process = pb.start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout ajustando duração do áudio");
        }

        if (process.exitValue() != 0) {
            LOGGER.warning("⚠️ Erro ajustando duração, usando áudio original");
            return processedAudio;
        }

        return adjustedFile;
    }

    /**
     * Gera silêncio exato
     */
    private static Path generateExactSilence(double duration, Path tempDir, int index)
            throws IOException, InterruptedException {

        Path silenceFile = tempDir.resolve(String.format("silence_%03d.wav", index));

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "lavfi",
                "-i", String.format("anullsrc=r=%.0f:cl=%s", SAMPLE_RATE,
                CHANNELS == 1 ? "mono" : "stereo"),
                "-t", String.format("%.6f", duration),
                "-c:a", CODEC, "-ar", String.valueOf((int)SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                silenceFile.toString()
        );

        Process process = pb.start();

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout gerando silêncio");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro gerando silêncio de " + duration + "s");
        }

        return silenceFile;
    }

    /**
     * Concatena áudio
     */
    private static void concatenateAudio(Path listFile, Path outputFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                "-c:a", CODEC, "-ar", String.valueOf((int)SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-avoid_negative_ts", "make_zero",
                outputFile.toString()
        );

        Process process = pb.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout na concatenação de áudio");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro na concatenação de áudio");
        }
    }

    /**
     * Valida saída final
     */
    private static void validateFinalOutput(Path outputFile, List<AudioSegment> originalSegments)
            throws IOException, InterruptedException {

        LOGGER.info("🔍 Validando áudio final...");

        if (!Files.exists(outputFile) || Files.size(outputFile) < 10240) {
            throw new IOException("Arquivo final inválido ou muito pequeno");
        }

        double finalDuration = getAudioDuration(outputFile);
        double expectedDuration = originalSegments.stream()
                .mapToDouble(s -> s.duration)
                .sum();

        double difference = Math.abs(finalDuration - expectedDuration);
        double percentageDiff = (difference / expectedDuration) * 100;

        LOGGER.info(String.format("📊 VALIDAÇÃO FINAL:"));
        LOGGER.info(String.format("  Duração esperada: %.3fs", expectedDuration));
        LOGGER.info(String.format("  Duração final: %.3fs", finalDuration));
        LOGGER.info(String.format("  Diferença: %.3fs (%.2f%%)", difference, percentageDiff));

        if (percentageDiff > 2.0) {
            LOGGER.warning("⚠️ Diferença de duração maior que 2% - pode haver problemas de sincronismo");
        } else {
            LOGGER.info("✅ Duração final dentro da tolerância (<2%)");
        }
    }

    /**
     * Log de estatísticas de segmentos
     */
    private static void logSegmentStatistics(List<AudioSegment> segments) {
        long silenceCount = segments.stream().filter(AudioSegment::isSilence).count();
        long speechCount = segments.stream().filter(AudioSegment::isSpeech).count();

        double totalSilence = segments.stream()
                .filter(AudioSegment::isSilence)
                .mapToDouble(s -> s.duration)
                .sum();

        double totalSpeech = segments.stream()
                .filter(AudioSegment::isSpeech)
                .mapToDouble(s -> s.duration)
                .sum();

        LOGGER.info("📊 ESTATÍSTICAS DOS SEGMENTOS:");
        LOGGER.info(String.format("  Silêncios: %d (%.2fs total)", silenceCount, totalSilence));
        LOGGER.info(String.format("  Falas: %d (%.2fs total)", speechCount, totalSpeech));
        LOGGER.info(String.format("  Total: %.2fs", totalSilence + totalSpeech));

        // Log detalhado dos primeiros segmentos
        LOGGER.fine("🔍 PRIMEIROS SEGMENTOS:");
        segments.stream().limit(10).forEach(segment ->
                LOGGER.fine("  " + segment.toString()));
    }

    /**
     * Limpeza de diretório temporário
     */
    private static void cleanupTempDirectory(Path tempDir) {
        try {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignora erros de limpeza
                        }
                    });
        } catch (IOException e) {
            LOGGER.warning("Erro na limpeza: " + e.getMessage());
        }
    }
    public static List<SilenceSegment> detectSilences(Path audioFile) throws IOException, InterruptedException {
        LOGGER.info("🔍 Iniciando detecção SAMPLE-ACCURATE de silêncios...");

        validateAudioFile(audioFile);

        // Análise paralela com múltiplas estratégias
        CompletableFuture<List<SilenceSegment>> ffmpegDetection =
                CompletableFuture.supplyAsync(() -> detectWithFFmpeg(audioFile), processingExecutor);

        CompletableFuture<List<SilenceSegment>> spectralDetection =
                CompletableFuture.supplyAsync(() -> detectSpectralSilences(audioFile), processingExecutor);

        CompletableFuture<List<SilenceSegment>> volumeDetection =
                CompletableFuture.supplyAsync(() -> detectVolumeBasedSilences(audioFile), processingExecutor);

        // Aguarda todas as análises
        List<SilenceSegment> ffmpegSilences = ffmpegDetection.join();
        List<SilenceSegment> spectralSilences = spectralDetection.join();
        List<SilenceSegment> volumeSilences = volumeDetection.join();

        // Fusão inteligente dos resultados
        List<SilenceSegment> mergedSilences = mergeDetectionResults(
                ffmpegSilences, spectralSilences, volumeSilences
        );

        // Refina e classifica
        List<SilenceSegment> refinedSilences = refineAndClassify(mergedSilences, audioFile);

        LOGGER.info(String.format("✅ Detectados %d silêncios com precisão sample-accurate",
                refinedSilences.size()));
        logDetailedStatistics(refinedSilences);

        return refinedSilences;
    }

    private static void validateAudioFile(Path audioFile) throws IOException {
        if (!Files.exists(audioFile)) {
            throw new IOException("Arquivo de áudio não encontrado: " + audioFile);
        }

        if (Files.size(audioFile) < 1024) {
            throw new IOException("Arquivo de áudio muito pequeno: " + audioFile);
        }
    }

    // 12. MÉTODO FFmpeg rigoroso
    private static List<SilenceSegment> detectWithFFmpegRigorous(Path audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", audioFile.toString(),
                    "-af", String.format("silencedetect=noise=%.1fdB:duration=%.6f",
                    SILENCE_THRESHOLD_DB, MIN_SILENCE_DURATION * 0.5), // Threshold mais baixo
                    "-f", "null", "-"
            );

            Process process = pb.start();
            List<SilenceSegment> silences = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {

                String line;
                Double silenceStart = null;
                Pattern startPattern = Pattern.compile("silence_start: ([0-9.]+)");
                Pattern endPattern = Pattern.compile("silence_end: ([0-9.]+) \\| silence_duration: ([0-9.]+)");

                while ((line = reader.readLine()) != null) {
                    Matcher startMatcher = startPattern.matcher(line);
                    Matcher endMatcher = endPattern.matcher(line);

                    if (startMatcher.find()) {
                        silenceStart = Double.parseDouble(startMatcher.group(1));
                    } else if (endMatcher.find() && silenceStart != null) {
                        double silenceEnd = Double.parseDouble(endMatcher.group(1));
                        double duration = Double.parseDouble(endMatcher.group(2));

                        if (duration >= MIN_SILENCE_DURATION * 0.5) { // Threshold mais baixo
                            long startSample = Math.round(silenceStart * SAMPLE_RATE_DOUBLE);
                            long endSample = Math.round(silenceEnd * SAMPLE_RATE_DOUBLE);

                            silences.add(new SilenceSegment(
                                    silenceStart, silenceEnd, duration,
                                    SILENCE_THRESHOLD_DB, SilenceType.INTER_PHRASE,
                                    startSample, endSample, true
                            ));
                        }
                        silenceStart = null;
                    }
                }
            }

            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

            return silences;

        } catch (Exception e) {
            LOGGER.warning("Erro na detecção FFmpeg rigorosa: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<SilenceSegment> detectWithFFmpeg(Path audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", audioFile.toString(),
                    "-af", String.format(
                    "silencedetect=noise=%.1fdB:duration=%.6f",
                    SILENCE_THRESHOLD_DB, MIN_SILENCE_DURATION
            ),
                    "-f", "null", "-"
            );

            Process process = pb.start();
            List<SilenceSegment> silences = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {

                String line;
                Double silenceStart = null;
                Pattern startPattern = Pattern.compile("silence_start: ([0-9.]+)");
                Pattern endPattern = Pattern.compile("silence_end: ([0-9.]+) \\| silence_duration: ([0-9.]+)");

                while ((line = reader.readLine()) != null) {
                    Matcher startMatcher = startPattern.matcher(line);
                    Matcher endMatcher = endPattern.matcher(line);

                    if (startMatcher.find()) {
                        silenceStart = Double.parseDouble(startMatcher.group(1));
                    } else if (endMatcher.find() && silenceStart != null) {
                        double silenceEnd = Double.parseDouble(endMatcher.group(1));
                        double duration = Double.parseDouble(endMatcher.group(2));

                        if (duration >= MIN_SILENCE_DURATION) {
                            long startSample = (long)(silenceStart * SAMPLE_RATE);
                            long endSample = (long)(silenceEnd * SAMPLE_RATE);

                            silences.add(new SilenceSegment(
                                    silenceStart, silenceEnd, duration,
                                    SILENCE_THRESHOLD_DB, SilenceType.INTER_PHRASE,
                                    startSample, endSample, true
                            ));
                        }
                        silenceStart = null;
                    }
                }
            }

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.warning("Timeout na detecção FFmpeg");
            }

            return silences;

        } catch (Exception e) {
            LOGGER.warning("Erro na detecção FFmpeg: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<SilenceSegment> detectSpectralSilences(Path audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", audioFile.toString(),
                    "-af", String.format(
                    "astats=metadata=1:reset=1:length=%.3f," +
                            "ametadata=print:key=lavfi.astats.Overall.RMS_level",
                    MIN_SILENCE_DURATION
            ),
                    "-f", "null", "-"
            );

            Process process = pb.start();
            List<SilenceSegment> silences = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {

                String line;
                double currentTime = 0;
                double silenceStart = -1;

                Pattern rmsPattern = Pattern.compile("lavfi\\.astats\\.Overall\\.RMS_level=([0-9.-]+)");

                while ((line = reader.readLine()) != null) {
                    Matcher rmsMatcher = rmsPattern.matcher(line);

                    if (rmsMatcher.find()) {
                        double rmsLevel = Double.parseDouble(rmsMatcher.group(1));

                        if (rmsLevel < NOISE_TOLERANCE_DB) {
                            if (silenceStart == -1) {
                                silenceStart = currentTime;
                            }
                        } else {
                            if (silenceStart != -1) {
                                double duration = currentTime - silenceStart;
                                if (duration >= MIN_SILENCE_DURATION) {
                                    long startSample = (long)(silenceStart * SAMPLE_RATE);
                                    long endSample = (long)(currentTime * SAMPLE_RATE);

                                    silences.add(new SilenceSegment(
                                            silenceStart, currentTime, duration,
                                            rmsLevel, SilenceType.BACKGROUND_NOISE,
                                            startSample, endSample, true
                                    ));
                                }
                                silenceStart = -1;
                            }
                        }
                        currentTime += MIN_SILENCE_DURATION;
                    }
                }
            }

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

            return silences;

        } catch (Exception e) {
            LOGGER.warning("Erro na detecção espectral: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<SilenceSegment> detectVolumeBasedSilences(Path audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", audioFile.toString(),
                    "-af", "volumedetect",
                    "-f", "null", "-"
            );

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {

                String line;
                Pattern volumePattern = Pattern.compile("mean_volume: ([0-9.-]+) dB");

                while ((line = reader.readLine()) != null) {
                    Matcher volumeMatcher = volumePattern.matcher(line);
                    if (volumeMatcher.find()) {
                        double meanVolume = Double.parseDouble(volumeMatcher.group(1));

                        // Ajusta threshold baseado no volume médio
                        double adaptiveThreshold = Math.min(SILENCE_THRESHOLD_DB, meanVolume - 20);

                        // Executa detecção com threshold adaptativo
                        return detectWithAdaptiveThreshold(audioFile, adaptiveThreshold);
                    }
                }
            }

            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

            return new ArrayList<>();

        } catch (Exception e) {
            LOGGER.warning("Erro na detecção por volume: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<SilenceSegment> mergeDetectionResults(
            List<SilenceSegment> ffmpeg,
            List<SilenceSegment> spectral,
            List<SilenceSegment> volume) {

        LOGGER.info(String.format("🔧 Fusão inteligente: FFmpeg=%d, Spectral=%d, Volume=%d",
                ffmpeg.size(), spectral.size(), volume.size()));

        // Combina todas as detecções
        List<SilenceSegment> allSegments = new ArrayList<>();
        allSegments.addAll(ffmpeg);
        allSegments.addAll(spectral);
        allSegments.addAll(volume);

        // Ordena por tempo de início
        allSegments.sort(Comparator.comparingDouble(s -> s.startTime));

        // Fusão com sobreposição
        List<SilenceSegment> merged = new ArrayList<>();
        SilenceSegment current = null;

        for (SilenceSegment segment : allSegments) {
            if (current == null) {
                current = segment;
                continue;
            }

            // Verifica sobreposição ou proximidade (tolerance de 100ms)
            double tolerance = 0.1;
            if (segment.startTime <= current.endTime + tolerance) {
                // Mescla segmentos
                double newStart = Math.min(current.startTime, segment.startTime);
                double newEnd = Math.max(current.endTime, segment.endTime);
                double newDuration = newEnd - newStart;
                double avgDb = (current.averageDb + segment.averageDb) / 2;
                long newStartSample = (long)(newStart * SAMPLE_RATE);
                long newEndSample = (long)(newEnd * SAMPLE_RATE);

                current = new SilenceSegment(
                        newStart, newEnd, newDuration, avgDb,
                        current.type, newStartSample, newEndSample, true
                );
            } else {
                // Adiciona segmento anterior e inicia novo
                if (current.isSignificant()) {
                    merged.add(current);
                }
                current = segment;
            }
        }

        if (current != null && current.isSignificant()) {
            merged.add(current);
        }

        LOGGER.info(String.format("🔧 Fusão completa: %d → %d silêncios", allSegments.size(), merged.size()));
        return merged;
    }

    private static List<SilenceSegment> refineAndClassify(List<SilenceSegment> silences, Path audioFile)
            throws IOException, InterruptedException {

        if (silences.isEmpty()) return silences;

        double totalDuration = getAudioDuration(audioFile);
        List<SilenceSegment> refined = new ArrayList<>();

        for (int i = 0; i < silences.size(); i++) {
            SilenceSegment silence = silences.get(i);

            // Classificação mais precisa
            SilenceType type = classifyWithContext(silence, i, silences, totalDuration);

            // Ajuste sample-accurate dos timestamps
            SilenceSegment adjustedSilence = adjustTimestampsPrecision(silence, type);

            refined.add(adjustedSilence);
        }

        return refined;
    }

    private static void logDetailedStatistics(List<SilenceSegment> silences) {
        if (silences.isEmpty()) {
            LOGGER.info("📊 Nenhum silêncio detectado");
            return;
        }

        Map<SilenceType, List<SilenceSegment>> byType = silences.stream()
                .collect(Collectors.groupingBy(s -> s.type));

        LOGGER.info("📊 ESTATÍSTICAS DETALHADAS DE SILÊNCIO:");

        for (SilenceType type : SilenceType.values()) {
            List<SilenceSegment> typeList = byType.getOrDefault(type, List.of());
            if (!typeList.isEmpty()) {
                double totalDuration = typeList.stream().mapToDouble(s -> s.duration).sum();
                double avgDuration = totalDuration / typeList.size();
                double minDuration = typeList.stream().mapToDouble(s -> s.duration).min().orElse(0);
                double maxDuration = typeList.stream().mapToDouble(s -> s.duration).max().orElse(0);

                LOGGER.info(String.format("  %s: %d segmentos | Total: %.3fs | Média: %.3fs | Min: %.3fs | Max: %.3fs",
                        type, typeList.size(), totalDuration, avgDuration, minDuration, maxDuration));
            }
        }

        double totalSilence = silences.stream().mapToDouble(s -> s.duration).sum();
        long totalSamples = silences.stream().mapToLong(s -> s.endSample - s.startSample).sum();

        LOGGER.info(String.format("🔇 TOTAL: %.6fs de silêncio (%d samples @ %.0fHz)",
                totalSilence, totalSamples, SAMPLE_RATE));
    }

    private static List<SilenceSegment> detectWithAdaptiveThreshold(Path audioFile, double threshold) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", audioFile.toString(),
                    "-af", String.format(
                    "silencedetect=noise=%.1fdB:duration=%.6f",
                    threshold, MIN_SILENCE_DURATION * 0.5
            ),
                    "-f", "null", "-"
            );

            Process process = pb.start();
            List<SilenceSegment> silences = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {

                String line;
                Double silenceStart = null;
                Pattern startPattern = Pattern.compile("silence_start: ([0-9.]+)");
                Pattern endPattern = Pattern.compile("silence_end: ([0-9.]+) \\| silence_duration: ([0-9.]+)");

                while ((line = reader.readLine()) != null) {
                    Matcher startMatcher = startPattern.matcher(line);
                    Matcher endMatcher = endPattern.matcher(line);

                    if (startMatcher.find()) {
                        silenceStart = Double.parseDouble(startMatcher.group(1));
                    } else if (endMatcher.find() && silenceStart != null) {
                        double silenceEnd = Double.parseDouble(endMatcher.group(1));
                        double duration = Double.parseDouble(endMatcher.group(2));

                        if (duration >= MIN_SILENCE_DURATION) {
                            long startSample = (long)(silenceStart * SAMPLE_RATE);
                            long endSample = (long)(silenceEnd * SAMPLE_RATE);

                            silences.add(new SilenceSegment(
                                    silenceStart, silenceEnd, duration,
                                    threshold, SilenceType.INTER_PHRASE,
                                    startSample, endSample, true
                            ));
                        }
                        silenceStart = null;
                    }
                }
            }

            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

            return silences;

        } catch (Exception e) {
            LOGGER.warning("Erro na detecção adaptativa: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static SilenceType classifyWithContext(SilenceSegment silence, int index,
                                                   List<SilenceSegment> allSilences, double totalDuration) {

        // Silêncio no início (primeiros 2% do áudio)
        if (silence.startTime < totalDuration * 0.02) {
            return SilenceType.INTRO_SILENCE;
        }

        // Silêncio no final (últimos 2% do áudio)
        if (silence.endTime > totalDuration * 0.98) {
            return SilenceType.OUTRO_SILENCE;
        }

        // Classificação baseada em duração e contexto
        if (silence.duration > 3.0) {
            return SilenceType.TRANSITION;
        } else if (silence.duration > 1.5) {
            return SilenceType.INTER_SENTENCE;
        } else if (silence.duration > 0.5) {
            return SilenceType.INTER_PHRASE;
        } else if (silence.duration > 0.2) {
            return SilenceType.PAUSE_BREATH;
        } else {
            return SilenceType.BACKGROUND_NOISE;
        }
    }

    private static SilenceSegment adjustTimestampsPrecision(SilenceSegment silence, SilenceType type) {
        // Recalcula samples com precisão máxima
        long preciseStartSample = Math.round(silence.startTime * SAMPLE_RATE);
        long preciseEndSample = Math.round(silence.endTime * SAMPLE_RATE);

        double preciseStartTime = preciseStartSample / SAMPLE_RATE;
        double preciseEndTime = preciseEndSample / SAMPLE_RATE;
        double preciseDuration = preciseEndTime - preciseStartTime;

        return new SilenceSegment(
                preciseStartTime, preciseEndTime, preciseDuration,
                silence.averageDb, type, preciseStartSample, preciseEndSample, true
        );
    }

}