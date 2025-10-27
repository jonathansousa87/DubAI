package org;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whisper - Sistema Consolidado de Transcrição
 * <p>
 * Combina funcionalidades de:
 * - WhisperUtils (transcrição básica)
 * - WhisperXPlusUtils (análise prosódica avançada)
 */
public class Whisper {

    private static final Logger logger = Logger.getLogger(Whisper.class.getName());

    // =========== CONFIGURAÇÕES ===========

    private static final String[] MODELS = {
            "large-v2"  // Único modelo suportado
            //"large-v3-turbo"
    };

    // Controle de concorrência
    private static final Semaphore transcriptionSemaphore = new Semaphore(2);
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Pattern para parsing VTT (suporta formatos MM:SS.mmm e HH:MM:SS.mmm)
    private static final Pattern VTT_TIMESTAMP_PATTERN = Pattern.compile(
            "^(?:(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})|(\\d{2}):(\\d{2})[.,](\\d{3}))\\s*-->\\s*(?:(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})|(\\d{2}):(\\d{2})[.,](\\d{3}))$"
    );

    // =========== CLASSES AUXILIARES ===========

    /**
     * Entrada VTT básica
     */
    public record VTTEntry(
            double startTime,
            double endTime,
            String text
    ) {
        public VTTEntry {
            if (startTime < 0) throw new IllegalArgumentException("startTime deve ser >= 0");
            if (endTime <= startTime) throw new IllegalArgumentException("endTime deve ser > startTime");
            if (text == null) text = "";
        }

        public double duration() {
            return endTime - startTime;
        }
    }

    /**
     * Entrada VTT enriquecida com dados prosódicos
     */
    public record EnhancedVTTEntry(
            double startTime,
            double endTime,
            String originalText,
            String enhancedText,
            double averagePitch,
            double averageIntensity,
            List<EmphasisMoment> emphasis,
            EmotionMetrics emotions
    ) {
        public EnhancedVTTEntry {
            if (startTime < 0) throw new IllegalArgumentException("startTime deve ser >= 0");
            if (endTime <= startTime) throw new IllegalArgumentException("endTime deve ser > startTime");
            if (originalText == null) originalText = "";
            if (enhancedText == null) enhancedText = originalText;
            if (emphasis == null) emphasis = List.of();
        }

        public double duration() {
            return endTime - startTime;
        }
    }

    /**
     * Momento de ênfase
     */
    public record EmphasisMoment(
            double relativeStartTime,
            double relativeEndTime,
            double intensity,
            String type
    ) {
    }

    /**
     * Transcrição completa enriquecida
     */
    public record EnhancedTranscription(
            Path audioFile,
            List<EnhancedVTTEntry> entries,
            List<SilenceSegment> silences,
            EmotionMetrics emotions,
            Prosody.Metrics prosody
    ) {
        public EnhancedTranscription {
            if (audioFile == null) throw new IllegalArgumentException("audioFile não pode ser null");
            entries = entries != null ? List.copyOf(entries) : Collections.emptyList();
            silences = silences != null ? List.copyOf(silences) : Collections.emptyList();
        }

        public int getSegmentCount() {
            return entries.size();
        }

        public double getTotalDuration() {
            if (entries.isEmpty()) return 0.0;
            return entries.get(entries.size() - 1).endTime();
        }

        public String generateReport() {
            StringBuilder report = new StringBuilder();
            report.append("=== RELATÓRIO DE TRANSCRIÇÃO AVANÇADA ===\n");
            report.append(String.format("Arquivo: %s\n", audioFile.getFileName()));
            report.append(String.format("Segmentos: %d\n", entries.size()));
            report.append(String.format("Duração total: %.2fs\n", getTotalDuration()));
            report.append(String.format("Silêncios detectados: %d\n", silences.size()));
            report.append(String.format("Estado emocional: %s\n", emotions.getEmotionalState()));
            report.append(String.format("Expressividade: %.1f%%\n", prosody.getExpressiveness() * 100));
            return report.toString();
        }
    }

    // =========== MÉTODOS PÚBLICOS PRINCIPAIS ===========

