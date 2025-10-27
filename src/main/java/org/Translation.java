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
    private static String GOOGLE_AI_API_KEY = null; // Carregada dinamicamente de arquivo criptografado
    private static final String GOOGLE_AI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemma-3-27b-it:generateContent";
    // DeepSeek API (V3.2-Exp - 50% mais barato que V3)
    private static String DEEPSEEK_API_KEY = null; // Carregada dinamicamente de arquivo criptografado
    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-chat"; // DeepSeek-V3.2-Exp (non-thinking mode)
    // Controle de método de tradução

    public enum TranslationMethod {
        GOOGLE_GEMMA_3, // Usa Google Gemma 3 27B API (gratuito, 15k tokens/min)
        GOOGLE_GEMINI, // Usa Google Gemini 2.0 Flash API (gratuito, limites)
        DEEPSEEK_V3 // Usa DeepSeek-V3.2-Exp API (MUITO barato: ~$0.001/vídeo)
    }

    private static TranslationMethod currentMethod = TranslationMethod.DEEPSEEK_V3;
    private static String googleApiKey = null;
    private static String deepseekApiKey = null;

    // =========== MÉTODOS PÚBLICOS DE CONFIGURAÇÃO ===========
    /**
     * Define o método de tradução a ser usado
     */
    public static void setTranslationMethod(TranslationMethod method) {
        currentMethod = method;
        logger.info(String.format("🔧 Método de tradução alterado para: %s", method));
    }

    /**
     * Define a API key do Google AI Studio (usada por Gemma 3 e Gemini)
     */
    public static void setGoogleApiKey(String apiKey) {
        googleApiKey = apiKey;
        GOOGLE_AI_API_KEY = apiKey;
        GEMINI_API_KEY = apiKey;
        logger.info("🔑 API Key do Google AI configurada");
    }

    /**
     * Define a API key do DeepSeek
     */
    public static void setDeepSeekApiKey(String apiKey) {
        deepseekApiKey = apiKey;
        DEEPSEEK_API_KEY = apiKey;
        logger.info("🔑 API Key do DeepSeek configurada");
    }

    /**
     * Retorna o método atual de tradução
     */
    public static TranslationMethod getCurrentMethod() {
        return currentMethod;
    }

    // Configurações anti-alucinação
    private static final int MAX_CHARS_PER_BATCH = 300; // Textos menores = menos alucinação
    private static final int MAX_RETRIES = 2; // Máximo 2 tentativas
    private static final long TIMEOUT_SECONDS = 30; // Timeout curto
    private static final double TEMPERATURE = 0.1; // Baixa criatividade

    // Configurações para validação de timestamp
    private static String GEMINI_API_KEY = null; // Carregada dinamicamente de arquivo criptografado
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final double PORTUGUESE_SPEAKING_SPEED = 3.7; // palavras por segundo (ritmo natural para dublagem
                                                                 // instrucional em PT-BR)
    private static final double TIMESTAMP_TOLERANCE = 1.2; // tolerância: aceitar até 1.2x o tempo necessário
    private static final double ENGLISH_WORD_THRESHOLD = 0.30; // Afrouxado de 0.4 pra evitar false-positives em tech
                                                               // terms

    // =========== CLASSES AUXILIARES ===========

    /**
     * Representa um segmento com timestamp preciso
     */
    public record TranscriptionSegment(
            long startMs, // Timestamp início em milissegundos
            long endMs, // Timestamp fim em milissegundos
            String text // Texto original
    ) {
        public TranscriptionSegment {
            if (startMs < 0)
                throw new IllegalArgumentException("startMs deve ser >= 0");
            if (endMs <= startMs)
                throw new IllegalArgumentException("endMs deve ser > startMs");
            if (text == null)
                text = "";
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
            long startMs, // EXATO do original
            long endMs, // EXATO do original
            String originalText, // Texto original
            String translatedText // Texto traduzido
    ) {
        public TranslatedSegment {
            if (startMs < 0)
                throw new IllegalArgumentException("startMs deve ser >= 0");
            if (endMs <= startMs)
                throw new IllegalArgumentException("endMs deve ser > startMs");
            if (originalText == null)
                originalText = "";
            if (translatedText == null)
                translatedText = originalText;
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
    public static void translateFile(String inputTsvPath, String outputVttPath)
            throws IOException, InterruptedException {
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
            List<TranslatedSegment> newTranslated = translateSegmentsWithIncrementalSave(remaining, tempOutputPath,
                    allTranslated);
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
    public static void translateFile(String inputPath, String outputPath, String method)
            throws IOException, InterruptedException {
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

                if (line.isEmpty())
                    continue;

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
                                textBuilder.toString().trim()));
                        textBuilder.setLength(0);
                        currentSegment = null;
                    }
                } else if (line.contains("-->")) {
                    currentSegment = parseVTTTimestamp(line);
                } else if (currentSegment != null && !line.startsWith("WEBVTT") && !line.matches("^\\d+$")) {
                    if (textBuilder.length() > 0)
                        textBuilder.append(" ");
                    textBuilder.append(line);
                }
            }

            // Último segmento
            if (currentSegment != null && textBuilder.length() > 0) {
                segments.add(new TranscriptionSegment(
                        currentSegment.startMs(),
                        currentSegment.endMs(),
                        textBuilder.toString().trim()));
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
     * Traduz segmentos em chunks com salvamento incremental e recuperação
     * automática
     */
    private static List<TranslatedSegment> translateSegments(List<TranscriptionSegment> segments)
            throws IOException, InterruptedException {
        List<TranslatedSegment> allTranslated = new ArrayList<>();

        final int CHUNK_SIZE = 20; // Chunks maiores para melhor eficiência
        final int SEGMENTS_PER_BATCH = 5; // Batches dentro de cada chunk

        // Processar em chunks maiores com salvamento incremental
        for (int chunkStart = 0; chunkStart < segments.size(); chunkStart += CHUNK_SIZE) {
            int chunkEnd = Math.min(chunkStart + CHUNK_SIZE, segments.size());
            List<TranscriptionSegment> chunk = segments.subList(chunkStart, chunkEnd);

            logger.info(
                    String.format("🔄 Processando CHUNK %d-%d (%d segmentos)", chunkStart + 1, chunkEnd, chunk.size()));

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

            logger.info(
                    String.format("✅ CHUNK concluído: %d/%d segmentos totais", allTranslated.size(), segments.size()));
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

            logger.info(
                    String.format("🔄 Processando CHUNK %d-%d (%d segmentos)", chunkStart + 1, chunkEnd, chunk.size()));

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
    private static void saveIncrementalProgress(List<TranslatedSegment> translated, String tempPath)
            throws IOException {
        try (PrintWriter writer = new PrintWriter(tempPath, StandardCharsets.UTF_8)) {
            writer.println("WEBVTT");
            writer.println();

            for (int i = 0; i < translated.size(); i++) {
                TranslatedSegment segment = translated.get(i);

                writer.println(i + 1);
                writer.printf(Locale.US, "%02d:%02d:%02d.%03d --> %02d:%02d:%02d.%03d%n",
                        (int) (segment.startMs() / 3600000),
                        (int) (segment.startMs() % 3600000) / 60000,
                        (int) (segment.startMs() % 60000) / 1000,
                        (int) (segment.startMs() % 1000),
                        (int) (segment.endMs() / 3600000),
                        (int) (segment.endMs() % 3600000) / 60000,
                        (int) (segment.endMs() % 60000) / 1000,
                        (int) (segment.endMs() % 1000));
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
                                textBuilder.toString().trim()));
                        textBuilder.setLength(0);
                        currentSegment = null;
                    }
                } else if (line.contains("-->")) {
                    // Parse timestamp line - usar método existente mas converter tipo
                    TranscriptionSegment temp = parseVTTTimestamp(line);
                    currentSegment = temp; // Reusar para startMs/endMs
                } else if (currentSegment != null && !line.matches("^\\d+$") && !line.equals("WEBVTT")) {
                    if (textBuilder.length() > 0)
                        textBuilder.append(" ");
                    textBuilder.append(line);
                }
            }

            // Add last segment if exists
            if (currentSegment != null && textBuilder.length() > 0) {
                existing.add(new TranslatedSegment(
                        currentSegment.startMs(),
                        currentSegment.endMs(),
                        "", // originalText não disponível na recuperação
                        textBuilder.toString().trim()));
            }

        } catch (IOException e) {
            logger.warning("⚠️ Erro carregando tradução parcial: " + e.getMessage());
        }

        return existing;
    }

    /**
     * Traduz um chunk com recuperação automática em caso de falha
     */
    private static List<TranslatedSegment> translateChunkWithRecovery(List<TranscriptionSegment> chunk,
            int chunkStartIndex) throws IOException, InterruptedException {
        List<TranslatedSegment> chunkResult = new ArrayList<>();

        // Processar chunk em batches pequenos
        for (int i = 0; i < chunk.size(); i += 5) {
            int batchEnd = Math.min(i + 5, chunk.size());
            List<TranscriptionSegment> batch = chunk.subList(i, batchEnd);
            int absoluteBatchIndex = chunkStartIndex + i;

            try {
                List<TranslatedSegment> batchTranslated = translateBatch(batch);
                chunkResult.addAll(batchTranslated);

                logger.info(String.format("📝 Batch %d-%d traduzido com sucesso", absoluteBatchIndex + 1,
                        absoluteBatchIndex + batch.size()));

            } catch (Exception e) {
                logger.warning(String.format("❌ FALHA no batch %d-%d: %s", absoluteBatchIndex + 1,
                        absoluteBatchIndex + batch.size(), e.getMessage()));

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
    private static List<TranslatedSegment> translateBatchWithReducedModel(List<TranscriptionSegment> batch)
            throws IOException, InterruptedException {
        // Usar método configurado para recuperação
        if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
            return translateBatchWithGoogleGemini(batch, buildBatchText(batch));
        } else {
            return translateBatchWithGoogleGemma(batch, buildBatchText(batch));
        }
    }

    /**
     * Tradução com modelo de fallback (usa Google Gemma 3)
     */
    private static List<TranslatedSegment> translateBatchWithFallbackModel(List<TranscriptionSegment> batch)
            throws IOException, InterruptedException {
        // FALLBACK REAL: trocar de modelo para aumentar chance de sucesso
        String batchText = buildBatchText(batch);
        if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
            // Se estava usando Gemini, tentar com Gemma
            logger.info("🔄 Tentando modelo alternativo: Google Gemma 3");
            return translateBatchWithGoogleGemma(batch, batchText);
        } else {
            // Se estava usando Gemma, tentar com Gemini
            logger.info("🔄 Tentando modelo alternativo: Google Gemini");
            return translateBatchWithGoogleGemini(batch, batchText);
        }
    }

    /**
     * Cria tradução de fallback quando tudo falha (copia texto original)
     */
    private static List<TranslatedSegment> createFallbackTranslation(List<TranscriptionSegment> batch) {
        logger.warning("⚠️ Usando tradução fallback (texto original mantido)");
        return batch.stream()
                .map(seg -> new TranslatedSegment(seg.startMs(), seg.endMs(), seg.text(),
                        "[ERRO TRADUÇÃO] " + seg.text()))
                .toList();
    }

    /**
     * Traduz um lote pequeno de segmentos com retry automático em caso de quota
     */
    private static List<TranslatedSegment> translateBatch(List<TranscriptionSegment> batch)
            throws IOException, InterruptedException {
        String batchText = buildBatchText(batch);
        int maxRetries = 3;
        // Tentar até 3 vezes com o modelo configurado antes de alternar
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                    return translateBatchWithDeepSeek(batch, batchText);
                } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                    return translateBatchWithGoogleGemini(batch, batchText);
                } else {
                    return translateBatchWithGoogleGemma(batch, batchText);
                }
            } catch (IOException e) {
                // Verificar se é erro de quota
                if (isQuotaExceededError(e.getMessage())) {
                    if (attempt < maxRetries) {
                        // Ainda há tentativas restantes - aguardar e tentar novamente com mesmo modelo
                        long retryTime = extractRetryTimeMs(e.getMessage());
                        String modelName = currentMethod == TranslationMethod.DEEPSEEK_V3 ? "DeepSeek V3.2-Exp"
                                : currentMethod == TranslationMethod.GOOGLE_GEMINI ? "Gemini" : "Gemma 3";
                        logger.warning(String.format(
                                "⚠️ Cota excedida (tentativa %d/%d). Aguardando %.1fs antes de tentar novamente com %s...",
                                attempt, maxRetries, retryTime / 1000.0, modelName));
                        Thread.sleep(retryTime);
                        logger.info(String.format("🔄 Tentando novamente (%d/%d) com %s...", attempt + 1, maxRetries,
                                modelName));
                        continue; // Tenta novamente com mesmo modelo
                    } else {
                        // Esgotou 3 tentativas - alternar permanentemente para modelo alternativo
                        logger.warning(String.format(
                                "⚠️ Cota excedida após %d tentativas. Alternando permanentemente para modelo alternativo...",
                                maxRetries));
                        if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                            // Se DeepSeek falhou, tenta Gemini
                            logger.info("🔄 Alternando permanentemente para Google Gemini (gratuito)...");
                            currentMethod = TranslationMethod.GOOGLE_GEMINI;
                            return translateBatchWithGoogleGemini(batch, batchText);
                        } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                            // Se Gemini falhou, tenta Gemma 3
                            logger.info("🔄 Alternando permanentemente para Google Gemma 3 (gratuito)...");
                            currentMethod = TranslationMethod.GOOGLE_GEMMA_3;
                            return translateBatchWithGoogleGemma(batch, batchText);
                        } else {
                            // Se Gemma falhou, tenta DeepSeek
                            logger.info("🔄 Alternando permanentemente para DeepSeek V3.2-Exp (pago, barato)...");
                            currentMethod = TranslationMethod.DEEPSEEK_V3;
                            return translateBatchWithDeepSeek(batch, batchText);
                        }
                    }
                }
                throw e; // Re-throw se não for erro de quota
            }
        }
        // Não deveria chegar aqui, mas por segurança
        throw new IOException("Falha após " + maxRetries + " tentativas");
    }

    /**
     * Traduz usando Google Gemma 3 27B API
     */
    private static List<TranslatedSegment> translateBatchWithGoogleGemma(List<TranscriptionSegment> batch,
            String batchText) throws IOException, InterruptedException {
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
     * Traduz usando Google Gemini 2.0 Flash API
     */
    private static List<TranslatedSegment> translateBatchWithGoogleGemini(List<TranscriptionSegment> batch,
            String batchText) throws IOException, InterruptedException {
        if (googleApiKey == null || googleApiKey.isEmpty()) {
            throw new IOException("❌ API Key do Google AI não configurada. Configure em setGoogleApiKey()");
        }

        logger.info("🤖 Traduzindo com Google Gemini 2.0 Flash...");

        try {
            String translatedBatch = callGoogleGeminiTranslation(batchText);
            List<TranslatedSegment> result = parseBatchResult(batch, translatedBatch);

            logger.info("✅ Tradução Google Gemini concluída com sucesso");
            return result;

        } catch (Exception e) {
            logger.severe("❌ Falha na tradução Google Gemini: " + e.getMessage());
            throw new IOException("Falha na API Google Gemini: " + e.getMessage(), e);
        }
    }

    /**
     * Traduz usando DeepSeek V3 API
     */
    private static List<TranslatedSegment> translateBatchWithDeepSeek(List<TranscriptionSegment> batch,
            String batchText) throws IOException, InterruptedException {
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.isEmpty()) {
            throw new IOException("❌ API Key do DeepSeek não configurada. Configure em setDeepSeekApiKey()");
        }
        logger.info("🤖 Traduzindo com DeepSeek V3...");
        try {
            String translatedBatch = callDeepSeekTranslation(batchText);
            List<TranslatedSegment> result = parseBatchResult(batch, translatedBatch);
            logger.info("✅ Tradução DeepSeek concluída com sucesso");
            return result;
        } catch (Exception e) {
            logger.severe("❌ Falha na tradução DeepSeek: " + e.getMessage());
            throw new IOException("Falha na API DeepSeek: " + e.getMessage(), e);
        }
    }

    /**
     * Constrói texto do lote com constraints de tempo E contagem de palavras pra
     * ajuste de length
     */
    private static String buildBatchText(List<TranscriptionSegment> batch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            TranscriptionSegment seg = batch.get(i);
            double startSeconds = seg.startMs() / 1000.0;
            double endSeconds = seg.endMs() / 1000.0;
            int wordCount = countWords(seg.text()); // Conta palavras originais pra target
            // Formato expandido: [1|2.5s-4.8s|50 words] Text
            String timestamp = String.format("[%d|%.1fs-%.1fs|%d words]", i + 1, startSeconds, endSeconds, wordCount);
            sb.append(String.format("%s %s\n", timestamp, seg.text()));
        }
        return sb.toString();
    }

    /**
     * Prompt DINÂMICO atualizado: Remove fillers excessivos, ajusta length pra
     * timing perfeito
     */
