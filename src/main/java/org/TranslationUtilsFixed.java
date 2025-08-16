package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Utilitário avançado de tradução com foco em naturalidade da fala brasileira
 * e preservação do contexto para dublagem de alta qualidade.
 *
 * Versão simplificada usando curl para comunicação com Ollama
 */
public class TranslationUtilsFixed {

    private static final Logger logger = Logger.getLogger(TranslationUtilsFixed.class.getName());

    // Configurações do modelo
    private static final String MODEL_NAME = "gemma3:12b-it-qat";
    private static final int MAX_CHARS_PER_BATCH = 500;
    private static final int MAX_CONCURRENT_REQUESTS = 2;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final long CACHE_TTL_MS = 3600000; // 1 hora

    // Padrões regex otimizados
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "^(\\d{2}):(\\d{2})[.:,](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2})[.:,](\\d{3})$"
    );

    private static final Pattern ENGLISH_DETECTION_PATTERN = Pattern.compile(
            "\\b(the|and|you|that|this|with|for|are|have|will|would|your|they|from|but|not|what|all|any|can|had|her|was|one|our|out|day|get|has|him|his|how|man|new|now|old|see|two|way|who|boy|did|its|let|put|say|she|too|use|when|which|time|each|make|more|very|after|first|well|work|life|only|over|think|also|back|other|many|come|most|take)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BLOCK_MARKER_PATTERN = Pattern.compile("\\[(\\d+)\\]\\s*(.*)");

    // Formatador de números decimal seguro
    private static final DecimalFormat DECIMAL_FORMAT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        DECIMAL_FORMAT = new DecimalFormat("#0.000", symbols);
    }

    // Cache com TTL
    private static final Map<String, CachedTranslation> translationCache = new ConcurrentHashMap<>();

    // Classe para cache com TTL
    private static class CachedTranslation {
        final String translation;
        final long timestamp;

        CachedTranslation(String translation) {
            this.translation = translation;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static class VTTBlock {
        String timestamp;
        String originalText;
        String translatedText;
        double startTime;
        double endTime;
        double duration;
        int sequenceNumber;
        boolean hasValidTiming;

        VTTBlock(String timestamp, String text, int sequenceNumber) {
            this.timestamp = timestamp;
            this.originalText = cleanText(text);
            this.sequenceNumber = sequenceNumber;
            this.hasValidTiming = parseTimestamp();

            if (!hasValidTiming) {
                logger.warning("Timestamp inválido para bloco " + sequenceNumber + ": " + timestamp);
                this.duration = 3.0; // Fallback padrão
            }
        }

        private boolean parseTimestamp() {
            try {
                Matcher matcher = TIMESTAMP_PATTERN.matcher(timestamp);
                if (matcher.matches()) {
                    int startH = Integer.parseInt(matcher.group(1));
                    int startM = Integer.parseInt(matcher.group(2));
                    int startMs = Integer.parseInt(matcher.group(3));

                    int endH = Integer.parseInt(matcher.group(4));
                    int endM = Integer.parseInt(matcher.group(5));
                    int endMs = Integer.parseInt(matcher.group(6));

                    this.startTime = startH * 3600 + startM * 60 + startMs / 1000.0;
                    this.endTime = endH * 3600 + endM * 60 + endMs / 1000.0;
                    this.duration = endTime - startTime;

                    // Validação básica - permite durações maiores para vídeos educacionais
                    if (duration <= 0) {
                        logger.warning("Duração inválida: " + duration + "s para bloco " + sequenceNumber);
                        this.duration = 3.0; // Fallback padrão
                    } else if (duration > 60) {
                        logger.info("Duração longa detectada: " + duration + "s para bloco " + sequenceNumber + " (normal para conteúdo educacional)");
                    }

                    return true;
                }
            } catch (NumberFormatException e) {
                logger.log(Level.WARNING, "Erro ao parsear timestamp: " + timestamp, e);
            }
            return false;
        }

        private String cleanText(String text) {
            if (text == null || text.trim().isEmpty()) {
                return "";
            }

            return text
                    .replaceAll("\\[.*?\\]", "") // Remove tags de descrição
                    .replaceAll("\\(.*?\\)", "") // Remove parênteses informativos
                    .replaceAll("<.*?>", "") // Remove HTML/XML tags
                    .replaceAll("♪.*?♪", "") // Remove notações musicais
                    .replaceAll("\\{.*?\\}", "") // Remove metadados
                    .replaceAll("\\s+", " ") // Normaliza espaços
                    .replaceAll("^[\\s\\-]+|[\\s\\-]+$", "") // Remove espaços e hifens das bordas
                    .trim();
        }

        boolean isEnglish() {
            if (originalText.length() < 5) return false;

            // Conta palavras em inglês
            Matcher matcher = ENGLISH_DETECTION_PATTERN.matcher(originalText.toLowerCase());
            int englishWords = 0;
            while (matcher.find()) {
                englishWords++;
            }

            String[] totalWords = originalText.split("\\s+");
            if (totalWords.length == 0) return false;
            
            double englishRatio = (double) englishWords / totalWords.length;

            // Considera inglês se mais de 30% das palavras forem comuns em inglês E houver pelo menos 3 palavras inglesas
            boolean isEnglish = englishRatio >= 0.30 && englishWords >= 3;
            
            if (isEnglish) {
                logger.fine(String.format("Bloco detectado como inglês: %d/%d palavras (%.1f%%) - '%s'", 
                    englishWords, totalWords.length, englishRatio * 100, 
                    originalText.length() > 50 ? originalText.substring(0, 50) + "..." : originalText));
            }
            
            return isEnglish;
        }

        boolean isEmpty() {
            return originalText == null || originalText.trim().isEmpty();
        }

        @Override
        public String toString() {
            return String.format("VTTBlock[%d]: %s (%.1fs) - %s",
                    sequenceNumber, timestamp, duration,
                    originalText.length() > 50 ? originalText.substring(0, 50) + "..." : originalText);
        }
    }

    /**
     * Método principal de tradução com verificações robustas e tratamento de erros
     */
    public static void translateFile(String inputFile, String outputFile, String method) throws Exception {
        logger.info("🌐 Iniciando tradução avançada: " + inputFile);

        // Validações iniciais
        if (!Files.exists(Paths.get(inputFile))) {
            throw new IllegalArgumentException("Arquivo de entrada não encontrado: " + inputFile);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(inputFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao ler arquivo de entrada: " + e.getMessage(), e);
        }

        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Arquivo de entrada está vazio");
        }

        List<String> outputLines = new ArrayList<>();

        // Preserva cabeçalho VTT
        processVTTHeader(lines, outputLines);

        // Parse dos blocos com validação
        List<VTTBlock> blocks = parseBlocks(lines);
        logger.info("📝 Blocos para traduzir: " + blocks.size());

        if (blocks.isEmpty()) {
            logger.warning("⚠️ Nenhum bloco válido encontrado para tradução");
            Files.write(Paths.get(outputFile), outputLines, StandardCharsets.UTF_8);
            return;
        }

        // Remove blocos vazios
        blocks.removeIf(VTTBlock::isEmpty);
        logger.info("📝 Blocos válidos após limpeza: " + blocks.size());

        // Verifica idioma
        long englishBlocks = blocks.stream().mapToLong(b -> b.isEnglish() ? 1 : 0).sum();
        double englishRatio = (double) englishBlocks / blocks.size();

        logger.info(String.format("🔍 Detectados %d/%d blocos em inglês (%.1f%%)",
                englishBlocks, blocks.size(), englishRatio * 100));

        if (englishRatio < 0.5) {
            logger.info("✅ Arquivo já parece estar em português, copiando...");
            Files.copy(Paths.get(inputFile), Paths.get(outputFile),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // Traduz em grupos contextuais
        try {
            translateBlocksInContext(blocks, method);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro durante tradução", e);
            throw new RuntimeException("Falha na tradução: " + e.getMessage(), e);
        }

        // Monta saída final com validação
        generateOutput(blocks, outputLines, outputFile);

        logger.info("✅ Tradução concluída: " + outputFile);

        // Limpeza periódica do cache
        cleanExpiredCache();
    }

    private static void processVTTHeader(List<String> lines, List<String> outputLines) {
        if (!lines.isEmpty() && lines.get(0).trim().equalsIgnoreCase("WEBVTT")) {
            outputLines.add("WEBVTT");
            outputLines.add("");
            lines.remove(0);

            // Remove linhas vazias do início
            while (!lines.isEmpty() && lines.get(0).trim().isEmpty()) {
                lines.remove(0);
            }
        }
    }

    private static void translateBlocksInContext(List<VTTBlock> blocks, String method) throws Exception {
        List<List<VTTBlock>> contextGroups = groupBlocksForContext(blocks);
        logger.info("📚 Grupos de contexto: " + contextGroups.size());

        Semaphore semaphore = new Semaphore(MAX_CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < contextGroups.size(); i++) {
            final int groupIndex = i;
            final List<VTTBlock> group = contextGroups.get(i);

            futures.add(executor.submit(() -> {
                semaphore.acquire();
                try {
                    logger.info(String.format("🔄 Traduzindo grupo %d/%d (%d blocos)",
                            groupIndex + 1, contextGroups.size(), group.size()));

                    translateBlockGroupWithRetry(group, method);

                    logger.info(String.format("✅ Grupo %d/%d concluído",
                            groupIndex + 1, contextGroups.size()));

                    return null;
                } finally {
                    semaphore.release();
                }
            }));
        }

        // Aguarda conclusão com timeout
        for (Future<Void> future : futures) {
            try {
                future.get(10, TimeUnit.MINUTES); // Timeout de 10 minutos por grupo
            } catch (TimeoutException e) {
                logger.severe("Timeout na tradução de grupo");
                future.cancel(true);
                throw new RuntimeException("Timeout na tradução", e);
            }
        }

        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    private static void translateBlockGroupWithRetry(List<VTTBlock> group, String method) throws IOException {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                translateBlockGroup(group, method);
                return; // Sucesso
            } catch (Exception e) {
                lastException = e;
                logger.warning(String.format("Tentativa %d/%d falhou: %s", attempt, MAX_RETRIES, e.getMessage()));

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt); // Backoff exponencial
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrompido durante retry", ie);
                    }
                }
            }
        }

        // Se chegou aqui, todas as tentativas falharam
        logger.severe("Todas as tentativas de tradução falharam para grupo");

        // Fallback: mantém texto original
        for (VTTBlock block : group) {
            if (block.translatedText == null || block.translatedText.trim().isEmpty()) {
                block.translatedText = block.originalText;
                logger.warning("Usando texto original como fallback para bloco " + block.sequenceNumber);
            }
        }
    }

    private static List<List<VTTBlock>> groupBlocksForContext(List<VTTBlock> blocks) {
        List<List<VTTBlock>> groups = new ArrayList<>();
        List<VTTBlock> currentGroup = new ArrayList<>();
        int currentGroupChars = 0;

        for (VTTBlock block : blocks) {
            int blockChars = block.originalText.length();

            // Critérios para novo grupo
            boolean shouldCreateNewGroup =
                    currentGroupChars + blockChars > MAX_CHARS_PER_BATCH ||
                            (!currentGroup.isEmpty() && hasContextBreak(currentGroup.get(currentGroup.size() - 1), block)) ||
                            currentGroup.size() >= 10; // Limite máximo de blocos por grupo

            if (shouldCreateNewGroup && !currentGroup.isEmpty()) {
                groups.add(new ArrayList<>(currentGroup));
                currentGroup.clear();
                currentGroupChars = 0;
            }

            currentGroup.add(block);
            currentGroupChars += blockChars;
        }

        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }

        return groups;
    }

    private static boolean hasContextBreak(VTTBlock prev, VTTBlock current) {
        // Pausa longa indica mudança de contexto
        if (prev.hasValidTiming && current.hasValidTiming) {
            double gap = current.startTime - prev.endTime;
            if (gap > 4.0) {
                return true;
            }
        }

        // Mudança drástica no comprimento da frase
        int prevLength = prev.originalText.length();
        int currentLength = current.originalText.length();

        if (prevLength > 0 && currentLength > 0) {
            double lengthRatio = (double) Math.max(prevLength, currentLength) /
                    Math.min(prevLength, currentLength);
            if (lengthRatio > 4.0) {
                return true;
            }
        }

        // Mudança de pontuação (final de período)
        String prevText = prev.originalText.trim();
        if (prevText.endsWith(".") || prevText.endsWith("!") || prevText.endsWith("?")) {
            return true;
        }

        return false;
    }

    private static void translateBlockGroup(List<VTTBlock> group, String method) throws IOException {
        if (group.isEmpty()) return;

        // Gera chave de cache para o grupo
        String contextText = buildContextText(group);
        String cacheKey = generateCacheKey(contextText);

        // Verifica cache
        CachedTranslation cachedResult = translationCache.get(cacheKey);
        if (cachedResult != null && !cachedResult.isExpired()) {
            logger.fine("Cache hit para grupo de " + group.size() + " blocos");
            applyTranslationToBlocks(cachedResult.translation, group);
            return;
        }

        // Tradução
        String translatedContext;
        if ("LLama".equalsIgnoreCase(method)) {
            translatedContext = performLlamaTranslation(contextText, group);
        } else {
            throw new IOException("Método de tradução não suportado: " + method);
        }

        if (translatedContext != null && !translatedContext.trim().isEmpty()) {
            // Salva no cache
            translationCache.put(cacheKey, new CachedTranslation(translatedContext));
            applyTranslationToBlocks(translatedContext, group);
        } else {
            throw new IOException("Tradução retornou resultado vazio");
        }
    }

    private static String buildContextText(List<VTTBlock> group) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            contextBuilder.append("[").append(i + 1).append("] ").append(group.get(i).originalText);
            if (i < group.size() - 1) {
                contextBuilder.append("\n");
            }
        }
        return contextBuilder.toString();
    }

    private static String performLlamaTranslation(String contextText, List<VTTBlock> blocks) throws IOException {
        // Constrói prompt completo
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(buildSystemPrompt()).append("\n\n");
        
        // Context com informações de duração
        promptBuilder.append("INFORMAÇÕES DE TEMPO:\n");
        for (int i = 0; i < blocks.size(); i++) {
            VTTBlock block = blocks.get(i);
            promptBuilder.append("Bloco [").append(i + 1).append("]: ");
            if (block.hasValidTiming) {
                promptBuilder.append(DECIMAL_FORMAT.format(block.duration)).append("s");
            } else {
                promptBuilder.append("~3s");
            }
            promptBuilder.append("\n");
        }
        
        promptBuilder.append("\nTEXTO PARA TRADUZIR:\n").append(contextText);
        promptBuilder.append("\n\nTRADUZA para português brasileiro natural e coloquial, mantendo a numeração [1], [2], etc.:");

        // Escapa adequadamente o prompt para JSON
        String escapedPrompt = escapeJsonString(promptBuilder.toString());
        
        // Constrói comando curl
        String curlCommand = String.format(
            "curl -s -X POST http://localhost:11434/api/generate " +
            "-H \"Content-Type: application/json\" " +
            "-d '{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0.05,\"top_p\":0.85}}'",
            MODEL_NAME, escapedPrompt
        );

        logger.info("🔍 Executando tradução com Ollama...");

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();
            
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                throw new IOException("Timeout ou erro na requisição Ollama");
            }
            
            // Parse da resposta JSON simples
            String responseStr = response.toString();
            
            // Busca pelo campo "response" no JSON
            Pattern responsePattern = Pattern.compile("\"response\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);
            
            if (matcher.find()) {
                String content = matcher.group(1);
                // Decodifica escape sequences
                content = content.replace("\\n", "\n")
                               .replace("\\\"", "\"")
                               .replace("\\\\", "\\");
                
                if (!content.trim().isEmpty()) {
                    logger.info("✅ Tradução recebida do Ollama");
                    return content;
                }
            }
            
            throw new IOException("Resposta do Ollama não contém tradução válida");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro na comunicação com Ollama", e);
            throw new IOException("Falha na comunicação com Ollama: " + e.getMessage(), e);
        }
    }

    private static String buildSystemPrompt() {
        return """
            Você é um especialista em dublagem e localização de conteúdo educacional do inglês para o português brasileiro.
            
            OBJETIVO: Traduzir legendas de vídeos educacionais mantendo máxima naturalidade da fala brasileira coloquial.
            
            REGRAS FUNDAMENTAIS:
            1. Use português brasileiro natural e coloquial (como falado no dia a dia)
            2. Mantenha a sincronização labial aproximada quando possível
            3. Preserve completamente a emoção e intenção original
            4. Use contrações naturais: "pra", "tá", "né", "cê", "vamo"
            5. Adapte expressões idiomáticas para equivalentes brasileiros naturais
            6. SEMPRE mantenha a numeração [1], [2], etc. para identificar cada bloco
            7. Respeite aproximadamente a duração indicada para cada bloco
            8. Evite formalismo excessivo - use linguagem acessível e natural
            
            ESTILO ESPECÍFICO PARA CONTEÚDO EDUCACIONAL:
            - Linguagem didática mas descontraída 
            - Mantenha termos técnicos quando apropriado
            - Use conectores naturais: "então", "aí", "daí", "beleza"
            - Preserve pausas e ritmo natural da fala brasileira
            - Transforme perguntas retóricas em estilo brasileiro
            
            ADAPTAÇÕES NATURAIS OBRIGATÓRIAS:
            "you know" → "sabe"
            "hands-on practice" → "colocar a mão na massa"
            "let's dive into" → "vamos mergulhar em" ou "bora ver"
            "that's awesome" → "isso é demais"
            "make sure you" → "certifica aí" ou "não esquece de"
            "so basically" → "então basicamente"
            "all right" → "beleza" ou "certo"
            "let's get started" → "bora começar" ou "vamos lá"
            
            FORMATO DE RESPOSTA OBRIGATÓRIO:
            [1] tradução do primeiro bloco
            [2] tradução do segundo bloco
            [3] tradução do terceiro bloco
            ...
            
            IMPORTANTE: 
            - Responda APENAS com as traduções numeradas
            - Não adicione explicações, comentários ou texto extra
            - Mantenha o texto natural para dublagem em português brasileiro
            - Foque na naturalidade da fala, não na tradução literal
            """;
    }

    /**
     * Escapa caracteres especiais para JSON
     */
    private static String escapeJsonString(String input) {
        if (input == null) return "";
        
        return input
            .replace("\\", "\\\\")  // Escape backslashes first
            .replace("\"", "\\\"")  // Escape quotes
            .replace("\n", "\\n")   // Escape newlines
            .replace("\r", "\\r")   // Escape carriage returns
            .replace("\t", "\\t")   // Escape tabs
            .replace("\b", "\\b")   // Escape backspaces
            .replace("\f", "\\f");  // Escape form feeds
    }

    private static void applyTranslationToBlocks(String translatedContext, List<VTTBlock> group) {
        if (translatedContext == null || translatedContext.trim().isEmpty()) {
            logger.warning("⚠️ Tradução vazia, mantendo texto original");
            for (VTTBlock block : group) {
                block.translatedText = block.originalText;
            }
            return;
        }

        // Parse das traduções numeradas
        Map<Integer, String> translations = parseNumberedTranslations(translatedContext);

        for (int i = 0; i < group.size(); i++) {
            VTTBlock block = group.get(i);
            int blockNumber = i + 1;

            String translation = translations.get(blockNumber);

            if (translation == null || translation.trim().isEmpty()) {
                // Fallback: busca por índice sequencial
                translation = extractTranslationByIndex(translatedContext, i);
            }

            if (translation == null || translation.trim().isEmpty()) {
                logger.warning("⚠️ Tradução não encontrada para bloco " + blockNumber + ", mantendo original");
                translation = block.originalText;
            }

            // Pós-processamento para naturalidade
            translation = postProcessTranslation(translation, block.duration, block.originalText);

            block.translatedText = translation;
        }
    }

    private static Map<Integer, String> parseNumberedTranslations(String translatedContext) {
        Map<Integer, String> translations = new HashMap<>();
        String[] lines = translatedContext.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = BLOCK_MARKER_PATTERN.matcher(line);
            if (matcher.matches()) {
                try {
                    int blockNumber = Integer.parseInt(matcher.group(1));
                    String translation = matcher.group(2).trim();
                    if (!translation.isEmpty()) {
                        translations.put(blockNumber, translation);
                    }
                } catch (NumberFormatException e) {
                    logger.warning("Número de bloco inválido: " + line);
                }
            }
        }

        return translations;
    }

    private static String extractTranslationByIndex(String translatedContext, int index) {
        String[] lines = translatedContext.split("\n");
        int validLineCount = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Remove numeração se existir
            line = line.replaceAll("^\\[\\d+\\]\\s*", "");

            if (validLineCount == index) {
                return line;
            }
            validLineCount++;
        }

        return null;
    }

    private static String postProcessTranslation(String translation, double duration, String originalText) {
        String processed = translation;

        // Remove marcadores residuais
        processed = processed.replaceAll("^\\[\\d+\\]\\s*", "");
        processed = processed.replaceAll("^\\d+[.)\\s]+", ""); // Remove numeração alternativa

        // Normaliza pontuação e espaços
        processed = processed.replaceAll("\\s+", " ");
        processed = processed.replaceAll("([.!?])\\s*([.!?])+", "$1");
        processed = processed.replaceAll("\\s*([,.!?;:])\\s*", "$1 ");

        // Limpeza final
        processed = processed.trim();
        if (processed.isEmpty()) {
            processed = originalText; // Fallback para texto original
        }

        return processed;
    }

    private static List<VTTBlock> parseBlocks(List<String> lines) {
        List<VTTBlock> blocks = new ArrayList<>();
        String currentTimestamp = null;
        StringBuilder currentText = new StringBuilder();
        int sequenceNumber = 1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Pula linhas vazias
            if (line.isEmpty()) {
                continue;
            }

            // Pula números de sequência standalone
            if (line.matches("^\\d+$")) {
                continue;
            }

            // Detecta timestamp
            if (TIMESTAMP_PATTERN.matcher(line).matches()) {
                // Processa bloco anterior se existir
                if (currentTimestamp != null && currentText.length() > 0) {
                    String textBlock = currentText.toString().trim();
                    if (!textBlock.isEmpty()) {
                        VTTBlock block = new VTTBlock(currentTimestamp, textBlock, sequenceNumber++);
                        if (!block.isEmpty()) {
                            blocks.add(block);
                        }
                    }
                    currentText.setLength(0);
                }
                currentTimestamp = line;
            } else if (currentTimestamp != null) {
                // Adiciona texto ao bloco atual
                if (currentText.length() > 0) {
                    currentText.append(" ");
                }
                currentText.append(line);
            }
        }

        // Processa último bloco
        if (currentTimestamp != null && currentText.length() > 0) {
            String textBlock = currentText.toString().trim();
            if (!textBlock.isEmpty()) {
                VTTBlock block = new VTTBlock(currentTimestamp, textBlock, sequenceNumber);
                if (!block.isEmpty()) {
                    blocks.add(block);
                }
            }
        }

        logger.info("Parsed " + blocks.size() + " VTT blocks");
        return blocks;
    }

    private static void generateOutput(List<VTTBlock> blocks, List<String> outputLines, String outputFile) throws IOException {
        for (VTTBlock block : blocks) {
            outputLines.add(block.timestamp);
            String finalText = block.translatedText != null ? block.translatedText : block.originalText;
            outputLines.add(finalText);
            outputLines.add("");
        }

        try {
            Files.write(Paths.get(outputFile), outputLines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.severe("Erro ao escrever arquivo de saída: " + e.getMessage());
            throw e;
        }
    }

    private static String generateCacheKey(String text) {
        return String.valueOf(text.trim().toLowerCase().hashCode());
    }

    private static void cleanExpiredCache() {
        try {
            int sizeBefore = translationCache.size();
            translationCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            int sizeAfter = translationCache.size();

            if (sizeBefore != sizeAfter) {
                logger.info(String.format("🧹 Cache limpo: %d entradas removidas, %d restantes",
                        sizeBefore - sizeAfter, sizeAfter));
            }
        } catch (Exception e) {
            logger.warning("Erro na limpeza do cache: " + e.getMessage());
        }
    }

    // Métodos de compatibilidade com versões anteriores
    public static void translateFileEnhanced(String inputFile, String outputFile, Object prosodyData) throws Exception {
        translateFile(inputFile, outputFile, "LLama");
    }

    public static void translateFileEnhanced(String inputFile, String outputFile) throws Exception {
        translateFile(inputFile, outputFile, "LLama");
    }

    public static void translateFileWithFallback(String inputFile, String outputFile) throws Exception {
        translateFile(inputFile, outputFile, "LLama");
    }

    public static boolean areAdvancedModelsAvailable() {
        return true;
    }

    public static String[] getPreferredModels() {
        return new String[]{MODEL_NAME};
    }

    public static void testModelsAvailability() {
        logger.info("🔍 Testando disponibilidade do modelo: " + MODEL_NAME);
    }
}