    /**
     * Transcrição básica de áudio - gera VTT usando WhisperX
     */
    public static void transcribeAudio(String inputFile, String outputVtt) throws IOException, InterruptedException {
        logger.info("🎯 Iniciando transcrição com WhisperX...");

        File outputFile = new File(outputVtt);
        String outputDir = outputFile.getParent();
        if (outputDir == null) outputDir = ".";

        String tempWhisperVtt = outputDir + "/whisperx_temp.vtt";

        // EXECUTAR WHISPERX (estrutura, pontuação, segmentação)
        boolean whisperSuccess = false;
        for (String model : MODELS) {
            transcriptionSemaphore.acquire();
            try {
                logger.info("🎤 Transcrevendo com WhisperX (" + model + ")...");
                executeWhisperX(inputFile, model, tempWhisperVtt);
                whisperSuccess = true;
                logger.info("✅ WhisperX concluído");
                break;
            } catch (IOException e) {
                logger.warning("⚠️ WhisperX falhou com " + model + ": " + e.getMessage());
            } finally {
                transcriptionSemaphore.release();
            }
        }

        if (!whisperSuccess) {
            throw new IOException("❌ WhisperX falhou com todos os modelos");
        }

        // Copiar resultado para arquivo final
        Files.copy(Paths.get(tempWhisperVtt), Paths.get(outputVtt), StandardCopyOption.REPLACE_EXISTING);
        logger.info("✅ Transcrição concluída: " + outputVtt);
    }

    /**
     * Transcrição otimizada para tradução - gera TSV diretamente
     * MELHOR PARA TRANSLATION: usa vocals.tsv que é mais limpo
     */
    public static String transcribeForTranslation(String inputFile, String outputDir) throws IOException, InterruptedException {
        logger.info("🎤 Transcrevendo para tradução (formato TSV)...");

        // Verificar se já existe VTT para reusar
        String finalVtt = outputDir + "/transcription.vtt";
        String tempVtt = outputDir + "/temp_vocals.vtt";

        if (Files.exists(Paths.get(finalVtt))) {
            logger.info("🔄 Reusando VTT existente: " + finalVtt);
            tempVtt = finalVtt; // Usar VTT existente
        } else {
            // Só executar WhisperX se VTT não existe
            transcribeAudio(inputFile, tempVtt);
        }

        // Converte para TSV (formato preferido da Translation)
        String outputTsv = outputDir + "/vocals.tsv";

        // Verificar se WhisperX já gerou TSV (--output_format all)
        if (Files.exists(Paths.get(outputTsv))) {
            logger.info("✅ TSV já gerado pelo WhisperX: " + outputTsv);
        } else {
            // Fallback: converter VTT → TSV manualmente
            if (!Files.exists(Paths.get(tempVtt))) {
                throw new IOException("Arquivo VTT temporário não foi criado: " + tempVtt);
            }
            convertVTTtoTSV(tempVtt, outputTsv);
            logger.info("✅ Convertido VTT → TSV: " + outputTsv);
        }

        // 🔗 CONSOLIDAÇÃO DE SEGMENTOS PRÓXIMOS
        // consolidateSegments(outputTsv);
        // logger.info("✅ Segmentos consolidados: " + outputTsv);

        // MANTER arquivo temporário para análise
        logger.info("📁 Arquivo VTT consolidado mantido: " + tempVtt);
        logger.info("📁 Arquivo TSV para tradução: " + outputTsv);

        return outputTsv;
    }

