package org;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Translation - Sistema de Tradução Limpo e Objetivo
 * 
 * FOCO: Traduzir texto mantendo timestamps exatos, sem alucinações da IA
 * ENTRADA PREFERIDA: vocals.tsv (formato mais limpo do WhisperX)
 * SAÍDA: Arquivo com mesmos timestamps + texto traduzido
 */
public class Translation {

    private static final Logger logger = Logger.getLogger(Translation.class.getName());

    // =========== CONFIGURAÇÕES ===========
    
    // Google Gemma 3 27B (API gratuita - mais poderoso)
    private static final String GOOGLE_AI_API_KEY = "AIzaSyA1pPJP2fhtFVAVRstdIOZfCZQlektuGpQ"; // Mesma key do Gemini
    private static final String GOOGLE_AI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemma-3-27b-it:generateContent";
    
    // Controle de método de tradução
    public enum TranslationMethod {
        GOOGLE_GEMMA_3  // Usa Google Gemma 3 27B API
    }
    
    private static TranslationMethod currentMethod = TranslationMethod.GOOGLE_GEMMA_3;
    private static String googleApiKey = GOOGLE_AI_API_KEY;
    
    // =========== MÉTODOS PÚBLICOS DE CONFIGURAÇÃO ===========
    
    /**
     * Define o método de tradução a ser usado
     */
    public static void setTranslationMethod(TranslationMethod method) {
        currentMethod = method;
        logger.info(String.format("🔧 Método de tradução alterado para: %s", method));
    }
    
    /**
     * Define a API key do Google AI Studio
     */
    public static void setGoogleApiKey(String apiKey) {
        googleApiKey = apiKey;
        logger.info("🔑 API Key do Google AI configurada");
    }
    
    /**
     * Retorna o método atual de tradução
     */
    public static TranslationMethod getCurrentMethod() {
        return currentMethod;
    }
    
    // Configurações anti-alucinação
    private static final int MAX_CHARS_PER_BATCH = 300;    // Textos menores = menos alucinação
    private static final int MAX_RETRIES = 2;              // Máximo 2 tentativas
    private static final long TIMEOUT_SECONDS = 30;       // Timeout curto
    private static final double TEMPERATURE = 0.1;        // Baixa criatividade
    
    // Configurações para validação de timestamp
    private static final String GEMINI_API_KEY = "AIzaSyA1pPJP2fhtFVAVRstdIOZfCZQlektuGpQ";
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final double PORTUGUESE_SPEAKING_SPEED = 4.5; // palavras por segundo (velocidade média PT-BR)
    private static final double TIMESTAMP_TOLERANCE = 1.2;       // tolerância: aceitar até 1.2x o tempo necessário
    
    // =========== CLASSES AUXILIARES ===========
    
    /**
     * Representa um segmento com timestamp preciso
     */
    public record TranscriptionSegment(
        long startMs,      // Timestamp início em milissegundos
        long endMs,        // Timestamp fim em milissegundos  
        String text        // Texto original
    ) {
        public TranscriptionSegment {
            if (startMs < 0) throw new IllegalArgumentException("startMs deve ser >= 0");
            if (endMs <= startMs) throw new IllegalArgumentException("endMs deve ser > startMs");
            if (text == null) text = "";
        }
        
        public long durationMs() {
            return endMs - startMs;
        }
        
        // Converte timestamp para formato VTT
        public String toVTTTimestamp() {
            return formatTimestamp(startMs) + " --> " + formatTimestamp(endMs);
        }
        
        private String formatTimestamp(long ms) {
            long hours = ms / 3600000;
            long minutes = (ms % 3600000) / 60000;
            long seconds = (ms % 60000) / 1000;
            long millis = ms % 1000;
            return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
        }
    }
    
    /**
     * Segmento traduzido (timestamps NUNCA mudam)
     */
    public record TranslatedSegment(
        long startMs,           // EXATO do original
        long endMs,             // EXATO do original
        String originalText,    // Texto original
        String translatedText   // Texto traduzido
    ) {
        public TranslatedSegment {
            if (startMs < 0) throw new IllegalArgumentException("startMs deve ser >= 0");
            if (endMs <= startMs) throw new IllegalArgumentException("endMs deve ser > startMs");
            if (originalText == null) originalText = "";
            if (translatedText == null) translatedText = originalText;
        }
        
        public String toVTTEntry() {
            TranscriptionSegment original = new TranscriptionSegment(startMs, endMs, "");
            return original.toVTTTimestamp() + "\n" + translatedText + "\n";
        }
    }
    
    // =========== MÉTODOS PRINCIPAIS ===========
    
    /**
     * Traduz arquivo TSV do WhisperX
     */
    public static void translateFile(String inputTsvPath, String outputVttPath) throws IOException, InterruptedException {
        logger.info("🌍 Iniciando tradução com salvamento incremental: " + inputTsvPath);
        
        // 1. Carregar segmentos do TSV
        List<TranscriptionSegment> segments = loadFromTSV(inputTsvPath);
        logger.info("📋 Carregados " + segments.size() + " segmentos");
        
        // 2. Verificar se existe tradução parcial (recuperação)
        String tempOutputPath = outputVttPath + ".temp";
        List<TranslatedSegment> existingTranslated = loadExistingTranslation(tempOutputPath);
        int startFrom = existingTranslated.size();
        
        if (startFrom > 0) {
            logger.info("🔄 Recuperando tradução parcial: " + startFrom + " segmentos já traduzidos");
        }
        
        // 3. Traduzir segmentos restantes com salvamento incremental
        List<TranslatedSegment> allTranslated = new ArrayList<>(existingTranslated);
        List<TranscriptionSegment> remaining = segments.subList(startFrom, segments.size());
        
        if (!remaining.isEmpty()) {
            List<TranslatedSegment> newTranslated = translateSegmentsWithIncrementalSave(remaining, tempOutputPath, allTranslated);
            allTranslated.addAll(newTranslated);
        }
        
        // 4. 🔍 VALIDAÇÃO FINAL E RE-TRADUÇÃO DE SEGMENTOS NÃO TRADUZIDOS
        logger.info("🔍 Validando tradução final...");
        List<TranslatedSegment> finalTranslated = retranslateUntranslatedSegments(allTranslated);
        
        // 5. ⏱️ VALIDAÇÃO DE TIMESTAMP E SIMPLIFICAÇÃO COM GEMINI
        // Nota: Consolidação já é feita no Whisper.java automaticamente
        logger.info("⏱️ INICIANDO VALIDAÇÃO DE TIMESTAMP COM GEMINI API...");
        List<TranslatedSegment> timestampValidated = validateAndSimplifyTimestamps(finalTranslated);
        
        // 6. Salvar como VTT final e remover temp
        saveAsVTT(timestampValidated, outputVttPath);
        Files.deleteIfExists(Paths.get(tempOutputPath));
        
        logger.info("✅ Tradução concluída com validação completa: " + outputVttPath);
    }
    
    /**
     * Método de compatibilidade (legacy)
     */
    public static void translateFile(String inputPath, String outputPath, String method) throws IOException, InterruptedException {
        // Detectar formato do arquivo
        if (inputPath.endsWith(".tsv")) {
            translateFile(inputPath, outputPath);
        } else if (inputPath.endsWith(".vtt")) {
            translateVTTFile(inputPath, outputPath);
        } else {
            throw new IllegalArgumentException("Formato não suportado: " + inputPath);
        }
    }
    
    // =========== MÉTODOS DE CARREGAMENTO ===========
    
