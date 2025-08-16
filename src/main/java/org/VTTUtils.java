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
 * VTTUtils - SOLUÇÃO CONSOLIDADA DEFINITIVA para processamento de VTT e integração de pipeline
 *
 * Consolida funcionalidades de:
 * - VTTSyncUtils
 * - VTTDebugTool
 * - SilencePipelineIntegration
 *
 * FUNCIONALIDADES:
 * 1. Parsing robusto de VTT (múltiplos formatos)
 * 2. Debug e validação de VTT
 * 3. Sincronização baseada em timestamps
 * 4. Integração com pipeline principal
 * 5. Detecção automática de formato
 */
public class VTTUtils {

    private static final Logger LOGGER = Logger.getLogger(VTTUtils.class.getName());

    // Configurações de áudio
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final String CODEC = "pcm_s24le";

    // Configurações de sincronização
    private static final double MAX_SPEED_FACTOR = 1.35;
    private static final double MIN_SPEED_FACTOR = 0.75;
    private static final double TOLERANCE_SECONDS = 0.1;

    // Padrões para parsing de VTT (suporte a múltiplos formatos)
    private static final Pattern[] VTT_PATTERNS = {
            // Formato padrão: HH:MM:SS.mmm --> HH:MM:SS.mmm
            Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"),
            // Formato alternativo: MM:SS.mmm --> MM:SS.mmm
            Pattern.compile("(\\d{1,2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2})[.,](\\d{3})"),
            // Formato com espaços extras
            Pattern.compile("\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*"),
            // Formato flexível com qualquer separador
            Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[\\.,:](\\d{1,3})\\s*[-=]*>\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[\\.,:](\\d{1,3})")
    };

    // Executor para processamento paralelo
    private static final ExecutorService processingExecutor =
            Executors.newFixedThreadPool(Math.min(6, Runtime.getRuntime().availableProcessors()));

    /**
     * Classe para representar um segmento VTT com timing preciso
     */
    public static class VTTSegment {
        public final double startTime;
        public final double endTime;
        public final double duration;
        public final String text;
        public final int index;
        public final double silenceBeforeSegment;
        public final long startSample;
        public final long endSample;

        public VTTSegment(double startTime, double endTime, String text, int index) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = endTime - startTime;
            this.text = text != null ? text.trim() : "";
            this.index = index;
            this.silenceBeforeSegment = 0.0; // Será calculado posteriormente
            this.startSample = Math.round(startTime * SAMPLE_RATE);
            this.endSample = Math.round(endTime * SAMPLE_RATE);
        }

        public VTTSegment withSilenceBefore(double silence) {
            VTTSegment newSegment = new VTTSegment(startTime, endTime, text, index);
            return new VTTSegment(startTime, endTime, text, index, silence);
        }