    /**
     * Transcrição avançada com análise prosódica
     */
    public static EnhancedTranscription transcribeWithProsody(String inputFile, String outputVtt)
            throws IOException, InterruptedException {

        logger.info("🎯 Iniciando transcrição avançada com análise prosódica");

        // 1. Transcrição básica
        transcribeAudio(inputFile, outputVtt);

        Path audioPath = Paths.get(inputFile);
        if (!Files.exists(audioPath)) {
            throw new IOException("Arquivo de áudio não encontrado: " + inputFile);
        }

        // 2. Análise prosódica em paralelo
        logger.info("🔍 Iniciando análises prosódicas paralelas...");

        CompletableFuture<Prosody.AnalysisResult> prosodyAnalysis =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return Prosody.analyzeAudio(audioPath);
                    } catch (Exception e) {
                        logger.warning("Erro na análise prosódica: " + e.getMessage());
                        return null;
                    }
                });

        // 3. Parse da transcrição VTT
        List<VTTEntry> basicTranscription = parseVTTFile(outputVtt);

        try {
            // 4. Aguardar análise prosódica
            Prosody.AnalysisResult prosodyResult = prosodyAnalysis.get(30, TimeUnit.SECONDS);

            if (prosodyResult != null) {
                // 5. Enriquecer transcrição com dados prosódicos
                List<EnhancedVTTEntry> enhancedEntries = enrichTranscriptionWithProsody(
                        basicTranscription, prosodyResult
                );

                EnhancedTranscription result = new EnhancedTranscription(
                        audioPath,
                        enhancedEntries,
                        prosodyResult.silences(),
                        prosodyResult.emotions(),
                        prosodyResult.prosody()
                );

                logger.info(String.format("✅ Transcrição avançada concluída: %d segmentos, %d silêncios",
                        enhancedEntries.size(), prosodyResult.silences().size()));

                return result;
            }

        } catch (Exception e) {
            logger.warning("⚠️ Erro na análise prosódica: " + e.getMessage());
        }

        // Fallback para transcrição básica
        return createBasicEnhancedTranscription(audioPath, basicTranscription);
    }

    // =========== MÉTODOS AUXILIARES ===========

    /**
     * Constrói prompt avançado para WhisperX detectar fillers, gaguejos e false starts
     *
     * Este prompt guia o modelo Whisper a transcrever de forma mais verbatim,
     * capturando hesitações naturais da fala que são importantes para dublagem realista.
     */
