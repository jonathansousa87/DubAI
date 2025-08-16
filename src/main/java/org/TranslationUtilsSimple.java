package org;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versão ULTRA SIMPLIFICADA que apenas traduz - SEM DEPENDÊNCIAS EXTERNAS
 */
public class TranslationUtilsSimple {

    private static final Logger logger = Logger.getLogger(TranslationUtilsSimple.class.getName());
    
    // Modelo único otimizado
    private static final String[] AVAILABLE_MODELS = {
            "gemma3n:e4b"  // Modelo único para máxima velocidade
    };
    
    // Configurações de timing PROFISSIONAIS para Piper TTS + dublagem
    private static final double CHARS_PER_SECOND_PT = 15.0; // Velocidade realista para português brasileiro (15 chars/segundo)
    private static final double CHARS_PER_SECOND_FAST = 18.0; // Velocidade rápida mas compreensível para blocos curtos
    private static final double CHARS_PER_SECOND_SLOW = 12.0; // Velocidade lenta para blocos complexos
    private static final double TIMING_TOLERANCE = 1.3; // 130% - tolerância profissional para dublagem
    private static final double CRITICAL_TIMING_THRESHOLD = 2.0; // Blocos < 2s são críticos
    
    // Configurações de RETRY e VALIDAÇÃO
    private static final int MAX_RETRY_ATTEMPTS = 2; // Máximo de tentativas para traduções incompletas
    private static final double MIN_COMPLETION_RATIO = 0.3; // 30% do tamanho original mínimo para considerar completa
    private static final String[] INCOMPLETE_INDICATORS = {"\\\\$", "\\\\.\\.\\.$", "\\[\\.\\.\\.]$"}; // Indicadores de texto truncado
    
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "^(\\d{2}):(\\d{2})[.:,](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2})[.:,](\\d{3})$"
    );

    private static class SimpleVTTBlock {
        String timestamp;
        String originalText;
        String translatedText;
        double duration; // Duração em segundos
        double maxCharsForTiming; // Máximo de caracteres que cabem no timing

        SimpleVTTBlock(String timestamp, String text) {
            this.timestamp = timestamp;
            this.originalText = cleanText(text);
            this.duration = calculateDuration(timestamp);
            
            // Cálculo adaptativo de caracteres baseado na duração
            double adaptiveCharsPerSecond = calculateAdaptiveCharsPerSecond(duration, text);
            this.maxCharsForTiming = duration * adaptiveCharsPerSecond * TIMING_TOLERANCE;
        }
        
        /**
         * Calcula velocidade adaptativa de caracteres baseada na duração e complexidade
         */
        private double calculateAdaptiveCharsPerSecond(double duration, String text) {
            // Para blocos muito curtos, permite velocidade ligeiramente maior
            if (duration < CRITICAL_TIMING_THRESHOLD) {
                return CHARS_PER_SECOND_FAST;
            }
            
            // Para blocos longos com conteúdo complexo, usa velocidade menor
            if (duration > 8.0 && isComplexContent(text)) {
                return CHARS_PER_SECOND_SLOW;
            }
            
            return CHARS_PER_SECOND_PT;
        }
        
        /**
         * Detecta conteúdo complexo que precisa de velocidade mais lenta
         */
        private boolean isComplexContent(String text) {
            if (text == null) return false;
            
            String lower = text.toLowerCase();
            
            // Conteúdo técnico ou educacional complexo
            return lower.matches(".*(algorithm|function|method|parameter|configuration|implementation).*") ||
                   lower.matches(".*(explanation|understand|important|remember|specifically).*") ||
                   text.length() > 200; // Textos muito longos
        }
        
        private double calculateDuration(String timestamp) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(timestamp);
            if (matcher.matches()) {
                try {
                    int startM = Integer.parseInt(matcher.group(1));
                    int startS = Integer.parseInt(matcher.group(2));
                    int startMs = Integer.parseInt(matcher.group(3));
                    
                    int endM = Integer.parseInt(matcher.group(4));
                    int endS = Integer.parseInt(matcher.group(5));
                    int endMs = Integer.parseInt(matcher.group(6));
                    
                    double start = startM * 60.0 + startS + startMs / 1000.0;
                    double end = endM * 60.0 + endS + endMs / 1000.0;
                    
                    return Math.max(0.1, end - start); // Mínimo de 0.1s
                } catch (Exception e) {
                    return 3.0; // Padrão: 3 segundos
                }
            }
            return 3.0;
        }
        
        boolean translationFitsInTiming(String translation) {
            if (translation == null) return false;
            return translation.length() <= maxCharsForTiming && 
                   validatePronunciationTiming(translation);
        }
        
        // VALIDAÇÃO DE PRONUNCIAMENTO PARA PIPER TTS
        private boolean validatePronunciationTiming(String translation) {
            if (translation == null) return false;
            
            // Contagem de sílabas aproximada para português brasileiro
            int syllableCount = estimateSyllables(translation);
            
            // Tempo estimado para pronunciar (sílabas por segundo para PT-BR)
            double estimatedTime = syllableCount / 2.5; // 2.5 sílabas por segundo é natural
            
            // Verifica se cabe no timing disponível com tolerância
            return estimatedTime <= (duration * 1.1); // 110% de tolerância
        }
        
        private int estimateSyllables(String text) {
            if (text == null) return 0;
            
            // Remove pontuação e números
            String cleanText = text.toLowerCase()
                                 .replaceAll("[^aeiouáéíóúâêîôûãõàèìòù]", "")
                                 .replaceAll("\\s+", "");
            
            // Conta vogais (aproximação de sílabas em português)
            int vowelCount = cleanText.length();
            
            // Ajustes para ditongos comuns em português brasileiro
            vowelCount -= countOccurrences(cleanText, "ai");
            vowelCount -= countOccurrences(cleanText, "ei");
            vowelCount -= countOccurrences(cleanText, "oi");
            vowelCount -= countOccurrences(cleanText, "ui");
            vowelCount -= countOccurrences(cleanText, "au");
            vowelCount -= countOccurrences(cleanText, "eu");
            vowelCount -= countOccurrences(cleanText, "ou");
            
            return Math.max(1, vowelCount);
        }
        
        private int countOccurrences(String text, String pattern) {
            int count = 0;
            int index = 0;
            while ((index = text.indexOf(pattern, index)) != -1) {
                count++;
                index += pattern.length();
            }
            return count;
        }

        private String cleanText(String text) {
            if (text == null) return "";
            return text.replaceAll("\\[.*?\\]", "")
                      .replaceAll("\\(.*?\\)", "")
                      .replaceAll("<.*?>", "")
                      .replaceAll("\\s+", " ")
                      .trim();
        }

        boolean isEmpty() {
            return originalText == null || originalText.trim().isEmpty();
        }
    }
    
    /**
     * TRADUZ UM BLOCO COM ABORDAGEM SIMPLES E RÁPIDA - VERSÃO ANTI-TRAVAMENTO
     */
    public static String translateBlock(SimpleVTTBlock block) {
        long startTime = System.currentTimeMillis();
        String context = determineOptimalContext(block, null);
        logger.info(String.format("🎯 Contexto escolhido: %s para duração %.1fs", context, block.duration));
        
        // Para ultra_concise, tenta primeiro com fallback direto se muito curto
        if (context.equals("ultra_concise") && block.duration < 1.5) {
            logger.info("⚡ Duração muito curta - usando tradução rápida direta");
            String quickTranslation = translateWithQuickFallback(block.originalText, block.duration);
            if (quickTranslation != null) {
                long elapsedTime = System.currentTimeMillis() - startTime;
                logger.info(String.format("✅ Tradução rápida em %dms: %.50s...", elapsedTime, quickTranslation));
                return quickTranslation;
            }
        }
        
        String translation = translateWithOllamaAdvanced(block.originalText, "gemma3n:e4b", context, block.duration);
        
        // Se conseguiu tradução, retorna diretamente
        if (translation != null && !translation.trim().isEmpty()) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            logger.info(String.format("✅ Tradução bem-sucedida em %dms: %.50s...", elapsedTime, translation));
            return translation;
        } else {
            long elapsedTime = System.currentTimeMillis() - startTime;
            logger.warning(String.format("❌ Primeira tradução falhou após %dms - tentando fallback robusto", elapsedTime));
        }
        
        // Fallback inteligente - tenta contextos menos complexos
        String[] fallbackContexts = {"concise", "normal"};
        for (String fallbackContext : fallbackContexts) {
            if (!fallbackContext.equals(context)) { // Evita repetir o mesmo contexto
                logger.info(String.format("🔄 Tentando fallback com contexto: %s", fallbackContext));
                String fallback = translateWithOllamaAdvanced(block.originalText, "gemma3n:e4b", fallbackContext, block.duration);
                if (fallback != null && !fallback.trim().isEmpty()) {
                    logger.info(String.format("✅ Fallback bem-sucedido (%s): %.50s...", fallbackContext, fallback));
                    return fallback;
                }
            }
        }
        
        // Último recurso: tradução rápida simplificada
        logger.warning("❌ Todos os fallbacks falharam - usando tradução rápida de último recurso");
        String emergencyTranslation = translateWithQuickFallback(block.originalText, block.duration);
        if (emergencyTranslation != null) {
            logger.info(String.format("✅ Tradução de emergência: %.50s...", emergencyTranslation));
            return emergencyTranslation;
        }
        
        // Fracasso total - retorna texto original
        logger.warning("❌ Todos os métodos falharam - usando texto original");
        return block.originalText;
    }
    
    /**
     * TRADUZ BLOCO COM SISTEMA DE RETRY ROBUSTO
     */
    private static String translateBlockWithRetry(SimpleVTTBlock block, int maxAttempts) {
        String context = determineOptimalContext(block, null);
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            logger.info(String.format("🔄 Tentativa %d/%d - Bloco: %.60s...", 
                attempt, maxAttempts, block.originalText));
                
            String translation = translateWithOllamaAdvanced(block.originalText, "gemma3n:e4b", context, block.duration);
            
            if (translation != null) {
                // Valida completude da tradução
                TranslationValidation validation = validateTranslationCompleteness(block.originalText, translation);
                
                if (validation.isValid()) {
                    if (isTranslationValid(translation, block)) {
                        logger.info(String.format("✅ Tradução válida na tentativa %d", attempt));
                        return translation;
                    } else {
                        logger.warning(String.format("⚠️ Tradução completa mas não atende timing - tentativa %d", attempt));
                    }
                } else {
                    logger.warning(String.format("❌ Tradução incompleta na tentativa %d: %s - %s", 
                        attempt, validation.reason(), validation.details()));
                        
                    // Se é truncada, tenta contexto diferente na próxima tentativa
                    if (validation.reason().equals("TRUNCATED") || validation.reason().equals("ABRUPT_ENDING")) {
                        context = adjustContextForRetry(context, attempt);
                        logger.info(String.format("🔄 Ajustando contexto para: %s", context));
                    }
                }
            } else {
                logger.warning(String.format("❌ Falha na tradução na tentativa %d - resposta nula", attempt));
            }
            
            // Pausa entre tentativas (aumenta progressivamente)
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000 * attempt); // 1s, 2s, 3s...
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        // Todas as tentativas falharam - usar fallback
        logger.warning("⚠️ Todas as tentativas falharam, usando fallback");
        return handleFailedTranslation(block);
    }
    
    /**
     * AJUSTA CONTEXTO PARA RETRY baseado no tipo de falha
     */
    private static String adjustContextForRetry(String currentContext, int attemptNumber) {
        return switch (currentContext) {
            case "ultra_concise" -> "concise";           // Relaxa restrições
            case "concise" -> "normal";                 // Mais espaço
            case "normal" -> "detailed";                // Máximo espaço
            case "detailed" -> attemptNumber > 2 ? "normal" : "concise"; // Volta ao meio
            default -> "normal";
        };
    }
    
    /**
     * LIDA COM TRADUÇÕES FALHADAS usando fallbacks
     */
    private static String handleFailedTranslation(SimpleVTTBlock block) {
        // Tenta modelo de fallback mais simple
        String fallbackTranslation = translateWithOllamaAdvanced(block.originalText, "gemma3n:e4b", "normal", block.duration);
        
        if (fallbackTranslation != null) {
            TranslationValidation validation = validateTranslationCompleteness(block.originalText, fallbackTranslation);
            if (validation.isValid()) {
                logger.info("✅ Fallback model produziu tradução válida");
                
                // Ajusta se necessário
                if (fallbackTranslation.length() > block.maxCharsForTiming) {
                    String compressed = compressBestTranslationFast(fallbackTranslation, block);
                    return compressed != null ? compressed : fallbackTranslation.substring(0, (int)block.maxCharsForTiming);
                }
                return fallbackTranslation;
            }
        }
        
        // Último recurso: tradução básica e truncar se necessário
        logger.warning("⚠️ Usando último recurso: tradução básica");
        String basicTranslation = translateWithOllamaAdvanced(block.originalText, "gemma3n:e4b", "normal", block.duration);
        
        if (basicTranslation != null) {
            if (basicTranslation.length() > block.maxCharsForTiming) {
                return basicTranslation.substring(0, (int)block.maxCharsForTiming - 3) + "...";
            }
            return basicTranslation;
        }
        
        // Fracasso total - retorna placeholder
        return "[Tradução não disponível]";
    }

    public static void translateFile(String inputFile, String outputFile, String method) throws Exception {
        logger.info("🌐 TRADUÇÃO SIMPLES: " + inputFile);

        List<String> lines = Files.readAllLines(Paths.get(inputFile), StandardCharsets.UTF_8);
        List<String> outputLines = new ArrayList<>();
        
        // Cabeçalho VTT
        if (!lines.isEmpty() && lines.get(0).trim().equalsIgnoreCase("WEBVTT")) {
            outputLines.add("WEBVTT");
            outputLines.add("");
            lines.remove(0);
            while (!lines.isEmpty() && lines.get(0).trim().isEmpty()) {
                lines.remove(0);
            }
        }

        // Parse simples
        List<SimpleVTTBlock> blocks = parseSimpleBlocks(lines);
        logger.info("📝 Blocos encontrados: " + blocks.size());

        if (blocks.isEmpty()) {
            Files.write(Paths.get(outputFile), outputLines, StandardCharsets.UTF_8);
            return;
        }

        // TRADUZIR SEMPRE - SEM VERIFICAÇÃO DE IDIOMA
        logger.info("🚀 TRADUZINDO TODOS OS " + blocks.size() + " BLOCOS");
        
        for (int i = 0; i < blocks.size(); i++) {
            SimpleVTTBlock block = blocks.get(i);
            if (!block.isEmpty()) {
                logger.info(String.format("🔄 Traduzindo bloco %d/%d: %.50s...", 
                           i+1, blocks.size(), block.originalText));
                
                // USA TRADUÇÃO SIMPLES E RÁPIDA
                String translation = translateBlock(block);
                block.translatedText = translation != null ? translation : block.originalText;
                
                logger.info(String.format("✅ Traduzido: %.50s...", block.translatedText));
            } else {
                block.translatedText = block.originalText;
            }
        }

        // Gerar saída
        for (SimpleVTTBlock block : blocks) {
            outputLines.add(block.timestamp);
            outputLines.add(block.translatedText);
            outputLines.add("");
        }

        Files.write(Paths.get(outputFile), outputLines, StandardCharsets.UTF_8);
        logger.info("✅ Tradução concluída: " + outputFile);
    }

    private static List<SimpleVTTBlock> parseSimpleBlocks(List<String> lines) {
        List<SimpleVTTBlock> blocks = new ArrayList<>();
        String currentTimestamp = null;
        StringBuilder currentText = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            
            if (line.isEmpty() || line.matches("^\\d+$")) {
                continue;
            }

            if (TIMESTAMP_PATTERN.matcher(line).matches()) {
                // Salva bloco anterior
                if (currentTimestamp != null && currentText.length() > 0) {
                    String textBlock = currentText.toString().trim();
                    if (!textBlock.isEmpty()) {
                        SimpleVTTBlock block = new SimpleVTTBlock(currentTimestamp, textBlock);
                        if (!block.isEmpty()) {
                            blocks.add(block);
                        }
                    }
                    currentText.setLength(0);
                }
                currentTimestamp = line;
            } else if (currentTimestamp != null) {
                if (currentText.length() > 0) {
                    currentText.append(" ");
                }
                currentText.append(line);
            }
        }

        // Último bloco
        if (currentTimestamp != null && currentText.length() > 0) {
            String textBlock = currentText.toString().trim();
            if (!textBlock.isEmpty()) {
                SimpleVTTBlock block = new SimpleVTTBlock(currentTimestamp, textBlock);
                if (!block.isEmpty()) {
                    blocks.add(block);
                }
            }
        }

        return blocks;
    }

    /**
     * Tradução RÁPIDA - usa apenas o melhor modelo sem validações complexas
     */
    private static String translateWithOllamaFast(String text) {
        Path tempFile = null;
        try {
            // Usa prompt simples e direto
            String prompt = "Traduza para português brasileiro coloquial. Responda APENAS com a tradução: " + text;
            
            // Usa modelo com boa qualidade e velocidade
            String model = "gemma3n:e4b"; // Melhor balanço qualidade/velocidade
            
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0.1}}",
                model, escapeForJson(prompt)
            );
            
            tempFile = Files.createTempFile("ollama_fast", ".json");
            Files.write(tempFile, jsonPayload.getBytes(StandardCharsets.UTF_8));
            
            String curlCommand = String.format(
                "curl -s -X POST http://localhost:11434/api/chat " +
                "-H \"Content-Type: application/json\" " +
                "-d @%s",
                tempFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS); // Timeout mais curto
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }

            String responseStr = response.toString();
            Pattern responsePattern = Pattern.compile("\"content\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);

            if (matcher.find()) {
                String content = matcher.group(1);
                
                // PRÉ-LIMPEZA: Remove tokens Unicode antes do unescape
                content = content.replaceAll("\\\\u003[cC].*?\\\\u003[eE]", ""); // Remove tokens completos
                
                // ORDEM CORRETA: escape duplo primeiro, depois simples
                content = content.replace("\\\\", "\\")
                               .replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .trim();
                
                // PÓS-LIMPEZA: Remove tokens que surgiram após unescape
                content = cleanAIThoughts(content);
                               
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }

            return null;

        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                // Ignora
            }
        }
    }

    /**
     * SISTEMA ADAPTATIVO INTELIGENTE - ajusta contexto baseado no timing e melhora iterativamente
     */
    private static String translateWithTimingValidation(SimpleVTTBlock block) {
        logger.info(String.format("🎯 Timing: %.1fs (máx %d chars)", 
                   block.duration, (int)block.maxCharsForTiming));
        
        String previousTranslation = null;
        String bestTranslation = null;
        int bestScore = -1; // Score baseado em qualidade vs timing
        
        for (int i = 0; i < AVAILABLE_MODELS.length; i++) {
            String model = AVAILABLE_MODELS[i];
            
            // DETERMINA CONTEXTO INTELIGENTE baseado no timing disponível
            String context = determineOptimalContext(block, previousTranslation);
            
            // TRADUZ usando contexto adaptativo
            String translation;
            if (previousTranslation == null) {
                // Primeira tentativa - tradução normal
                translation = translateWithAdaptiveContext(block.originalText, model, context, block.duration);
            } else {
                // Tentativas seguintes - melhora tradução anterior
                translation = improveTranslationBasedOnTiming(
                    block.originalText, previousTranslation, model, context, block.duration, block.maxCharsForTiming
                );
            }
            
            if (translation != null) {
                int score = evaluateTranslationQuality(translation, block);
                
                // Atualiza melhor tradução baseado no score
                if (score > bestScore) {
                    bestTranslation = translation;
                    bestScore = score;
                }
                
                // Se cabe perfeitamente no timing, usa imediatamente
                if (block.translationFitsInTiming(translation)) {
                    logger.info(String.format("✅ PERFEITO: %s + %s (%d chars, score: %d)", 
                               model, context, translation.length(), score));
                    return translation;
                } else {
                    logger.info(String.format("📝 Candidato: %s + %s (%d chars, score: %d) - %s", 
                               model, context, translation.length(), score, 
                               translation.length() > block.maxCharsForTiming ? "muito longo" : "timing OK"));
                }
                
                previousTranslation = translation;
            }
        }
        
        // FALLBACK ULTRA-AGRESSIVO - APENAS se timing estiver muito próximo (reduzir timeouts)
        if (bestTranslation != null && !block.translationFitsInTiming(bestTranslation)) {
            double excess = (bestTranslation.length() - block.maxCharsForTiming) / block.maxCharsForTiming;
            
            // Só tenta compressão se excesso for pequeno (< 50%), senão é perda de tempo
            if (excess < 0.5) {
                logger.warning("🆘 Tentando compressão rápida (excesso: " + String.format("%.1f%%", excess * 100) + ")...");
                String compressedTranslation = compressBestTranslationFast(bestTranslation, block);
                if (compressedTranslation != null && block.translationFitsInTiming(compressedTranslation)) {
                    logger.info("✅ Compressão funcionou: " + compressedTranslation.length() + " chars");
                    return compressedTranslation;
                }
            } else {
                logger.warning("🚫 Excesso muito grande (" + String.format("%.1f%%", excess * 100) + "), pulando compressão");
            }
        }
        
        // Retorna melhor tradução disponível
        if (bestTranslation != null) {
            logger.warning("🆘 USANDO MELHOR TRADUÇÃO (score: " + bestScore + ")");
            return bestTranslation;
        }
        
        // Último recurso: texto original
        logger.warning("🆘 USANDO TEXTO ORIGINAL - nenhuma tradução foi gerada");
        return block.originalText;
    }

    /**
     * DETERMINA CONTEXTO OPTIMAL baseado no timing, TTS e tradução anterior
     */
    private static String determineOptimalContext(SimpleVTTBlock block, String previousTranslation) {
        double availableRatio = block.maxCharsForTiming / Math.max(1, block.originalText.length());
        double ttsOptimalChars = block.duration * CHARS_PER_SECOND_PT; // Chars ideais para TTS natural
        
        // Análise do contexto da fala baseado no texto original
        String speechContext = analyzeSpeechContext(block.originalText);
        ContentPriority priority = analyzeContentPriority(block.originalText);
        
        if (previousTranslation != null) {
            // Adaptação baseada na tentativa anterior COM PROTEÇÃO DE CONTEÚDO ESSENCIAL
            double excessRatio = previousTranslation.length() / block.maxCharsForTiming;
            
            if (excessRatio > 1.4) {
                // Só comprime drasticamente se não for conteúdo crítico
                if (priority == ContentPriority.CRITICAL) {
                    return "concise"; // Mantém mais informação para conteúdo crítico
                } else {
                    return "ultra_concise";
                }
            } else if (excessRatio > 1.15) {
                return "concise";
            } else if (excessRatio < 0.5) {
                // Expansão inteligente baseada no contexto e duração
                if (block.duration > 8.0 && speechContext.equals("instructional")) {
                    return "detailed"; // Aproveita tempo extra para educação
                } else if (block.duration > 5.0) {
                    return "normal";
                } else {
                    return "concise"; // Mantém conciso para blocos curtos mesmo com espaço
                }
            }
        }
        
        // ANÁLISE CRÍTICA: Blocos muito curtos precisam estratégia especial
        if (block.duration < CRITICAL_TIMING_THRESHOLD) {
            if (priority == ContentPriority.CRITICAL) {
                return "concise"; // Mantém essencial mesmo em tempo curto
            } else {
                return "ultra_concise"; // Máxima compressão para não-críticos
            }
        }
        
        // Análise dinâmica baseada no tipo de conteúdo e timing
        if (speechContext.equals("technical")) {
            // Conteúdo técnico: prioriza clareza sobre velocidade
            if (availableRatio < 0.8 && priority != ContentPriority.CRITICAL) {
                return "concise";
            } else if (availableRatio > 1.4) {
                return "detailed"; // Aproveita tempo para explicar melhor conceitos técnicos
            } else {
                return "normal";
            }
        } else if (speechContext.equals("conversational")) {
            // Conteúdo conversacional: pode comprimir mais naturalmente
            if (availableRatio < 0.9) {
                return "ultra_concise"; // Contrações e linguagem informal natural
            } else if (availableRatio < 1.4) {
                return "concise";
            } else {
                return "normal"; // Evita over-expansion em conversas
            }
        } else if (speechContext.equals("instructional")) {
            // Conteúdo instrucional: equilibra compressão com clareza educacional
            if (availableRatio < 0.85) {
                return priority == ContentPriority.CRITICAL ? "concise" : "ultra_concise";
            } else if (availableRatio > 1.6) {
                return "detailed"; // Expansão educacional valiosa
            } else {
                return "normal";
            }
        } else if (speechContext.equals("enthusiastic")) {
            // Conteúdo entusiástico: mantém energia mesmo comprimindo
            if (availableRatio < 0.8) {
                return "concise"; // Comprime mas mantém entusiasmo
            } else {
                return "normal";
            }
        }
        
        // Fallback baseado no timing disponível (método original aprimorado)
        if (availableRatio < 0.75) {
            return priority == ContentPriority.CRITICAL ? "concise" : "ultra_concise";
        } else if (availableRatio < 1.2) {
            return "concise";
        } else if (availableRatio < 1.7) {
            return "normal";
        } else {
            return "detailed"; // Aproveita tempo abundante
        }
    }
    
    /**
     * Enum para prioridade de conteúdo
     */
    private enum ContentPriority {
        CRITICAL,    // Informação essencial que não pode ser perdida
        IMPORTANT,   // Informação importante mas comprimível
        NORMAL,      // Informação padrão
        FILLER       // Palavras de preenchimento que podem ser removidas
    }
    
    /**
     * ANALISA PRIORIDADE DO CONTEÚDO para proteção de informação essencial
     */
    private static ContentPriority analyzeContentPriority(String text) {
        if (text == null) return ContentPriority.NORMAL;
        
        String lowerText = text.toLowerCase();
        
        // Conteúdo CRÍTICO - nunca deve ser perdido
        if (lowerText.matches(".*(important|critical|essential|must|required|necessary).*") ||
            lowerText.matches(".*(error|warning|caution|danger|alert).*") ||
            lowerText.matches(".*(step [0-9]|first|second|third|finally|conclusion).*") ||
            lowerText.matches(".*(definition|means|is defined as|refers to).*")) {
            return ContentPriority.CRITICAL;
        }
        
        // Conteúdo IMPORTANTE - pode ser comprimido mas deve ser preservado
        if (lowerText.matches(".*(because|therefore|however|but|although|since).*") ||
            lowerText.matches(".*(example|for instance|such as|like).*") ||
            lowerText.matches(".*(remember|note|notice|observe).*")) {
            return ContentPriority.IMPORTANT;
        }
        
        // Palavras de PREENCHIMENTO - podem ser removidas
        if (lowerText.matches(".*(well|um|uh|you know|basically|actually|really|very|quite).*") ||
            lowerText.matches(".*(sort of|kind of|pretty much|i mean|right).*")) {
            return ContentPriority.FILLER;
        }
        
        return ContentPriority.NORMAL;
    }
    
    /**
     * ANALISA CONTEXTO DA FALA para adaptação dinâmica
     */
    private static String analyzeSpeechContext(String text) {
        if (text == null) return "normal";
        
        String lowerText = text.toLowerCase();
        
        // Identificar conteúdo técnico
        if (lowerText.matches(".*(function|method|class|algorithm|code|syntax|variable|parameter|debug).*") ||
            lowerText.matches(".*(configure|install|setup|compile|execute|run|build).*")) {
            return "technical";
        }
        
        // Identificar conteúdo instrucional
        if (lowerText.matches(".*(let's|how to|step|first|next|then|finally|remember|make sure).*") ||
            lowerText.matches(".*(now|so|here|this is|what we|you need|important).*")) {
            return "instructional";
        }
        
        // Identificar conteúdo conversacional
        if (lowerText.matches(".*(you know|well|actually|basically|pretty|really|kind of).*") ||
            lowerText.matches(".*(right|okay|cool|awesome|great|nice).*")) {
            return "conversational";
        }
        
        // Identificar emoção/entusiasmo (precisa manter energia na tradução)
        if (lowerText.matches(".*(amazing|incredible|fantastic|awesome|wow|great|excellent).*") ||
            lowerText.matches(".*(love|excited|enjoy|fun|cool|interesting).*")) {
            return "enthusiastic";
        }
        
        return "normal";
    }
    
    /**
     * TRADUÇÃO COM CONTEXTO ADAPTATIVO
     */
    private static String translateWithAdaptiveContext(String text, String model, String context, double duration) {
        return translateWithOllamaAdvanced(text, model, context, duration);
    }
    
    /**
     * MELHORA TRADUÇÃO ANTERIOR baseada no timing e contexto
     */
    private static String improveTranslationBasedOnTiming(String originalText, String previousTranslation, 
                                                         String model, String context, double duration, double maxChars) {
        Path tempFile = null;
        try {
            // Monta prompt de melhoria inteligente
            String improvementPrompt = buildImprovementPrompt(originalText, previousTranslation, context, duration, maxChars);
            
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0.1}}",
                model, escapeForJson(improvementPrompt)
            );
            
            tempFile = Files.createTempFile("ollama_improve", ".json");
            Files.write(tempFile, jsonPayload.getBytes(StandardCharsets.UTF_8));
            
            String curlCommand = String.format(
                "curl -s -X POST http://localhost:11434/api/chat " +
                "-H \"Content-Type: application/json\" " +
                "-d @%s",
                tempFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }

            String responseStr = response.toString();
            Pattern responsePattern = Pattern.compile("\"content\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);

            if (matcher.find()) {
                String content = matcher.group(1);
                // PRÉ-LIMPEZA: Remove tokens Unicode antes do unescape
                content = content.replaceAll("\\\\u003[cC].*?\\\\u003[eE]", ""); // Remove tokens completos
                
                // ORDEM CORRETA: escape duplo primeiro, depois simples
                content = content.replace("\\\\", "\\")
                               .replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .trim();
                               
                // PÓS-LIMPEZA: Remove tokens que surgiram após unescape
                content = cleanAIThoughts(content);
                               
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }

            return null;

        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                // Ignora
            }
        }
    }
    
    /**
     * AVALIA QUALIDADE DA TRADUÇÃO (score 0-100)
     */
    private static int evaluateTranslationQuality(String translation, SimpleVTTBlock block) {
        if (translation == null || translation.trim().isEmpty()) {
            return 0;
        }
        
        int score = 50; // Base score
        
        // Bônus se cabe no timing perfeitamente
        if (block.translationFitsInTiming(translation)) {
            score += 30;
        } else {
            // Penalidade baseada no quanto ultrapassa
            double excess = (translation.length() - block.maxCharsForTiming) / block.maxCharsForTiming;
            if (excess > 0) {
                score -= Math.min(25, (int)(excess * 50)); // Penalidade máxima de 25 pontos
            }
        }
        
        // Bônus por qualidade da tradução
        if (!translation.equals(block.originalText)) {
            score += 15; // Bônus por traduzir (vs manter original)
        }
        
        // Bônus por naturalidade (presença de contrações brasileiras)
        if (translation.contains("pra") || translation.contains("tá") || translation.contains("né") || 
            translation.contains("cê") || translation.contains("vamo")) {
            score += 5;
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    /**
     * COMPRESSÃO INTELIGENTE E RÁPIDA com preservação de significado
     */
    private static String compressBestTranslationFast(String bestTranslation, SimpleVTTBlock block) {
        Path tempFile = null;
        try {
            // Analisa o que deve ser preservado antes de comprimir
            String essentialContent = extractEssentialContent(block.originalText);
            ContentPriority priority = analyzeContentPriority(block.originalText);
            
            String compressionPrompt = String.format(
                "INTELLIGENT FAST COMPRESSION: Rewrite to fit %d chars (%.1fs) while preserving core meaning. " +
                "\n\nESSENTIAL TO PRESERVE: %s" +
                "\n\nCOMPRESSION PRIORITY: %s" +
                "\n\nSMART COMPRESSION RULES: " +
                "- KEEP all essential information and key concepts " +
                "- REMOVE only filler words and redundancies " +
                "- USE efficient contractions: 'pra', 'tá', 'cê', 'né' " +
                "- ADD strategic comma for TTS breathing if needed " +
                "- ENSURE natural pronunciation flow " +
                "\n\nCurrent translation: %s\n\n" +
                "PROVIDE COMPRESSED VERSION THAT PRESERVES MEANING:",
                (int)block.maxCharsForTiming, block.duration, essentialContent, 
                priority == ContentPriority.CRITICAL ? "CRITICAL - Preserve all key information" : "NORMAL - Standard compression",
                bestTranslation
            );
            
            // Usa modelo mais rápido
            String model = AVAILABLE_MODELS[0];
            
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0.0}}",
                model, escapeForJson(compressionPrompt)
            );
            
            tempFile = Files.createTempFile("ollama_fast_compress", ".json");
            Files.write(tempFile, jsonPayload.getBytes(StandardCharsets.UTF_8));
            
            String curlCommand = String.format(
                "curl -s -X POST http://localhost:11434/api/chat " +
                "-H \"Content-Type: application/json\" " +
                "-d @%s",
                tempFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // TIMEOUT MUITO REDUZIDO - máximo 30 segundos
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warning("⏰ Timeout na compressão rápida (30s)");
                return null;
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }

            String responseStr = response.toString();
            Pattern responsePattern = Pattern.compile("\"content\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);

            if (matcher.find()) {
                String content = matcher.group(1);
                // PRÉ-LIMPEZA: Remove tokens Unicode antes do unescape
                content = content.replaceAll("\\\\u003[cC].*?\\\\u003[eE]", ""); // Remove tokens completos
                
                // ORDEM CORRETA: escape duplo primeiro, depois simples
                content = content.replace("\\\\", "\\")
                               .replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .trim();
                               
                // PÓS-LIMPEZA: Remove tokens que surgiram após unescape
                content = cleanAIThoughts(content);
                               
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }

            return null;

        } catch (Exception e) {
            logger.warning("❌ Erro na compressão rápida: " + e.getMessage());
            return null;
        } finally {
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                // Ignora
            }
        }
    }

    
    /**
     * MONTA PROMPT DE MELHORIA INTELIGENTE com preservação de conteúdo
     */
    private static String buildImprovementPrompt(String originalText, String previousTranslation, 
                                               String context, double duration, double maxChars) {
        String basePrompt = buildSystemPrompt();
        String improvementInstruction = "";
        
        // Analisa o que deve ser preservado no conteúdo
        String essentialContent = extractEssentialContent(originalText);
        
        switch (context) {
            case "ultra_concise":
                improvementInstruction = String.format(
                    "\n\nINTELLIGENT COMPRESSION REQUIRED: Previous translation exceeded timing constraints. " +
                    "Rewrite to fit MAXIMUM %d characters (%.1fs) while preserving CORE MEANING. " +
                    "\n\nESSENTIAL CONTENT TO PRESERVE: %s" +
                    "\n\nCOMPRESSION STRATEGY: " +
                    "- KEEP essential concepts and keywords " +
                    "- REMOVE filler words (actually, basically, well, you know) " +
                    "- USE natural contractions: 'pra', 'tá', 'cê' " +
                    "- COMBINE related ideas into single phrases " +
                    "- ENSURE TTS-friendly pronunciation " +
                    "- ADD strategic commas ONLY where necessary for breathing " +
                    "\n\nOriginal: %s\n" +
                    "Previous (too long): %s\n\n" +
                    "PROVIDE THE COMPRESSED VERSION THAT PRESERVES MEANING:",
                    (int)maxChars, duration, essentialContent, originalText, previousTranslation);
                break;
                
            case "concise":
                improvementInstruction = String.format(
                    "\n\nBALANCED COMPRESSION: Previous translation needs adjustment for %.1fs timing. " +
                    "Rewrite to fit %d characters while maintaining educational value. " +
                    "\n\nESSENTIAL CONTENT: %s" +
                    "\n\nREVISION STRATEGY: " +
                    "- Preserve all key concepts and important information " +
                    "- Use more concise but natural phrasing " +
                    "- Include strategic pauses (1-2 commas) for TTS emphasis " +
                    "- Choose shorter but equivalent words when possible " +
                    "- Maintain natural Brazilian Portuguese flow " +
                    "\n\nOriginal: %s\n" +
                    "Previous: %s\n\n" +
                    "PROVIDE THE IMPROVED CONCISE VERSION:",
                    duration, (int)maxChars, essentialContent, originalText, previousTranslation);
                break;
                
            case "detailed":
                improvementInstruction = String.format(
                    "\n\nEDUCATIONAL EXPANSION: Previous translation was too brief for %.1fs available. " +
                    "Expand to %d characters maximum with valuable educational content. " +
                    "\n\nCORE CONTENT: %s" +
                    "\n\nEXPANSION STRATEGY: " +
                    "- Add helpful explanations and context " +
                    "- Include educational connectors and transitions " +
                    "- Use descriptive language that aids comprehension " +
                    "- Add strategic pauses (2-3 commas) for TTS pacing " +
                    "- Expand with meaningful content, not padding " +
                    "- Ensure natural TTS delivery rhythm " +
                    "\n\nOriginal: %s\n" +
                    "Previous (too short): %s\n\n" +
                    "PROVIDE THE EDUCATIONALLY EXPANDED VERSION:",
                    duration, (int)maxChars, essentialContent, originalText, previousTranslation);
                break;
                
            default: // normal
                improvementInstruction = String.format(
                    "\n\nTIMING OPTIMIZATION: Fine-tune translation for perfect %.1fs timing (%d chars max). " +
                    "\n\nKEY CONTENT: %s" +
                    "\n\nOPTIMIZATION FOCUS: " +
                    "- Perfect balance between content and timing " +
                    "- Natural TTS pronunciation and flow " +
                    "- Strategic comma placement for breathing and emphasis " +
                    "- Maintain educational clarity and engagement " +
                    "\n\nOriginal: %s\n" +
                    "Previous: %s\n\n" +
                    "PROVIDE THE TIMING-OPTIMIZED VERSION:",
                    duration, (int)maxChars, essentialContent, originalText, previousTranslation);
                break;
        }
        
        return basePrompt + improvementInstruction;
    }
    
    /**
     * VALIDA COMPLETUDE DA TRADUÇÃO
     */
    private static TranslationValidation validateTranslationCompleteness(String originalText, String translation) {
        if (translation == null || translation.trim().isEmpty()) {
            return new TranslationValidation(false, "EMPTY_TRANSLATION", "Tradução vazia ou nula");
        }
        
        String cleanTranslation = translation.trim();
        
        // 1. Verifica indicadores de truncamento
        for (String indicator : INCOMPLETE_INDICATORS) {
            if (cleanTranslation.matches(".*" + indicator + "\\s*$")) {
                return new TranslationValidation(false, "TRUNCATED", 
                    "Termina com indicador de truncamento: " + indicator);
            }
        }
        
        // 2. Verifica se termina abruptamente (sem pontuação) - mais tolerância
        if (!cleanTranslation.matches(".*[.!?;:,]\\s*$") && cleanTranslation.length() > 50) {
            // Verifica se não é uma frase naturalmente curta ou palavra isolada
            if (!cleanTranslation.matches(".*(sim|não|ok|certo|muito bem|perfeito|legal|[A-Za-zà-ÿ]+)\\s*$")) {
                return new TranslationValidation(false, "ABRUPT_ENDING", 
                    "Termina abruptamente sem pontuação");
            }
        }
        
        // 3. Verifica proporção de tamanho (muito curta pode indicar incompletude) - mais tolerância
        double completionRatio = (double) cleanTranslation.length() / Math.max(1, originalText.length());
        if (completionRatio < MIN_COMPLETION_RATIO && originalText.length() > 100 && cleanTranslation.length() < 10) {
            return new TranslationValidation(false, "TOO_SHORT", 
                String.format("Tradução muito curta: %.1f%% do original", completionRatio * 100));
        }
        
        // 4. Verifica estrutura de sentença básica
        if (cleanTranslation.length() > 20 && !cleanTranslation.matches(".*\\s.*")) {
            return new TranslationValidation(false, "MALFORMED", 
                "Tradução parece malformada (sem espaços)");
        }
        
        // 5. Verifica se contém tokens de controle residuais
        if (cleanTranslation.matches(".*(\\\\u[0-9a-fA-F]+|<[^>]*>|\\\\[ntr]|\\\\\\\\).*")) {
            return new TranslationValidation(false, "CONTROL_TOKENS", 
                "Contém tokens de controle não processados");
        }
        
        return new TranslationValidation(true, "COMPLETE", "Tradução completa e válida");
    }
    
    /**
     * Record para resultado de validação
     */
    private record TranslationValidation(boolean isValid, String reason, String details) {}
    
    /**
     * VALIDA SE TRADUÇÃO ATENDE CRITÉRIOS DE TIMING
     */
    private static boolean isTranslationValid(String translation, SimpleVTTBlock block) {
        if (translation == null || translation.trim().isEmpty()) {
            return false;
        }
        
        // Verifica se respeita o limite de caracteres para timing
        if (translation.length() > block.maxCharsForTiming * 1.1) { // 10% de tolerância
            return false;
        }
        
        // Verifica se não é muito curta (pode indicar erro)
        if (translation.length() < 3) {
            return false;
        }
        
        return true;
    }
    
    /**
     * EXTRAI CONTEÚDO ESSENCIAL que deve ser preservado
     */
    private static String extractEssentialContent(String text) {
        if (text == null) return "";
        
        // Identifica elementos críticos que não podem ser perdidos
        String essential = "";
        String lowerText = text.toLowerCase();
        
        // Conceitos técnicos importantes
        if (lowerText.matches(".*(function|method|class|variable|parameter|algorithm).*")) {
            essential += "[Technical concepts] ";
        }
        
        // Instruções sequenciais
        if (lowerText.matches(".*(step|first|second|then|next|finally).*")) {
            essential += "[Sequential instructions] ";
        }
        
        // Avisos ou informações críticas
        if (lowerText.matches(".*(important|warning|note|remember|must|required).*")) {
            essential += "[Critical information] ";
        }
        
        // Definições ou explicações
        if (lowerText.matches(".*(means|defined as|refers to|is called).*")) {
            essential += "[Definitions] ";
        }
        
        // Exemplos
        if (lowerText.matches(".*(example|for instance|such as|like).*")) {
            essential += "[Examples] ";
        }
        
        return essential.trim().isEmpty() ? "[Main educational content]" : essential.trim();
    }

    private static String buildSystemPrompt() {
        return """
            You are an expert dubbing and localization specialist for educational content from English to Brazilian Portuguese.
            
            OBJECTIVE: Translate and CORRECT educational video subtitles with natural, professional Brazilian Portuguese optimized for TTS dubbing systems and timestamp synchronization.
            
            CRITICAL FIRST STEP - TRANSCRIPTION CORRECTION:
            Before translating, ANALYZE the English text for transcription errors and correct them based on context:
            - Fix obvious speech recognition mistakes (homophones, missing words, garbled text)
            - Correct incomplete sentences that make contextual sense
            - Fix technical terms that were misheard (e.g., "react" instead of "re-act", "JavaScript" instead of "java script")
            - Maintain the original meaning while ensuring grammatical correctness
            - If text seems nonsensical, infer the most likely intended meaning from context
            
            DYNAMIC TIMING ADAPTATION RULES:
            1. TIMESTAMP FLEXIBILITY: Adapt translation length based on available subtitle duration
               - For SHORT timestamps (< 2 seconds): Use concise, direct translations with natural Brazilian contractions
               - For MEDIUM timestamps (2-4 seconds): Provide balanced, natural translations maintaining educational clarity
               - For LONG timestamps (> 4 seconds): Expand naturally without padding, maintaining engagement and flow
            
            2. TTS RHYTHM OPTIMIZATION: Structure sentences for natural Text-to-Speech delivery
               - Use strategic COMMA placement for natural breathing pauses in TTS
               - Avoid complex consonant clusters that make TTS sound robotic
               - Balance syllable distribution for smooth TTS pronunciation rhythm
               - Consider natural Brazilian Portuguese stress patterns and intonation
            
            3. CHARACTER COUNT EFFICIENCY: Optimize translation density for TTS timing
               - Calculate approximately 15 characters per second for natural Brazilian Portuguese TTS
               - Prioritize information density while maintaining naturalness
               - Use strategic abbreviations and contractions when appropriate for timing
               - Ensure essential educational content is never sacrificed for brevity
            
            4. ADAPTIVE SPEED CONSIDERATIONS: Account for variable TTS delivery speeds
               - For fast delivery needs: Use shorter words, simpler sentence structures
               - For slower delivery needs: Allow for more descriptive, detailed explanations
               - Maintain comprehension regardless of playback speed adjustments
            
            TRANSLATION RULES:
            1. Use natural Brazilian Portuguese with moderate colloquial level appropriate for educational content
            2. Maintain approximate lip sync timing when possible for dubbing synchronization
            3. Completely preserve the original emotion, emphasis, and pedagogical intention
            4. Use MODERATE contractions strategically for natural flow and timing: "para" → "pra", "está" → "tá"
            5. Adapt idiomatic expressions to culturally relevant Brazilian equivalents
            6. DYNAMICALLY respect timing constraints while maintaining educational clarity
            7. Balance accessibility with professionalism - avoid excessive regional slang
            
            ENHANCED STYLE FOR EDUCATIONAL CONTENT:
            - Clear, didactic but approachable language that maintains student engagement
            - Preserve technical terms when they enhance comprehension, simplify when timing is tight
            - Use natural Brazilian connectors that aid comprehension: "então", "assim", "dessa forma", "ou seja"
            - Maintain natural Brazilian speech rhythm with appropriate emphasis for TTS
            - Transform rhetorical questions to engage Brazilian learners effectively
            - Ensure conceptual clarity is prioritized, with timing optimization as secondary consideration
            
            CONTEXTUAL SPEECH ADAPTATION:
            - Analyze the apparent speaking pace from the original content context
            - For instructional segments: Use clear, measured language suitable for learning
            - For enthusiastic segments: Maintain energy while ensuring TTS can convey excitement naturally
            - For technical explanations: Prioritize clarity over speed, using natural pauses
            - For casual conversations: Use appropriate Brazilian colloquialisms that enhance relatability
            
            REGIONAL BRAZILIAN CONSIDERATIONS:
            - Use neutral Brazilian Portuguese that works across all regions
            - Incorporate mild colloquialisms that enhance relatability without alienating any region
            - Consider how technical terms are commonly used in Brazilian educational contexts
            - Adapt cultural references to Brazilian equivalents when necessary
            - Use pronunciation-friendly alternatives for complex terms
            
            NATURAL ADAPTATIONS FOR BRAZILIAN CONTEXT:
            "you know" → "sabe" or "você sabe" (maintains conversational tone, good for timing)
            "hands-on practice" → "prática hands-on" or "colocar a mão na massa" (culturally relevant)
            "let's dive into" → "vamos explorar" or "vamos mergulhar em" (maintains energy, timing-flexible)
            "that's awesome" → "isso é incrível" or "que massa" (age-appropriate enthusiasm)
            "make sure you" → "certifique-se de" or "não esqueça de" (instructional clarity)
            "so basically" → "então basicamente" or "resumindo" (maintains flow, timing-conscious)
            "all right" → "certo" or "muito bem" (natural transitions, good for TTS)
            "let's get started" → "vamos começar" or "mãos à obra" (energetic beginnings)
            
            ENHANCED PROCESS:
            1. Analyze English text for transcription errors and correct contextually
            2. Identify key educational concepts that must be preserved
            3. Assess implied speaking pace and energy level from context
            4. Consider TTS pronunciation and rhythm requirements
            5. Translate with attention to Brazilian cultural context and speech patterns
            6. Optimize for natural TTS delivery while maintaining educational value
            7. Respond ONLY with the final Portuguese translation
            
            CRITICAL REQUIREMENTS: 
            - NO explanations, reasoning, comments, or extra text in response
            - NO control tokens like </end_of_turn> or similar AI markup
            - Prioritize natural speech flow for TTS systems while respecting timing constraints
            - Maintain educational clarity and engagement as primary goal
            - Ensure cultural appropriateness for Brazilian learners
            - Adapt translation length and complexity based on available subtitle duration
            - PRESERVE technical terms, file names, and code references exactly (e.g., 'not-found.tsx')
            - Use smooth compression: prefer shorter phrases from start rather than aggressive cutting
            - Ensure every translation is complete and naturally flows into next subtitle
            """;
    }

    private static String buildContextualPrompt(String contextType, double duration) {
        String basePrompt = buildSystemPrompt();
        String contextInstruction = "";
        
        // Cálculos adaptativos para diferentes estratégias
        double targetChars = duration < CRITICAL_TIMING_THRESHOLD ? 
                           duration * CHARS_PER_SECOND_FAST : 
                           duration * CHARS_PER_SECOND_PT;
        
        switch (contextType) {
            case "ultra_concise":
                contextInstruction = String.format(
                    "\n\nULTRA TTS CONSTRAINT: ONLY %.1f seconds - SMOOTH COMPRESSION required. " +
                    "PRESERVE CORE MEANING with natural efficiency: " +
                    "\n\nSMOOTH COMPRESSION STRATEGY: " +
                    "- START WITH CONCISE PHRASING: Create naturally short sentences from beginning " +
                    "- PRESERVE technical terms, file names, code references EXACTLY (e.g., 'not-found.tsx') " +
                    "- REMOVE only filler words: 'well', 'actually', 'basically', 'you know', 'sort of' " +
                    "- USE TTS-optimized words: 'fácil' not 'simplificado', 'usar' not 'utilizar' " +
                    "- COMBINE ideas smoothly: 'Agora vamos...' instead of 'Agora nós vamos...' " +
                    "- ONE strategic comma for TTS breathing, placed before key concepts " +
                    "- Target ~%.0f characters maximum with natural flow " +
                    "- ENSURE translation is COMPLETE and ready for next subtitle " +
                    "- RESPOND QUICKLY - avoid complex analysis to prevent timeouts " +
                    "\n\nCRITICAL: Prioritize SPEED and natural short phrasing over complex optimization",
                    duration, targetChars * 0.8); // 80% da capacidade para segurança máxima
                break;
                
            case "concise":
                contextInstruction = String.format(
                    "\n\nTTS TIMING: %.1f seconds - BALANCED NATURAL PHRASING with meaning preservation. " +
                    "\n\nNATURAL COMPRESSION RULES: " +
                    "- START with naturally concise phrasing rather than cutting later " +
                    "- PRESERVE ALL essential information, technical terms, and file names " +
                    "- REMOVE only truly redundant words and excessive descriptors " +
                    "- USE natural Brazilian contractions appropriately: 'pra', 'tá', 'cê' " +
                    "- INCLUDE 1-2 strategic pauses (commas) for TTS breathing and emphasis " +
                    "- CHOOSE words with clear TTS pronunciation and natural rhythm " +
                    "- TARGET ~%.0f characters for comfortable delivery " +
                    "- ENSURE complete sentences that flow naturally into next subtitle " +
                    "\n\nRHYTHM STRATEGY: Balance brevity with natural speech patterns",
                    duration, targetChars);
                break;
                
            case "normal":
                contextInstruction = String.format(
                    "\n\nOPTIMAL TTS TIMING: %.1f seconds - NATURAL Brazilian Portuguese delivery. " +
                    "\n\nNATURAL FLOW OPTIMIZATION: " +
                    "- Use complete, well-structured sentences with natural rhythm " +
                    "- Include appropriate pauses (2-3 commas) for TTS breath control and phrasing " +
                    "- Balance syllable distribution for smooth TTS pronunciation " +
                    "- Use varied sentence length for engaging TTS delivery " +
                    "- Target ~%.0f characters for comfortable, clear delivery " +
                    "- Emphasize educational concepts through strategic phrasing " +
                    "\n\nEMPHASIS STRATEGY: Use natural Brazilian Portuguese stress patterns",
                    duration, targetChars);
                break;
                
            case "detailed":
                contextInstruction = String.format(
                    "\n\nEXTENDED TTS TIMING: %.1f seconds - COMPREHENSIVE delivery with rich content. " +
                    "\n\nEXPANSION STRATEGY: " +
                    "- Add valuable educational context and explanations " +
                    "- Use descriptive language that enhances understanding " +
                    "- Include natural connectors and transitions for TTS flow " +
                    "- Add clarifying phrases that improve comprehension " +
                    "- Use strategic pauses (3-4 commas) for TTS emphasis and pacing " +
                    "- Target ~%.0f characters for rich, informative delivery " +
                    "\n\nEDUCATIONAL ENHANCEMENT: Expand with meaningful context, not just padding",
                    duration, targetChars * 1.2); // 120% para aproveitar tempo extra
                break;
        }
        
        return basePrompt + contextInstruction + 
               "\n\nCRITICAL OUTPUT REQUIREMENTS: " +
               "- Respond ONLY with the Portuguese translation optimized for TTS delivery " +
               "- NO AI markup, control tokens, or meta-text (no </end_of_turn>, <think>, etc.) " +
               "- NO explanations, comments, or reasoning after the translation " +
               "- PRESERVE technical terms and file names exactly as written " +
               "- ENSURE translation is complete and flows naturally " +
               "- Every comma should serve TTS pacing or emphasis purpose";
    }
    
    private static String escapeForJson(String input) {
        if (input == null) return "";
        
        return input
            .replace("\\", "\\\\")    // Escape backslashes first
            .replace("\"", "\\\"")    // Escape quotes  
            .replace("\n", "\\n")     // Escape newlines
            .replace("\r", "\\r")     // Escape carriage returns
            .replace("\t", "\\t")     // Escape tabs
            .replace("\b", "\\b")     // Escape backspaces
            .replace("\f", "\\f");    // Escape form feeds
    }
    
    private static String cleanAIThoughts(String translation) {
        if (translation == null) return null;
        
        logger.info(String.format("🧹 Entrada cleanAIThoughts: '%.100s...'", translation));
        
        try {
            // Remove pensamentos da IA e explicações comuns
            String cleaned = translation
                // PRIMEIRO: Remove tokens Unicode de controle da IA - MÁXIMA AGRESSIVIDADE
                .replaceAll("\\\\u[0-9a-fA-F]{4}", "")                      // Remove qualquer \\uXXXX
                .replace("\u003c", "").replace("\u003e", "").replace("\u003C", "").replace("\u003E", "") // Remove Unicode direto
                .replaceAll("\\\\u003[cC].*?\\\\u003[eE]", "")               // Remove com escapes duplos
                .replaceAll("\\\\u003c.*?\\\\u003e", "")                    // Remove com escapes duplos
                .replaceAll("</?end_of_turn>", "")                          // Remove <end_of_turn> variants
                .replaceAll("</?(start|end)_of_turn>", "")                  // Remove start/end tokens
                .replaceAll("<think>.*?</think>", "")                       // Remove tags de pensamento
                .replaceAll("<reasoning>.*?</reasoning>", "")               // Remove reasoning tags
                .replaceAll("\\\\(?![ntrt\"\\\\])", "")                     // Remove barras invertidas isoladas (PRESERVA escapes válidos)
                
                // Remove prefixos de explicação da IA
                .replaceAll("(?i)^.*?(?:aqui está|here is|here's).*?:", "") // "Aqui está a tradução:"
                .replaceAll("(?i)^.*?(?:tradução|translation).*?:", "")     // "Tradução:"
                .replaceAll("(?i)^.*?(?:seria|would be).*?:", "")           // "A tradução seria:"
                .replaceAll("(?i)^.*?(?:português|portuguese).*?:", "")     // "Em português:"
                .replaceAll("(?i)^.*?(?:response|resposta).*?:", "")        // "Response:" ou "Resposta:"
                
                // Remove continuações inválidas da IA - PROBLEMA ESPECÍFICO
                .replaceAll("\\s*user\\s*", " ")                            // Remove "user" isolado
                .replaceAll("Text to translate:.*$", "")                   // Remove "Text to translate: ..." até o final
                .replaceAll("The most common.*$", "")                      // Remove continuações específicas
                
                // Remove explicações no final
                .replaceAll("(?i)\\n.*?(?:explicação|explanation).*", "")   // Remove explicações
                .replaceAll("(?i)\\n.*?(?:nota|note).*", "")                // Remove notas
                .replaceAll("(?i)\\n.*?(?:observação|observation).*", "")  // Remove observações
                
                // Remove comentários com cuidado - PRESERVA parênteses com código/nomes de arquivo
                .replaceAll("(?i)\\*(?:note|nota|obs|observação).*?\\*", "") // Remove *nota* mas preserva outros *
                .replaceAll("\\[(?:note|nota|obs|observação).*?\\]", "")     // Remove [nota] mas preserva outros []
                // NÃO remove todos os parênteses - apenas os de comentários explicativos
                .replaceAll("\\((?:note|nota|obs|observação|explanation|explicação).*?\\)", "") // Remove apenas parênteses explicativos
                
                // Normalização final
                .replaceAll("\\n+", " ")                                    // Une múltiplas linhas
                .replaceAll("\\s+", " ")                                    // Normaliza espaços
                .trim();
        
            // LIMPEZA FINAL AGRESSIVA para casos extremos
            if (cleaned.contains("\\u003") || cleaned.contains("\\\\")) {
                // Se ainda contém escapes problemáticos, força limpeza total
                cleaned = cleaned.replaceAll("\\\\u[0-9a-fA-F]+", "")      // Remove todos Unicode escapes
                                .replaceAll("\\\\[^ntrt\"\\\\]", "")        // Remove barras inválidas
                                .replaceAll("\\s+", " ")                    // Normaliza espaços novamente
                                .trim();
            }
            
            // Se ficou vazio após limpeza, retorna null
            logger.info(String.format("🧹 Saída cleanAIThoughts: %s", 
                       cleaned.isEmpty() ? "EMPTY (retornando null)" : String.format("'%.100s...'", cleaned)));
            return cleaned.isEmpty() ? null : cleaned;
            
        } catch (Exception e) {
            logger.severe(String.format("❌ Erro em cleanAIThoughts: %s", e.getMessage()));
            e.printStackTrace();
            // Em caso de erro, retorna o texto original
            return translation;
        }
    }

    /**
     * Tradução rápida de emergência com timeout muito baixo
     */
    private static String translateWithQuickFallback(String text, double duration) {
        Path tempFile = null;
        try {
            int maxChars = (int)(duration * CHARS_PER_SECOND_FAST * 0.7); // 70% para segurança máxima
            
            String quickPrompt = String.format(
                "Tradução PT-BR rápida (%d chars máx): %s\nApenas a tradução:",
                maxChars, text
            );
            
            String jsonPayload = String.format(
                "{\"model\":\"gemma3n:e4b\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false,\"options\":{\"temperature\":0.0}}",
                escapeForJson(quickPrompt)
            );
            
            tempFile = Files.createTempFile("ollama_quick", ".json");
            Files.write(tempFile, jsonPayload.getBytes(StandardCharsets.UTF_8));
            
            String curlCommand = String.format(
                "curl -s -X POST http://localhost:11434/api/chat " +
                "-H \"Content-Type: application/json\" " +
                "--max-time 10 " + // Timeout no próprio curl
                "-d @%s",
                tempFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // Timeout ainda mais agressivo para emergência
            boolean finished = process.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                logger.warning("⏰ Timeout na tradução de emergência (12s)");
                process.destroyForcibly();
                return null;
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }

            String responseStr = response.toString();
            Pattern responsePattern = Pattern.compile("\"content\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);

            if (matcher.find()) {
                String content = matcher.group(1);
                content = content.replaceAll("\\\\u003[cC].*?\\\\u003[eE]", "")
                               .replace("\\\\", "\\")
                               .replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .trim();
                               
                content = cleanAIThoughts(content);
                               
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }

            return null;

        } catch (Exception e) {
            logger.warning("❌ Erro na tradução rápida: " + e.getMessage());
            return null;
        } finally {
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                // Ignora
            }
        }
    }

    /**
     * Determina timeout baseado no contexto para evitar travamentos
     */
    private static int getTimeoutForContext(String contextType) {
        return switch (contextType) {
            case "ultra_concise" -> 15; // Reduzido significativamente para evitar travamentos
            case "concise" -> 20;
            case "normal" -> 25;
            case "detailed" -> 30;
            default -> 20;
        };
    }
    
    /**
     * Constrói prompt simplificado para ultra_concise para evitar travamentos
     */
    private static String buildSimplifiedPromptForUltraConcise(String text, double duration) {
        int maxChars = (int)(duration * CHARS_PER_SECOND_FAST * 0.8); // 80% da capacidade para segurança
        
        return String.format(
            "Traduza para português brasileiro ULTRA CONCISO (máx %d chars, %.1fs): %s\n\n" +
            "REGRAS:\n" +
            "- Preserve termos técnicos exatos\n" +
            "- Use contrações: 'pra', 'tá', 'cê'\n" +
            "- Remova palavras dispensáveis\n" +
            "- Mantenha significado essencial\n" +
            "- Responda SÓ a tradução",
            maxChars, duration, text
        );
    }

    /**
     * Traduz com modelo e contexto específicos
     */
    private static String translateWithOllamaAdvanced(String text, String model, String contextType, double duration) {
        Path tempFile = null;
        try {
            // Determina timeout baseado no contexto - ultra_concise precisa ser mais restritivo
            int timeoutSeconds = getTimeoutForContext(contextType);
            
            // Para ultra_concise, usa prompt simplificado para evitar travamento
            String prompt = contextType.equals("ultra_concise") ? 
                buildSimplifiedPromptForUltraConcise(text, duration) :
                buildContextualPrompt(contextType, duration) + "\n\nText to translate: " + text;
            
            logger.info(String.format("📝 Preparando tradução com modelo %s, contexto %s (timeout: %ds)", 
                model, contextType, timeoutSeconds));
            
            // Cria JSON payload
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false,\"options\":{\"temperature\":0.1}}",
                model, escapeForJson(prompt)
            );
            
            // Salva em arquivo temporário
            tempFile = Files.createTempFile("ollama_request", ".json");
            Files.write(tempFile, jsonPayload.getBytes(StandardCharsets.UTF_8));
            logger.info(String.format("📄 JSON salvo em: %s", tempFile.toString()));
            
            String curlCommand = String.format(
                "curl -s -X POST http://localhost:11434/api/chat " +
                "-H \"Content-Type: application/json\" " +
                "-d @%s",
                tempFile.toString()
            );

            logger.info(String.format("🔗 Executando: %s", curlCommand));

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();

            StringBuilder response = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            
            // Lê stdout e stderr em paralelo para evitar bloqueio
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                } catch (Exception e) {
                    logger.warning("Erro lendo stdout: " + e.getMessage());
                }
            });
            
            Thread errorReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line);
                    }
                } catch (Exception e) {
                    logger.warning("Erro lendo stderr: " + e.getMessage());
                }
            });
            
            outputReader.start();
            errorReader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                logger.warning(String.format("⏰ TIMEOUT na chamada curl (%ds) - contexto %s", timeoutSeconds, contextType));
                process.destroyForcibly();
                // Aguarda threads terminarem
                try {
                    outputReader.join(1000);
                    errorReader.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
            
            // Aguarda threads terminarem
            try {
                outputReader.join(2000);
                errorReader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warning(String.format("❌ Curl falhou com exit code: %d", exitCode));
                if (errorOutput.length() > 0) {
                    logger.warning("Error output: " + errorOutput.toString());
                }
                return null;
            }

            // Parse da resposta JSON
            String responseStr = response.toString();
            logger.info(String.format("📥 Resposta Ollama (%d chars): %.200s...", 
                       responseStr.length(), responseStr));
            Pattern responsePattern = Pattern.compile("\"content\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);

            if (matcher.find()) {
                String content = matcher.group(1);
                logger.info(String.format("🔍 Regex encontrou conteúdo: %.100s...", content));
                
                // PRÉ-LIMPEZA: Remove tokens Unicode antes do unescape
                content = content.replaceAll("\\\\u003[cC].*?\\\\u003[eE]", ""); // Remove tokens completos
                
                // ORDEM CORRETA: escape duplo primeiro, depois simples
                content = content.replace("\\\\", "\\")
                               .replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .trim();
                               
                logger.info(String.format("🔧 Após unescape: %.100s...", content));
                
                // PÓS-LIMPEZA: Remove tokens que surgiram após unescape
                content = cleanAIThoughts(content);
                logger.info(String.format("🧹 Após limpeza: %s", content == null ? "NULL" : String.format("%.100s...", content)));
                               
                if (content != null && !content.isEmpty()) {
                    logger.info("✅ Retornando tradução processada");
                    return content;
                } else {
                    logger.warning("❌ Conteúdo vazio após processamento");
                }
            } else {
                logger.warning("❌ Regex não encontrou 'content' na resposta JSON");
            }

            return null;

        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                // Ignora erros na limpeza
            }
        }
    }

    private static String translateWithOllama(String text) {
        Path tempFile = null;
        try {
            // Constrói o prompt completo
            String fullPrompt = buildSystemPrompt() + "\n\nTexto para traduzir: " + text;
            
            // Cria JSON payload - usa gemma3n:e4b como padrão para método legado
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0.1}}",
                "gemma3n:e4b", escapeForJson(fullPrompt)
            );
            
            // Salva em arquivo temporário para evitar problemas de escape
            tempFile = Files.createTempFile("ollama_request", ".json");
            Files.write(tempFile, jsonPayload.getBytes(StandardCharsets.UTF_8));
            
            String curlCommand = String.format(
                "curl -s -X POST http://localhost:11434/api/chat " +
                "-H \"Content-Type: application/json\" " +
                "-d @%s",
                tempFile.toString()
            );

            logger.info("🔍 Chamando Ollama...");

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS); // Otimizado para velocidade
            if (!finished) {
                logger.warning("❌ Timeout no processo curl");
                process.destroyForcibly();
                return null;
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warning("❌ Erro no processo curl (exit code: " + exitCode + ")");
                // Captura stderr para debug
                try (BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String errorLine;
                    StringBuilder errorOutput = new StringBuilder();
                    while ((errorLine = errorReader.readLine()) != null) {
                        errorOutput.append(errorLine).append("\n");
                    }
                    if (errorOutput.length() > 0) {
                        logger.warning("Erro curl: " + errorOutput.toString());
                    }
                }
                return null;
            }

            // Parse simples da resposta JSON
            String responseStr = response.toString();
            logger.info("📥 Resposta Ollama: " + responseStr.substring(0, Math.min(200, responseStr.length())));
            
            // Buscar "response":"..."
            Pattern responsePattern = Pattern.compile("\"content\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);

            if (matcher.find()) {
                String content = matcher.group(1);
                
                // PRÉ-LIMPEZA: Remove tokens Unicode antes do unescape
                content = content.replaceAll("\\\\u003[cC].*?\\\\u003[eE]", ""); // Remove tokens completos
                
                // Decodificar escape sequences - ORDEM CORRETA: escape duplo primeiro!
                content = content.replace("\\\\", "\\")
                               .replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .trim();
                
                // PÓS-LIMPEZA: Remove tokens que surgiram após unescape
                content = cleanAIThoughts(content);
                               
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }

            logger.warning("❌ Não foi possível extrair tradução da resposta");
            return null;

        } catch (Exception e) {
            logger.severe("❌ Erro na tradução: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            // Limpa arquivo temporário se existir
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                // Ignora erros na limpeza
            }
        }
    }

    /**
     * FALLBACK ULTRA-CONCISO: usa prompt extremamente agressivo para caber no timing
     */
    private static String translateUltraConcise(SimpleVTTBlock block) {
        Path tempFile = null;
        try {
            // Prompt ULTRA AGRESSIVO para máxima concisão
            String ultraPrompt = String.format(
                "Traduza para português EXTREMAMENTE CONCISO (máximo %d caracteres, %.1fs). " +
                "Use só abreviações, gírias, contrações máximas. Responda SÓ a tradução: %s",
                (int)block.maxCharsForTiming, block.duration, block.originalText
            );
            
            // Usa o modelo mais rápido disponível
            String model = AVAILABLE_MODELS[0]; // qwen3:8b
            
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"temperature\":0.0}}",
                model, escapeForJson(ultraPrompt)
            );
            
            tempFile = Files.createTempFile("ollama_ultra", ".json");
            Files.write(tempFile, jsonPayload.getBytes(StandardCharsets.UTF_8));
            
            String curlCommand = String.format(
                "curl -s -X POST http://localhost:11434/api/chat " +
                "-H \"Content-Type: application/json\" " +
                "-d @%s",
                tempFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", curlCommand);
            Process process = pb.start();

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS); // SUPER RÁPIDO
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }

            String responseStr = response.toString();
            Pattern responsePattern = Pattern.compile("\"content\":\\s*\"([^\"]*(?:\\\\.[^\"]*)*?)\"");
            Matcher matcher = responsePattern.matcher(responseStr);

            if (matcher.find()) {
                String content = matcher.group(1);
                // PRÉ-LIMPEZA: Remove tokens Unicode antes do unescape
                content = content.replaceAll("\\\\u003[cC].*?\\\\u003[eE]", ""); // Remove tokens completos
                
                // ORDEM CORRETA: escape duplo primeiro, depois simples
                content = content.replace("\\\\", "\\")
                               .replace("\\\"", "\"")
                               .replace("\\n", "\n")
                               .replace("\\r", "\r")
                               .replace("\\t", "\t")
                               .trim();
                               
                // PÓS-LIMPEZA: Remove tokens que surgiram após unescape
                content = cleanAIThoughts(content);
                               
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }

            return null;

        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                // Ignora
            }
        }
    }

    // Métodos de compatibilidade
    public static void translateFileEnhanced(String inputFile, String outputFile, Object prosodyData) throws Exception {
        translateFile(inputFile, outputFile, "LLama");
    }

    public static void translateFileEnhanced(String inputFile, String outputFile) throws Exception {
        translateFile(inputFile, outputFile, "LLama");
    }

    public static void translateFileWithFallback(String inputFile, String outputFile) throws Exception {
        translateFile(inputFile, outputFile, "LLama");
    }

    // Main para teste direto
    public static void main(String[] args) {
        if (args.length >= 2) {
            try {
                translateFile(args[0], args[1], "LLama");
            } catch (Exception e) {
                System.err.println("Erro: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("Uso: java TranslationUtilsSimple <input.vtt> <output.vtt>");
        }
    }
}