//    private static String buildCleanPrompt(String text) {
//        return """
//                Translate the following English subtitles to natural Brazilian Portuguese for voice-over dubbing.
//
//                FORMAT: [number|time|wordcount] English -> [number] Portuguese Translation
//
//                **CRITICAL RULES:**
//
//                1.  **STRICT WORD LIMIT - DO NOT EXCEED 1.25X:**
//                    *   Portuguese needs 10-25% more words than English maximum.
//                    *   **FORMULA: If English = N words, Portuguese MUST be ≤ 1.25N words.**
//                    *   Count your output words and verify before submitting.
//                    *   If you exceed 1.25x, your translation is WRONG.
//
//                2.  **ZERO TOLERANCE FOR ADDED CONTENT:**
//                    *   **FORBIDDEN:** Adding concluding statements, explanations, or clarifications NOT present in the original English.
//                    *   **FORBIDDEN:** Adding introductory fillers like "Então, o que acontece é o seguinte:", "Veja bem:", "Um exemplo prático:", "Na prática:".
//                    *   If the English says "It's important to understand", then translate it. But if the English does NOT say it, DO NOT ADD IT.
//                    *   Translate ONLY what exists in the original. Nothing more, nothing less.
//
//                3.  **NO HALLUCINATIONS OR REPETITIONS:**
//                    *   Translate the original meaning with 100% fidelity. Do not invent content.
//                    *   Provide ONLY ONE final translation per segment.
//
//                4.  **DYNAMIC LANGUAGE:**
//                    *   Vary your language: alternate "nós vamos", "a gente vai", "vamos".
//                    *   Omit English fillers like "like", "you know", "uh", "um".
//
//                5.  **CLEAN OUTPUT ONLY:** Output format: [number] Portuguese text. No extra characters, notes, or English text.
//
//                6.  **TECHNICAL TERMS:** Keep common technical terms in English (React, Node, API, state, function, JavaScript, npm, const, let, async, await, CQL, timestamp, etc.).
//
//                7.  **TONE:** Use informal Brazilian Portuguese ("você", "tá", "pra", "pro"). Friendly instructor tone.
//
//                **EXAMPLES:**
//
//                Input: [1|2.5s-4.8s|8 words] So, let's start with React basics.
//                Output: [1] Então, vamos começar com os fundamentos do React.
//                (9 words = 1.12x ✓)
//
//                Input: [2|5.0s-7.2s|10 words] This is like, uh, super important for understanding state.
//                Output: [2] Isso é super importante pra entender state.
//                (8 words = 0.8x ✓)
//
//                Input: [3|11.9s-31.1s|41 words] But we can work with those intervals and also calculate them from timestamps and dates and then we can also just extract specific values from it. So, this was the wrap-up about timestamps and dates in CQL.
//                ❌ BAD (58 words = 1.41x): Mas a gente pode trabalhar com esses intervalos, calculá-los a partir de timestamps e datas, e depois extrair valores específicos deles. Então, esse foi o resumo sobre timestamps e datas no CQL. É importante a gente entender como essas funções operam, porque elas são fundamentais para manipular dados temporais no nosso banco de dados.
//                (REASON: Added entire sentence about "É importante..." that doesn't exist in English!)
//                ✅ GOOD (47 words = 1.15x): Mas a gente pode trabalhar com esses intervalos, calcular eles a partir de timestamps e datas, e depois extrair valores específicos. Então, esse foi o resumo sobre timestamps e datas no CQL.
//
//                Input: [4|10.5s-23.8s|41 words] If we are grouping our data, sometimes we want to limit the number of rows only to those that really matter to us by filtering using aggregation columns.
//                ❌ BAD (78 words = 1.9x): Então, o que acontece é o seguinte: quando a gente agrupa os nossos dados, às vezes a gente só quer focar nas linhas que realmente importam pra gente, filtrando usando colunas de agregação. É uma forma de isolar as informações relevantes.
//                (REASON: Added "Então, o que acontece é o seguinte:" + conclusion sentence not in original!)
//                ✅ GOOD (48 words = 1.17x): Se estamos agrupando nossos dados, às vezes queremos limitar o número de linhas apenas àquelas que realmente importam, filtrando pelas colunas de agregação.
//
//                Input: [5|5.0s-10.5s|15 words] It's important to understand how these functions work with temporal data in our database.
//                ✅ CORRECT (17 words = 1.13x): É importante entender como essas funções trabalham com dados temporais no nosso banco de dados.
//                (NOTE: "É importante" is CORRECT here because English says "It's important"!)
//
//                TEXT TO TRANSLATE:
//                """
//                + text + "\n\n**OUTPUT ONLY THE TRANSLATIONS. VERIFY WORD COUNT ≤ 1.25X ENGLISH.**";
//    }

    private static String buildCleanPrompt(String text) {
        return """
                You are a PRECISE subtitle translator for voice-over dubbing. Your PRIMARY GOAL is FAITHFUL, NATURAL translation with STRICT word count control.

                FORMAT: [number|time|wordcount] English -> [number] Portuguese Translation

                **ABSOLUTE RULES - VIOLATIONS MEAN FAILURE:**

                1.  **MANDATORY WORD COUNT LIMIT (MOST IMPORTANT RULE):**
                    *   **MAXIMUM: Portuguese ≤ 1.25x English word count**
                    *   **TARGET: Portuguese = 1.10x to 1.20x English word count (ideal range)**
                    *   **Count every single word** in your output before submitting
                    *   Example: 20 English words → Portuguese MUST be 22-25 words maximum (≤25)
                    *   Example: 40 English words → Portuguese MUST be 44-50 words maximum (≤50)
                    *   **If you exceed 1.25x, you FAILED the translation**

                2.  **FAITHFUL TRANSLATION - ZERO ADDITIONS:**
                    *   Translate word-for-word meaning, not concepts or ideas
                    *   **FORBIDDEN:** Adding words, phrases, or explanations NOT in English
                    *   **FORBIDDEN:** Introductory fillers ("Então, o que acontece é:", "Veja bem:", "Basicamente:", "Na prática:")
                    *   **FORBIDDEN:** Concluding statements not in original
                    *   **FORBIDDEN:** Expanding abbreviations or adding clarifications
                    *   Translate EXACTLY what is written. Nothing more, nothing less.

                3.  **NATURAL BUT CONCISE:**
                    *   Use natural Brazilian Portuguese grammar and flow
                    *   Use contractions: "tá", "pra", "pro", "né"
                    *   **Remove ONLY stuttering/annoying fillers:** "uh", "um", "like like", "you know you know" (repetitions)
                    *   **Keep conversational fillers when natural:** "like" (tipo), "you know" (sabe), "so" (então), "well" (bom) - use ONCE, not repeated
                    *   Remove hesitations and stutters but keep natural speech flow

                4.  **TECHNICAL TERMS IN ENGLISH:**
                    *   Keep tech terms unchanged: React, TypeScript, Node, API, state, props, function, JavaScript, npm, IDE, UI, CQL, timestamp
                    *   DO NOT translate these to Portuguese

                5.  **CLEAN OUTPUT FORMAT:**
                    *   Output ONLY: [number] Portuguese text
                    *   NO English text, NO notes, NO explanations

                6.  **VARIED LANGUAGE:**
                    *   Alternate pronouns: "você", "a gente", "nós"
                    *   Alternate verbs: "vamos", "a gente vai", "nós vamos"

                **EXAMPLES WITH WORD COUNTS:**

                Input: [1|2.5s-4.8s|8 words] So, let's start with React basics.
                ✅ CORRECT (9 words = 1.12x): [1] Então, vamos começar com o básico do React.
                Analysis: 8 English words × 1.25 = 10 max → 9 words ✓

                Input: [2|5.0s-7.2s|10 words] This is like, uh, super important for understanding state.
                ✅ CORRECT (8 words = 0.80x): [2] Isso é super importante pra entender state.
                Analysis: Removed "like" and "uh" (fillers) → 10 words × 1.25 = 12 max → 8 words ✓

                Input: [3|11.9s-31.1s|41 words] But we can work with those intervals and also calculate them from timestamps and dates and then we can also just extract specific values from it. So, this was the wrap-up about timestamps and dates in CQL.
                ❌ WRONG (58 words = 1.41x): Mas a gente pode trabalhar com esses intervalos, calculá-los a partir de timestamps e datas, e depois extrair valores específicos deles. Então, esse foi o resumo sobre timestamps e datas no CQL. É importante entender como essas funções operam.
                WHY WRONG: 41 × 1.25 = 51 max → 58 words EXCEEDS LIMIT + added sentence not in English!
                ✅ CORRECT (47 words = 1.15x): [3] Mas podemos trabalhar com esses intervalos e calcular eles de timestamps e datas, e depois extrair valores específicos. Então, esse foi o resumo sobre timestamps e datas no CQL.
                Analysis: 41 words × 1.25 = 51 max → 47 words ✓

                Input: [4|10.5s-23.8s|28 words] All right, we're live, we'll do a little round of intros and see what happens. Nate's chewing, so let Scott start.
                ❌ WRONG (44 words = 1.57x): Beleza, estamos ao vivo, vamos fazer uma rodada rápida de apresentações e ver o que acontece. O Nate tá mascando, então deixa o Scott começar.
                WHY WRONG: 28 × 1.25 = 35 max → 44 words EXCEEDS LIMIT by 9 words!
                ✅ CORRECT (33 words = 1.18x): [4] Beleza, estamos ao vivo, vamos fazer uma rodada de apresentações e ver no que dá. Nate tá mascando, então Scott começa.
                Analysis: 28 words × 1.25 = 35 max → 33 words ✓

                Input: [5|5.0s-10.5s|15 words] It's important to understand how these functions work with temporal data in our database.
                ✅ CORRECT (17 words = 1.13x): [5] É importante entender como essas funções trabalham com dados temporais no banco de dados.
                Analysis: 15 words × 1.25 = 18 max → 17 words ✓ ("É importante" is in original!)

                **NOW TRANSLATE THIS TEXT:**
                """
                + text + "\n\n**MANDATORY: Count words in EACH translation. Portuguese ≤ 1.25x English for EVERY segment.**";
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
                "--max-time", String.valueOf(TIMEOUT_SECONDS));

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
     * Chama a API do Google Gemini 2.0 Flash para tradução
     */
    private static String callGoogleGeminiTranslation(String text) throws IOException, InterruptedException {
        String prompt = buildCleanPrompt(text);
        String payload = buildGeminiPayload(prompt);

        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-X", "POST", GEMINI_API_URL + "?key=" + googleApiKey,
                "-H", "Content-Type: application/json",
                "-d", payload,
                "--max-time", String.valueOf(TIMEOUT_SECONDS));

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
            throw new IOException("Timeout na tradução Google Gemini");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro na API Google Gemini: " + process.exitValue());
        }

        return parseGeminiResponse(response.toString());
    }

    /**
     * Chama a API do DeepSeek V3 para tradução
     */
    private static String callDeepSeekTranslation(String text) throws IOException, InterruptedException {
        if (DEEPSEEK_API_KEY == null || DEEPSEEK_API_KEY.isEmpty()) {
            throw new IOException("❌ API Key do DeepSeek não configurada. Configure em setDeepSeekApiKey()");
        }
        String prompt = buildCleanPrompt(text);
        String payload = buildDeepSeekPayload(prompt);
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-X", "POST", DEEPSEEK_API_URL,
                "-H", "Content-Type: application/json",
                "-H", "Authorization: Bearer " + DEEPSEEK_API_KEY,
                "-d", payload,
                "--max-time", String.valueOf(TIMEOUT_SECONDS));
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
            throw new IOException("Timeout na tradução DeepSeek");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Erro na API DeepSeek: " + process.exitValue());
        }
        return parseDeepSeekResponse(response.toString());
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
                TEMPERATURE);
    }

    /**
     * Verifica se o erro é de cota excedida
     */
    private static boolean isQuotaExceededError(String errorMessage) {
        if (errorMessage == null)
            return false;
        String lowerMsg = errorMessage.toLowerCase();
        return lowerMsg.contains("quota") ||
                lowerMsg.contains("rate limit") ||
                lowerMsg.contains("resource_exhausted") ||
                lowerMsg.contains("429") ||
                lowerMsg.contains("too many requests");
    }

    /**
     * Extrai tempo de retry da mensagem de erro do Google (formato: "Please retry
     * in 8.337468297s")
     * Retorna tempo em milissegundos + 1000ms de margem
     */
    private static long extractRetryTimeMs(String errorMessage) {
        if (errorMessage == null)
            return 10000; // Default 10s se não encontrar
        try {
            // Procurar padrão "retry in X.Xs" ou "retry in Xs"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("retry in ([0-9.]+)s",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(errorMessage);
            if (matcher.find()) {
                double seconds = Double.parseDouble(matcher.group(1));
                long milliseconds = (long) (seconds * 1000);
                long withMargin = milliseconds + 1000; // +1s de margem
                logger.info(String.format("⏱️ Tempo de retry extraído: %.2fs + 1s margem = %.2fs total",
                        seconds, withMargin / 1000.0));
                return withMargin;
            }
        } catch (Exception e) {
            logger.warning("⚠️ Erro extraindo tempo de retry: " + e.getMessage());
        }
        return 10000; // Default 10s se parsing falhar
    }

    /**
     * Extrai mensagem de erro da resposta da API
     */
    private static String extractErrorMessage(String response) {
        try {
            // Procurar por "message": "..."
            int msgStart = response.indexOf("\"message\"");
            if (msgStart != -1) {
                int colonPos = response.indexOf(":", msgStart);
                int quoteStart = response.indexOf("\"", colonPos + 1);
                int quoteEnd = response.indexOf("\"", quoteStart + 1);
                if (quoteStart != -1 && quoteEnd != -1) {
                    return response.substring(quoteStart + 1, quoteEnd);
                }
            }
            // Fallback: procurar por "error": {"message": "..."}
            int errorStart = response.indexOf("\"error\"");
            if (errorStart != -1) {
                String errorPart = response.substring(errorStart, Math.min(errorStart + 500, response.length()));
                return errorPart;
            }
        } catch (Exception e) {
            logger.warning("Erro extraindo mensagem de erro: " + e.getMessage());
        }
        return "Unknown API error";
    }

    /**
     * Parseia resposta da API do Google Gemma 3
     */
    private static String parseGoogleGemmaResponse(String response) throws IOException {
        try {
            // Verificar erros da API primeiro
            if (response.contains("\"error\"")) {
                String errorMsg = extractErrorMessage(response);
                logger.severe("❌ Erro da API Google Gemma: " + errorMsg);
                throw new IOException("API Error: " + errorMsg);
            }
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

                logger.info("🔤 Google Gemma resposta extraída: " + content.substring(0, Math.min(50, content.length()))
                        + "...");
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
        } catch (IOException e) {
            // Re-lançar IOException (erros da API como quota exceeded)
            throw e;
        } catch (Exception e) {
            logger.warning("Erro parseando resposta Google Gemma: " + e.getMessage());
        }
        return "";
    }

    /**
     * Parseia resultado do lote traduzido
     */
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
            // Aceita AMBOS os formatos: [1|timing] texto OU [1] texto
            if (line.matches("\\[\\d+[\\|\\]].*")) {
                try {
                    int startBracket = line.indexOf('[');
                    int endBracket = line.indexOf(']');
                    if (startBracket != -1 && endBracket != -1) {
                        String insideBrackets = line.substring(startBracket + 1, endBracket);

                        // Extrai número (pode ter | ou não)
                        String numStr;
                        if (insideBrackets.contains("|")) {
                            numStr = insideBrackets.substring(0, insideBrackets.indexOf('|'));
                        } else {
                            numStr = insideBrackets;
                        }

                        int num = Integer.parseInt(numStr.trim());
                        String text = line.substring(endBracket + 1).trim();

                        // Limpeza reforçada: Remove qualquer inglês residual + fillers excess se
                        // detectado
                        text = text.replaceAll("\\b(so|uh|um|like|you know|i mean)\\b", "").trim(); // Remove fillers
                                                                                                    // ingleses leaks
                        text = text.replaceAll("\\[.*?\\]", "").trim();
                        text = text.replaceAll("\\(.*?\\)", "").trim();
                        text = text.replaceAll("\\*([^*]+)\\*", "$1").trim();
                        text = text.replaceAll("\\s*–\\s*[^.]*\\.", "").trim();
                        // Nova: Remove fillers PT excessivos se >3 por linha (rápido heuristic)
                        if (countFillers(text) > 3) {
                            text = text.replaceAll("(hum|éh|tipo|né|quer dizer)\\s+", " ").trim(); // Espaça e remove
                                                                                                   // excess
                        }

                        if (!text.isEmpty()) {
                            translations.put(num, text);
                        }
                    }
                } catch (Exception e) {
                    logger.fine("Linha ignorada: " + line);
                }
            }
        }

        // NOVA SEÇÃO: Detecção e remoção de duplicatas pós-parsing
        for (int i = 0; i < original.size(); i++) {
            TranscriptionSegment orig = original.get(i);
            String translatedText = translations.getOrDefault(i + 1, orig.text());

            // Detectar e remover duplicação interna (ex: texto repetido 2x)
            translatedText = detectAndRemoveDuplicates(translatedText);

            result.add(new TranslatedSegment(
                    orig.startMs(),
                    orig.endMs(),
                    orig.text(),
                    translatedText));
        }

        return result;
    }

    /**
     * Nova helper: Detecta e remove duplicatas internas em um texto traduzido
     */
    private static String detectAndRemoveDuplicates(String text) {
        if (text == null || text.trim().isEmpty())
            return text;

        String lowerText = text.toLowerCase().trim();
        String[] sentences = lowerText.split("[.!?]+"); // Divide em sentenças

        // Se >1 sentença e a primeira é similar à segunda (>70% similaridade), mantém
        // só a mais curta
        if (sentences.length >= 2) {
            String firstSent = sentences[0].trim();
            String secondSent = sentences[1].trim();
            double sim = calculateStringSimilarity(firstSent, secondSent);
            if (sim > 0.7) { // Threshold para "duplicata"
                int firstLen = countWords(firstSent);
                int secondLen = countWords(secondSent);
                // Mantém a mais curta (evita expansão desnecessária) + adiciona ponto final
                return firstLen < secondLen ? sentences[0].trim() + "." : sentences[1].trim() + ".";
            }
        }

        // Remove fillers excessivos se duplicata causou repetição (ex: "tipo" 2x
        // seguidas)
        if (countFillers(text) > 3) {
            text = text.replaceAll("(hum|éh|tipo|né|quer dizer)\\s+", " ").trim();
        }

        return text;
    }

    /**
     * Checa se um texto tem duplicatas internas (para validação global)
     */
    private static boolean hasInternalDuplicates(String text) {
        String[] parts = text.split("\\. ");
        return parts.length > 1 && calculateStringSimilarity(parts[0], parts[1]) > 0.7;
    }

    /**
     * Nova helper: Conta fillers pra limpeza
     */
    private static int countFillers(String text) {
        return (int) Arrays.stream(text.toLowerCase().split("\\s+"))
                .filter(word -> Arrays.asList("hum", "éh", "tipo", "né", "quer dizer").contains(word))
                .count();
    }

    /**
     * Valida se a tradução é válida (sem alucinação)
     */
    private static boolean isValidTranslation(List<TranslatedSegment> translated) {
        if (translated.isEmpty())
            return false;

        int untranslatedCount = 0;
        int totalSegments = translated.size();

        for (TranslatedSegment seg : translated) {
            String original = seg.originalText().trim();
            String translatedText = seg.translatedText().trim();

            // 1. Verificar se não há alucinação óbvia
            if (translatedText.length() > original.length() * 3) {
                logger.warning("❌ Alucinação detectada (muito longo): "
                        + translatedText.substring(0, Math.min(50, translatedText.length())) + "...");
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

            // Nova: Detectar alucinações extras (análises, listas, etc.)
            if (translatedLower.contains("análise") || translatedLower.contains("contagem") ||
                    translatedLower.contains("déficit") || translatedLower.contains("estratégias") ||
                    translatedLower.contains("sugestões") || translatedLower.contains("meta") ||
                    translatedText.contains("*") || translatedText.matches(".*[-•*].*")) {
                logger.warning(
                        "❌ Alucinação extra detectada (análise/lista): " + translatedText.substring(0, 50) + "...");
                return false; // Trigger re-tradução
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

        // Checagem de duplicatas globais
        int duplicateCount = 0;
        for (TranslatedSegment seg : translated) {
            if (hasInternalDuplicates(seg.translatedText())) {
                duplicateCount++;
            }
        }
        if ((double) duplicateCount / translated.size() > 0.1) { // >10% com duplicatas
            logger.warning(String.format("❌ Duplicatas detectadas em %.1f%% dos segmentos",
                    (duplicateCount / (double) translated.size()) * 100));
            return false; // Trigger re-tradução via retranslateUntranslatedSegments
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
        String[] englishWords = { "the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was", "one",
                "our", "had", "but", "words", "use", "each", "which", "their", "time", "will", "about",
                "if", "up", "out", "many", "then", "them", "would", "like", "into", "him", "has", "more",
                "go", "no", "way", "could", "my", "than", "first", "been", "call", "who", "its", "now",
                "find", "long", "down", "day", "did", "get", "come", "made", "may", "part", "over",
                "new", "sound", "take", "only", "little", "work", "know", "place", "year", "live",
                "me", "back", "give", "most", "very", "after", "thing", "just", "name", "good",
                "sentence", "man", "think", "say", "great", "where", "help", "through", "much",
                "before", "line", "right", "too", "any", "same", "tell", "boy", "follow", "came",
                "want", "show", "also", "around", "form", "three", "small", "set", "put", "end" };

        int englishWordCount = 0;
        String[] words = transLower.split("\\s+");

        for (String word : words) {
            word = word.replaceAll("[^a-z]", "");
            if (word.length() >= 3) { // Palavras com 3+ letras
                for (String englishWord : englishWords) {
                    if (word.equals(englishWord)) {
                        englishWordCount++;
                        break;
                    }
                }
            }
        }

        // Afrouxado: >30% em vez de 40%, pra tolerar tech terms como "node", "react"
        if (words.length > 2 && (double) englishWordCount / words.length > ENGLISH_WORD_THRESHOLD) {
            return true;
        }

        return false;
    }

    /**
     * Calcula similaridade entre duas strings (0.0 = totalmente diferente, 1.0 =
     * idênticas)
     */
    private static double calculateStringSimilarity(String s1, String s2) {
        if (s1.equals(s2))
            return 1.0;

        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0)
            return 1.0;

        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLength;
    }

    /**
     * Calcula distância de Levenshtein entre duas strings
     */
    private static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++)
            dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++)
            dp[0][j] = j;

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
    public static List<TranslatedSegment> retranslateUntranslatedSegments(List<TranslatedSegment> segments)
            throws IOException, InterruptedException {
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
                                seg.startMs(), seg.endMs(), seg.originalText(), retranslatedText));
                        logger.info(String.format("✅ Segmento %d re-traduzido: '%s' → '%s'",
                                i + 1, seg.originalText(), retranslatedText));
                        retranslatedCount++;
                    } else {
                        // Re-tradução falhou, manter original
                        logger.warning(
                                String.format("⚠️ Re-tradução falhou para segmento %d, mantendo original", i + 1));
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
     * Prompt de re-tradução atualizado: Mais foco em length match e fillers mínimos
     */
    private static String retranslateSingleSegmentPrompt(String originalText, int originalWordCount) {
        return String.format(
                """
                        A previous translation of this sentence was incorrect or untranslated. Please provide a new one.

                        **Goal:** A natural and accurate Brazilian Portuguese translation for a technical video dubbing.

                        **Guidelines:**
                        - Focus on clarity and correct meaning.
                        - Use a conversational tone ("você", "tá", "pra", "pro").
                        - Keep technical terms in English (React, Node, API, function, etc.).
                        - Vary language: alternate "nós vamos", "a gente vai", "vamos".
                        - Output only the clean, translated sentence (no extra formatting).

                        English Sentence: "%s"

                        Portuguese Translation:""",
                originalText);
    }

    /**
     * Re-traduz um segmento individual específico com retry e fallback
     */
    private static String retranslateSingleSegment(String originalText) throws IOException, InterruptedException {
        // Prompt conciso e otimizado para re-tradução
        int wordCount = countWords(originalText);
        String prompt = retranslateSingleSegmentPrompt(originalText, wordCount);
        int maxRetries = 3;
        // Tentar até 3 vezes com o modelo atual antes de alternar
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String response;
                if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                    logger.info(String.format("🤖 Re-traduzindo segmento com DeepSeek V3.2-Exp (tentativa %d/%d)...",
                            attempt, maxRetries));
                    response = callDeepSeekTranslation(prompt);
                } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                    logger.info(String.format("🤖 Re-traduzindo segmento com Google Gemini (tentativa %d/%d)...",
                            attempt, maxRetries));
                    response = callGoogleGeminiTranslation(prompt);
                } else {
                    logger.info(String.format("🤖 Re-traduzindo segmento com Google Gemma 3 (tentativa %d/%d)...",
                            attempt, maxRetries));
                    response = callGoogleGemmaTranslation(prompt);
                }
                String cleanedResponse = cleanSimpleResponse(response, originalText);
                if (!cleanedResponse.isEmpty() && !isTextUntranslated(originalText, cleanedResponse)) {
                    logger.info("✅ Re-tradução bem-sucedida");
                    return cleanedResponse;
                }
            } catch (IOException e) {
                // Verificar se é erro de quota
                if (isQuotaExceededError(e.getMessage())) {
                    if (attempt < maxRetries) {
                        // Ainda há tentativas restantes - aguardar e tentar novamente
                        long retryTime = extractRetryTimeMs(e.getMessage());
                        logger.warning(String.format(
                                "⚠️ Cota excedida na re-tradução (tentativa %d/%d). Aguardando %.1fs antes de tentar novamente...",
                                attempt, maxRetries, retryTime / 1000.0));
                        Thread.sleep(retryTime);
                        logger.info(String.format("🔄 Tentando novamente (%d/%d)...", attempt + 1, maxRetries));
                        continue; // Tenta novamente com mesmo modelo
                    } else {
                        // Esgotou 3 tentativas - alternar permanentemente para modelo alternativo
                        logger.warning(String.format(
                                "⚠️ Cota excedida após %d tentativas. Alternando permanentemente para modelo alternativo...",
                                maxRetries));
                        try {
                            if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                                logger.info("🔄 Alternando permanentemente para Google Gemini...");
                                currentMethod = TranslationMethod.GOOGLE_GEMINI;
                                String response = callGoogleGeminiTranslation(prompt);
                                String cleanedResponse = cleanSimpleResponse(response, originalText);
                                if (!cleanedResponse.isEmpty() && !isTextUntranslated(originalText, cleanedResponse)) {
                                    return cleanedResponse;
                                }
                            } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                                logger.info("🔄 Alternando permanentemente para Google Gemma 3...");
                                currentMethod = TranslationMethod.GOOGLE_GEMMA_3;
                                String response = callGoogleGemmaTranslation(prompt);
                                String cleanedResponse = cleanSimpleResponse(response, originalText);
                                if (!cleanedResponse.isEmpty() && !isTextUntranslated(originalText, cleanedResponse)) {
                                    return cleanedResponse;
                                }
                            } else {
                                logger.info("🔄 Alternando permanentemente para DeepSeek V3.2-Exp...");
                                currentMethod = TranslationMethod.DEEPSEEK_V3;
                                String response = callDeepSeekTranslation(prompt);
                                String cleanedResponse = cleanSimpleResponse(response, originalText);
                                if (!cleanedResponse.isEmpty() && !isTextUntranslated(originalText, cleanedResponse)) {
                                    return cleanedResponse;
                                }
                            }
                        } catch (Exception fallbackException) {
                            logger.warning("⚠️ Fallback também falhou: " + fallbackException.getMessage());
                        }
                    }
                } else {
                    // Erro que não é de quota - continua tentando
                    if (attempt < maxRetries) {
                        logger.warning(String.format("⚠️ Falha re-traduzindo (tentativa %d/%d): %s", attempt,
                                maxRetries, e.getMessage()));
                        Thread.sleep(2000); // Aguarda 2 segundos antes de retry
                        continue;
                    } else {
                        logger.warning(String.format("⚠️ Falha re-traduzindo após %d tentativas: %s", maxRetries,
                                e.getMessage()));
                        throw e; // Re-throw após 3 tentativas
                    }
                }
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    logger.warning(String.format("⚠️ Erro re-traduzindo (tentativa %d/%d): %s", attempt, maxRetries,
                            e.getMessage()));
                    Thread.sleep(2000);
                    continue;
                } else {
                    logger.warning(
                            String.format("⚠️ Erro re-traduzindo após %d tentativas: %s", maxRetries, e.getMessage()));
                }
            }
        }
        // Se chegou aqui, todas as tentativas falharam
        return originalText; // Retorna texto original
    }

    /**
     * Limpa resposta simples de tradução
     */
    private static String cleanSimpleResponse(String response, String originalText) {
        String cleanedResponse = response.trim();
        cleanedResponse = cleanedResponse.replaceAll("^\\[?\\d+\\]?\\.?\\s*", "");
        cleanedResponse = cleanedResponse.replaceAll("^(Tradução:|Resposta:)\\s*", "");
        cleanedResponse = cleanedResponse.replaceAll("\\[.*?\\]", "").trim(); // Remove TUDO entre colchetes [qualquer
                                                                              // coisa]
        cleanedResponse = cleanedResponse.replaceAll("\\(.*?\\)", "").trim();
        cleanedResponse = cleanedResponse.replaceAll("\\*([^*]+)\\*", "$1").trim();
        cleanedResponse = cleanedResponse.replaceAll("\\s*–\\s*[^.]*\\.", "").trim();
        return cleanedResponse;
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
    public static void translateVTTFile(String inputVttPath, String outputVttPath)
            throws IOException, InterruptedException {
        List<TranscriptionSegment> segments = loadFromVTT(inputVttPath);
        List<TranslatedSegment> translated = translateSegments(segments);
        saveAsVTT(translated, outputVttPath);
    }

    public static void translateFileWithModel(String inputPath, String outputPath, String method, String model)
            throws IOException, InterruptedException {
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
    public static String testGeminiSimplification(String text, double seconds)
            throws IOException, InterruptedException {
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
        System.out.println("📁 Input: " + args[0]);
        System.out.println("📁 Output: " + args[1]);

        translateFile(args[0], args[1]);

        System.out.println("✅ Teste concluído!");
    }

    // =========== VALIDAÇÃO DE TIMESTAMP COM GEMINI ===========

    /**
     * Valida timestamps e simplifica frases muito longas usando Gemini API
     */
    private static List<TranslatedSegment> validateAndSimplifyTimestamps(List<TranslatedSegment> segments)
            throws IOException, InterruptedException {
        List<TranslatedSegment> validatedSegments = new ArrayList<>();
        int simplifiedCount = 0;

        logger.info(String.format("🔍 Analisando %d segmentos para validação de timestamp...", segments.size()));

        for (int i = 0; i < segments.size(); i++) {
            TranslatedSegment segment = segments.get(i);

            String translatedText = segment.translatedText();

            // VERIFICAR SE A TRADUÇÃO FALHOU (texto permanece em inglês)
            if (isEnglishText(translatedText)) {
                logger.warning(
                        String.format("🚨 Segmento [%d] não foi traduzido! Tentando traduzir novamente...", i + 1));
                logger.info(String.format("📝 Texto em inglês: '%s'", translatedText));

                try {
                    // Tentar traduzir novamente com Gemma 3
                    String retranslatedText = retranslateSegmentWithGoogleGemma3(segment.originalText());
                    if (!retranslatedText.isEmpty() && !isEnglishText(retranslatedText)) {
                        logger.info(String.format("✅ Re-tradução bem-sucedida [%d]: '%s'", i + 1,
                                retranslatedText.length() > 50 ? retranslatedText.substring(0, 50) + "..."
                                        : retranslatedText));
                        translatedText = retranslatedText;
                        // Atualizar o segmento
                        segment = new TranslatedSegment(segment.startMs(), segment.endMs(), segment.originalText(),
                                translatedText);
                    } else {
                        logger.severe(
                                String.format("❌ Re-tradução falhou [%d], mantendo texto original em inglês", i + 1));
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

            // Determinar status e ação
            String status;
            String action;
            if (ratio > 1.05) {
                status = "MUITO LONGO";
                action = "Simplificar";
            } else if (ratio < 0.50) {
                status = "MUITO CURTO";
                action = "Estender";
            } else {
                status = "OK";
                action = "Manter";
            }
            logger.info(String.format(
                    "📊 Segmento [%d]: %d palavras, %.1fs necessário / %.1fs disponível = %.2fx | Status: %s | Ação: %s",
                    i + 1, wordCount, requiredTimeSeconds, availableTimeSeconds, ratio, status, action));
            // AJUSTAR TEXTO AO TIMESTAMP - SIMPLIFICAR OU ESTENDER
            // Threshold ajustado: só simplifica se realmente não couber (ratio > 1.05)
            if (ratio > 1.05) {
                // TEXTO NÃO CABE - SIMPLIFICAR
                logger.info(String.format(
                        "📏 Segmento [%d] não cabe no tempo: %.1fs necessário vs %.1fs disponível (%.1fx)",
                        i + 1, requiredTimeSeconds, availableTimeSeconds, ratio));
                logger.info(String.format("🤖 Simplificando com Gemini: '%s'",
                        translatedText.length() > 50 ? translatedText.substring(0, 50) + "..." : translatedText));

                try {
                    // Simplificar com retry automático (3 tentativas + fallback)
                    logger.info(String.format("🔧 Simplificando [%d]...", i + 1));
                    String adjustedText = simplifyTextWithRetry(translatedText, availableTimeSeconds, false);
                    if (adjustedText != null && !adjustedText.isEmpty() && !adjustedText.equals(translatedText)) {
                        // Limpar texto simplificado (remover timestamps e artefatos)
                        String cleanedText = cleanTranslatedText(adjustedText);
                        int originalWords = countWords(translatedText);
                        int adjustedWords = countWords(cleanedText);
                        // Critério adaptativo baseado em quão urgente é simplificar
                        double minReduction;
                        if (ratio > 1.20) {
                            minReduction = 15.0; // Muito longo: exige 15% redução
                        } else if (ratio > 1.10) {
                            minReduction = 12.0; // Longo: aceita 12% redução
                        } else {
                            minReduction = 10.0; // Levemente longo: aceita 10% redução
                        }
                        double reduction = ((double) (originalWords - adjustedWords) / originalWords) * 100;
                        if (adjustedWords < originalWords && reduction >= minReduction) {
                            validatedSegments.add(new TranslatedSegment(
                                    segment.startMs(), segment.endMs(),
                                    segment.originalText(), cleanedText));
                            logger.info(String.format("✅ Simplificado [%d] (-%.1f%% palavras): '%s'", i + 1, reduction,
                                    cleanedText.length() > 50 ? cleanedText.substring(0, 50) + "..." : cleanedText));
                            simplifiedCount++;
                        } else {
                            // TENTATIVA 2: Simplificação AGRESSIVA se primeira foi insuficiente
                            if (reduction < minReduction && ratio > 1.08) {
                                logger.warning(String.format(
                                        "⚠️ Simplificação insuficiente [%d] (-%.1f%%), tentando modo AGRESSIVO...",
                                        i + 1, reduction));
                                try {
                                    logger.info(String.format("🔥 Simplificação AGRESSIVA [%d]...", i + 1));
                                    String aggressiveText = simplifyTextWithRetry(translatedText, availableTimeSeconds,
                                            true);
                                    if (aggressiveText != null && !aggressiveText.isEmpty()) {
                                        String cleanedAggressive = cleanTranslatedText(aggressiveText);
                                        int aggressiveWords = countWords(cleanedAggressive);
                                        double aggressiveReduction = ((double) (originalWords - aggressiveWords)
                                                / originalWords) * 100;
                                        if (aggressiveWords < originalWords && aggressiveReduction >= minReduction) {
                                            validatedSegments.add(new TranslatedSegment(
                                                    segment.startMs(), segment.endMs(),
                                                    segment.originalText(), cleanedAggressive));
                                            logger.info(String.format(
                                                    "✅ Simplificação AGRESSIVA bem-sucedida [%d] (-%.1f%% palavras): '%s'",
                                                    i + 1, aggressiveReduction,
                                                    cleanedAggressive.length() > 50
                                                            ? cleanedAggressive.substring(0, 50) + "..."
                                                            : cleanedAggressive));
                                            simplifiedCount++;
                                        } else {
                                            logger.warning(String.format(
                                                    "⚠️ Simplificação AGRESSIVA também insuficiente [%d] (-%.1f%%), mantendo original",
                                                    i + 1, aggressiveReduction));
                                            validatedSegments.add(segment);
                                        }
                                    } else {
                                        validatedSegments.add(segment);
                                    }
                                } catch (Exception e2) {
                                    logger.warning(String.format(
                                            "❌ Erro na simplificação AGRESSIVA [%d]: %s, mantendo original", i + 1,
                                            e2.getMessage()));
                                    validatedSegments.add(segment);
                                }
                            } else {
                                logger.warning(
                                        String.format("⚠️ Simplificação insuficiente [%d] (-%.1f%%), mantendo original",
                                                i + 1, reduction));
                                validatedSegments.add(segment);
                            }
                        }
                    } else {
                        validatedSegments.add(segment);
                    }
                } catch (Exception e) {
                    logger.warning(String.format("❌ Erro simplificando [%d]: %s", i + 1, e.getMessage()));
                    validatedSegments.add(segment);
                }
            } else if (ratio < 0.50) {
                // TEXTO MUITO CURTO - ESTENDER PARA EVITAR VOZ LENTA NO PIPER
                logger.info(String.format("📏 Segmento [%d] muito curto: %.1fs necessário vs %.1fs disponível (%.1fx)",
                        i + 1, requiredTimeSeconds, availableTimeSeconds, ratio));
                logger.info(String.format("🔍 Estendendo: '%s'",
                        translatedText.length() > 50 ? translatedText.substring(0, 50) + "..." : translatedText));
                try {
                    // Estender com retry automático (3 tentativas + fallback)
                    logger.info(String.format("🔧 Estendendo [%d]...", i + 1));
                    String extendedText = extendTextWithRetry(translatedText, availableTimeSeconds);
                    if (extendedText != null && !extendedText.isEmpty() && !extendedText.equals(translatedText)) {
                        int originalWords = countWords(translatedText);
                        int extendedWords = countWords(extendedText);
                        if (extendedWords > originalWords) {
                            validatedSegments.add(new TranslatedSegment(
                                    segment.startMs(), segment.endMs(),
                                    segment.originalText(), extendedText));
                            double expansion = ((double) (extendedWords - originalWords) / originalWords) * 100;
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
            logger.info(String.format(
                    "✅ Validação de timestamp concluída: %d/%d segmentos ajustados (simplificados/estendidos)",
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
     * Constrói prompt AGRESSIVO para simplificação de texto (quando simplificação
     * normal falha)
     */
    private static String buildAggressiveSimplificationPrompt(String text, double maxTimeSeconds, int targetWords) {
        return String.format(
                """
                        TASK: AGGRESSIVELY shorten this Portuguese sentence. A normal attempt failed.
                        Constraint: Must fit %.1f seconds (MAX %d words). Cut ruthlessly.

                        **Strategy:**
                        - Delete ALL conversational parts and fillers (hum, éh, tipo, né, quer dizer).
                        - Use the shortest possible phrasing that keeps the main technical point.
                        - Rephrase complex ideas into very simple ones.
                        - Replace long words with shorter synonyms ("extremamente" → "muito").

                        **Examples:**
                        Input (30 words): "Então hum, tipo, o React é éh muito versátil né, e você pode usar ele pra criar aplicações web modernas"
                        Output (8 words): "React é versátil pra criar apps web modernas"

                        Input (22 words): "Éh, quer dizer, isso aqui tipo é bem útil quando você precisa trabalhar com APIs"
                        Output (7 words): "Isso é útil pra trabalhar com APIs"

                        Current Text (%d words): "%s"

                        Aggressively Shortened Text:""",
                maxTimeSeconds, targetWords, countWords(text), text);
    }

    /**
     * Constrói prompt otimizado para simplificação de texto
     */
    private static String buildSimplificationPrompt(String text, double maxTimeSeconds, int targetWords) {
        return String.format(
                """
                        TASK: Rewrite this Portuguese sentence to be SHORTER for dubbing sync.
                        It's too long to fit into %.1f seconds (target: max %d words).

                        **Strategy:**
                        - Be more direct. Use contractions ("pra", "pro", "tá").
                        - Remove redundant words and all fillers (hum, éh, tipo, né).
                        - Preserve the core technical meaning 100%%.
                        - Keep technical terms in English.

                        **Example:**
                        Input (25 words): "Então tipo, hum, JavaScript é tipo versátil e pode ser usado pra várias coisas diferentes, né"
                        Output (12 words): "Então, JavaScript é versátil e pode ser usado pra várias coisas"

                        Current Text (%d words): "%s"

                        Shortened Text (Output only the rewritten text):""",
                maxTimeSeconds, targetWords, countWords(text), text);
    }

    /**
     * Constrói prompt otimizado para extensão de texto
     */
    private static String buildExtensionPrompt(String text, double maxTimeSeconds, int targetWords) {
        int currentWords = countWords(text);
        return String.format(
                """
                        TASK: CONSERVATIVELY expand this short Portuguese sentence to better fill the dubbing time.

                        **Current Status:**
                        - Current: %d words (%.1f seconds at 3.7 words/sec)
                        - Available: %.1f seconds
                        - Target: %d words (be conservative, aim for 80%% of target = %d words)

                        **CONSERVATIVE Strategy:**
                        - **DO NOT add unnecessary introductory phrases** like "Então, o que acontece é:", "Veja bem:", "Um exemplo prático:".
                        - Slightly elaborate on existing ideas WITHOUT adding new information.
                        - Use more complete grammatical structures instead of shortcuts.
                        - Keep technical terms in English.
                        - Vary language: use "nós vamos", "a gente vai", or "vamos".
                        - **STAY UNDER the target word count** - better to be slightly short than too long.

                        **Examples:**
                        Input (5 words): "Instale o Node"
                        ❌ BAD (12 words): "Então, o que acontece é: instale o Node pro projeto"
                        ✅ GOOD (8 words): "Agora você vai instalar o Node aqui"

                        Input (8 words): "Use React pra criar aplicações web"
                        ❌ BAD (18 words): "Então veja bem, o que você vai fazer é usar React pra criar aplicações web modernas na prática"
                        ✅ GOOD (13 words): "Você vai usar o React pra criar as suas aplicações web aqui"

                        Current Text (%d words): "%s"

                        Expanded Text (Output only the rewritten text, aim for ~%d words MAX):""",
                currentWords, currentWords / 3.7, maxTimeSeconds, targetWords, (int) (targetWords * 0.8), currentWords,
                text, (int) (targetWords * 0.8));
    }

    /**
     * Simplifica texto AGRESSIVAMENTE usando Google Gemma 3 (modo agressivo para
     * casos difíceis)
     */
    private static String simplifyTextAggressiveWithGoogleGemma3(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        String prompt = buildAggressiveSimplificationPrompt(text, maxTimeSeconds, targetWords);
        String response = callGoogleGemmaTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Simplifica texto AGRESSIVAMENTE usando Google Gemini (modo agressivo para
     * casos difíceis)
     */
    private static String simplifyTextAggressiveWithGoogleGemini(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        String prompt = buildAggressiveSimplificationPrompt(text, maxTimeSeconds, targetWords);
        String response = callGoogleGeminiTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Simplifica texto AGRESSIVAMENTE usando DeepSeek (modo agressivo para casos
     * difíceis)
     */
    private static String simplifyTextAggressiveWithDeepSeek(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        String prompt = buildAggressiveSimplificationPrompt(text, maxTimeSeconds, targetWords);
        String response = callDeepSeekTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Simplifica texto com retry e fallback (normal mode)
     * Tenta 3x com modelo atual, aguardando tempo de retry em caso de quota
     * exceeded
     * Se falhar 3x, alterna para modelo alternativo
     */
    private static String simplifyTextWithRetry(String text, double maxTimeSeconds, boolean aggressive)
            throws IOException, InterruptedException {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (aggressive) {
                    // Modo agressivo
                    if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                        return simplifyTextAggressiveWithDeepSeek(text, maxTimeSeconds);
                    } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                        return simplifyTextAggressiveWithGoogleGemini(text, maxTimeSeconds);
                    } else {
                        return simplifyTextAggressiveWithGoogleGemma3(text, maxTimeSeconds);
                    }
                } else {
                    // Modo normal
                    if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                        return simplifyTextWithDeepSeek(text, maxTimeSeconds);
                    } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                        return simplifyTextWithGoogleGemini(text, maxTimeSeconds);
                    } else {
                        return simplifyTextWithGoogleGemma3(text, maxTimeSeconds);
                    }
                }
            } catch (Exception e) {
                if (isQuotaExceededError(e.getMessage())) {
                    if (attempt < maxRetries) {
                        // Aguardar e tentar novamente com mesmo modelo
                        long retryTime = extractRetryTimeMs(e.getMessage());
                        logger.warning(String.format(
                                "⚠️ Cota excedida na simplificação (tentativa %d/%d). Aguardando %.1fs...",
                                attempt, maxRetries, retryTime / 1000.0));
                        Thread.sleep(retryTime);
                        continue;
                    } else {
                        // Após 3 tentativas, alternar para modelo alternativo
                        logger.warning(
                                String.format("⚠️ Cota excedida após %d tentativas, alternando modelo...", maxRetries));
                        try {
                            if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                                if (aggressive) {
                                    return simplifyTextAggressiveWithGoogleGemma3(text, maxTimeSeconds);
                                } else {
                                    return simplifyTextWithGoogleGemma3(text, maxTimeSeconds);
                                }
                            } else {
                                if (aggressive) {
                                    return simplifyTextAggressiveWithGoogleGemini(text, maxTimeSeconds);
                                } else {
                                    return simplifyTextWithGoogleGemini(text, maxTimeSeconds);
                                }
                            }
                        } catch (Exception e2) {
                            logger.warning("❌ Fallback também falhou: " + e2.getMessage());
                            return text; // Retornar original se tudo falhar
                        }
                    }
                }
                // Erro que não é quota exceeded
                throw e;
            }
        }
        return text; // Fallback final
    }

    /**
     * Estende texto com retry e fallback
     * Tenta 3x com modelo atual, aguardando tempo de retry em caso de quota
     * exceeded
     * Se falhar 3x, alterna para modelo alternativo
     */
    private static String extendTextWithRetry(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                    return extendTextWithDeepSeek(text, maxTimeSeconds);
                } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                    return extendTextWithGoogleGemini(text, maxTimeSeconds);
                } else {
                    return extendTextWithGoogleGemma3(text, maxTimeSeconds);
                }
            } catch (Exception e) {
                if (isQuotaExceededError(e.getMessage())) {
                    if (attempt < maxRetries) {
                        // Aguardar e tentar novamente com mesmo modelo
                        long retryTime = extractRetryTimeMs(e.getMessage());
                        logger.warning(
                                String.format("⚠️ Cota excedida na extensão (tentativa %d/%d). Aguardando %.1fs...",
                                        attempt, maxRetries, retryTime / 1000.0));
                        Thread.sleep(retryTime);
                        continue;
                    } else {
                        // Após 3 tentativas, alternar para modelo alternativo
                        logger.warning(
                                String.format("⚠️ Cota excedida após %d tentativas, alternando modelo...", maxRetries));
                        try {
                            if (currentMethod == TranslationMethod.DEEPSEEK_V3) {
                                return extendTextWithGoogleGemini(text, maxTimeSeconds);
                            } else if (currentMethod == TranslationMethod.GOOGLE_GEMINI) {
                                return extendTextWithGoogleGemma3(text, maxTimeSeconds);
                            } else {
                                return extendTextWithDeepSeek(text, maxTimeSeconds);
                            }
                        } catch (Exception e2) {
                            logger.warning("❌ Fallback também falhou: " + e2.getMessage());
                            return text; // Retornar original se tudo falhar
                        }
                    }
                }
                // Erro que não é quota exceeded
                throw e;
            }
        }
        return text; // Fallback final
    }

    /**
     * Simplifica texto usando Google Gemma 3 API configurado
     */
    private static String simplifyTextWithGoogleGemma3(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        String prompt = buildSimplificationPrompt(text, maxTimeSeconds, targetWords);
        String response = callGoogleGemmaTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Simplifica texto usando Google Gemini API
     */
    private static String simplifyTextWithGoogleGemini(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        String prompt = buildSimplificationPrompt(text, maxTimeSeconds, targetWords);
        String response = callGoogleGeminiTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Simplifica texto usando DeepSeek API
     */
    private static String simplifyTextWithDeepSeek(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        String prompt = buildSimplificationPrompt(text, maxTimeSeconds, targetWords);
        String response = callDeepSeekTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Simplifica texto usando Google Gemini API (fallback)
     */
    private static String simplifyTextWithGemini(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.8);
        String prompt = buildSimplificationPrompt(text, maxTimeSeconds, targetWords);
        String payload = buildGeminiPayload(prompt);
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-X", "POST", GEMINI_API_URL + "?key=" + GEMINI_API_KEY,
                "-H", "Content-Type: application/json",
                "-d", payload,
                "--max-time", "15");
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
        String parsedResponse = parseGeminiResponse(response.toString());
        return cleanSimpleResponse(parsedResponse, text);
    }

    /**
     * Estende texto curto usando Google Gemma 3 API configurado
     */
    private static String extendTextWithGoogleGemma3(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.9);
        String prompt = buildExtensionPrompt(text, maxTimeSeconds, targetWords);
        String response = callGoogleGemmaTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Estende texto curto usando Google Gemini API
     */
    private static String extendTextWithGoogleGemini(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.9);
        String prompt = buildExtensionPrompt(text, maxTimeSeconds, targetWords);
        String response = callGoogleGeminiTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Estende texto curto usando DeepSeek API
     */
    private static String extendTextWithDeepSeek(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.9);
        String prompt = buildExtensionPrompt(text, maxTimeSeconds, targetWords);
        String response = callDeepSeekTranslation(prompt);
        return cleanSimpleResponse(response, text);
    }

    /**
     * Estende texto curto para preencher timestamp com fala natural (fallback)
     */
    private static String extendTextWithGemini(String text, double maxTimeSeconds)
            throws IOException, InterruptedException {
        int targetWords = (int) Math.ceil(maxTimeSeconds * PORTUGUESE_SPEAKING_SPEED * 0.9);
        String prompt = buildExtensionPrompt(text, maxTimeSeconds, targetWords);
        String payload = buildGeminiPayload(prompt);
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-X", "POST", GEMINI_API_URL + "?key=" + GEMINI_API_KEY,
                "-H", "Content-Type: application/json",
                "-d", payload,
                "--max-time", "15");
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
        String parsedResponse = parseGeminiResponse(response.toString());
        return cleanSimpleResponse(parsedResponse, text);
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
                escapedPrompt);
    }

    /**
     * Parseia resposta da API Gemini
     */
    private static String parseGeminiResponse(String response) throws IOException {
        try {
            // Verificar erros da API primeiro
            if (response.contains("\"error\"")) {
                String errorMsg = extractErrorMessage(response);
                logger.severe("❌ Erro da API Google Gemini: " + errorMsg);
                throw new IOException("API Error: " + errorMsg);
            }
            // Buscar pelo padrão: "text": "conteúdo"
            int textStart = response.indexOf("\"text\":");
            if (textStart == -1) {
                logger.warning(
                        "Resposta Gemini sem campo 'text': " + response.substring(0, Math.min(100, response.length())));
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
                content = content.replaceAll("\\[(?!\\d+\\])[^\\]]*\\]", "").trim(); // Remove outros [texto] mas mantém
                                                                                     // [número]
                content = content.replaceAll("\\(.*?\\)", "").trim(); // Remove (qualquer coisa)
                content = content.replaceAll("\\*([^*]+)\\*", "$1").trim(); // Remove *palavra* → palavra
                content = content.replaceAll("\\s*–\\s*[^.]*\\.", "").trim(); // Remove comentários com –

                return content.trim();
            }

        } catch (IOException e) {
            // Re-lançar IOException (erros da API como quota exceeded)
            throw e;
        } catch (Exception e) {
            logger.warning("Erro parseando resposta Gemini: " + e.getMessage());
        }
        return "";
    }

    /**
     * Constrói payload JSON para API do DeepSeek
     */
    private static String buildDeepSeekPayload(String prompt) {
        // Escapar JSON corretamente
        String escapedPrompt = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return String.format(Locale.US,
                "{" +
                        "\"model\":\"%s\"," +
                        "\"messages\":[{" +
                        "\"role\":\"user\"," +
                        "\"content\":\"%s\"" +
                        "}]," +
                        "\"temperature\":%.1f," +
                        "\"max_tokens\":4096," +
                        "\"stream\":false" +
                        "}",
                DEEPSEEK_MODEL,
                escapedPrompt,
                TEMPERATURE);
    }

    /**
     * Parse da resposta JSON do DeepSeek
     */
    private static String parseDeepSeekResponse(String response) throws IOException {
        try {
            // Verificar erros da API primeiro
            if (response.contains("\"error\"")) {
                String errorMsg = extractErrorMessage(response);
                logger.severe("❌ Erro da API DeepSeek: " + errorMsg);
                throw new IOException("API Error: " + errorMsg);
            }
            // Buscar pelo padrão: "content": "conteúdo"
            int contentStart = response.indexOf("\"content\":");
            if (contentStart == -1) {
                logger.warning("Resposta DeepSeek sem campo 'content': "
                        + response.substring(0, Math.min(100, response.length())));
                return "";
            }
            contentStart = response.indexOf("\"", contentStart + 10) + 1;
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
                logger.info("🔤 DeepSeek resposta extraída: " + content.substring(0, Math.min(50, content.length()))
                        + "...");
                return content.trim();
            }
        } catch (IOException e) {
            // Re-lançar IOException (erros da API)
            throw e;
        } catch (Exception e) {
            logger.warning("Erro parseando resposta DeepSeek: " + e.getMessage());
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
    private static String retranslateSegmentWithGoogleGemma3(String englishText)
            throws IOException, InterruptedException {
        String prompt = String.format(
                "Translate this English text to natural Brazilian Portuguese for video dubbing:\n\n" +
                        "\"%s\"\n\n" +
                        "Rules:\n" +
                        "- Natural Brazilian Portuguese\n" +
                        "- Keep technical terms in English if commonly used\n" +
                        "- Maintain the same meaning and tone\n" +
                        "- No explanations, just the translation\n\n" +
                        "Portuguese translation:",
                englishText);

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