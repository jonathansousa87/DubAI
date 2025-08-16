package org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * PipelineDebugLogger - Sistema de logging abrangente para diagnosticar problemas na pipeline
 * 
 * Monitora todas as etapas:
 * 1. Análise prosódica
 * 2. Tradução inteligente 
 * 3. Geração TTS
 * 4. Problemas de silêncio
 * 5. Timing e qualidade
 */
public class PipelineDebugLogger {
    
    private static final Logger logger = Logger.getLogger(PipelineDebugLogger.class.getName());
    private static final Path DEBUG_LOG_FILE = Paths.get("output/pipeline-debug.log");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private static final List<String> debugMessages = new ArrayList<>();
    private static boolean isEnabled = true;
    
    static {
        try {
            Files.createDirectories(DEBUG_LOG_FILE.getParent());
            initializeLog();
        } catch (IOException e) {
            logger.warning("Erro inicializando pipeline debug log: " + e.getMessage());
        }
    }
    
    private static void initializeLog() throws IOException {
        String header = String.format("""
            ================================================================================
            PIPELINE DEBUG LOG - %s
            ================================================================================
            Monitorando toda a pipeline de dublagem:
            - Análise prosódica (WhisperXPlusUtils)
            - Tradução inteligente (TranslationUtilsFixed)  
            - Geração TTS (TTSUtils otimizado)
            - Detecção de problemas de silêncio
            - Análise de timing e qualidade
            ================================================================================
            
            """, LocalDateTime.now().format(TIMESTAMP_FORMAT));
            
        Files.writeString(DEBUG_LOG_FILE, header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    /**
     * Log de entrada na pipeline
     */
    public static void logPipelineStart(String inputFile) {
        logEvent("PIPELINE_START", String.format("Iniciando processamento: %s", inputFile));
    }
    
    /**
     * Log da análise prosódica
     */
    public static void logProsodyAnalysis(String stage, String details) {
        logEvent("PROSODY_ANALYSIS", String.format("[%s] %s", stage, details));
    }
    
    /**
     * Log da tradução inteligente
     */
    public static void logTranslationAttempt(String model, String status, String details) {
        logEvent("TRANSLATION", String.format("Modelo: %s | Status: %s | %s", model, status, details));
    }
    
    /**
     * Log da geração TTS
     */
    public static void logTTSGeneration(int segmentIndex, String text, double vttDuration, 
                                       double generatedDuration, String quality) {
        String message = String.format("Seg%03d | VTT: %.3fs | TTS: %.3fs | Diff: %.3fs | Quality: %s | Text: %.50s...", 
                                     segmentIndex, vttDuration, generatedDuration, 
                                     Math.abs(vttDuration - generatedDuration), quality, text);
        logEvent("TTS_GENERATION", message);
    }
    
    /**
     * Log de problemas de silêncio
     */
    public static void logSilenceIssue(int segmentIndex, String issueType, double silenceDuration, String details) {
        logEvent("SILENCE_ISSUE", String.format("Seg%03d | %s | Duração: %.3fs | %s", 
                                               segmentIndex, issueType, silenceDuration, details));
    }
    
    /**
     * Log de problemas de timing
     */
    public static void logTimingIssue(int segmentIndex, double expected, double actual, String cause) {
        double diff = Math.abs(expected - actual);
        String severity = diff > 1.0 ? "CRITICAL" : diff > 0.5 ? "WARNING" : "INFO";
        logEvent("TIMING_ISSUE", String.format("Seg%03d | %s | Expected: %.3fs | Actual: %.3fs | Diff: %.3fs | Cause: %s", 
                                             segmentIndex, severity, expected, actual, diff, cause));
    }
    
    /**
     * Log de qualidade de áudio
     */
    public static void logAudioQuality(int segmentIndex, double volume, double quality, boolean hasClipping, String issues) {
        logEvent("AUDIO_QUALITY", String.format("Seg%03d | Volume: %.1fdB | Quality: %.1f%% | Clipping: %s | Issues: %s", 
                                               segmentIndex, volume, quality, hasClipping ? "YES" : "NO", issues));
    }
    
    /**
     * Log de fallback da tradução
     */
    public static void logTranslationFallback(String reason, String fallbackMethod) {
        logEvent("TRANSLATION_FALLBACK", String.format("Reason: %s | Using: %s", reason, fallbackMethod));
    }
    
    /**
     * Log de erro crítico
     */
    public static void logCriticalError(String component, String error, String stackTrace) {
        logEvent("CRITICAL_ERROR", String.format("Component: %s | Error: %s | Stack: %s", 
                                                component, error, stackTrace.substring(0, Math.min(200, stackTrace.length()))));
    }
    
    /**
     * Resumo final da pipeline
     */
    public static void logPipelineSummary(int totalSegments, int successfulSegments, 
                                         int silenceIssues, int timingIssues, double overallQuality) {
        String summary = String.format("""
            
            ================================================================================
            PIPELINE SUMMARY - %s
            ================================================================================
            Total segments: %d
            Successful segments: %d (%.1f%%)
            Silence issues: %d
            Timing issues: %d  
            Overall quality: %.1f%%
            
            Success rate: %.1f%%
            ================================================================================
            """, LocalDateTime.now().format(TIMESTAMP_FORMAT), totalSegments, successfulSegments,
            (double) successfulSegments / totalSegments * 100, silenceIssues, timingIssues, 
            overallQuality, (double) successfulSegments / totalSegments * 100);
            
        logEvent("PIPELINE_SUMMARY", summary);
    }
    
    /**
     * Diagnóstico específico de segmento
     */
    public static void diagnoseSegerment(int segmentIndex, String originalText, String translatedText,
                                        double vttDuration, Path audioFile) {
        StringBuilder diagnosis = new StringBuilder();
        diagnosis.append(String.format("=== SEGMENT %03d DIAGNOSIS ===\n", segmentIndex));
        diagnosis.append(String.format("Original: %s\n", originalText.substring(0, Math.min(100, originalText.length()))));
        diagnosis.append(String.format("Translated: %s\n", translatedText.substring(0, Math.min(100, translatedText.length()))));
        diagnosis.append(String.format("Expected duration: %.3fs\n", vttDuration));
        
        // Verificar arquivo de áudio
        if (audioFile != null && Files.exists(audioFile)) {
            try {
                long fileSize = Files.size(audioFile);
                diagnosis.append(String.format("Audio file: %s (%.1fKB)\n", audioFile.getFileName(), fileSize / 1024.0));
                
                if (fileSize < 1000) {
                    diagnosis.append("⚠️ WARNING: Audio file muito pequeno, pode ser silêncio\n");
                }
                
                // Tentar medir duração real
                diagnosis.append(String.format("Audio analysis: %s\n", analyzeAudioFile(audioFile)));
                
            } catch (IOException e) {
                diagnosis.append(String.format("❌ ERROR: Erro lendo arquivo de áudio: %s\n", e.getMessage()));
            }
        } else {
            diagnosis.append("❌ ERROR: Arquivo de áudio não encontrado ou não existe\n");
        }
        
        logEvent("SEGMENT_DIAGNOSIS", diagnosis.toString());
    }
    
    private static String analyzeAudioFile(Path audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "quiet", 
                "-show_entries", "format=duration,size", 
                "-of", "csv=p=0", audioFile.toString());
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Timeout analyzing audio";
            }
            
            if (process.exitValue() == 0) {
                String[] parts = output.toString().split(",");
                if (parts.length >= 2) {
                    double duration = Double.parseDouble(parts[0]);
                    long size = Long.parseLong(parts[1]);
                    return String.format("Duration: %.3fs, Size: %d bytes", duration, size);
                }
            }
            
            return "Could not analyze audio";
            
        } catch (Exception e) {
            return "Error analyzing: " + e.getMessage();
        }
    }
    
    /**
     * Método interno para log de eventos
     */
    private static void logEvent(String category, String message) {
        if (!isEnabled) return;
        
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logLine = String.format("[%s] [%s] %s", timestamp, category, message);
        
        // Log no console
        System.out.println("🔍 " + logLine);
        
        // Log no arquivo
        try {
            Files.writeString(DEBUG_LOG_FILE, logLine + "\n", StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("Erro escrevendo debug log: " + e.getMessage());
        }
        
        // Manter em memória para análise
        debugMessages.add(logLine);
        
        // Limitar tamanho em memória
        if (debugMessages.size() > 1000) {
            debugMessages.remove(0);
        }
    }
    
    /**
     * Habilitar/desabilitar logging
     */
    public static void setEnabled(boolean enabled) {
        isEnabled = enabled;
        logEvent("SYSTEM", "Debug logging " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Obter caminho do arquivo de log
     */
    public static Path getLogFilePath() {
        return DEBUG_LOG_FILE;
    }
    
    /**
     * Obter mensagens recentes em memória
     */
    public static List<String> getRecentMessages(int count) {
        int start = Math.max(0, debugMessages.size() - count);
        return new ArrayList<>(debugMessages.subList(start, debugMessages.size()));
    }
    
    /**
     * Limpar logs
     */
    public static void clearLogs() {
        debugMessages.clear();
        try {
            initializeLog();
        } catch (IOException e) {
            logger.warning("Erro reinicializando log: " + e.getMessage());
        }
    }
}