    /**
     * Carrega segmentos do formato TSV (PREFERIDO)
     */
    private static List<TranscriptionSegment> loadFromTSV(String tsvPath) throws IOException {
        List<TranscriptionSegment> segments = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(tsvPath), StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Pular cabeçalho
                if (firstLine) {
                    firstLine = false;
                    continue;
                }
                
                if (line.isEmpty()) continue;
                
                String[] parts = line.split("\\t", 3);
                if (parts.length >= 3) {
                    try {
                        long startMs = Long.parseLong(parts[0]);
                        long endMs = Long.parseLong(parts[1]);
                        String text = parts[2].trim();
                        
                        if (!text.isEmpty()) {
                            segments.add(new TranscriptionSegment(startMs, endMs, text));
                        }
                    } catch (NumberFormatException e) {
                        logger.warning("⚠️ Linha inválida ignorada: " + line);
                    }
                }
            }
        }
        
        return segments;
    }
    
    /**
     * Carrega segmentos do formato VTT (fallback)
     */
    private static List<TranscriptionSegment> loadFromVTT(String vttPath) throws IOException {
        List<TranscriptionSegment> segments = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(vttPath), StandardCharsets.UTF_8)) {
            String line;
            TranscriptionSegment currentSegment = null;
            StringBuilder textBuilder = new StringBuilder();
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.isEmpty()) {
                    if (currentSegment != null && textBuilder.length() > 0) {
                        segments.add(new TranscriptionSegment(
                            currentSegment.startMs(),
                            currentSegment.endMs(),
                            textBuilder.toString().trim()
                        ));
                        textBuilder.setLength(0);
                        currentSegment = null;
                    }
                } else if (line.contains("-->")) {
                    currentSegment = parseVTTTimestamp(line);
                } else if (currentSegment != null && !line.startsWith("WEBVTT") && !line.matches("^\\d+$")) {
                    if (textBuilder.length() > 0) textBuilder.append(" ");
                    textBuilder.append(line);
                }
            }
            
            // Último segmento
            if (currentSegment != null && textBuilder.length() > 0) {
                segments.add(new TranscriptionSegment(
                    currentSegment.startMs(),
                    currentSegment.endMs(),
                    textBuilder.toString().trim()
                ));
            }
        }
        
        return segments;
    }
    
    private static TranscriptionSegment parseVTTTimestamp(String line) {
        try {
            String[] parts = line.split("-->");
            if (parts.length == 2) {
                long startMs = parseVTTTime(parts[0].trim());
                long endMs = parseVTTTime(parts[1].trim());
                return new TranscriptionSegment(startMs, endMs, "");
            }
        } catch (Exception e) {
            logger.warning("Erro parseando timestamp: " + line);
        }
        return new TranscriptionSegment(0, 1000, "");
    }
    
    private static long parseVTTTime(String timeStr) {
        // Formato: HH:MM:SS.mmm ou MM:SS.mmm
        timeStr = timeStr.replace(',', '.');
        String[] parts = timeStr.split(":");
        
        if (parts.length == 3) {
            // HH:MM:SS.mmm
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            String[] secParts = parts[2].split("\\.");
            int seconds = Integer.parseInt(secParts[0]);
            int millis = secParts.length > 1 ? Integer.parseInt(secParts[1]) : 0;
            
            return hours * 3600000L + minutes * 60000L + seconds * 1000L + millis;
        } else if (parts.length == 2) {
            // MM:SS.mmm
            int minutes = Integer.parseInt(parts[0]);
            String[] secParts = parts[1].split("\\.");
            int seconds = Integer.parseInt(secParts[0]);
            int millis = secParts.length > 1 ? Integer.parseInt(secParts[1]) : 0;
            
            return minutes * 60000L + seconds * 1000L + millis;
        }
        
        return 0;
    }
    
    // =========== MÉTODOS DE TRADUÇÃO ===========
    
    /**
     * Traduz segmentos em chunks com salvamento incremental e recuperação automática
     */
    private static List<TranslatedSegment> translateSegments(List<TranscriptionSegment> segments) throws IOException, InterruptedException {
        List<TranslatedSegment> allTranslated = new ArrayList<>();
        
        final int CHUNK_SIZE = 20; // Chunks maiores para melhor eficiência
        final int SEGMENTS_PER_BATCH = 5; // Batches dentro de cada chunk
        
        // Processar em chunks maiores com salvamento incremental
        for (int chunkStart = 0; chunkStart < segments.size(); chunkStart += CHUNK_SIZE) {
            int chunkEnd = Math.min(chunkStart + CHUNK_SIZE, segments.size());
            List<TranscriptionSegment> chunk = segments.subList(chunkStart, chunkEnd);
            
            logger.info(String.format("🔄 Processando CHUNK %d-%d (%d segmentos)", chunkStart + 1, chunkEnd, chunk.size()));
            
            // Tentar traduzir o chunk com recuperação automática
            List<TranslatedSegment> chunkTranslated = translateChunkWithRecovery(chunk, chunkStart);
            allTranslated.addAll(chunkTranslated);
            
            // 🧹 LIMPEZA DE MEMÓRIA ENTRE CHUNKS
            if (chunkStart > 0 && chunkStart % 40 == 0) { // A cada 2 chunks (40 segmentos)
                logger.info("🧹 Limpando memória GPU entre chunks...");
                ClearMemory.runClearNameThenThreshold("translation_chunk_" + chunkStart);
                System.gc(); // Força garbage collection
                Thread.sleep(2000); // Pausa para limpeza
            }
            
            logger.info(String.format("✅ CHUNK concluído: %d/%d segmentos totais", allTranslated.size(), segments.size()));
        }
        
        return allTranslated;
    }
    
    /**
     * Traduz segmentos com salvamento incremental a cada chunk
     */
    private static List<TranslatedSegment> translateSegmentsWithIncrementalSave(
            List<TranscriptionSegment> segments, 
            String tempOutputPath, 
            List<TranslatedSegment> existingTranslated) throws IOException, InterruptedException {
        
        List<TranslatedSegment> newTranslated = new ArrayList<>();
        final int CHUNK_SIZE = 20;
        
        for (int chunkStart = 0; chunkStart < segments.size(); chunkStart += CHUNK_SIZE) {
            int chunkEnd = Math.min(chunkStart + CHUNK_SIZE, segments.size());
            List<TranscriptionSegment> chunk = segments.subList(chunkStart, chunkEnd);
            
            logger.info(String.format("🔄 Processando CHUNK %d-%d (%d segmentos)", chunkStart + 1, chunkEnd, chunk.size()));
            
            // Traduzir chunk com recuperação
            List<TranslatedSegment> chunkTranslated = translateChunkWithRecovery(chunk, chunkStart);
            newTranslated.addAll(chunkTranslated);
            
            // 💾 SALVAMENTO INCREMENTAL APÓS CADA CHUNK
            List<TranslatedSegment> allSoFar = new ArrayList<>(existingTranslated);
            allSoFar.addAll(newTranslated);
            saveIncrementalProgress(allSoFar, tempOutputPath);
            
            logger.info(String.format("💾 Progresso salvo: %d segmentos totais", allSoFar.size()));
            
            // Limpeza entre chunks
            if (chunkStart > 0 && chunkStart % 40 == 0) {
                logger.info("🧹 Limpando memória GPU entre chunks...");
                ClearMemory.runClearNameThenThreshold("translation_chunk_" + chunkStart);
                System.gc();
                Thread.sleep(2000);
            }
        }
        
        return newTranslated;
    }
    
    /**
     * Salva progresso incremental em arquivo temporário
     */
    private static void saveIncrementalProgress(List<TranslatedSegment> translated, String tempPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(tempPath, StandardCharsets.UTF_8)) {
            writer.println("WEBVTT");
            writer.println();
            
            for (int i = 0; i < translated.size(); i++) {
                TranslatedSegment segment = translated.get(i);
                
                writer.println(i + 1);
                writer.printf(Locale.US, "%02d:%02d:%02d.%03d --> %02d:%02d:%02d.%03d%n",
                    (int)(segment.startMs() / 3600000), 
                    (int)(segment.startMs() % 3600000) / 60000,
                    (int)(segment.startMs() % 60000) / 1000,
                    (int)(segment.startMs() % 1000),
                    (int)(segment.endMs() / 3600000),
                    (int)(segment.endMs() % 3600000) / 60000,
                    (int)(segment.endMs() % 60000) / 1000,
                    (int)(segment.endMs() % 1000)
                );
                writer.println(segment.translatedText());
                writer.println();
            }
        }
    }
    
    /**
     * Carrega tradução existente de arquivo temporário para recuperação
     */
    private static List<TranslatedSegment> loadExistingTranslation(String tempPath) {
        if (!Files.exists(Paths.get(tempPath))) {
            return new ArrayList<>();
        }
        
        List<TranslatedSegment> existing = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(tempPath))) {
            String line;
            TranscriptionSegment currentSegment = null;
            StringBuilder textBuilder = new StringBuilder();
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.isEmpty()) {
                    if (currentSegment != null && textBuilder.length() > 0) {
                        existing.add(new TranslatedSegment(
                            currentSegment.startMs(),
                            currentSegment.endMs(),
                            "", // originalText não disponível na recuperação
                            textBuilder.toString().trim()
                        ));
                        textBuilder.setLength(0);
                        currentSegment = null;
                    }
                } else if (line.contains("-->")) {
                    // Parse timestamp line - usar método existente mas converter tipo
                    TranscriptionSegment temp = parseVTTTimestamp(line);
                    currentSegment = temp; // Reusar para startMs/endMs
                } else if (currentSegment != null && !line.matches("^\\d+$") && !line.equals("WEBVTT")) {
                    if (textBuilder.length() > 0) textBuilder.append(" ");
                    textBuilder.append(line);
                }
            }
            
            // Add last segment if exists
            if (currentSegment != null && textBuilder.length() > 0) {
                existing.add(new TranslatedSegment(
                    currentSegment.startMs(),
                    currentSegment.endMs(),
                    "", // originalText não disponível na recuperação
                    textBuilder.toString().trim()
                ));
            }
            
        } catch (IOException e) {
            logger.warning("⚠️ Erro carregando tradução parcial: " + e.getMessage());
        }
        
        return existing;
    }
    
    /**
     * Traduz um chunk com recuperação automática em caso de falha
     */
    private static List<TranslatedSegment> translateChunkWithRecovery(List<TranscriptionSegment> chunk, int chunkStartIndex) throws IOException, InterruptedException {
        List<TranslatedSegment> chunkResult = new ArrayList<>();
        
        // Processar chunk em batches pequenos
        for (int i = 0; i < chunk.size(); i += 5) {
            int batchEnd = Math.min(i + 5, chunk.size());
            List<TranscriptionSegment> batch = chunk.subList(i, batchEnd);
            int absoluteBatchIndex = chunkStartIndex + i;
            
            try {
                List<TranslatedSegment> batchTranslated = translateBatch(batch);
                chunkResult.addAll(batchTranslated);
                
                logger.info(String.format("📝 Batch %d-%d traduzido com sucesso", absoluteBatchIndex + 1, absoluteBatchIndex + batch.size()));
                
            } catch (Exception e) {
                logger.warning(String.format("❌ FALHA no batch %d-%d: %s", absoluteBatchIndex + 1, absoluteBatchIndex + batch.size(), e.getMessage()));
                
                // 🚨 RECUPERAÇÃO AUTOMÁTICA AGRESSIVA
                logger.severe("🚨 FALHA DETECTADA - Ativando recuperação automática imediata!");
                
                boolean recovered = false;
                
                // TENTATIVA 1: Limpeza agressiva
                try {
                    logger.info("🔧 [1/3] Limpeza agressiva...");
                    ClearMemory.runClearNameThenThreshold("critical_recovery_" + absoluteBatchIndex);
                    Thread.sleep(8000); // Aguarda mais tempo para restart
                    
                    logger.info("🔄 Tentativa de recuperação 1/3...");
                    List<TranslatedSegment> recoveredBatch = translateBatchWithReducedModel(batch);
                    chunkResult.addAll(recoveredBatch);
                    logger.info("✅ Recuperação SUCESSO (tentativa 1)!");
                    recovered = true;
                    
                } catch (Exception e1) {
                    logger.warning("⚠️ Recuperação tentativa 1 falhou: " + e1.getMessage());
                    
                    // TENTATIVA 2: Duplo restart + modelo menor
                    try {
                        logger.info("🔧 [2/3] Duplo restart + modelo menor...");
                        Thread.sleep(5000);
                        
                        logger.info("🔄 Tentativa de recuperação 2/3 (modelo menor)...");
                        List<TranslatedSegment> recoveredBatch2 = translateBatchWithFallbackModel(batch);
                        chunkResult.addAll(recoveredBatch2);
                        logger.info("✅ Recuperação SUCESSO (tentativa 2)!");
                        recovered = true;
                        
                    } catch (Exception e2) {
                        logger.severe("💀 Todas as recuperações falharam: " + e2.getMessage());
                    }
                }
                
                // FALLBACK FINAL se tudo falhou
                if (!recovered) {
                    logger.severe("🆘 USANDO FALLBACK - mantendo texto original");
                    List<TranslatedSegment> fallbackBatch = createFallbackTranslation(batch);
                    chunkResult.addAll(fallbackBatch);
                }
            }
        }
        
        return chunkResult;
    }
    
    /**
     * Tradução com modelo reduzido (usa Google Gemma 3)
     */
    private static List<TranslatedSegment> translateBatchWithReducedModel(List<TranscriptionSegment> batch) throws IOException, InterruptedException {
        // Usar Google Gemma 3 para recuperação
        return translateBatchWithGoogleGemma(batch, buildBatchText(batch));
    }
    
    /**
     * Tradução com modelo de fallback (usa Google Gemma 3)
     */
    private static List<TranslatedSegment> translateBatchWithFallbackModel(List<TranscriptionSegment> batch) throws IOException, InterruptedException {
        // Usar Google Gemma 3 para fallback também
        return translateBatchWithGoogleGemma(batch, buildBatchText(batch));
    }
    
    /**
     * Cria tradução de fallback quando tudo falha (copia texto original)
     */
    private static List<TranslatedSegment> createFallbackTranslation(List<TranscriptionSegment> batch) {
        logger.warning("⚠️ Usando tradução fallback (texto original mantido)");
        return batch.stream()
            .map(seg -> new TranslatedSegment(seg.startMs(), seg.endMs(), seg.text(), "[ERRO TRADUÇÃO] " + seg.text()))
            .toList();
    }
    
    /**
     * Traduz um lote pequeno de segmentos
     */
    private static List<TranslatedSegment> translateBatch(List<TranscriptionSegment> batch) throws IOException, InterruptedException {
        String batchText = buildBatchText(batch);
        
        // Usar Google Gemma 3 por padrão
        return translateBatchWithGoogleGemma(batch, batchText);
    }
    
    /**
     * Traduz usando API Google (método removido)
     */
    
    /**
     * Traduz usando Google Gemma 3 27B API
     */
    private static List<TranslatedSegment> translateBatchWithGoogleGemma(List<TranscriptionSegment> batch, String batchText) throws IOException, InterruptedException {
        if (googleApiKey == null || googleApiKey.isEmpty()) {
            throw new IOException("❌ API Key do Google AI não configurada. Configure em setGoogleApiKey()");
        }
        
        logger.info("🤖 Traduzindo com Google Gemma 3 27B...");
        
        try {
            String translatedBatch = callGoogleGemmaTranslation(batchText);
            List<TranslatedSegment> result = parseBatchResult(batch, translatedBatch);
            
            logger.info("✅ Tradução Google Gemma 3 concluída com sucesso");
            return result;
            
        } catch (Exception e) {
            logger.severe("❌ Falha na tradução Google Gemma 3: " + e.getMessage());
            throw new IOException("Falha na API Google Gemma 3: " + e.getMessage(), e);
        }
    }
    
    /**
     * Constrói texto do lote com constraints de tempo para dublagem sincronizada
     */
    private static String buildBatchText(List<TranscriptionSegment> batch) {
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < batch.size(); i++) {
            TranscriptionSegment seg = batch.get(i);
            double startSeconds = seg.startMs() / 1000.0;
            double endSeconds = seg.endMs() / 1000.0;
            
            // Formato: [1|2.5s-4.8s] Text
            String timestamp = String.format("[%d|%.1fs-%.1fs]", i + 1, startSeconds, endSeconds);
            
            sb.append(String.format("%s %s\n", timestamp, seg.text()));
        }
        
        return sb.toString();
    }
    
    /**
     * Chama API Google com prompt limpo e direto
     */
    
    /**
     * Prompt DINÂMICO para dublagem sincronizada de cursos de programação
     */
    private static String buildCleanPrompt(String text) {
        return "Translate to Brazilian Portuguese for synchronized programming course dubbing. " +
               "CRITICAL TIMING RULES:\n" +
               "- Each line has format [number|start-end] followed by English text\n" +
               "- The timestamp shows EXACTLY how much time you have for that translation\n" +
               "- Example: [1|2.5s-4.8s] means you have 2.3 seconds for this phrase\n" +
               "- Adjust translation length to fit the exact time available\n" +
               "- Short time (< 2s) = Concise, direct translation\n" +
               "- Medium time (2-6s) = Natural conversational pace\n" +
               "- Long time (> 6s) = Detailed explanation with educational context\n" +
               "- DO NOT translate technical terms: JavaScript, TypeScript, React, Angular, Next.js, Java, Spring Boot, API, framework, component, props, state, hook, function, class, method, variable, array, object, promise, async, await, import, export, npm, yarn, webpack, etc.\n" +
               "- Use natural Brazilian Portuguese for explanations\n" +
               "- Keep code examples and file names in original language\n" +
               "- Respond with same [number] format but REMOVE the timestamp from your answer\n\n" + text;
    }
    
    
    
    /**
     * Chama a API do Google Gemma 3 27B para tradução
     */
    private static String callGoogleGemmaTranslation(String text) throws IOException, InterruptedException {
        String prompt = buildCleanPrompt(text);
        String payload = buildGoogleGemmaPayload(prompt);
        
        ProcessBuilder pb = new ProcessBuilder(
            "curl", "-s", "-X", "POST", GOOGLE_AI_API_URL + "?key=" + googleApiKey,
            "-H", "Content-Type: application/json",
            "-d", payload,
            "--max-time", String.valueOf(TIMEOUT_SECONDS)
        );
        
        Process process = pb.start();
        
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout na tradução Google Gemma 3");
        }
        
        if (process.exitValue() != 0) {
            throw new IOException("Erro na API Google Gemma 3: " + process.exitValue());
        }
        
        return parseGoogleGemmaResponse(response.toString());
    }
    
    /**
     * Constrói payload para API do Google Gemma 3
     */
    private static String buildGoogleGemmaPayload(String prompt) {
        // Escapar JSON corretamente
        String escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
            
        return String.format(Locale.US,
            "{" +
            "\"contents\":[{" +
                "\"parts\":[{\"text\":\"%s\"}]" +
            "}]," +
            "\"generationConfig\":{" +
                "\"temperature\":%.1f," +
                "\"maxOutputTokens\":2048," +
                "\"topP\":0.8," +
                "\"topK\":40" +
            "}" +
            "}",
            escapedPrompt,
            TEMPERATURE
        );
    }
    
    /**
     * Parseia resposta da API do Google Gemma 3
     */
    private static String parseGoogleGemmaResponse(String response) {
        try {
            // Parse simples e robusto usando regex
            // Formato: {"candidates":[{"content":{"parts":[{"text":"CONTEUDO_AQUI"}]}}]}
            
            // Procurar pelo primeiro "text": e extrair até a próxima aspa não escapada
            String pattern = "\"text\"\\s*:\\s*\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"";
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = regex.matcher(response);
            
            if (matcher.find()) {
                String content = matcher.group(1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\t", "\t")
                    .replace("\\r", "\r");
                
                logger.info("🔤 Google Gemma resposta extraída: " + content.substring(0, Math.min(50, content.length())) + "...");
                return content.trim();
            } else {
                // Fallback: busca manual mais simples
                int textStart = response.indexOf("\"text\":\"");
                if (textStart != -1) {
                    textStart += 8; // pular "text":"
                    int textEnd = response.indexOf("\"", textStart);
                    
                    // Procurar fim real considerando escapes
                    while (textEnd > 0 && textEnd < response.length() && response.charAt(textEnd - 1) == '\\') {
                        textEnd = response.indexOf("\"", textEnd + 1);
                    }
                    
                    if (textEnd > textStart) {
                        String content = response.substring(textStart, textEnd)
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                        return content.trim();
                    }
                }
            }
            
            logger.warning("❌ Não foi possível extrair texto da resposta Google Gemma");
            logger.warning("🔍 Resposta bruta: " + response.substring(0, Math.min(200, response.length())));
            
        } catch (Exception e) {
            logger.warning("Erro parseando resposta Google Gemma: " + e.getMessage());
        }
        
        return "";
    }
    
    /**
     * Parseia resultado do lote traduzido
     */
    private static List<TranslatedSegment> parseBatchResult(List<TranscriptionSegment> original, String translated) {
        List<TranslatedSegment> result = new ArrayList<>();
        String[] lines = translated.split("\n");
        
        Map<Integer, String> translations = new HashMap<>();
        
        // Extrair traduções numeradas
        for (String line : lines) {
            line = line.trim();
            if (line.matches("\\[\\d+\\].*")) {
                try {
                    int num = Integer.parseInt(line.substring(1, line.indexOf(']')));
                    String text = line.substring(line.indexOf(']') + 1).trim();
                    
                    // Limpar timestamps e comentários da IA, mas preservar numeração [1], [2], etc.
                    text = text.replaceAll("\\[(\\d+)\\|[^\\]]+\\]", "[$1]").trim(); // [1|2.5s-4.8s] → [1]
                    text = text.replaceAll("\\[(?!\\d+\\])[^\\]]*\\]", "").trim(); // Remove outros [texto] mas mantém [número]
                    text = text.replaceAll("\\(.*?\\)", "").trim(); // Remove (qualquer coisa)
                    text = text.replaceAll("\\*([^*]+)\\*", "$1").trim(); // Remove *palavra* → palavra
                    text = text.replaceAll("\\s*–\\s*[^.]*\\.", "").trim(); // Remove comentários com –
                    
                    if (!text.isEmpty()) {
                        translations.put(num, text);
                    }
                } catch (Exception e) {
                    logger.fine("Linha ignorada: " + line);
                }
            }
        }
        
        // Mapear de volta para segmentos originais
        for (int i = 0; i < original.size(); i++) {
            TranscriptionSegment orig = original.get(i);
            String translatedText = translations.getOrDefault(i + 1, orig.text());
            
            result.add(new TranslatedSegment(
                orig.startMs(), 
                orig.endMs(), 
                orig.text(), 
                translatedText
            ));
        }
        
        return result;
    }
    
    /**
     * Valida se a tradução é válida (sem alucinação)
     */
    private static boolean isValidTranslation(List<TranslatedSegment> translated) {
        if (translated.isEmpty()) return false;
        
        int untranslatedCount = 0;
        int totalSegments = translated.size();
        
        for (TranslatedSegment seg : translated) {
            String original = seg.originalText().trim();
            String translatedText = seg.translatedText().trim();
            
            // 1. Verificar se não há alucinação óbvia
            if (translatedText.length() > original.length() * 3) {
                logger.warning("❌ Alucinação detectada (muito longo): " + translatedText.substring(0, Math.min(50, translatedText.length())) + "...");
                return false;
            }
            
            // 2. Verificar reflexões da IA
            String translatedLower = translatedText.toLowerCase();
            if (translatedLower.contains("como assistente") ||
                translatedLower.contains("não posso") ||
                translatedLower.contains("desculpe") ||
                translatedLower.contains("como ia") ||
                translatedLower.contains("sou uma ia")) {
                logger.warning("❌ Reflexão da IA detectada: " + translatedText);
                return false;
            }
            
            // 3. 🚨 NOVA VALIDAÇÃO: Detectar texto não traduzido
            if (isTextUntranslated(original, translatedText)) {
                untranslatedCount++;
                logger.warning("⚠️ Texto não traduzido: '" + original + "' → '" + translatedText + "'");
            }
        }
        
        // 4. Verificar percentual de texto não traduzido
        double untranslatedPercentage = (double) untranslatedCount / totalSegments;
        if (untranslatedPercentage > 0.3) { // Mais de 30% não traduzido
            logger.warning(String.format("❌ Muitos textos não traduzidos: %d/%d (%.1f%%)", 
                untranslatedCount, totalSegments, untranslatedPercentage * 100));
            return false;
        }
        
        if (untranslatedCount > 0) {
            logger.info(String.format("⚠️ Alguns textos não traduzidos: %d/%d (%.1f%%) - dentro do limite aceitável", 
                untranslatedCount, totalSegments, untranslatedPercentage * 100));
        }
        
        return true;
    }
    
    /**
     * Detecta se um texto não foi traduzido do inglês para português
     */
    private static boolean isTextUntranslated(String original, String translated) {
        String origLower = original.toLowerCase().trim();
        String transLower = translated.toLowerCase().trim();
        
        // 1. Textos idênticos (exceto pontuação)
        if (origLower.equals(transLower)) {
            return true;
        }
        
        // 2. Textos muito similares (90%+ igual)
        double similarity = calculateStringSimilarity(origLower, transLower);
        if (similarity > 0.9) {
            return true;
        }
        
        // 3. Contém muitas palavras inglesas comuns
        String[] englishWords = {"the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was", "one", 
                                "our", "had", "but", "words", "use", "each", "which", "their", "time", "will", "about", 
                                "if", "up", "out", "many", "then", "them", "would", "like", "into", "him", "has", "more", 
                                "go", "no", "way", "could", "my", "than", "first", "been", "call", "who", "its", "now",
                                "find", "long", "down", "day", "did", "get", "come", "made", "may", "part", "over",
                                "new", "sound", "take", "only", "little", "work", "know", "place", "year", "live",
                                "me", "back", "give", "most", "very", "after", "thing", "just", "name", "good",
                                "sentence", "man", "think", "say", "great", "where", "help", "through", "much",
                                "before", "line", "right", "too", "any", "same", "tell", "boy", "follow", "came",
                                "want", "show", "also", "around", "form", "three", "small", "set", "put", "end"};
        
        int englishWordCount = 0;
        String[] words = transLower.split("\\s+");
        
        for (String word : words) {
            word = word.replaceAll("[^a-z]", ""); // Remove pontuação
            if (word.length() >= 3) { // Palavras com 3+ letras
                for (String englishWord : englishWords) {
                    if (word.equals(englishWord)) {
                        englishWordCount++;
                        break;
                    }
                }
            }
        }
        
        // Se mais de 40% das palavras são inglês comum
        if (words.length > 2 && (double) englishWordCount / words.length > 0.4) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Calcula similaridade entre duas strings (0.0 = totalmente diferente, 1.0 = idênticas)
     */
    private static double calculateStringSimilarity(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        
        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) return 1.0;
        
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLength;
    }
    
    /**
     * Calcula distância de Levenshtein entre duas strings
     */
    private static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
    
    /**
     * Re-traduz segmentos individuais que não foram traduzidos corretamente
     */
    public static List<TranslatedSegment> retranslateUntranslatedSegments(List<TranslatedSegment> segments) throws IOException, InterruptedException {
        List<TranslatedSegment> correctedSegments = new ArrayList<>();
        int retranslatedCount = 0;
        
        logger.info("🔍 Verificando segmentos não traduzidos...");
        
        // Processar cada segmento individualmente
        for (int i = 0; i < segments.size(); i++) {
            TranslatedSegment seg = segments.get(i);
            
            if (isTextUntranslated(seg.originalText(), seg.translatedText())) {
                logger.info(String.format("🔄 Re-traduzindo segmento [%d]: '%s'", i + 1, seg.originalText()));
                
                try {
                    // Re-traduzir APENAS esta frase específica
                    String retranslatedText = retranslateSingleSegment(seg.originalText());
                    
                    // Verificar se a re-tradução foi bem-sucedida
                    if (!isTextUntranslated(seg.originalText(), retranslatedText)) {
                        correctedSegments.add(new TranslatedSegment(
                            seg.startMs(), seg.endMs(), seg.originalText(), retranslatedText
                        ));
                        logger.info(String.format("✅ Segmento %d re-traduzido: '%s' → '%s'", 
                            i + 1, seg.originalText(), retranslatedText));
                        retranslatedCount++;
                    } else {
                        // Re-tradução falhou, manter original
                        logger.warning(String.format("⚠️ Re-tradução falhou para segmento %d, mantendo original", i + 1));
                        correctedSegments.add(seg);
                    }
                    
                } catch (Exception e) {
                    logger.warning(String.format("⚠️ Erro re-traduzindo segmento %d: %s", i + 1, e.getMessage()));
                    correctedSegments.add(seg);
                }
                
            } else {
                // Segmento já está bem traduzido
                correctedSegments.add(seg);
            }
        }
        
        if (retranslatedCount == 0) {
            logger.info("✅ Nenhum segmento precisou de re-tradução");
        } else {
            logger.info(String.format("✅ Re-tradução concluída: %d/%d segmentos corrigidos", 
                retranslatedCount, segments.size()));
        }
        
        return correctedSegments;
    }
    
    /**
     * Re-traduz um segmento individual específico
     */
    private static String retranslateSingleSegment(String originalText) throws IOException, InterruptedException {
        // Prompt específico para re-traduzir UMA frase de curso de programação
        String prompt = String.format(
            "CRITICAL: Translate this programming course sentence to Brazilian Portuguese for dubbing.\n" +
            "RULES:\n" +
            "- DO NOT translate programming terms: JavaScript, TypeScript, React, Angular, Next.js, Java, Spring Boot, API, component, props, state, function, class, method, variable, array, object, promise, async, await, import, export, npm, yarn, webpack, etc.\n" +
            "- Use natural conversational Brazilian Portuguese for explanations\n" +
            "- Keep code examples and technical terms in English\n" +
            "- Respond ONLY with the Brazilian Portuguese translation (no numbering)\n\n" +
            "Sentence: %s\n\n" +
            "Translation:",
            originalText
        );
        
        // USAR O MESMO MÉTODO CONFIGURADO NA GUI
        if (currentMethod == TranslationMethod.GOOGLE_GEMMA_3) {
            // Re-traduzir com Google Gemma 3
            try {
                logger.info("🤖 Re-traduzindo segmento com Google Gemma 3...");
                String response = callGoogleGemmaTranslation(prompt);
                String cleanedResponse = response.trim();
                
                // Limpar resposta (remover possíveis numerações, prefixos e comentários da IA)
                cleanedResponse = cleanedResponse.replaceAll("^\\[?\\d+\\]?\\.?\\s*", "");
                cleanedResponse = cleanedResponse.replaceAll("^(Tradução:|Resposta:)\\s*", "");
                cleanedResponse = cleanedResponse.replaceAll("\\[(\\d+)\\|[^\\]]+\\]", "[$1]").trim(); // [1|2.5s-4.8s] → [1]
                cleanedResponse = cleanedResponse.replaceAll("\\[(?!\\d+\\])[^\\]]*\\]", "").trim(); // Remove outros [texto] mas mantém [número]
                cleanedResponse = cleanedResponse.replaceAll("\\(.*?\\)", "").trim(); // Remove (qualquer coisa)
                cleanedResponse = cleanedResponse.replaceAll("\\*([^*]+)\\*", "$1").trim(); // Remove *palavra* → palavra
                cleanedResponse = cleanedResponse.replaceAll("\\s*–\\s*[^.]*\\.", "").trim(); // Remove comentários com –
                
                if (!cleanedResponse.isEmpty() && !isTextUntranslated(originalText, cleanedResponse)) {
                    logger.info("✅ Re-tradução Google Gemma 3 bem-sucedida");
                    return cleanedResponse;
                }
                
            } catch (Exception e) {
                logger.warning(String.format("⚠️ Falha re-traduzindo com Google Gemma 3: %s", e.getMessage()));
            }
        }
        
        return originalText; // Se tudo falhar, retorna texto original
    }
    
    // =========== MÉTODOS DE SAÍDA ===========
    
    /**
     * Salva como arquivo VTT
     */
    private static void saveAsVTT(List<TranslatedSegment> segments, String outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.println("WEBVTT");
            writer.println();
            
            for (TranslatedSegment seg : segments) {
                writer.println(seg.toVTTEntry());
            }
        }
    }
    
    // =========== MÉTODOS DE COMPATIBILIDADE ===========
    
    /**
     * Traduz arquivo VTT diretamente (legacy)
     */
    public static void translateVTTFile(String inputVttPath, String outputVttPath) throws IOException, InterruptedException {
        List<TranscriptionSegment> segments = loadFromVTT(inputVttPath);
        List<TranslatedSegment> translated = translateSegments(segments);
        saveAsVTT(translated, outputVttPath);
    }
    
    public static void translateFileWithModel(String inputPath, String outputPath, String method, String model) throws IOException, InterruptedException {
        translateFile(inputPath, outputPath, method);
    }
    
    public static void printAdvancedStats() {
        logger.info("📊 Translation stats: Sistema limpo ativo");
    }
    
    public static void shutdown() {
        logger.info("✅ Translation shutdown");
    }
    
    /**
     * Teste público da simplificação do Gemini (sem asteriscos)
     */
    public static String testGeminiSimplification(String text, double seconds) throws IOException, InterruptedException {
        return simplifyTextWithGemini(text, seconds);
    }
    
    /**
     * Método de teste para validação de timestamp
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: java Translation <input.tsv> <output.vtt>");
            System.out.println("Teste: java Translation output/vocals.tsv output/test_translated.vtt");
            return;
        }
        
        System.out.println("🧪 TESTANDO VALIDAÇÃO DE TIMESTAMP COM GEMINI");
        System.out.println("📁 Input:  " + args[0]);
        System.out.println("📁 Output: " + args[1]);
        
        translateFile(args[0], args[1]);
        
        System.out.println("✅ Teste concluído!");
    }
    
    // =========== VALIDAÇÃO DE TIMESTAMP COM GEMINI ===========
    
    /**
     * Valida timestamps e simplifica frases muito longas usando Gemini API
     */
    private static List<TranslatedSegment> validateAndSimplifyTimestamps(List<TranslatedSegment> segments) throws IOException, InterruptedException {
        List<TranslatedSegment> validatedSegments = new ArrayList<>();
        int simplifiedCount = 0;
        
        logger.info(String.format("🔍 Analisando %d segmentos para validação de timestamp...", segments.size()));
        
        for (int i = 0; i < segments.size(); i++) {
            TranslatedSegment segment = segments.get(i);
            
            String translatedText = segment.translatedText();
            
            // VERIFICAR SE A TRADUÇÃO FALHOU (texto permanece em inglês)
            if (isEnglishText(translatedText)) {
                logger.warning(String.format("🚨 Segmento [%d] não foi traduzido! Tentando traduzir novamente...", i + 1));
                logger.info(String.format("📝 Texto em inglês: '%s'", translatedText));
                
                try {
                    // Tentar traduzir novamente com Gemma 3
                    String retranslatedText = retranslateSegmentWithGoogleGemma3(segment.originalText());
                    if (!retranslatedText.isEmpty() && !isEnglishText(retranslatedText)) {
                        logger.info(String.format("✅ Re-tradução bem-sucedida [%d]: '%s'", i + 1, 
                            retranslatedText.length() > 50 ? retranslatedText.substring(0, 50) + "..." : retranslatedText));
                        translatedText = retranslatedText;
                        // Atualizar o segmento
                        segment = new TranslatedSegment(segment.startMs(), segment.endMs(), segment.originalText(), translatedText);
                    } else {
                        logger.severe(String.format("❌ Re-tradução falhou [%d], mantendo texto original em inglês", i + 1));
                    }
                } catch (Exception e) {
                    logger.severe(String.format("💀 Erro na re-tradução [%d]: %s", i + 1, e.getMessage()));
                }
            }
            
            // Calcular duração disponível vs necessária
            double availableTimeSeconds = segment.endMs() - segment.startMs();
            availableTimeSeconds = availableTimeSeconds / 1000.0;
            int wordCount = countWords(translatedText);
            double requiredTimeSeconds = wordCount / PORTUGUESE_SPEAKING_SPEED;
            
            // Verificar se excede significativamente a tolerância
            double ratio = requiredTimeSeconds / availableTimeSeconds;
            
            logger.info(String.format("🔍 Segmento [%d]: %d palavras, %.2fs necessário vs %.2fs disponível (ratio: %.2f)", 
                i + 1, wordCount, requiredTimeSeconds, availableTimeSeconds, ratio));
            
            // AJUSTAR TEXTO AO TIMESTAMP - SIMPLIFICAR OU ESTENDER
            // Threshold mais rigoroso para evitar fala corrida/robótica (ajustado para TTS realista)
            if (ratio > 0.80) {
                // TEXTO MUITO LONGO - SIMPLIFICAR
                logger.info(String.format("📏 Segmento [%d] muito longo: %.1fs necessário vs %.1fs disponível (%.1fx)",
                    i + 1, requiredTimeSeconds, availableTimeSeconds, ratio));
                logger.info(String.format("🤖 Simplificando com Gemini: '%s'", 
                    translatedText.length() > 50 ? translatedText.substring(0, 50) + "..." : translatedText));
                
                try {
                    // Usar Google Gemma 3 configurado para simplificação
                    String adjustedText;
                    if (currentMethod == TranslationMethod.GOOGLE_GEMMA_3 && googleApiKey != null) {
                        adjustedText = simplifyTextWithGoogleGemma3(translatedText, availableTimeSeconds);
                    } else {
                        adjustedText = simplifyTextWithGemini(translatedText, availableTimeSeconds);
                    }
                    
                    if (!adjustedText.isEmpty() && !adjustedText.equals(translatedText)) {
                        // Limpar texto simplificado (remover timestamps e artefatos)
                        String cleanedText = cleanTranslatedText(adjustedText);
                        
                        int originalWords = countWords(translatedText);
                        int adjustedWords = countWords(cleanedText);
                        
                        // Verificar se a simplificação foi realmente efetiva (pelo menos 15% redução)
                        double reduction = ((double)(originalWords - adjustedWords) / originalWords) * 100;
                        if (adjustedWords < originalWords && reduction >= 15.0) {
                            validatedSegments.add(new TranslatedSegment(
                                segment.startMs(), segment.endMs(), 
                                segment.originalText(), cleanedText
                            ));
                            
                            logger.info(String.format("✅ Simplificado [%d] (-%.1f%% palavras): '%s'", i + 1, reduction,
                                cleanedText.length() > 50 ? cleanedText.substring(0, 50) + "..." : cleanedText));
                            simplifiedCount++;
                        } else {
                            logger.warning(String.format("⚠️ Simplificação insuficiente [%d] (-%.1f%%), mantendo original", i + 1, reduction));
                            validatedSegments.add(segment);
                        }
                    } else {
                        validatedSegments.add(segment);
                    }
                    
                } catch (Exception e) {
                    logger.warning(String.format("❌ Erro simplificando [%d]: %s", i + 1, e.getMessage()));
                    validatedSegments.add(segment);
                }
                
            } else if (ratio < 0.2) {
                // TEXTO MUITO CURTO - ESTENDER PARA EVITAR VOZ LENTA NO PIPER
                logger.info(String.format("📏 Segmento [%d] muito curto: %.1fs necessário vs %.1fs disponível (%.1fx)",
                    i + 1, requiredTimeSeconds, availableTimeSeconds, ratio));
                logger.info(String.format("🔍 Estendendo com Gemini: '%s'", 
                    translatedText.length() > 50 ? translatedText.substring(0, 50) + "..." : translatedText));
                
                try {
                    // Usar Google Gemma 3 configurado para extensão
                    String extendedText;
                    if (currentMethod == TranslationMethod.GOOGLE_GEMMA_3 && googleApiKey != null) {
                        extendedText = extendTextWithGoogleGemma3(translatedText, availableTimeSeconds);
                    } else {
                        extendedText = extendTextWithGemini(translatedText, availableTimeSeconds);
                    }
                    
                    if (!extendedText.isEmpty() && !extendedText.equals(translatedText)) {
                        int originalWords = countWords(translatedText);
                        int extendedWords = countWords(extendedText);
                        
                        if (extendedWords > originalWords) {
                            validatedSegments.add(new TranslatedSegment(
                                segment.startMs(), segment.endMs(), 
                                segment.originalText(), extendedText
                            ));
                            
                            double expansion = ((double)(extendedWords - originalWords) / originalWords) * 100;
                            logger.info(String.format("✅ Estendido [%d] (+%.1f%% palavras): '%s'", i + 1, expansion,
                                extendedText.length() > 50 ? extendedText.substring(0, 50) + "..." : extendedText));
                            simplifiedCount++;
                        } else {
                            validatedSegments.add(segment);
                        }
                    } else {
                        validatedSegments.add(segment);
                    }
                    
                } catch (Exception e) {
                    logger.warning(String.format("❌ Erro estendendo [%d]: %s", i + 1, e.getMessage()));
                    validatedSegments.add(segment);
                }
                
            } else {
                // TIMESTAMP ADEQUADO (0.2x a 1.1x) - VOZ NATURAL NO PIPER
                validatedSegments.add(segment);
            }
        }
        
        if (simplifiedCount > 0) {
            logger.info(String.format("✅ Validação de timestamp concluída: %d/%d segmentos ajustados (simplificados/estendidos)", 
                simplifiedCount, segments.size()));
        } else {
            logger.info("✅ Todos os timestamps estão adequados, nenhum ajuste necessário");
        }
        
        return validatedSegments;
    }
    
    /**
     * Conta palavras em um texto (aproximado)
     */
    private static int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        
        // Remove pontuação e conta palavras separadas por espaços
        String cleanText = text.trim().replaceAll("[^a-zA-ZáéíóúâêîôûãõçÁÉÍÓÚÂÊÎÔÛÃÕÇ\\s]", "");
        String[] words = cleanText.split("\\s+");
        
        return words.length;
    }
    
    /**
     * Simplifica texto usando Google Gemma 3 API configurado
     */
    private static String simplifyTextWithGoogleGemma3(String text, double maxTimeSeconds) throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        
        String prompt = String.format(
            "CRITICAL: Simplify this Portuguese text to fit EXACTLY %.1f seconds (MAX %d words). " +
            "TIMING IS CRUCIAL - the text is currently TOO LONG and will sound rushed/robotic.\n\n" +
            "MANDATORY RULES:\n" +
            "- Must fit exactly %.1f seconds of natural speech (2.5 words per second max)\n" +
            "- Remove unnecessary words, use shorter synonyms\n" +
            "- Keep ONLY essential meaning, cut decorative language\n" +
            "- DO NOT translate: JavaScript, TypeScript, React, Angular, Next.js, Java, Spring Boot, API\n" +
            "- Result MUST be natural pace for TTS voice synthesis\n" +
            "- NO explanations, just the simplified text\n\n" +
            "Original (TOO LONG): %s\n\n" +
            "Simplified version (EXACTLY %d words or less):",
            maxTimeSeconds, targetWords, maxTimeSeconds, text, targetWords
        );
        
        try {
            String response = callGoogleGemmaTranslation(prompt);
            return response.trim();
        } catch (Exception e) {
            logger.warning("❌ Erro simplificando com Google Gemma 3: " + e.getMessage());
            return text; // Retornar texto original em caso de erro
        }
    }

    /**
     * Simplifica texto usando Google Gemini API (fallback)
     */
    private static String simplifyTextWithGemini(String text, double maxTimeSeconds) throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8); // 80% da capacidade para margem
        
        String prompt = String.format(
            "CRITICAL: Simplify this Portuguese text to fit EXACTLY %.1f seconds (MAX %d words). " +
            "TIMING IS CRUCIAL - the text is currently TOO LONG and will sound rushed/robotic.\n\n" +
            "MANDATORY RULES:\n" +
            "- Must fit exactly %.1f seconds of natural speech (2.5 words per second max)\n" +
            "- Remove unnecessary words, use shorter synonyms\n" +
            "- Keep ONLY essential meaning, cut decorative language\n" +
            "- DO NOT translate: JavaScript, TypeScript, React, Angular, Next.js, Java, Spring Boot, API, component, props, state, function, class, method, variable, array, object, promise, async, await, import, export, npm, yarn, webpack\n" +
            "- Result MUST be natural pace for TTS voice synthesis\n" +
            "- NO explanations, just the simplified text\n\n" +
            "Original (TOO LONG): %s\n\n" +
            "Simplified version (EXACTLY %d words or less):",
            maxTimeSeconds, targetWords, maxTimeSeconds, text, targetWords
        );
        
        String payload = buildGeminiPayload(prompt);
        
        ProcessBuilder pb = new ProcessBuilder(
            "curl", "-s", "-X", "POST", GEMINI_API_URL + "?key=" + GEMINI_API_KEY,
            "-H", "Content-Type: application/json",
            "-d", payload,
            "--max-time", "15"
        );
        
        Process process = pb.start();
        
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout na API Gemini");
        }
        
        if (process.exitValue() != 0) {
            throw new IOException("Erro na API Gemini: " + process.exitValue());
        }
        
        return parseGeminiResponse(response.toString());
    }
    
    /**
     * Estende texto curto usando Google Gemma 3 API configurado
     */
    private static String extendTextWithGoogleGemma3(String text, double maxTimeSeconds) throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.9);
        
        String prompt = String.format(
            "Extend this short Portuguese text to naturally fill %.1f seconds (target %d words). " +
            "CRITICAL RULES: " +
            "- Keep original meaning and technical context " +
            "- DO NOT translate: JavaScript, TypeScript, React, Angular, Next.js, Java, Spring Boot, API\n" +
            "- Add natural explanatory phrases for educational dubbing " +
            "- Make it sound conversational and natural for TTS " +
            "- NO asterisks or markdown. Result must be natural Brazilian Portuguese\n\n" +
            "Short text: %s\n\n" +
            "Extended version:",
            maxTimeSeconds, targetWords, text
        );
        
        try {
            String response = callGoogleGemmaTranslation(prompt);
            return response.trim();
        } catch (Exception e) {
            logger.warning("❌ Erro estendendo com Google Gemma 3: " + e.getMessage());
            return text;
        }
    }

    /**
     * Estende texto curto para preencher timestamp com fala natural (fallback)
     */
    private static String extendTextWithGemini(String text, double maxTimeSeconds) throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.9); // 90% da capacidade
        
        String prompt = String.format(
            "Extend this short Portuguese programming course text to naturally fill %.1f seconds (target %d words). " +
            "CRITICAL RULES: " +
            "- Keep original meaning and technical context " +
            "- DO NOT translate programming terms: JavaScript, TypeScript, React, Angular, Next.js, Java, Spring Boot, API, component, props, state, function, class, method, variable, array, object, promise, async, await, import, export, npm, yarn, webpack, etc. " +
            "- Add natural explanatory phrases, examples, or clarifications that instructors use " +
            "- Make it sound conversational and educational for dubbing " +
            "- NO asterisks or markdown. Result must be natural Brazilian Portuguese " +
            "- Keep code examples and technical terms in English\n\n" +
            "Short text: %s\n\n" +
            "Extended version:",
            maxTimeSeconds, targetWords, text
        );
        
        String payload = buildGeminiPayload(prompt);
        
        ProcessBuilder pb = new ProcessBuilder(
            "curl", "-s", "-X", "POST", GEMINI_API_URL + "?key=" + GEMINI_API_KEY,
            "-H", "Content-Type: application/json",
            "-d", payload,
            "--max-time", "15"
        );
        
        Process process = pb.start();
        
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout na API Gemini");
        }
        
        if (process.exitValue() != 0) {
            throw new IOException("Erro na API Gemini: " + process.exitValue());
        }
        
        return parseGeminiResponse(response.toString());
    }
    
    /**
     * Constrói payload JSON para Gemini API
     */
    private static String buildGeminiPayload(String prompt) {
        String escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        
        return String.format(
            "{\"contents\":[{\"parts\":[{\"text\":\"%s\"}]}],\"generationConfig\":{\"temperature\":0.3,\"maxOutputTokens\":200}}",
            escapedPrompt
        );
    }
    
    /**
     * Parseia resposta da API Gemini
     */
    private static String parseGeminiResponse(String response) {
        try {
            // Buscar pelo padrão: "text": "conteúdo"
            int textStart = response.indexOf("\"text\":");
            if (textStart == -1) {
                logger.warning("Resposta Gemini sem campo 'text': " + response.substring(0, Math.min(100, response.length())));
                return "";
            }
            
            int contentStart = response.indexOf("\"", textStart + 7) + 1;
            int contentEnd = response.indexOf("\"", contentStart);
            
            // Procurar pelo final correto considerando escapes
            while (contentEnd != -1 && response.charAt(contentEnd - 1) == '\\') {
                contentEnd = response.indexOf("\"", contentEnd + 1);
            }
            
            if (contentStart > 0 && contentEnd > contentStart) {
                String content = response.substring(contentStart, contentEnd);
                
                // Decodificar escapes JSON
                content = content
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\u003c", "<")
                    .replace("\\u003e", ">");
                
                // FALLBACK: Remover asteriscos, timestamps e comentários explicativos da IA
                content = content.replaceAll("\\*\\*", "").trim();
                content = content.replaceAll("\\[(\\d+)\\|[^\\]]+\\]", "[$1]").trim(); // [1|2.5s-4.8s] → [1]
                content = content.replaceAll("\\[(?!\\d+\\])[^\\]]*\\]", "").trim(); // Remove outros [texto] mas mantém [número]
                content = content.replaceAll("\\(.*?\\)", "").trim(); // Remove (qualquer coisa)
                content = content.replaceAll("\\*([^*]+)\\*", "$1").trim(); // Remove *palavra* → palavra
                content = content.replaceAll("\\s*–\\s*[^.]*\\.", "").trim(); // Remove comentários com –
                
                return content.trim();
            }
            
        } catch (Exception e) {
            logger.warning("Erro parseando resposta Gemini: " + e.getMessage());
        }
        
        return "";
    }
    
    /**
     * Limpa texto traduzido removendo timestamps e artefatos
     */
    private static String cleanTranslatedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        String cleaned = text.trim();
        
        // Remover timestamps com formato [número|timing]
        cleaned = cleaned.replaceAll("\\[(\\d+)\\|[^\\]]+\\]", "").trim();
        
        // Remover apenas números entre colchetes [1] [2] etc
        cleaned = cleaned.replaceAll("\\[\\d+\\]", "").trim();
        
        // Remover asteriscos **texto**
        cleaned = cleaned.replaceAll("\\*\\*", "").trim();
        
        // Remover aspas no início/fim
        cleaned = cleaned.replaceAll("^[\"']|[\"']$", "").trim();
        
        return cleaned;
    }
    
    /**
     * Detecta se o texto está em inglês (não foi traduzido)
     */
    private static boolean isEnglishText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        // Palavras comuns em inglês que raramente aparecem em português
        String[] englishIndicators = {
            " the ", " and ", " for ", " with ", " this ", " that ", " have ", " been ",
            " will ", " would ", " could ", " should ", " their ", " there ", " where ",
            " what ", " when ", " which ", " while ", " about ", " after ", " before ",
            " during ", " between ", " through ", " without ", " within ", " because ",
            " however ", " therefore ", " although ", " unless ", " since ", " until "
        };
        
        String lowerText = " " + text.toLowerCase() + " ";
        
        int englishMatches = 0;
        for (String indicator : englishIndicators) {
            if (lowerText.contains(indicator)) {
                englishMatches++;
            }
        }
        
        // Se encontrar 2 ou mais indicadores, provavelmente é inglês
        return englishMatches >= 2;
    }
    
    /**
     * Re-traduz um segmento individual usando Google Gemma 3
     */
    private static String retranslateSegmentWithGoogleGemma3(String englishText) throws IOException, InterruptedException {
        String prompt = String.format(
            "Translate this English text to natural Brazilian Portuguese for video dubbing:\n\n" +
            "\"%s\"\n\n" +
            "Rules:\n" +
            "- Natural Brazilian Portuguese\n" +
            "- Keep technical terms in English if commonly used\n" +
            "- Maintain the same meaning and tone\n" +
            "- No explanations, just the translation\n\n" +
            "Portuguese translation:",
            englishText
        );
        
        try {
            String response = callGoogleGemmaTranslation(prompt);
            
            // Limpar resposta
            response = response.trim();
            response = response.replaceAll("^[\"']|[\"']$", ""); // Remove aspas no início/fim
            response = response.replaceAll("\\*\\*", "").trim(); // Remove asteriscos
            
            return response;
            
        } catch (Exception e) {
            logger.warning("❌ Erro na re-tradução com Google Gemma 3: " + e.getMessage());
            return "";
        }
    }
}