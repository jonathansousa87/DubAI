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
 * 
 * Combina funcionalidades de:
 * - WhisperUtils (transcrição básica)
 * - WhisperXPlusUtils (análise prosódica avançada)
 */
public class Whisper {

    private static final Logger logger = Logger.getLogger(Whisper.class.getName());
    
    // =========== CONFIGURAÇÕES ===========
    
    private static final String[] MODELS = {
        "large-v3",
        "large-v3-turbo", 
        "large-v2",
        "large",
        "medium",
        "small",
        "base"
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
    ) {}
    
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
     * Transcrição básica de áudio - gera VTT
     */
    public static void transcribeAudio(String inputFile, String outputVtt) throws IOException, InterruptedException {
        for (String model : MODELS) {
            transcriptionSemaphore.acquire();
            try {
                logger.info("🎤 Tentando transcrever com modelo: " + model);
                executeWhisperX(inputFile, model, outputVtt);
                logger.info("✅ Transcrição concluída com modelo: " + model);
                return;
            } catch (IOException e) {
                String msg = e.getMessage().toLowerCase();
                logger.warning("⚠️ Falha com modelo " + model + ": " + e.getMessage());

                if (msg.contains("out of memory") || msg.contains("cuda") || msg.contains("memory")) {
                    logger.warning("🔥 Erro de memória GPU detectado, tentando próximo modelo...");
                } else {
                    logger.warning("❓ Erro não relacionado à memória, tentando próximo modelo...");
                }
            } finally {
                transcriptionSemaphore.release();
            }
        }
        throw new IOException("❌ Falha ao transcrever. Todos os modelos falharam.");
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
        
        // Verificar se arquivo temp existe antes da conversão
        if (!Files.exists(Paths.get(tempVtt))) {
            throw new IOException("Arquivo VTT temporário não foi criado: " + tempVtt);
        }
        
        convertVTTtoTSV(tempVtt, outputTsv);
        logger.info("✅ Convertido VTT → TSV: " + outputTsv);
        
        // 🔗 CONSOLIDAÇÃO DE SEGMENTOS PRÓXIMOS
        consolidateSegments(outputTsv);
        logger.info("✅ Segmentos consolidados: " + outputTsv);
        
        // Remove arquivo temporário APENAS se foi criado agora (não reusar VTT final)
        if (!tempVtt.equals(finalVtt)) {
            try {
                Files.deleteIfExists(Paths.get(tempVtt));
                logger.fine("✅ Arquivo temporário removido: " + tempVtt);
            } catch (Exception e) {
                logger.fine("Não foi possível deletar arquivo temp: " + e.getMessage());
            }
        } else {
            logger.fine("ℹ️ VTT final preservado: " + finalVtt);
        }
        
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
    
    private static void executeWhisperX(String inputFile, String model, String outputVtt) throws IOException, InterruptedException {
        File outputFile = new File(outputVtt);
        String outputDir = outputFile.getParent();
        if (outputDir == null) outputDir = ".";

        String[] command = {
                "whisperx",
                "--model", model,
                "--device", "cuda",
                "--output_dir", outputDir,
                inputFile
        };

        logger.fine("Executando: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("LD_LIBRARY_PATH", "/usr/lib:/usr/local/cuda/lib64");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder outputLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append("\n");
                logger.fine(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Comando falhou com código: " + exitCode + ". Saída: " + outputLog.toString());
        }

        File generatedFile = new File(outputDir, "vocals.vtt");
        if (!generatedFile.exists()) {
            throw new IOException("vocals.vtt não encontrado. Saída:\n" + outputLog.toString());
        }

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
        } catch (IOException e) {
            throw new IOException("Erro ao renomear arquivo: " + e.getMessage());
        }
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
     * Converte arquivo VTT para TSV (formato preferido para tradução)
     */
    public static void convertVTTtoTSV(String vttPath, String tsvPath) throws IOException {
        List<VTTEntry> entries = parseVTTFile(vttPath);
        
        try (PrintWriter writer = new PrintWriter(tsvPath, StandardCharsets.UTF_8)) {
            writer.println("start\tend\ttext");
            
            for (VTTEntry entry : entries) {
                long startMs = (long) (entry.startTime() * 1000);
                long endMs = (long) (entry.endTime() * 1000);
                writer.println(startMs + "\t" + endMs + "\t" + entry.text());
            }
        }
        
        logger.info("✅ Convertido VTT → TSV: " + tsvPath);
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
    private record TSVEntry(long start, long end, String text) {}
    
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