//    private static String buildAdvancedFillerPrompt() {
//        // Prompt em inglês (língua do áudio fonte) com exemplos de padrões de fala natural
//        return "Um, uh, so, you know, like, I mean, well, actually, basically, literally, " +
//                "right, okay, alright, hmm, ah, er, oh, eh, uh-huh, mm-hmm, yeah, yep, nah, " +
//                "sort of, kind of, I guess, I think, you see, let me see, let's see, " +
//                "how do I, how do I say, what's the word, " +
//                "the the, I I, we we, it's it's, that's that's, " + // gaguejos
//                "so-, uh-, I-, we-, like-, kind-, " + // false starts (palavras cortadas)
//                "and um, but uh, so like, I mean like, you know what I mean, " +
//                "at the end of the day, to be honest, if you will, per se, " +
//                "as it were, so to speak, in a way, in a sense.";
//    }
    private static String buildAdvancedFillerPrompt() {
        // PROMPT MINIMALISTA (~15 tokens): Reduzido de 150+ tokens para evitar alucinações
        // Whisper large-v2 alucina com prompts longos - mantém apenas fillers essenciais
        return "Um, uh, like, you know, I mean, so, well, right, okay.";
    }
    

    /**
     * Formata timestamp para VTT
     */
    private static String formatVTTTimestamp(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);
        int millis = (int) ((seconds % 1) * 1000);
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, secs, millis);
    }

    /**
     * Converte VTT para TSV (formato otimizado para tradução)
     */
    private static void convertVTTtoTSV(String vttPath, String tsvPath) throws IOException {
        logger.info("🔄 Convertendo VTT para TSV: " + vttPath);

        List<VTTEntry> entries = parseVTTFile(vttPath);

        try (PrintWriter writer = new PrintWriter(new FileWriter(tsvPath))) {
            // Header TSV
            writer.println("start\tend\ttext");

            for (VTTEntry entry : entries) {
                writer.println(String.format("%.3f\t%.3f\t%s",
                        entry.startTime(),
                        entry.endTime(),
                        entry.text()));
            }
        }

        logger.info("✅ TSV gerado: " + tsvPath + " (" + entries.size() + " segmentos)");
    }

    private static void executeWhisperX(String inputFile, String model, String outputVtt) throws IOException, InterruptedException {
        File outputFile = new File(outputVtt);
        String outputDir = outputFile.getParent();
        if (outputDir == null) outputDir = ".";

        // Construir prompt avançado para detecção de fillers
        String fillerPrompt = buildAdvancedFillerPrompt();
        logger.info("🎯 Usando prompt avançado para detecção de fillers e hesitações");
        logger.fine("   Prompt: " + fillerPrompt.substring(0, Math.min(100, fillerPrompt.length())) + "...");

        // Limpar memória GPU agressivamente antes de iniciar
        logger.info("🧹 Limpando memória GPU antes da transcrição...");
        try {
            ClearMemory.forceCudaCleanup();
            Thread.sleep(2000); // Aguarda estabilização
        } catch (Exception e) {
            logger.warning("⚠️ Falha na limpeza GPU: " + e.getMessage());
        }

        // Tentar com batch_size progressivamente menor em caso de OOM
        String[] computeTypes = {"float16", "int8"};
        int[] batchSizes = {8, 4, 1};
        IOException lastException = null;

        for (int batchSize : batchSizes) {
            try {
                logger.info(String.format("🎤 Tentando WhisperX com batch_size=%d...", batchSize));

                String[] command = {
                        "whisperx",
                        "--model", model,
                        "--device", "cuda",
                        "--compute_type", "int8",  // 🔥 INT8 usa 75% menos memória que FP16!
                        "--batch_size", String.valueOf(batchSize),
                        "--output_dir", outputDir,
                        "--output_format", "all",  // Gera VTT + JSON com word timestamps
                        "--initial_prompt", fillerPrompt,  // Prompt para detectar fillers
                        "--condition_on_previous_text", "False",  // 🔥 Desliga histórico (economiza RAM)
                        "--no_align",  // 🔥 DESLIGA alinhamento (economiza ~2GB VRAM!)
                        "--vad_onset", "0.3",  // VAD mais sensível (detecta hesitações)
                        "--vad_offset", "0.3",  // VAD offset
                        "--no_speech_threshold", "0.4",  // Mais sensível a falas sutis
                        "--logprob_threshold", "-0.8",  // Aceita predições com menor confiança (captura fillers)
                        "--compression_ratio_threshold", "2.8",  // Permite texto mais verboso
                        inputFile
                };

                logger.fine("Executando WhisperX: " + String.join(" ", command));
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.environment().put("LD_LIBRARY_PATH", "/usr/lib:/usr/local/cuda/lib64");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder outputLog = new StringBuilder();
                boolean cudaOOM = false;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputLog.append(line).append("\n");
                        logger.fine(line);

                        // Detectar erro de memória CUDA
                        if (line.contains("CUDA") && line.contains("out of memory")) {
                            cudaOOM = true;
                            logger.warning("⚠️ Detectado CUDA OOM, vai tentar batch_size menor...");
                        }
                    }
                }

                int exitCode = process.waitFor();

                // Se sucesso, verificar arquivo e sair
                if (exitCode == 0) {
                    File generatedFile = new File(outputDir, "vocals.vtt");
                    if (generatedFile.exists()) {
                        logger.info(String.format("✅ WhisperX bem-sucedido com batch_size=%d", batchSize));

                        // Backup e rename
                        try {
                            File backupFile = new File(outputDir, "vocals_backup.vtt");
                            Files.copy(generatedFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            logger.fine("✅ Backup criado: " + backupFile.getName());
                        } catch (IOException e) {
                            logger.warning("⚠️ Não foi possível criar backup: " + e.getMessage());
                        }

                        try {
                            Files.move(generatedFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            logger.fine("✅ Arquivo renomeado para: " + outputFile.getName());
                            return; // SUCESSO!
                        } catch (IOException e) {
                            throw new IOException("Erro ao renomear arquivo: " + e.getMessage());
                        }
                    }
                }

                // Se falhou com OOM e ainda tem batch_size menor para tentar
                if (cudaOOM && batchSize > 1) {
                    logger.warning(String.format("❌ OOM com batch_size=%d, limpando GPU e tentando menor...", batchSize));
                    ClearMemory.forceCudaCleanup();
                    Thread.sleep(3000); // Aguarda limpeza
                    lastException = new IOException("CUDA OOM com batch_size=" + batchSize);
                    continue;
                }

                // Erro não relacionado a memória
                lastException = new IOException("Comando falhou com código: " + exitCode + ". Saída: " + outputLog.toString());

                // Se não foi OOM, não adianta tentar com batch menor
                if (!cudaOOM) {
                    throw lastException;
                }

            } catch (IOException e) {
                lastException = e;

                // Se contém "out of memory" e ainda tem batch_size menor, continua loop
                if (e.getMessage().contains("out of memory") && batchSize > 1) {
                    logger.warning(String.format("❌ OOM com batch_size=%d, tentando menor...", batchSize));
                    try {
                        ClearMemory.forceCudaCleanup();
                        Thread.sleep(3000);
                    } catch (Exception ex) {
                        logger.warning("⚠️ Erro na limpeza GPU: " + ex.getMessage());
                    }
                    continue;
                }

                // Erro não relacionado a memória ou último batch_size
                throw e;
            }
        }

        // Se chegou aqui, todas as tentativas falharam
        throw new IOException("WhisperX falhou com todos batch_sizes testados. Último erro: " +
            (lastException != null ? lastException.getMessage() : "desconhecido"));
    }

    private static List<VTTEntry> parseVTTFile(String vttPath) throws IOException {
        List<VTTEntry> entries = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(vttPath))) {
            String line;
            VTTEntry currentEntry = null;
            StringBuilder textBuilder = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    if (currentEntry != null && textBuilder.length() > 0) {
                        entries.add(new VTTEntry(
                                currentEntry.startTime(),
                                currentEntry.endTime(),
                                textBuilder.toString().trim()
                        ));
                        textBuilder.setLength(0);
                        currentEntry = null;
                    }
                } else if (VTT_TIMESTAMP_PATTERN.matcher(line).matches()) {
                    currentEntry = parseTimestamp(line);
                } else if (currentEntry != null && !line.startsWith("WEBVTT") && !line.matches("^\\d+$")) {
                    if (textBuilder.length() > 0) textBuilder.append(" ");
                    textBuilder.append(line);
                }
            }

            // Adicionar última entrada se existe
            if (currentEntry != null && textBuilder.length() > 0) {
                entries.add(new VTTEntry(
                        currentEntry.startTime(),
                        currentEntry.endTime(),
                        textBuilder.toString().trim()
                ));
            }
        }

        return entries;
    }

    private static VTTEntry parseTimestamp(String line) {
        Matcher matcher = VTT_TIMESTAMP_PATTERN.matcher(line);
        if (matcher.matches()) {
            try {
                double startTime, endTime;

                // Formato HH:MM:SS.mmm (grupos 1-4)
                if (matcher.group(1) != null) {
                    int startH = Integer.parseInt(matcher.group(1));
                    int startM = Integer.parseInt(matcher.group(2));
                    int startS = Integer.parseInt(matcher.group(3));
                    int startMs = Integer.parseInt(matcher.group(4));
                    startTime = startH * 3600 + startM * 60 + startS + startMs / 1000.0;
                } else {
                    // Formato MM:SS.mmm (grupos 5-7)
                    int startM = Integer.parseInt(matcher.group(5));
                    int startS = Integer.parseInt(matcher.group(6));
                    int startMs = Integer.parseInt(matcher.group(7));
                    startTime = startM * 60 + startS + startMs / 1000.0;
                }

                // Formato HH:MM:SS.mmm (grupos 8-11)
                if (matcher.group(8) != null) {
                    int endH = Integer.parseInt(matcher.group(8));
                    int endM = Integer.parseInt(matcher.group(9));
                    int endS = Integer.parseInt(matcher.group(10));
                    int endMs = Integer.parseInt(matcher.group(11));
                    endTime = endH * 3600 + endM * 60 + endS + endMs / 1000.0;
                } else {
                    // Formato MM:SS.mmm (grupos 12-14)
                    int endM = Integer.parseInt(matcher.group(12));
                    int endS = Integer.parseInt(matcher.group(13));
                    int endMs = Integer.parseInt(matcher.group(14));
                    endTime = endM * 60 + endS + endMs / 1000.0;
                }

                return new VTTEntry(startTime, endTime, "");

            } catch (NumberFormatException e) {
                logger.warning("Erro parseando timestamp: " + line);
            }
        }
        return new VTTEntry(0.0, 1.0, "");
    }

    private static List<EnhancedVTTEntry> enrichTranscriptionWithProsody(
            List<VTTEntry> transcription, Prosody.AnalysisResult prosodyResult) {

        List<EnhancedVTTEntry> enhanced = new ArrayList<>();

        for (VTTEntry entry : transcription) {
            // Dados prosódicos básicos
            double averagePitch = calculateSegmentAveragePitch(entry, prosodyResult);
            double averageIntensity = calculateSegmentAverageIntensity(entry, prosodyResult);

            // Aplicar ênfases
            String enhancedText = applyEmphasisToText(entry.text(), prosodyResult);

            enhanced.add(new EnhancedVTTEntry(
                    entry.startTime(),
                    entry.endTime(),
                    entry.text(),
                    enhancedText,
                    averagePitch,
                    averageIntensity,
                    findEmphasisInSegment(entry, prosodyResult),
                    prosodyResult.emotions()
            ));
        }

        return enhanced;
    }

    private static double calculateSegmentAveragePitch(VTTEntry entry, Prosody.AnalysisResult prosodyResult) {
        // Simplificado - retorna pitch médio geral
        return prosodyResult.prosody().averagePitch();
    }

    private static double calculateSegmentAverageIntensity(VTTEntry entry, Prosody.AnalysisResult prosodyResult) {
        // Baseado na expressividade
        return prosodyResult.prosody().getExpressiveness() * 100;
    }

    private static String applyEmphasisToText(String text, Prosody.AnalysisResult prosodyResult) {
        if (prosodyResult.prosody().getExpressiveness() > 0.7) {
            // Adiciona marcação de ênfase para textos expressivos
            return text.replaceAll("\\b(very|really|absolutely|completely|totally)\\b", "<emphasis>$1</emphasis>");
        }
        return text;
    }

    private static List<EmphasisMoment> findEmphasisInSegment(VTTEntry entry, Prosody.AnalysisResult prosodyResult) {
        List<EmphasisMoment> moments = new ArrayList<>();

        if (prosodyResult.prosody().getExpressiveness() > 0.5) {
            // Adiciona momento de ênfase no meio do segmento
            double duration = entry.duration();
            moments.add(new EmphasisMoment(
                    duration * 0.3,
                    duration * 0.7,
                    prosodyResult.prosody().getExpressiveness(),
                    "emotional"
            ));
        }

        return moments;
    }

    private static EnhancedTranscription createBasicEnhancedTranscription(Path audioFile, List<VTTEntry> basicTranscription) {
        logger.info("📋 Criando transcrição básica como fallback");

        List<EnhancedVTTEntry> enhancedEntries = basicTranscription.stream()
                .map(entry -> new EnhancedVTTEntry(
                        entry.startTime(),
                        entry.endTime(),
                        entry.text(),
                        entry.text(),
                        200.0, // pitch padrão
                        50.0,  // intensidade padrão
                        List.of(),
                        createNeutralEmotion()
                ))
                .toList();

        return new EnhancedTranscription(
                audioFile,
                enhancedEntries,
                List.of(),
                createNeutralEmotion(),
                createDefaultProsodyMetrics()
        );
    }

    private static EmotionMetrics createNeutralEmotion() {
        Map<String, Double> neutral = Map.of("neutral", 0.9);
        return new EmotionMetrics(0.5, 0.3, 0.5, neutral);
    }

    private static Prosody.Metrics createDefaultProsodyMetrics() {
        return new Prosody.Metrics(200.0, 50.0, 50.0, List.of(), Prosody.VoiceType.TENOR, 0.5);
    }

    /**
     * Consolida segmentos próximos no TSV para formar frases mais coerentes
     * Combina segmentos quando o intervalo é pequeno (< 3 segundos)
     */
    public static void consolidateSegments(String tsvPath) throws IOException {
        logger.info("🔗 Iniciando consolidação de segmentos próximos...");

        List<TSVEntry> entries = new ArrayList<>();

        // 1. Ler TSV atual
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(tsvPath))) {
            String line = reader.readLine(); // pular header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\t");
                if (parts.length >= 3) {
                    long start = Long.parseLong(parts[0]);
                    long end = Long.parseLong(parts[1]);
                    String text = parts[2];
                    entries.add(new TSVEntry(start, end, text));
                }
            }
        }

        // 2. Consolidar segmentos próximos
        List<TSVEntry> consolidated = new ArrayList<>();
        TSVEntry current = null;

        for (TSVEntry entry : entries) {
            if (current == null) {
                current = entry;
            } else {
                // Calcular intervalo entre segmentos (em segundos)
                double intervalSeconds = (entry.start - current.end) / 1000.0;

                // Se intervalo é pequeno E faz sentido conectar
                if (intervalSeconds <= 3.0 && shouldConsolidate(current, entry)) {
                    logger.fine("🔗 Consolidando: '" + current.text + "' + '" + entry.text + "'");
                    // Combinar segmentos
                    current = new TSVEntry(
                            current.start,
                            entry.end,
                            current.text + " " + entry.text
                    );
                } else {
                    // Salvar segmento atual e começar novo
                    consolidated.add(current);
                    current = entry;
                }
            }
        }

        // Adicionar último segmento
        if (current != null) {
            consolidated.add(current);
        }

        // 3. Reescrever TSV consolidado
        try (PrintWriter writer = new PrintWriter(tsvPath, StandardCharsets.UTF_8)) {
            writer.println("start\tend\ttext");

            for (TSVEntry entry : consolidated) {
                writer.println(entry.start + "\t" + entry.end + "\t" + entry.text);
            }
        }

        logger.info(String.format("✅ Consolidação concluída: %d → %d segmentos", entries.size(), consolidated.size()));
    }

    /**
     * Determina se dois segmentos devem ser consolidados
     */
    private static boolean shouldConsolidate(TSVEntry current, TSVEntry next) {
        String currentText = current.text.trim().toLowerCase();
        String nextText = next.text.trim().toLowerCase();

        // Não consolidar se o segmento atual já termina com pontuação forte
        if (currentText.endsWith(".") || currentText.endsWith("!") || currentText.endsWith("?")) {
            return false;
        }

        // Não consolidar se próximo segmento começa com maiúscula indicando nova frase
        if (nextText.length() > 0 && Character.isUpperCase(next.text.trim().charAt(0)) &&
                !startsWithCommonConnector(nextText)) {
            return false;
        }

        // Não consolidar segmentos muito longos juntos
        if (current.text.length() > 200 || next.text.length() > 200) {
            return false;
        }

        // Consolidar se:
        // - Texto atual é muito curto (< 30 chars)
        // - Próximo texto começa com conectores
        // - Textos fazem sentido juntos
        return currentText.length() < 30 ||
                startsWithCommonConnector(nextText) ||
                seemsRelated(currentText, nextText);
    }

    private static boolean startsWithCommonConnector(String text) {
        return text.startsWith("and ") || text.startsWith("then ") || text.startsWith("but ") ||
                text.startsWith("so ") || text.startsWith("also ") || text.startsWith("now ") ||
                text.startsWith("this ") || text.startsWith("that ");
    }

    private static boolean seemsRelated(String current, String next) {
        // Heurística simples: se compartilham palavras-chave
        String[] currentWords = current.split("\\s+");
        String[] nextWords = next.split("\\s+");

        int commonWords = 0;
        for (String word1 : currentWords) {
            for (String word2 : nextWords) {
                if (word1.length() > 3 && word1.equalsIgnoreCase(word2)) {
                    commonWords++;
                }
            }
        }

        return commonWords >= 1;
    }

    /**
     * Entrada TSV para consolidação
     */
    private record TSVEntry(long start, long end, String text) {
    }

    // =========== SHUTDOWN ===========

    public static void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("✅ Whisper shutdown concluído");
    }
}