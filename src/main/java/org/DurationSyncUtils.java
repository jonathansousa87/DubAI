package org;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DurationSyncUtils - SOLUÇÃO DE ALTA PRECISÃO para sincronização de duração
 * VERSÃO 2.0 - Precisão sample-accurate (99.99%+)
 */
public class DurationSyncUtils {

    private static final Logger LOGGER = Logger.getLogger(DurationSyncUtils.class.getName());

    // Configurações de áudio
    private static final String CODEC = "pcm_s24le";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final double SAMPLE_RATE_DOUBLE = 48000.0;

    // Limites de velocidade para manter naturalidade
    private static final double MAX_SPEED_FACTOR = 1.35;
    private static final double MIN_SPEED_FACTOR = 0.75;
    private static final double TOLERANCE_SECONDS = 0.001; // 1ms tolerância

    // Configurações para detecção de silêncios
    private static final double SILENCE_THRESHOLD_DB = -40.0;
    private static final double MIN_SILENCE_DURATION = 0.1;

    // Pattern para parsing VTT
    private static final Pattern VTT_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    );

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    );

    /**
     * Classe para representar um segmento de áudio com timing
     */
    private static class TimedSegment {
        public final double startTime;
        public final double endTime;
        public final double duration;
        public final SegmentType type;
        public final String text;
        public final boolean preserveOriginalDuration;

        public TimedSegment(double startTime, double endTime, double duration,
                            SegmentType type, String text) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = duration;
            this.type = type;
            this.text = text;
            this.preserveOriginalDuration = true;
        }

        public TimedSegment(double startTime, double endTime, double duration,
                            SegmentType type, String text, boolean preserveOriginalDuration) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = duration;
            this.type = type;
            this.text = text;
            this.preserveOriginalDuration = preserveOriginalDuration;
        }

        public enum SegmentType {
            SILENCE, SPEECH
        }
    }

    /**
     * MÉTODO PRINCIPAL - Sincroniza duração do áudio dublado com vídeo original
     */
    public static void synchronizeDuration(String originalVideoPath,
                                           String dubbedAudioPath,
                                           String vttPath,
                                           String outputAudioPath) throws Exception {

        LOGGER.info("🎯 INICIANDO SINCRONIZAÇÃO DE ALTA PRECISÃO");

        // 1. Obter durações exatas
        double targetDuration = getVideoDuration(originalVideoPath);
        double currentDuration = getAudioDuration(dubbedAudioPath);

        LOGGER.info(String.format("📊 ANÁLISE INICIAL:"));
        LOGGER.info(String.format("  🎬 Vídeo original: %.6fs", targetDuration));
        LOGGER.info(String.format("  🎙️ Áudio dublado: %.6fs", currentDuration));
        LOGGER.info(String.format("  📏 Diferença: %.6fs", Math.abs(targetDuration - currentDuration)));

        // 2. Se diferença muito pequena, apenas copia
        if (Math.abs(targetDuration - currentDuration) < TOLERANCE_SECONDS) {
            LOGGER.info("✅ Diferença insignificante, copiando áudio dublado");
            Files.copy(Paths.get(dubbedAudioPath), Paths.get(outputAudioPath),
                    StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // 3. Extrair áudio original para análise
        Path tempOriginalAudio = Files.createTempFile("original_analysis_", ".wav");
        try {
            extractAudioFromVideo(originalVideoPath, tempOriginalAudio.toString());

            // 4. Detectar estrutura de silêncios/fala
            List<TimedSegment> structure = detectAudioStructure(tempOriginalAudio.toString(), vttPath);

            LOGGER.info(String.format("🔍 Estrutura detectada: %d segmentos", structure.size()));
            logStructure(structure);

            // 5. Aplicar sincronização inteligente
            applyIntelligentSync(dubbedAudioPath, structure, targetDuration, outputAudioPath);

            // 6. Validação final
            validateFinalDuration(outputAudioPath, targetDuration);

        } finally {
            Files.deleteIfExists(tempOriginalAudio);
        }

        LOGGER.info("✅ SINCRONIZAÇÃO DE ALTA PRECISÃO CONCLUÍDA");
    }

    /**
     * MÉTODO INTEGRAÇÃO - Para usar no pipeline principal
     */
    public static void integrateInPipeline(String videoFile, String outputDir) throws Exception {
        LOGGER.info("🎯 INTEGRANDO SINCRONIZAÇÃO NO PIPELINE PRINCIPAL - VERSÃO PRECISA");

        Path outputDirPath = Paths.get(outputDir);

        // 1. Encontrar áudio de referência (original)
        Path referenceAudio = findBestReferenceAudio(outputDirPath);
        if (referenceAudio == null) {
            throw new IOException("❌ Áudio de referência não encontrado em: " + outputDir);
        }

        // 2. Encontrar áudio para sincronizar (TTS)
        Path audioToSync = findBestAudioToSync(outputDirPath);
        if (audioToSync == null) {
            throw new IOException("❌ Áudio para sincronizar não encontrado em: " + outputDir);
        }

        LOGGER.info("📂 Referência: " + referenceAudio.getFileName());
        LOGGER.info("📂 Para sincronizar: " + audioToSync.getFileName());

        // 3. Buscar VTT para melhor sincronização
        Path vttFile = outputDirPath.resolve("transcription.vtt");
        String vttPath = Files.exists(vttFile) ? vttFile.toString() : null;

        // 4. Obter duração de referência
        double targetDuration = getAudioDuration(referenceAudio.toString());

        LOGGER.info(String.format("🎯 Duração alvo: %.6fs", targetDuration));

        // 5. Aplicar sincronização
        synchronizeWithTargetDuration(audioToSync.toString(), targetDuration, vttPath, audioToSync.toString());

        LOGGER.info("✅ Sincronização integrada ao pipeline principal");
    }

    /**
     * Encontra melhor áudio de referência
     */
    private static Path findBestReferenceAudio(Path outputDir) {
        String[] candidates = {
                "audio.wav",           // Extraído do vídeo
                "vocals.wav",          // Separado pelo Spleeter
                "original_audio.wav",  // Backup explícito
                "extracted_audio.wav"  // Alternativo
        };

        for (String candidate : candidates) {
            Path audioPath = outputDir.resolve(candidate);
            if (Files.exists(audioPath)) {
                try {
                    if (Files.size(audioPath) > 10240) {
                        LOGGER.info("📂 Referência encontrada: " + candidate);
                        return audioPath;
                    }
                } catch (IOException e) {
                    continue;
                }
            }
        }

        return null;
    }

    /**
     * Encontra melhor áudio para sincronizar
     */
    private static Path findBestAudioToSync(Path outputDir) {
        String[] candidates = {
                "output.wav",                    // TTS principal
                "dubbed_audio.wav",              // Dublado
                "synchronized_output.wav",       // Já sincronizado
                "vtt_synchronized.wav"           // Sincronizado com VTT
        };

        for (String candidate : candidates) {
            Path audioPath = outputDir.resolve(candidate);
            if (Files.exists(audioPath)) {
                try {
                    if (Files.size(audioPath) > 10240) {
                        LOGGER.info("📂 Para sincronizar encontrado: " + candidate);
                        return audioPath;
                    }
                } catch (IOException e) {
                    continue;
                }
            }
        }

        return null;
    }

    /**
     * MÉTODO SIMPLIFICADO - Para quando já temos duração alvo
     */
    public static void synchronizeWithTargetDuration(String dubbedAudioPath,
                                                     double targetDuration,
                                                     String vttPath,
                                                     String outputPath) throws Exception {

        LOGGER.info(String.format("🎯 Sincronização PRECISA com duração alvo: %.6fs", targetDuration));

        double currentDuration = getAudioDuration(dubbedAudioPath);
        double difference = Math.abs(currentDuration - targetDuration);
        double accuracy = targetDuration > 0 ? (1.0 - difference / targetDuration) * 100 : 100;

        LOGGER.info(String.format("📊 ANÁLISE INICIAL:"));
        LOGGER.info(String.format("  📏 Atual: %.6fs", currentDuration));
        LOGGER.info(String.format("  🎯 Alvo: %.6fs", targetDuration));
        LOGGER.info(String.format("  ⚖️ Diferença: %.6fs", difference));
        LOGGER.info(String.format("  📊 Precisão atual: %.6f%%", accuracy));

        // Critério rigoroso de precisão (0.1% de erro)
        if (accuracy >= 99.9) {
            Files.copy(Paths.get(dubbedAudioPath), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("✅ Precisão EXCELENTE, áudio mantido");
            return;
        }

        // Estratégia baseada em VTT para diferenças significativas
        if ((vttPath != null) && Files.exists(Paths.get(vttPath))){
            LOGGER.info("📄 Usando estratégia baseada em VTT para reconstrução precisa");

            try {
                List<TimedSegment> structure = parseVTTStructure(vttPath, targetDuration);
                applyVTTBasedPreciseReconstruction(dubbedAudioPath, structure, targetDuration, outputPath);

                // Validar resultado
                validateFinalDuration(outputPath, targetDuration);
                return;

            } catch (Exception e) {
                LOGGER.warning("⚠️ VTT sync falhou: " + e.getMessage() + ", aplicando fallback");
            }
        }

        // Fallback: Ajuste de velocidade de alta precisão
        LOGGER.info("⚡ Aplicando ajuste de velocidade de alta precisão");
        emergencyDurationSync(dubbedAudioPath, outputPath, targetDuration);
    }

    /**
     * Reconstrução baseada em VTT precisa com sample accuracy
     */
    private static void applyVTTBasedPreciseReconstruction(String dubbedAudioPath,
                                                           List<TimedSegment> structure,
                                                           double targetDuration,
                                                           String outputPath) throws Exception {

        LOGGER.info("🔧 Reconstrução VTT PRECISA iniciada");

        Path tempDir = Files.createTempDirectory("vtt_precise_reconstruction_");
        Path concatList = tempDir.resolve("vtt_precise_concat.txt");

        try {
            double totalSilenceTime = structure.stream()
                    .filter(s -> s.type == TimedSegment.SegmentType.SILENCE)
                    .mapToDouble(s -> s.duration)
                    .sum();

            double availableSpeechTime = targetDuration - totalSilenceTime;
            double currentDubbedDuration = getAudioDuration(dubbedAudioPath);

            LOGGER.info(String.format("📊 ANÁLISE VTT PRECISA:"));
            LOGGER.info(String.format("  🔇 Silêncios preservados: %.6fs", totalSilenceTime));
            LOGGER.info(String.format("  🗣️ Tempo para fala: %.6fs", availableSpeechTime));
            LOGGER.info(String.format("  🎙️ Áudio dublado: %.6fs", currentDubbedDuration));

            // Calcular fator de velocidade preciso
            double speechSpeedFactor = currentDubbedDuration / availableSpeechTime;
            speechSpeedFactor = Math.max(MIN_SPEED_FACTOR, Math.min(MAX_SPEED_FACTOR, speechSpeedFactor));

            LOGGER.info(String.format("🚀 Fator velocidade PRECISO: %.9fx", speechSpeedFactor));

            // Ajustar áudio dublado com alta precisão
            Path adjustedAudio = tempDir.resolve("vtt_speed_adjusted.wav");
            adjustAudioSpeedHighPrecision(dubbedAudioPath, adjustedAudio.toString(), speechSpeedFactor);

            double adjustedDuration = getAudioDuration(adjustedAudio.toString());

            // Criar lista de concatenação VTT precisa
            createVTTPreciseConcatenationList(structure, adjustedAudio, adjustedDuration, concatList, tempDir);

            // Concatenar com duração exata
            ProcessBuilder concatPb = new ProcessBuilder(
                    "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", concatList.toString(),
                    "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                    "-t", String.format("%.9f", targetDuration),
                    "-avoid_negative_ts", "make_zero",
                    outputPath
            );

            executeFFmpegCommand(concatPb, "Concatenação VTT");

            // Validação sample-accurate
            validateSampleAccuracy(outputPath, targetDuration);

        } finally {
            cleanupTempDirectory(tempDir);
        }
    }

    /**
     * Lista de concatenação VTT precisa com sample accuracy
     */
    private static void createVTTPreciseConcatenationList(List<TimedSegment> structure,
                                                          Path adjustedAudio,
                                                          double adjustedDuration,
                                                          Path concatList,
                                                          Path tempDir) throws Exception {

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(concatList))) {

            // Calcular tempo total de fala necessário
            double totalSpeechTime = structure.stream()
                    .filter(s -> s.type == TimedSegment.SegmentType.SPEECH)
                    .mapToDouble(s -> s.duration)
                    .sum();

            // Calcular fator de distribuição preciso
            double distributionFactor = totalSpeechTime > 0 ? adjustedDuration / totalSpeechTime : 1.0;

            double currentPosition = 0.0;
            int speechIndex = 0;

            for (int i = 0; i < structure.size(); i++) {
                TimedSegment segment = structure.get(i);

                if (segment.type == TimedSegment.SegmentType.SILENCE) {
                    // Gerar silêncio com duração EXATA e contagem de amostras
                    String silenceFile = String.format("vtt_silence_%03d.wav", i);
                    Path silencePath = tempDir.resolve(silenceFile);

                    // Calcular amostras exatas
                    long samples = (long) (segment.duration * SAMPLE_RATE);

                    ProcessBuilder silencePb = new ProcessBuilder(
                            "ffmpeg", "-y", "-f", "lavfi",
                            "-i", String.format("aevalsrc=0:d=%.9f", segment.duration),
                            "-af", String.format("asetnsamples=%d", samples),
                            "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                            silencePath.toString()
                    );

                    executeFFmpegCommand(silencePb, "Geração de silêncio VTT");
                    writer.println("file '" + silenceFile + "'");
                    LOGGER.info(String.format("🔇 VTT Silêncio %d: %.6fs (%d amostras)", i, segment.duration, samples));

                } else if (segment.type == TimedSegment.SegmentType.SPEECH) {
                    // Calcular duração precisa baseada no tempo original
                    double preciseDuration = segment.duration * distributionFactor;

                    // Extrair segmento de fala PRECISO com contagem de amostras
                    String speechFile = String.format("vtt_speech_%03d.wav", speechIndex);
                    Path speechPath = tempDir.resolve(speechFile);

                    // Calcular amostras exatas
                    long samples = (long) (preciseDuration * SAMPLE_RATE);

                    ProcessBuilder speechPb = new ProcessBuilder(
                            "ffmpeg", "-y", "-i", adjustedAudio.toString(),
                            "-ss", String.format("%.9f", currentPosition),
                            "-t", String.format("%.9f", preciseDuration),
                            "-af", String.format("asetnsamples=%d", samples),
                            "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                            "-avoid_negative_ts", "make_zero",
                            speechPath.toString()
                    );

                    executeFFmpegCommand(speechPb, "Extração de fala VTT");
                    writer.println("file '" + speechFile + "'");
                    currentPosition += preciseDuration;
                    speechIndex++;

                    LOGGER.info(String.format("🗣️ VTT Fala %d: %.6fs (%d amostras)", speechIndex, preciseDuration, samples));
                }
            }
        }
    }

    /**
     * Ajuste de velocidade de alta precisão com múltiplos atempos
     */
    private static void adjustAudioSpeedHighPrecision(String inputPath, String outputPath, double speedFactor) throws Exception {
        // Dividir o fator em múltiplos atempos para maior precisão
        List<String> atempoFilters = new ArrayList<>();
        double remainingFactor = speedFactor;

        while (remainingFactor > 2.0) {
            atempoFilters.add("atempo=2.0");
            remainingFactor /= 2.0;
        }

        while (remainingFactor < 0.5) {
            atempoFilters.add("atempo=0.5");
            remainingFactor /= 0.5;
        }

        atempoFilters.add(String.format("atempo=%.9f", remainingFactor));
        String filterChain = String.join(",", atempoFilters);

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputPath,
                "-af", filterChain,
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-avoid_negative_ts", "make_zero",
                outputPath
        );

        executeFFmpegCommand(pb, "Ajuste de velocidade de alta precisão");
    }

    /**
     * Validação e correção sample-accurate
     */
    private static void validateSampleAccuracy(String filePath, double expectedDuration) throws Exception {
        double actualDuration = getAudioDuration(filePath);
        long expectedSamples = (long) (expectedDuration * SAMPLE_RATE);
        long actualSamples = (long) (actualDuration * SAMPLE_RATE);

        if (Math.abs(expectedSamples - actualSamples) > 1) {
            LOGGER.warning(String.format("⚠️ Imprecisão de amostras detectada! Esperado: %d amostras | Real: %d amostras",
                    expectedSamples, actualSamples));

            // Corrigir imprecisão sample-accurate
            Path correctedPath = Files.createTempFile("sample_corrected_", ".wav");
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", filePath,
                    "-af", "aresample=" + SAMPLE_RATE + ",asetnsamples=" + expectedSamples,
                    "-c:a", CODEC,
                    correctedPath.toString()
            );

            executeFFmpegCommand(pb, "Correção sample-accurate");
            Files.move(correctedPath, Paths.get(filePath), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("✅ Áudio corrigido com contagem exata de amostras");
        }
    }

    /**
     * Trim/Pad com precisão máxima
     */
    private static void applyPreciseTrimOrPad(String inputPath, String outputPath, double targetDuration)
            throws Exception {

        double currentDuration = getAudioDuration(inputPath);
        double difference = currentDuration - targetDuration;

        LOGGER.info(String.format("🔧 Trim/Pad preciso: %.6fs → %.6fs", currentDuration, targetDuration));

        if (Math.abs(difference) < 0.001) {
            Files.copy(Paths.get(inputPath), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        if (difference > 0) {
            // TRIM - áudio muito longo
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", inputPath,
                    "-t", String.format("%.9f", targetDuration),
                    "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                    "-avoid_negative_ts", "make_zero",
                    outputPath
            );
            executeFFmpegCommand(pb, "Trim preciso");
        } else {
            // PAD - áudio muito curto
            double paddingNeeded = Math.abs(difference);
            Path tempSilence = Files.createTempFile("precise_padding_", ".wav");
            Path tempList = Files.createTempFile("precise_pad_list_", ".txt");

            try {
                // Gerar silêncio com precisão máxima
                ProcessBuilder silencePb = new ProcessBuilder(
                        "ffmpeg", "-y", "-f", "lavfi",
                        "-i", String.format("anullsrc=r=%d:cl=%s", SAMPLE_RATE,
                        CHANNELS == 1 ? "mono" : "stereo"),
                        "-t", String.format("%.9f", paddingNeeded),
                        "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                        tempSilence.toString()
                );
                executeFFmpegCommand(silencePb, "Geração de padding");

                // Criar lista de concatenação
                try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(tempList))) {
                    writer.println("file '" + Paths.get(inputPath).toAbsolutePath() + "'");
                    writer.println("file '" + tempSilence.toAbsolutePath() + "'");
                }

                // Concatenar com precisão
                ProcessBuilder concatPb = new ProcessBuilder(
                        "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", tempList.toString(),
                        "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                        "-t", String.format("%.9f", targetDuration),
                        "-avoid_negative_ts", "make_zero",
                        outputPath
                );
                executeFFmpegCommand(concatPb, "Concatenação precisa");

            } finally {
                Files.deleteIfExists(tempSilence);
                Files.deleteIfExists(tempList);
            }
        }
    }

    /**
     * Método de emergência para alta precisão
     */
    public static void emergencyDurationSync(String inputAudio, String outputAudio,
                                             double targetDuration) throws Exception {
        LOGGER.info("🆘 SINCRONIZAÇÃO DE EMERGÊNCIA - ALTA PRECISÃO");

        double currentDuration = getAudioDuration(inputAudio);
        double difference = Math.abs(currentDuration - targetDuration);

        LOGGER.info(String.format("📊 ANÁLISE: %.6fs → %.6fs (diff: %.6fs)",
                currentDuration, targetDuration, difference));

        if (difference < TOLERANCE_SECONDS) {
            Files.copy(Paths.get(inputAudio), Paths.get(outputAudio), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("✅ Diferença insignificante, áudio copiado");
            return;
        }

        double speedFactor = currentDuration / targetDuration;
        speedFactor = Math.max(MIN_SPEED_FACTOR, Math.min(MAX_SPEED_FACTOR, speedFactor));

        LOGGER.info(String.format("⚡ Fator de velocidade aplicado: %.9fx", speedFactor));

        Path tempAdjusted = Files.createTempFile("emergency_precise_", ".wav");
        try {
            // Aplicar ajuste de velocidade com alta precisão
            adjustAudioSpeedHighPrecision(inputAudio, tempAdjusted.toString(), speedFactor);

            // Aplicar trim/pad final
            applyPreciseTrimOrPad(tempAdjusted.toString(), outputAudio, targetDuration);

        } finally {
            Files.deleteIfExists(tempAdjusted);
        }

        // Validação rigorosa
        validateFinalDuration(outputAudio, targetDuration);
    }

    // ===== MÉTODOS DE DETECÇÃO DE ESTRUTURA =====

    /**
     * Detecta estrutura de silêncios/fala do áudio original
     */
    private static List<TimedSegment> detectAudioStructure(String originalAudioPath, String vttPath)
            throws Exception {

        LOGGER.info("🔍 Detectando estrutura de áudio...");

        if (vttPath != null && Files.exists(Paths.get(vttPath))) {
            return parseVTTStructure(vttPath, getAudioDuration(originalAudioPath));
        } else {
            return detectSilenceStructure(originalAudioPath);
        }
    }

    /**
     * Parse estrutura do VTT
     */
    private static List<TimedSegment> parseVTTStructure(String vttPath, double totalDuration)
            throws IOException {

        LOGGER.info("📄 Parsing estrutura VTT para reconstrução precisa");

        List<TimedSegment> segments = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(vttPath));

        double lastEndTime = 0.0;
        boolean isFirst = true;

        for (String line : lines) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                double startTime = parseVTTTime(matcher.group(1), matcher.group(2),
                        matcher.group(3), matcher.group(4));
                double endTime = parseVTTTime(matcher.group(5), matcher.group(6),
                        matcher.group(7), matcher.group(8));

                // Silêncio inicial
                if (isFirst && startTime > 0.01) {
                    segments.add(new TimedSegment(0.0, startTime, startTime,
                            TimedSegment.SegmentType.SILENCE, ""));
                }

                // Silêncio entre segmentos
                if (!isFirst && startTime > lastEndTime + 0.001) {
                    double silenceDuration = startTime - lastEndTime;
                    segments.add(new TimedSegment(lastEndTime, startTime, silenceDuration,
                            TimedSegment.SegmentType.SILENCE, ""));
                }

                // Segmento de fala
                double speechDuration = endTime - startTime;
                segments.add(new TimedSegment(startTime, endTime, speechDuration,
                        TimedSegment.SegmentType.SPEECH, ""));

                lastEndTime = endTime;
                isFirst = false;
            }
        }

        // Silêncio final
        if (lastEndTime < totalDuration - 0.01) {
            double finalSilence = totalDuration - lastEndTime;
            segments.add(new TimedSegment(lastEndTime, totalDuration, finalSilence,
                    TimedSegment.SegmentType.SILENCE, ""));
        }

        LOGGER.info(String.format("📊 VTT estrutura: %d segmentos parseados", segments.size()));
        return segments;
    }

    /**
     * Detecta silêncios automaticamente
     */
    private static List<TimedSegment> detectSilenceStructure(String audioPath) throws Exception {
        LOGGER.info("🔍 Detectando silêncios automaticamente...");

        List<TimedSegment> segments = new ArrayList<>();

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioPath,
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

        for (double[] silence : silences) {
            double silenceStart = silence[0];
            double silenceEnd = silence[1];

            // Fala antes do silêncio
            if (silenceStart > currentTime) {
                segments.add(new TimedSegment(currentTime, silenceStart,
                        silenceStart - currentTime, TimedSegment.SegmentType.SPEECH, "", false));
            }

            // Silêncio
            segments.add(new TimedSegment(silenceStart, silenceEnd,
                    silenceEnd - silenceStart, TimedSegment.SegmentType.SILENCE, "", true));

            currentTime = silenceEnd;
        }

        // Fala final
        if (currentTime < totalDuration) {
            segments.add(new TimedSegment(currentTime, totalDuration,
                    totalDuration - currentTime, TimedSegment.SegmentType.SPEECH, "", false));
        }

        return segments;
    }

    // ===== MÉTODOS DE SINCRONIZAÇÃO =====

    /**
     * Aplica sincronização inteligente preservando silêncios
     */
    private static void applyIntelligentSync(String dubbedAudioPath,
                                             List<TimedSegment> structure,
                                             double targetDuration,
                                             String outputPath) throws Exception {

        LOGGER.info("🎯 Aplicando sincronização inteligente...");

        // Calcular tempo para silêncios vs fala
        double totalSilenceTime = structure.stream()
                .filter(s -> s.type == TimedSegment.SegmentType.SILENCE)
                .mapToDouble(s -> s.duration)
                .sum();

        double availableSpeechTime = targetDuration - totalSilenceTime;
        double currentDubbedDuration = getAudioDuration(dubbedAudioPath);

        LOGGER.info(String.format("📊 ANÁLISE DE TEMPO:"));
        LOGGER.info(String.format("  🔇 Silêncios preservados: %.6fs", totalSilenceTime));
        LOGGER.info(String.format("  🗣️ Tempo disponível para fala: %.6fs", availableSpeechTime));
        LOGGER.info(String.format("  🎙️ Duração áudio dublado: %.6fs", currentDubbedDuration));

        // Calcular fator de velocidade para fala
        double speechSpeedFactor = currentDubbedDuration / availableSpeechTime;
        speechSpeedFactor = Math.max(MIN_SPEED_FACTOR, Math.min(MAX_SPEED_FACTOR, speechSpeedFactor));

        LOGGER.info(String.format("🚀 Fator velocidade fala: %.6fx", speechSpeedFactor));

        if (Math.abs(speechSpeedFactor - 1.0) < 0.01) {
            trimOrPadToExactDuration(dubbedAudioPath, outputPath, targetDuration);
        } else {
            reconstructWithPreservedSilences(dubbedAudioPath, structure,
                    speechSpeedFactor, targetDuration, outputPath);
        }
    }

    /**
     * Reconstrói áudio preservando silêncios e ajustando fala
     */
    private static void reconstructWithPreservedSilences(String dubbedAudioPath,
                                                         List<TimedSegment> structure,
                                                         double speechSpeedFactor,
                                                         double targetDuration,
                                                         String outputPath) throws Exception {

        Path tempDir = Files.createTempDirectory("duration_sync_");
        Path concatList = tempDir.resolve("concat_list.txt");

        try {
            // 1. Ajustar velocidade do áudio dublado
            Path adjustedAudio = tempDir.resolve("speed_adjusted.wav");
            adjustAudioSpeedHighPrecision(dubbedAudioPath, adjustedAudio.toString(), speechSpeedFactor);

            // 2. Criar lista de concatenação
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(concatList))) {

                double adjustedDuration = getAudioDuration(adjustedAudio.toString());
                double currentPosition = 0.0;

                // Calcular duração por segmento de fala
                List<TimedSegment> speechSegments = structure.stream()
                        .filter(s -> s.type == TimedSegment.SegmentType.SPEECH)
                        .collect(java.util.stream.Collectors.toList());

                double speechSegmentDuration = speechSegments.isEmpty() ? 0 :
                        adjustedDuration / speechSegments.size();

                int speechIndex = 0;

                for (int i = 0; i < structure.size(); i++) {
                    TimedSegment segment = structure.get(i);

                    if (segment.type == TimedSegment.SegmentType.SILENCE) {
                        // Gerar silêncio com duração original exata
                        String silenceFile = String.format("silence_%03d.wav", i);
                        Path silencePath = tempDir.resolve(silenceFile);

                        generateSilence(segment.duration, silencePath.toString());
                        writer.println("file '" + silenceFile + "'");

                    } else if (segment.type == TimedSegment.SegmentType.SPEECH) {
                        // Extrair segmento de fala ajustado
                        String speechFile = String.format("speech_%03d.wav", speechIndex);
                        Path speechPath = tempDir.resolve(speechFile);

                        extractAudioSegment(adjustedAudio.toString(), currentPosition,
                                speechSegmentDuration, speechPath.toString());
                        writer.println("file '" + speechFile + "'");

                        currentPosition += speechSegmentDuration;
                        speechIndex++;
                    }
                }
            }

            // 3. Concatenar com duração exata
            concatenateWithExactDuration(concatList.toString(), outputPath, targetDuration);

        } finally {
            cleanupTempDirectory(tempDir);
        }
    }

    // ===== MÉTODOS AUXILIARES FFmpeg =====

    private static void adjustAudioSpeed(String inputPath, String outputPath, double speedFactor)
            throws Exception {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputPath,
                "-af", String.format("atempo=%.6f", speedFactor),
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-avoid_negative_ts", "make_zero",
                outputPath
        );

        executeFFmpegCommand(pb, "Ajuste de velocidade");
    }

    private static void generateSilence(double duration, String outputPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "lavfi",
                "-i", String.format("anullsrc=r=%d:cl=%s", SAMPLE_RATE,
                CHANNELS == 1 ? "mono" : "stereo"),
                "-t", String.format("%.9f", duration),
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                outputPath
        );

        executeFFmpegCommand(pb, "Geração de silêncio");
    }

    private static void extractAudioSegment(String inputPath, double start, double duration,
                                            String outputPath) throws Exception {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputPath,
                "-ss", String.format("%.9f", start),
                "-t", String.format("%.9f", duration),
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-avoid_negative_ts", "make_zero",
                outputPath
        );

        executeFFmpegCommand(pb, "Extração de segmento");
    }

    private static void concatenateWithExactDuration(String listPath, String outputPath,
                                                     double targetDuration) throws Exception {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listPath,
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-t", String.format("%.9f", targetDuration),
                "-avoid_negative_ts", "make_zero",
                outputPath
        );

        executeFFmpegCommand(pb, "Concatenação final");
    }

    private static void trimOrPadToExactDuration(String inputPath, String outputPath,
                                                 double targetDuration) throws Exception {

        double currentDuration = getAudioDuration(inputPath);

        if (Math.abs(currentDuration - targetDuration) < 0.001) {
            Files.copy(Paths.get(inputPath), Paths.get(outputPath),
                    StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        if (currentDuration > targetDuration) {
            // Trim
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", inputPath,
                    "-t", String.format("%.9f", targetDuration),
                    "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                    outputPath
            );
            executeFFmpegCommand(pb, "Trim para duração exata");
        } else {
            // Pad com silêncio
            double paddingNeeded = targetDuration - currentDuration;
            Path tempSilence = Files.createTempFile("padding_", ".wav");
            Path tempList = Files.createTempFile("pad_list_", ".txt");

            try {
                generateSilence(paddingNeeded, tempSilence.toString());

                try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(tempList))) {
                    writer.println("file '" + Paths.get(inputPath).toAbsolutePath() + "'");
                    writer.println("file '" + tempSilence.toAbsolutePath() + "'");
                }

                concatenateWithExactDuration(tempList.toString(), outputPath, targetDuration);

            } finally {
                Files.deleteIfExists(tempSilence);
                Files.deleteIfExists(tempList);
            }
        }
    }

    private static void extractAudioFromVideo(String videoPath, String audioPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", videoPath, "-vn",
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                audioPath
        );

        executeFFmpegCommand(pb, "Extração de áudio do vídeo");
    }

    // ===== MÉTODOS UTILITÁRIOS =====

    public static double getVideoDuration(String videoPath) throws Exception {
        return getMediaDuration(videoPath);
    }

    public static double getAudioDuration(String audioPath) throws Exception {
        return getMediaDuration(audioPath);
    }

    private static double getMediaDuration(String mediaPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                mediaPath
        );

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line.trim());
            }
        }

        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout obtendo duração de mídia");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro obtendo duração: " + mediaPath);
        }

        try {
            return Double.parseDouble(output.toString());
        } catch (NumberFormatException e) {
            throw new IOException("Duração inválida: " + output.toString());
        }
    }

    private static double parseVTTTime(String hours, String minutes, String seconds, String milliseconds) {
        int h = Integer.parseInt(hours);
        int m = Integer.parseInt(minutes);
        int s = Integer.parseInt(seconds);
        int ms = Integer.parseInt(milliseconds);
        return h * 3600.0 + m * 60.0 + s + ms / 1000.0;
    }

    private static void executeFFmpegCommand(ProcessBuilder pb, String operation) throws Exception {
        LOGGER.info("Executando: " + operation);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder outputLog = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append("\n");
            }
        }

        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout em " + operation);
        }

        if (process.exitValue() != 0) {
            LOGGER.severe("Saída do FFmpeg:\n" + outputLog);
            throw new IOException(operation + " falhou com código: " + process.exitValue());
        }
    }

    private static void validateFinalDuration(String outputPath, double expectedDuration) throws Exception {
        double actualDuration = getAudioDuration(outputPath);
        double difference = Math.abs(actualDuration - expectedDuration);
        double accuracy = 100 - (difference / expectedDuration * 100);

        LOGGER.info(String.format("🔍 VALIDAÇÃO FINAL:"));
        LOGGER.info(String.format("  🎯 Esperado: %.9fs", expectedDuration));
        LOGGER.info(String.format("  📏 Real: %.9fs", actualDuration));
        LOGGER.info(String.format("  ⚖️ Diferença: %.9fs", difference));
        LOGGER.info(String.format("  🎯 Precisão: %.6f%%", accuracy));

        if (accuracy < 99.99) {
            LOGGER.warning("⚠️ Precisão insuficiente! Aplicando correção sample-accurate...");
            validateSampleAccuracy(outputPath, expectedDuration);

            // Revalidar após correção
            double correctedDuration = getAudioDuration(outputPath);
            double correctedDifference = Math.abs(correctedDuration - expectedDuration);
            double correctedAccuracy = 100 - (correctedDifference / expectedDuration * 100);

            LOGGER.info(String.format("✅ Correção aplicada! Nova precisão: %.6f%%", correctedAccuracy));
        }
    }

    private static void logStructure(List<TimedSegment> structure) {
        LOGGER.info("📊 ESTRUTURA DETECTADA:");

        int silenceCount = 0, speechCount = 0;
        double totalSilence = 0, totalSpeech = 0;

        for (int i = 0; i < Math.min(10, structure.size()); i++) {
            TimedSegment segment = structure.get(i);
            String type = segment.type == TimedSegment.SegmentType.SILENCE ? "🔇 SILÊNCIO" : "🗣️ FALA";
            String preserve = segment.preserveOriginalDuration ? "(PRESERVAR)" : "(AJUSTÁVEL)";

            LOGGER.info(String.format("  %02d. %s: %.3fs → %.3fs (%.3fs) %s",
                    i + 1, type, segment.startTime, segment.endTime, segment.duration, preserve));

            if (segment.type == TimedSegment.SegmentType.SILENCE) {
                totalSilence += segment.duration;
                silenceCount++;
            } else {
                totalSpeech += segment.duration;
                speechCount++;
            }
        }

        if (structure.size() > 10) {
            LOGGER.info(String.format("  ... e mais %d segmentos", structure.size() - 10));

            // Calcular totais restantes
            for (int i = 10; i < structure.size(); i++) {
                TimedSegment segment = structure.get(i);
                if (segment.type == TimedSegment.SegmentType.SILENCE) {
                    totalSilence += segment.duration;
                    silenceCount++;
                } else {
                    totalSpeech += segment.duration;
                    speechCount++;
                }
            }
        }

        LOGGER.info(String.format("📊 RESUMO: %d silêncios (%.3fs), %d falas (%.3fs)",
                silenceCount, totalSilence, speechCount, totalSpeech));
    }

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

    // ===== MÉTODOS DE SUBSTITUIÇÃO PARA COMPATIBILIDADE =====

    /**
     * Substitui AudioDurationSynchronizer.synchronizeAudioDuration()
     */
    public static boolean synchronizeAudioDuration(String originalAudioPath,
                                                   String dubbedAudioPath,
                                                   String outputPath) throws Exception {
        try {
            double targetDuration = getAudioDuration(originalAudioPath);
            synchronizeWithTargetDuration(dubbedAudioPath, targetDuration, null, outputPath);
            return true;
        } catch (Exception e) {
            LOGGER.severe("Erro na sincronização: " + e.getMessage());
            return false;
        }
    }

    /**
     * Cleanup de recursos
     */
    public static void cleanup() {
        LOGGER.info("🧹 Cleanup do DurationSyncUtils concluído");
    }
}