        private VTTSegment(double startTime, double endTime, String text, int index, double silenceBefore) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = endTime - startTime;
            this.text = text != null ? text.trim() : "";
            this.index = index;
            this.silenceBeforeSegment = silenceBefore;
            this.startSample = Math.round(startTime * SAMPLE_RATE);
            this.endSample = Math.round(endTime * SAMPLE_RATE);
        }

        @Override
        public String toString() {
            return String.format("VTT[%d]: %.3fs-%.3fs (%.3fs) | %.3fs silence | %s",
                    index, startTime, endTime, duration, silenceBeforeSegment, text);
        }
    }

    /**
     * Resultado da análise de VTT
     */
    public static class VTTAnalysis {
        public final List<VTTSegment> segments;
        public final double totalDuration;
        public final boolean isValid;
        public final String formatDetected;
        public final List<String> errors;
        public final Map<String, Integer> statistics;

        public VTTAnalysis(List<VTTSegment> segments, double totalDuration, boolean isValid,
                           String formatDetected, List<String> errors, Map<String, Integer> statistics) {
            this.segments = segments;
            this.totalDuration = totalDuration;
            this.isValid = isValid;
            this.formatDetected = formatDetected;
            this.errors = errors;
            this.statistics = statistics;
        }
    }

    /**
     * MÉTODO PRINCIPAL - Analisa arquivo VTT com detecção automática de formato
     *
     * @param vttFilePath Caminho do arquivo VTT
     * @return Análise completa do VTT
     */
    public static VTTAnalysis analyzeVTT(String vttFilePath) throws IOException {
        LOGGER.info("🔍 Analisando arquivo VTT: " + Paths.get(vttFilePath).getFileName());

        Path vttPath = Paths.get(vttFilePath);

        if (!Files.exists(vttPath)) {
            throw new IOException("Arquivo VTT não encontrado: " + vttFilePath);
        }

        List<String> lines = Files.readAllLines(vttPath);
        List<VTTSegment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> statistics = new HashMap<>();

        // Inicializar estatísticas
        statistics.put("totalLines", lines.size());
        statistics.put("emptyLines", 0);
        statistics.put("timestampLines", 0);
        statistics.put("textLines", 0);
        statistics.put("headerLines", 0);

        boolean foundWebVTT = false;
        String formatDetected = "Unknown";
        int segmentIndex = 1;

        // Primeira passada: detectar formato e coletar estatísticas básicas
        for (String line : lines) {
            String trimmedLine = line.trim();

            if (trimmedLine.isEmpty()) {
                statistics.put("emptyLines", statistics.get("emptyLines") + 1);
            } else if (trimmedLine.startsWith("WEBVTT")) {
                foundWebVTT = true;
                statistics.put("headerLines", statistics.get("headerLines") + 1);
            } else if (isTimestampLine(trimmedLine)) {
                statistics.put("timestampLines", statistics.get("timestampLines") + 1);
            } else if (!trimmedLine.matches("^\\d+$") && !trimmedLine.startsWith("NOTE")) {
                statistics.put("textLines", statistics.get("textLines") + 1);
            }
        }

        // Segunda passada: parsing dos segmentos
        String currentText = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Detecta linha de timestamp
            VTTTimestamp timestamp = parseTimestamp(line);
            if (timestamp != null) {
                formatDetected = timestamp.formatUsed;

                // Busca texto associado (próximas linhas não vazias)
                StringBuilder textBuilder = new StringBuilder();
                for (int j = i + 1; j < lines.size(); j++) {
                    String textLine = lines.get(j).trim();
                    if (textLine.isEmpty()) break;
                    if (isTimestampLine(textLine)) break;
                    if (textLine.matches("^\\d+$")) break;

                    if (textBuilder.length() > 0) textBuilder.append(" ");
                    textBuilder.append(textLine);
                }

                try {
                    VTTSegment segment = new VTTSegment(
                            timestamp.startTime, timestamp.endTime,
                            textBuilder.toString(), segmentIndex++
                    );
                    segments.add(segment);

                } catch (Exception e) {
                    errors.add(String.format("Erro criando segmento na linha %d: %s", i + 1, e.getMessage()));
                }
            }
        }

        // Calcular silêncios entre segmentos
        List<VTTSegment> segmentsWithSilences = calculateSilenceGaps(segments);

        // Determinar duração total
        double totalDuration = segmentsWithSilences.isEmpty() ? 0.0 :
                segmentsWithSilences.get(segmentsWithSilences.size() - 1).endTime;

        // Validar resultado
        boolean isValid = foundWebVTT && !segmentsWithSilences.isEmpty() && errors.isEmpty();

        if (!foundWebVTT) {
            errors.add("WEBVTT header não encontrado");
        }
        if (segmentsWithSilences.isEmpty()) {
            errors.add("Nenhum segmento válido encontrado");
        }

        VTTAnalysis analysis = new VTTAnalysis(
                segmentsWithSilences, totalDuration, isValid, formatDetected, errors, statistics
        );

        logAnalysisResults(analysis, vttPath);
        return analysis;
    }

    /**
     * MÉTODO PRINCIPAL - Sincroniza áudio com base no VTT
     *
     * @param vttFilePath Caminho do arquivo VTT
     * @param outputDir Diretório de saída
     */
    public static void synchronizeWithVTT(String vttFilePath, String outputDir) throws IOException, InterruptedException {
        LOGGER.info("🎯 Iniciando sincronização com VTT");

        Path vttPath = Paths.get(vttFilePath);
        Path outputDirPath = Paths.get(outputDir);

        // 1. Analisar VTT
        VTTAnalysis analysis = analyzeVTT(vttFilePath);

        if (!analysis.isValid) {
            LOGGER.warning("⚠️ VTT inválido, aplicando correção automática...");
            Path correctedVTT = outputDirPath.resolve("transcription_corrected.vtt");
            autoFixVTT(vttPath, correctedVTT);
            analysis = analyzeVTT(correctedVTT.toString());
        }

        if (!analysis.isValid) {
            throw new IOException("VTT não pôde ser corrigido automaticamente");
        }

        // 2. Encontrar áudio para sincronização
        Path audioToSync = findAudioForSync(outputDirPath);

        if (audioToSync == null) {
            throw new IOException("Nenhum áudio encontrado para sincronização em: " + outputDir);
        }

        LOGGER.info("📂 Áudio encontrado para sync: " + audioToSync.getFileName());

        // 3. Aplicar sincronização baseada no tipo de áudio
        Path syncedAudio = outputDirPath.resolve("vtt_synchronized.wav");

        if (audioToSync.getFileName().toString().equals("output.wav")) {
            // Áudio TTS - usar correção de gaps
            applySilenceGapsCorrection(analysis, audioToSync, syncedAudio);
        } else {
            // Áudio original - usar ajuste de velocidade
            applySpeedBasedSync(analysis, audioToSync, syncedAudio);
        }

        // 4. Substituir áudio original
        Files.move(syncedAudio, audioToSync, StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("✅ Sincronização com VTT concluída");
    }

    /**
     * MÉTODO PRINCIPAL - Integra VTT no pipeline principal
     *
     * @param originalVideoPath Caminho do vídeo original
     * @param outputDirectory Diretório de saída
     */
    public static void integrateWithMainPipeline(String originalVideoPath, String outputDirectory)
            throws IOException, InterruptedException {

        LOGGER.info("🔗 INTEGRANDO VTT NO PIPELINE PRINCIPAL");

        Path outputDir = Paths.get(outputDirectory);
        
        // Primeiro tentar usar o backup original do WhisperX (preserva timestamps originais)
        Path originalVttFile = outputDir.resolve("transcription_whisperx_original.vtt");
        Path vttFile;
        
        if (Files.exists(originalVttFile)) {
            vttFile = originalVttFile;
            LOGGER.info("🎯 Usando timestamps originais do WhisperX: " + originalVttFile.getFileName());
        } else {
            vttFile = outputDir.resolve("transcription.vtt");
            LOGGER.warning("⚠️ Backup original não encontrado, usando transcription.vtt (pode ter timestamps modificados)");
        }

        if (!Files.exists(vttFile)) {
            LOGGER.warning("⚠️ VTT não encontrado, pipeline sem sincronização VTT");
            handlePipelineWithoutVTT(originalVideoPath, outputDirectory);
            return;
        }

        try {
            // 1. Extrair áudio original se necessário
            Path originalAudio = ensureOriginalAudioExists(originalVideoPath, outputDirectory);

            // 2. Analisar VTT
            VTTAnalysis analysis = analyzeVTT(vttFile.toString());

            // 3. Encontrar áudios processados
            List<Path> processedAudios = findProcessedAudioSegments(outputDirectory);

            // 4. Aplicar estratégia baseada no que está disponível
            if (!processedAudios.isEmpty()) {
                // Temos áudios processados - preservar silêncios originais
                applyPreservationStrategy(originalAudio, processedAudios, analysis, outputDirectory);
            } else {
                // Apenas TTS - aplicar correção de gaps
                Path ttsOutput = outputDir.resolve("output.wav");
                if (Files.exists(ttsOutput)) {
                    applyTTSGapStrategy(analysis, ttsOutput, outputDirectory);
                } else {
                    throw new IOException("Nenhum áudio processado encontrado");
                }
            }

            LOGGER.info("✅ Integração VTT no pipeline concluída");

        } catch (Exception e) {
            LOGGER.severe("❌ Erro na integração VTT: " + e.getMessage());
            handleIntegrationFailure(originalVideoPath, outputDirectory, e);
        }
    }

    /**
     * MÉTODO PRINCIPAL - Debug completo de arquivo VTT
     *
     * @param vttFilePath Caminho do arquivo VTT
     */
    public static void debugVTT(String vttFilePath) throws IOException {
        LOGGER.info("🔍 DEBUG COMPLETO VTT: " + Paths.get(vttFilePath).getFileName());

        Path vttPath = Paths.get(vttFilePath);

        if (!Files.exists(vttPath)) {
            LOGGER.severe("❌ Arquivo VTT não encontrado: " + vttFilePath);
            return;
        }

        List<String> lines = Files.readAllLines(vttPath);

        LOGGER.info("📁 Arquivo: " + vttPath.getFileName());
        LOGGER.info("📊 Total de linhas: " + lines.size());
        LOGGER.info("📦 Tamanho: " + Files.size(vttPath) + " bytes");
        LOGGER.info("");

        // Análise detalhada
        VTTAnalysis analysis = analyzeVTT(vttFilePath);

        // Log das primeiras linhas para debug
        LOGGER.info("🔍 PRIMEIRAS 20 LINHAS:");
        for (int i = 0; i < Math.min(20, lines.size()); i++) {
            String line = lines.get(i);
            String status = "";

            if (line.trim().startsWith("WEBVTT")) status = " ✅ HEADER";
            else if (isTimestampLine(line.trim())) status = " 🕐 TIMESTAMP";
            else if (line.trim().isEmpty()) status = " ⬜ VAZIO";
            else if (line.trim().matches("^\\d+$")) status = " 🔢 NÚMERO";
            else status = " 📝 TEXTO";

            LOGGER.info(String.format("  %2d: '%s'%s", i + 1, line, status));
        }

        // Debug de parsing de timestamps específicos
        LOGGER.info("");
        LOGGER.info("🔍 TESTE DE PARSING DE TIMESTAMPS:");
        for (int i = 0; i < Math.min(5, analysis.segments.size()); i++) {
            VTTSegment segment = analysis.segments.get(i);
            LOGGER.info(String.format("  Segmento %d: %.3fs → %.3fs | %s",
                    segment.index, segment.startTime, segment.endTime, segment.text));
        }

        // Detectar padrões alternativos se houver problemas
        if (!analysis.isValid) {
            LOGGER.info("");
            LOGGER.info("⚠️ PROCURANDO PADRÕES ALTERNATIVOS:");
            detectAlternativePatterns(lines);
        }
    }

    // ===== MÉTODOS DE PARSING =====

    /**
     * Classe para resultado de parsing de timestamp
     */
    private static class VTTTimestamp {
        public final double startTime;
        public final double endTime;
        public final String formatUsed;

        public VTTTimestamp(double startTime, double endTime, String formatUsed) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.formatUsed = formatUsed;
        }
    }

    /**
     * Parse robusto de timestamp com suporte a múltiplos formatos
     */
    private static VTTTimestamp parseTimestamp(String line) {
        if (!line.contains("-->")) {
            return null;
        }

        // Tenta cada padrão até encontrar um que funcione
        for (int i = 0; i < VTT_PATTERNS.length; i++) {
            Pattern pattern = VTT_PATTERNS[i];
            Matcher matcher = pattern.matcher(line);

            if (matcher.find()) {
                try {
                    double startTime, endTime;
                    String formatName;

                    // Parse baseado no número de grupos capturados
                    if (matcher.groupCount() == 8) {
                        // Formato completo: HH:MM:SS.mmm
                        startTime = parseTimeComponents(
                                matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)
                        );
                        endTime = parseTimeComponents(
                                matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8)
                        );
                        formatName = "HH:MM:SS.mmm";
                    } else if (matcher.groupCount() == 6) {
                        // Formato reduzido: MM:SS.mmm
                        startTime = parseTimeComponents(
                                "0", matcher.group(1), matcher.group(2), matcher.group(3)
                        );
                        endTime = parseTimeComponents(
                                "0", matcher.group(4), matcher.group(5), matcher.group(6)
                        );
                        formatName = "MM:SS.mmm";
                    } else {
                        continue; // Padrão não suportado
                    }

                    return new VTTTimestamp(startTime, endTime, formatName);

                } catch (NumberFormatException e) {
                    // Continua para o próximo padrão
                    continue;
                }
            }
        }

        return null;
    }

    /**
     * Parse de componentes de tempo
     */
    private static double parseTimeComponents(String hours, String minutes, String seconds, String milliseconds) {
        int h = Integer.parseInt(hours);
        int m = Integer.parseInt(minutes);
        int s = Integer.parseInt(seconds);

        // Normalizar milissegundos para 3 dígitos
        String msStr = milliseconds;
        while (msStr.length() < 3) msStr += "0";
        while (msStr.length() > 3) msStr = msStr.substring(0, 3);
        int ms = Integer.parseInt(msStr);

        return h * 3600.0 + m * 60.0 + s + ms / 1000.0;
    }

    /**
     * Verifica se linha contém timestamp
     */
    private static boolean isTimestampLine(String line) {
        return line.contains("-->") &&
                Arrays.stream(VTT_PATTERNS).anyMatch(pattern -> pattern.matcher(line).find());
    }

    /**
     * Calcula gaps de silêncio entre segmentos
     */
    private static List<VTTSegment> calculateSilenceGaps(List<VTTSegment> segments) {
        if (segments.isEmpty()) return segments;

        List<VTTSegment> result = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            VTTSegment current = segments.get(i);
            double silenceBefore = 0.0;

            if (i == 0) {
                // Primeiro segmento: silêncio desde o início
                silenceBefore = current.startTime;
            } else {
                // Segmentos seguintes: gap desde o fim do anterior
                VTTSegment previous = segments.get(i - 1);
                silenceBefore = Math.max(0, current.startTime - previous.endTime);
            }

            result.add(current.withSilenceBefore(silenceBefore));
        }

        return result;
    }

    // ===== MÉTODOS DE SINCRONIZAÇÃO =====

    /**
     * Encontra áudio adequado para sincronização
     */
    private static Path findAudioForSync(Path outputDir) throws IOException {
        // Ordem de prioridade para sincronização
        String[] priorities = {
                "output.wav",           // TTS gerado
                "dubbed_audio.wav",     // Áudio dublado
                "vocals.wav",           // Separação de áudio
                "audio.wav",            // Áudio extraído
                "extracted_audio.wav"   // Alternativo
        };

        for (String filename : priorities) {
            Path candidate = outputDir.resolve(filename);
            if (Files.exists(candidate) && Files.size(candidate) > 1024) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Aplica correção de gaps para áudio TTS
     */
    private static void applySilenceGapsCorrection(VTTAnalysis analysis, Path ttsAudio, Path outputAudio)
            throws IOException, InterruptedException {

        LOGGER.info("🔧 Aplicando correção de gaps TTS baseada em VTT");

        // Usar SilenceUtils para correção de gaps
        try {
            SilenceUtils.fixTTSSilenceGaps(
                    ttsAudio.getParent().resolve("transcription.vtt").toString(),
                    ttsAudio.getParent().toString()
            );

            // O resultado está em output.wav, copiar para destino
            Files.copy(ttsAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);

        } catch (Exception e) {
            LOGGER.warning("⚠️ Correção de gaps falhou, usando ajuste simples: " + e.getMessage());
            applySpeedBasedSync(analysis, ttsAudio, outputAudio);
        }
    }

    /**
     * Aplica sincronização baseada em ajuste de velocidade
     */
    private static void applySpeedBasedSync(VTTAnalysis analysis, Path inputAudio, Path outputAudio)
            throws IOException, InterruptedException {

        LOGGER.info("⚡ Aplicando sincronização por velocidade");

        double currentDuration = getAudioDuration(inputAudio);
        double targetDuration = analysis.totalDuration;

        if (Math.abs(currentDuration - targetDuration) < TOLERANCE_SECONDS) {
            // Diferença pequena, apenas copia
            Files.copy(inputAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("✅ Duração adequada, áudio copiado");
            return;
        }

        double speedFactor = currentDuration / targetDuration;
        speedFactor = Math.max(MIN_SPEED_FACTOR, Math.min(MAX_SPEED_FACTOR, speedFactor));

        LOGGER.info(String.format("🔧 Ajuste de velocidade: %.3fx", speedFactor));

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputAudio.toString(),
                "-af", String.format("atempo=%.6f", speedFactor),
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                "-avoid_negative_ts", "make_zero",
                outputAudio.toString()
        );

        Process process = pb.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout no ajuste de velocidade");
        }

        if (process.exitValue() != 0) {
            LOGGER.warning("⚠️ Ajuste de velocidade falhou, copiando original");
            Files.copy(inputAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ===== MÉTODOS DE INTEGRAÇÃO =====

    /**
     * Estratégia de preservação de silêncios (quando temos áudios processados)
     */
    private static void applyPreservationStrategy(Path originalAudio, List<Path> processedAudios,
                                                  VTTAnalysis analysis, String outputDir)
            throws IOException, InterruptedException {

        LOGGER.info("🔗 Aplicando estratégia de preservação de silêncios");

        Path finalOutput = Paths.get(outputDir, "vtt_integrated.wav");

        // Usar SilenceUtils para preservação
        SilenceUtils.preserveOriginalSilences(originalAudio, processedAudios, finalOutput);

        // Mover para local final
        Files.move(finalOutput, Paths.get(outputDir, "dublado.wav"), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Estratégia TTS com correção de gaps
     */
    private static void applyTTSGapStrategy(VTTAnalysis analysis, Path ttsOutput, String outputDir)
            throws IOException, InterruptedException {

        LOGGER.info("🎙️ Aplicando estratégia TTS com correção de gaps");

        // Usar SilenceUtils para correção
        SilenceUtils.fixTTSSilenceGaps(
                Paths.get(outputDir, "transcription.vtt").toString(),
                outputDir
        );

        // output.wav já foi corrigido pelo SilenceUtils
        Path correctedOutput = Paths.get(outputDir, "output.wav");
        if (Files.exists(correctedOutput)) {
            Files.move(correctedOutput, Paths.get(outputDir, "dublado.wav"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Pipeline sem VTT
     */
    private static void handlePipelineWithoutVTT(String originalVideoPath, String outputDirectory)
            throws IOException, InterruptedException {

        LOGGER.info("🔄 Pipeline sem VTT - aplicando estratégia alternativa");

        Path outputDir = Paths.get(outputDirectory);

        // Buscar qualquer áudio disponível
        String[] candidates = {"output.wav", "dubbed_audio.wav", "vocals.wav", "audio.wav"};

        for (String candidate : candidates) {
            Path audioFile = outputDir.resolve(candidate);
            if (Files.exists(audioFile)) {
                Path finalOutput = outputDir.resolve("dublado.wav");
                Files.copy(audioFile, finalOutput, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("✅ Usado " + candidate + " como áudio final");
                return;
            }
        }

        throw new IOException("Nenhum áudio disponível para pipeline sem VTT");
    }

    /**
     * Tratamento de falhas na integração
     */
    private static void handleIntegrationFailure(String originalVideoPath, String outputDir, Exception error)
            throws IOException, InterruptedException {

        LOGGER.warning("⚠️ Aplicando fallbacks de integração...");

        // Fallback 1: Usar DurationSyncUtils
        try {
            DurationSyncUtils.integrateInPipeline(originalVideoPath, outputDir);
            LOGGER.info("✅ Fallback com DurationSyncUtils aplicado");
            return;
        } catch (Exception e) {
            LOGGER.warning("⚠️ Fallback DurationSyncUtils falhou: " + e.getMessage());
        }

        // Fallback 2: Copiar qualquer áudio disponível
        Path outputDirPath = Paths.get(outputDir);
        String[] fallbackCandidates = {"output.wav", "vocals.wav", "audio.wav"};

        for (String candidate : fallbackCandidates) {
            Path audioFile = outputDirPath.resolve(candidate);
            if (Files.exists(audioFile)) {
                Path finalOutput = outputDirPath.resolve("dublado.wav");
                Files.copy(audioFile, finalOutput, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("✅ Fallback final: copiado " + candidate);
                return;
            }
        }

        throw new RuntimeException("Todos os fallbacks de integração falharam", error);
    }

    // ===== MÉTODOS AUXILIARES =====

    private static Path ensureOriginalAudioExists(String videoPath, String outputDir)
            throws IOException, InterruptedException {

        Path audioPath = Paths.get(outputDir, "original_audio.wav");

        if (!Files.exists(audioPath)) {
            LOGGER.info("🔊 Extraindo áudio original do vídeo...");
            extractAudioFromVideo(Paths.get(videoPath), audioPath);
        }

        return audioPath;
    }

    private static List<Path> findProcessedAudioSegments(String outputDir) throws IOException {
        Path directory = Paths.get(outputDir);

        LOGGER.info("🔍 Procurando arquivos de áudio processados em: " + outputDir);

        // Estratégias de busca em ordem de prioridade
        String[][] searchPatterns = {
                {"audio_\\d{3}\\.wav", "TTSUtils pattern"},
                {"segment_\\d+\\.wav", "Segment pattern"},
                {"processed_\\d+\\.wav", "Processed pattern"},
                {"speech_\\d+\\.wav", "Speech pattern"},
                {"vocal_\\d+\\.wav", "Vocal pattern"}
        };

        for (String[] pattern : searchPatterns) {
            List<Path> audioFiles = Files.list(directory)
                    .filter(path -> path.getFileName().toString().matches(pattern[0]))
                    .sorted()
                    .collect(Collectors.toList());

            if (!audioFiles.isEmpty()) {
                LOGGER.info("✅ Encontrados " + audioFiles.size() + " arquivos: " + pattern[1]);
                return audioFiles;
            }
        }

        // Fallback: todos os .wav exceto alguns específicos
        List<Path> allWavFiles = Files.list(directory)
                .filter(path -> path.toString().endsWith(".wav"))
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return !Arrays.asList("original_audio.wav", "audio.wav", "vocals.wav",
                            "accompaniment.wav", "output.wav").contains(name);
                })
                .sorted()
                .collect(Collectors.toList());

        if (!allWavFiles.isEmpty()) {
            LOGGER.info("⚠️ Usando " + allWavFiles.size() + " arquivos .wav genéricos");
        }

        return allWavFiles;
    }

    private static void extractAudioFromVideo(Path videoPath, Path audioPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", videoPath.toString(), "-vn",
                "-c:a", CODEC, "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS),
                audioPath.toString()
        );

        Process process = pb.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout extraindo áudio do vídeo");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro extraindo áudio do vídeo");
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

    private static void logAnalysisResults(VTTAnalysis analysis, Path vttPath) {
        LOGGER.info(String.format("📊 RESULTADO DA ANÁLISE VTT:"));
        LOGGER.info(String.format("  📁 Arquivo: %s", vttPath.getFileName()));
        LOGGER.info(String.format("  ✅ Válido: %s", analysis.isValid ? "SIM" : "NÃO"));
        LOGGER.info(String.format("  🎯 Formato: %s", analysis.formatDetected));
        LOGGER.info(String.format("  📊 Segmentos: %d", analysis.segments.size()));
        LOGGER.info(String.format("  ⏱️ Duração total: %.3fs", analysis.totalDuration));

        // Estatísticas detalhadas
        LOGGER.info(String.format("📈 ESTATÍSTICAS:"));
        analysis.statistics.forEach((key, value) ->
                LOGGER.info(String.format("  %s: %d", key, value)));

        // Erros se houver
        if (!analysis.errors.isEmpty()) {
            LOGGER.warning("⚠️ ERROS ENCONTRADOS:");
            analysis.errors.forEach(error -> LOGGER.warning("  - " + error));
        }

        // Amostra dos primeiros segmentos
        if (!analysis.segments.isEmpty()) {
            LOGGER.info("🔍 PRIMEIROS SEGMENTOS:");
            analysis.segments.stream().limit(3).forEach(segment ->
                    LOGGER.info(String.format("  %s", segment.toString())));
        }
    }

    private static void detectAlternativePatterns(List<String> lines) {
        String[][] patterns = {
                {"\\d{1,2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2},\\d{3}", "VTT vírgulas (1-2h)"},
                {"\\d{1,2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}\\.\\d{3}", "VTT pontos (1-2h)"},
                {"\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}", "VTT vírgulas (2h)"},
                {"\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}", "VTT pontos (2h)"},
                {"\\d{1,2}:\\d{2},\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2},\\d{3}", "MM:SS,mmm"},
                {"\\d{1,2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}\\.\\d{3}", "MM:SS.mmm"},
                {".*-->.*", "Qualquer linha com -->"},
                {"\\d+:\\d+:\\d+.*\\d+:\\d+:\\d+", "Qualquer timestamp"}
        };

        for (String[] patternInfo : patterns) {
            Pattern pattern = Pattern.compile(patternInfo[0]);
            int matches = 0;
            List<String> examples = new ArrayList<>();

            for (String line : lines) {
                if (pattern.matcher(line.trim()).matches()) {
                    matches++;
                    if (examples.size() < 3) {
                        examples.add(line.trim());
                    }
                }
            }

            if (matches > 0) {
                LOGGER.info(String.format("  %s: %d matches", patternInfo[1], matches));
                examples.forEach(example -> LOGGER.info("    Exemplo: '" + example + "'"));
            }
        }
    }

    // ===== MÉTODOS DE CORREÇÃO =====

    /**
     * Correção automática de arquivo VTT
     */
    public static Path autoFixVTT(Path inputVTT, Path outputVTT) throws IOException {
        LOGGER.info("🔧 Aplicando correção automática de VTT");

        List<String> lines = Files.readAllLines(inputVTT);
        List<String> correctedLines = new ArrayList<>();

        boolean hasWebVTTHeader = false;

        for (String line : lines) {
            String corrected = line;

            // Adicionar header se ausente
            if (!hasWebVTTHeader && !line.trim().startsWith("WEBVTT")) {
                if (correctedLines.isEmpty()) {
                    correctedLines.add("WEBVTT");
                    correctedLines.add("");
                }
            }

            if (line.trim().startsWith("WEBVTT")) {
                hasWebVTTHeader = true;
            }

            // Normalizar separadores de timestamp
            if (line.contains("-->")) {
                corrected = line.replace(',', '.');

                // Garantir formato correto de milissegundos
                corrected = corrected.replaceAll(":(\\d{2})\\.(\\d{1,2})\\s", ":$1.$200 ");
                corrected = corrected.replaceAll(":(\\d{2})\\.(\\d{1,2})$", ":$1.$200");
            }

            // Remover caracteres de controle
            corrected = corrected.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");

            correctedLines.add(corrected);
        }

        // Adicionar header se ainda não foi adicionado
        if (!hasWebVTTHeader) {
            correctedLines.add(0, "");
            correctedLines.add(0, "WEBVTT");
        }

        Files.write(outputVTT, correctedLines);
        LOGGER.info("✅ VTT corrigido salvo: " + outputVTT.getFileName());

        return outputVTT;
    }

    // ===== MÉTODOS DE COMPATIBILIDADE =====

    /**
     * Substitui VTTSyncUtils.synchronizeAudioWithVTT()
     */
    public static void synchronizeAudioWithVTTCompat(Path vttFile, Path dubbedAudioDir, Path outputAudio)
            throws IOException, InterruptedException {

        // Usar método novo baseado em análise
        VTTAnalysis analysis = analyzeVTT(vttFile.toString());
        Path audioToSync = findAudioForSync(dubbedAudioDir);

        if (audioToSync == null) {
            throw new IOException("Nenhum áudio encontrado para sincronização");
        }

        if (audioToSync.getFileName().toString().equals("output.wav")) {
            applySilenceGapsCorrection(analysis, audioToSync, outputAudio);
        } else {
            applySpeedBasedSync(analysis, audioToSync, outputAudio);
        }
    }

    /**
     * Substitui VTTSyncUtils.integrateSyncWithPipeline()
     */
    public static void integrateSyncWithPipeline(String vttFile, String outputDir)
            throws IOException, InterruptedException {

        try {
            synchronizeWithVTT(vttFile, outputDir);

            // Mover resultado para local esperado
            Path syncedAudio = Paths.get(outputDir, "vtt_synchronized.wav");
            Path finalOutput = Paths.get(outputDir, "dublado.wav");

            if (Files.exists(syncedAudio)) {
                Files.move(syncedAudio, finalOutput, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (Exception e) {
            LOGGER.warning("⚠️ Sincronização VTT falhou, aplicando fallback: " + e.getMessage());

            // Fallback
            Path fallbackSource = Paths.get(outputDir, "output.wav");
            Path fallbackTarget = Paths.get(outputDir, "dublado.wav");

            if (Files.exists(fallbackSource)) {
                Files.copy(fallbackSource, fallbackTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Substitui VTTDebugTool.analyzeVTTFile()
     */
    public static void analyzeVTTFileCompat(String vttFilePath) throws IOException {
        debugVTT(vttFilePath);
    }

    /**
     * Substitui VTTDebugTool.autoFixVTT()
     */
    public static void autoFixVTTCompat(String inputPath, String outputPath) throws IOException {
        autoFixVTT(Paths.get(inputPath), Paths.get(outputPath));
    }

    /**
     * Substitui SilencePipelineIntegration.integrateWithMainPipeline()
     */
    public static void integrateWithMainPipelineCompat(String originalVideoPath, String outputDirectory)
            throws IOException, InterruptedException {
        integrateWithMainPipeline(originalVideoPath, outputDirectory);
    }

    /**
     * Substitui SilencePipelineIntegration.enhanceTTSWithSilencePreservation()
     */
    public static void enhanceTTSWithSilencePreservation(String vttFile, String outputDir)
            throws IOException, InterruptedException {

        LOGGER.info("🎙️ Melhorando TTS com preservação de silêncios (VTT)");

        // Processar VTT normalmente primeiro
        TTSUtils.processVttFile(vttFile);

        // Aplicar sincronização VTT
        synchronizeWithVTT(vttFile, outputDir);
    }

    /**
     * Substitui SilencePipelineIntegration.replaceMainFinalizationStep()
     */
    public static void replaceMainFinalizationStep(Path videoFile, String outputDir, boolean useAdvancedProcessing)
            throws IOException, InterruptedException {

        LOGGER.info("🎬 FINALIZAÇÃO MELHORADA COM VTT");

        try {
            // Aplicar integração VTT
            integrateWithMainPipeline(videoFile.toString(), outputDir);

            // Continuar com criação do vídeo final
            Path dubAudioPath = Paths.get(outputDir, "dublado.wav");
            Path outputVideoPath = Paths.get(videoFile.toString().replace(".mp4", "_dub_vtt_synced.mp4"));

            if (Files.exists(dubAudioPath)) {
                if (useAdvancedProcessing) {
                    AudioUtils.replaceAudioAdvanced(videoFile, dubAudioPath, outputVideoPath);
                } else {
                    AudioUtils.replaceAudio(videoFile, dubAudioPath, outputVideoPath);
                }

                LOGGER.info("🎬 Vídeo final criado com sincronização VTT: " + outputVideoPath.getFileName());
            } else {
                throw new IOException("Áudio dublado não foi gerado: " + dubAudioPath);
            }

        } catch (Exception e) {
            LOGGER.severe("❌ Erro na finalização VTT: " + e.getMessage());

            // Fallback para finalização sem VTT
            Path dubAudioPath = Paths.get(outputDir, "output.wav");
            if (Files.exists(dubAudioPath)) {
                Files.copy(dubAudioPath, Paths.get(outputDir, "dublado.wav"), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("✅ Fallback: usado output.wav como dublado.wav");
            }
            throw e;
        }
    }

    // ===== MÉTODOS DE DEBUG E TESTE =====

    /**
     * Debug de arquivos no diretório
     */
    public static void debugDirectoryContents(String outputDir) {
        try {
            LOGGER.info("🔍 DEBUG - Conteúdo do diretório: " + outputDir);

            Files.list(Paths.get(outputDir))
                    .forEach(path -> {
                        try {
                            long size = Files.size(path);
                            String type = Files.isDirectory(path) ? "DIR" : "FILE";
                            LOGGER.info(String.format("  📄 %s %s (%.1f KB)",
                                    type, path.getFileName(), size / 1024.0));
                        } catch (IOException e) {
                            LOGGER.info("  📄 " + path.getFileName() + " (erro obtendo info)");
                        }
                    });

        } catch (IOException e) {
            LOGGER.warning("❌ Erro no debug do diretório: " + e.getMessage());
        }
    }

    /**
     * Valida se integração VTT pode ser aplicada
     */
    public static boolean canApplyVTTIntegration(String outputDir) {
        try {
            Path vttFile = Paths.get(outputDir, "transcription.vtt");
            boolean hasVTT = Files.exists(vttFile);

            Path audioFile = findAudioForSync(Paths.get(outputDir));
            boolean hasAudio = audioFile != null;

            LOGGER.info("🔍 VALIDAÇÃO VTT INTEGRATION:");
            LOGGER.info("  VTT: " + (hasVTT ? "✅ " + vttFile.getFileName() : "❌ Não encontrado"));
            LOGGER.info("  Áudio: " + (hasAudio ? "✅ " + audioFile.getFileName() : "❌ Não encontrado"));

            if (hasVTT) {
                try {
                    VTTAnalysis analysis = analyzeVTT(vttFile.toString());
                    LOGGER.info("  Válido: " + (analysis.isValid ? "✅ SIM" : "❌ NÃO"));
                    LOGGER.info("  Segmentos: " + analysis.segments.size());
                    return analysis.isValid && hasAudio;
                } catch (Exception e) {
                    LOGGER.warning("  Erro análise VTT: " + e.getMessage());
                    return false;
                }
            }

            return false;

        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro na validação VTT: " + e.getMessage());
            return false;
        }
    }

    /**
     * Teste completo de VTT
     */
    public static void testVTTProcessing(String vttFile, String outputDir) {
        try {
            LOGGER.info("🧪 TESTE COMPLETO VTT");

            // 1. Debug do VTT
            debugVTT(vttFile);

            // 2. Debug do diretório
            debugDirectoryContents(outputDir);

            // 3. Validação
            boolean canApply = canApplyVTTIntegration(outputDir);
            LOGGER.info("Pode aplicar integração: " + (canApply ? "✅ SIM" : "❌ NÃO"));

            // 4. Teste de sincronização se possível
            if (canApply) {
                synchronizeWithVTT(vttFile, outputDir);
                LOGGER.info("✅ Teste de sincronização concluído");
            }

            LOGGER.info("✅ Teste completo VTT concluído");

        } catch (Exception e) {
            LOGGER.severe("❌ Teste VTT falhou: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Benchmark de parsing VTT
     */
    public static void benchmarkVTTParsing(String vttFile, int iterations) throws IOException {
        LOGGER.info(String.format("🚀 BENCHMARK PARSING VTT (%d iterações)", iterations));

        long[] times = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            VTTAnalysis analysis = analyzeVTT(vttFile);
            times[i] = System.nanoTime() - start;

            if (i == 0) {
                LOGGER.info(String.format("Primeira análise: %d segmentos, %.3fs duração",
                        analysis.segments.size(), analysis.totalDuration));
            }
        }

        double avgTimeMs = Arrays.stream(times).average().orElse(0) / 1_000_000;
        double minTimeMs = Arrays.stream(times).min().orElse(0) / 1_000_000;
        double maxTimeMs = Arrays.stream(times).max().orElse(0) / 1_000_000;

        LOGGER.info("📊 RESULTADOS BENCHMARK:");
        LOGGER.info(String.format("  Tempo médio: %.2fms", avgTimeMs));
        LOGGER.info(String.format("  Tempo mínimo: %.2fms", minTimeMs));
        LOGGER.info(String.format("  Tempo máximo: %.2fms", maxTimeMs));
    }

    /**
     * Shutdown graceful
     */
    public static void shutdown() {
        LOGGER.info("🔄 Finalizando processamento VTT...");

        processingExecutor.shutdown();
        try {
            if (!processingExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
                if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warning("⚠️ Executor VTT não finalizou completamente");
                }
            }
        } catch (InterruptedException e) {
            processingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("✅ Recursos de processamento VTT liberados");
    }
}
