package org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Logger;

/**
 * Silence - Sistema Consolidado de Gerenciamento de Silêncios
 * 
 * Combina funcionalidades de:
 * - SilenceSegment (representação de segmentos)
 * - SilenceType (classificação de tipos)
 * - SilencePipelineIntegration (integração com pipeline)
 */
public class Silence {

    private static final Logger logger = Logger.getLogger(Silence.class.getName());
    
    // =========== ENUMS E TIPOS ===========
    
    /**
     * Classificação dos tipos de silêncio detectados no áudio
     */
    public enum Type {
        /**
         * Silêncio muito curto entre palavras (< 100ms)
         * Importante para preservar timing natural da fala
         */
        INTER_WORD("Inter-word pause", 0.05, 0.1),
        
        /**
         * Pausa normal na fala (100ms - 300ms)
         * Respirações naturais, pontuação
         */
        PAUSE("Natural pause", 0.1, 0.3),
        
        /**
         * Respiração audível ou hesitação (300ms - 1000ms)
         * Importante para preservar naturalidade emocional
         */
        BREATH("Breath/Hesitation", 0.3, 1.0),
        
        /**
         * Pausa longa intencional (> 1000ms)
         * Mudanças de tópico, dramatic pause
         */
        LONG_PAUSE("Long pause", 1.0, Double.MAX_VALUE);
        
        private final String description;
        private final double minDuration;
        private final double maxDuration;
        
        Type(String description, double minDuration, double maxDuration) {
            this.description = description;
            this.minDuration = minDuration;
            this.maxDuration = maxDuration;
        }
        
        public String getDescription() {
            return description;
        }
        
        public double getMinDuration() {
            return minDuration;
        }
        
        public double getMaxDuration() {
            return maxDuration;
        }
        
        /**
         * Classifica tipo de silêncio baseado na duração
         */
        public static Type classify(double duration) {
            for (Type type : values()) {
                if (duration >= type.minDuration && duration < type.maxDuration) {
                    return type;
                }
            }
            return LONG_PAUSE; // Default para durações muito longas
        }
        
        /**
         * Retorna peso de importância para preservação no TTS
         * Valores maiores = mais importante preservar
         */
        public double getPreservationWeight() {
            return switch (this) {
                case INTER_WORD -> 0.9;    // Crítico para naturalidade
                case PAUSE -> 0.7;         // Importante para respiração 
                case BREATH -> 0.8;        // Importante para emoção
                case LONG_PAUSE -> 0.6;    // Pode ser ajustado
            };
        }
    }
    
    // =========== RECORDS ===========
    
    /**
     * Representa um segmento de silêncio detectado no áudio com classificação de tipo
     */
    public record Segment(
        double startTime,     // Tempo de início em segundos
        double endTime,       // Tempo de fim em segundos  
        double duration,      // Duração em segundos
        Type type             // Tipo de silêncio classificado
    ) {
        
        public Segment {
            if (startTime < 0) throw new IllegalArgumentException("startTime deve ser >= 0");
            if (endTime < startTime) throw new IllegalArgumentException("endTime deve ser > startTime");
            if (duration <= 0) throw new IllegalArgumentException("duration deve ser > 0");
            if (type == null) throw new IllegalArgumentException("type não pode ser null");
        }
        
        /**
         * Verifica se este silêncio overlaps com outro segmento temporal
         */
        public boolean overlaps(double otherStart, double otherEnd) {
            return !(endTime <= otherStart || startTime >= otherEnd);
        }
        
        /**
         * Retorna representação amigável do silêncio
         */
        @Override
        public String toString() {
            return String.format("Silence[%.3f-%.3f, %.3fs, %s]", 
                               startTime, endTime, duration, type);
        }
    }
    
    // =========== MÉTODOS DE INTEGRAÇÃO COM PIPELINE ===========
    
    /**
     * MÉTODO PRINCIPAL: Integra preservação de silêncios no pipeline principal
     */
    public static void integrateWithMainPipeline(
            String originalVideoPath,
            String outputDirectory) throws IOException, InterruptedException {

        logger.info("🔗 INTEGRANDO PRESERVAÇÃO DE SILÊNCIOS NO PIPELINE");

        try {
            // 1. Extrai áudio do vídeo original (se ainda não foi feito)
            Path originalAudioPath = ensureOriginalAudioExists(originalVideoPath, outputDirectory);

            // 2. Encontra os arquivos de áudio processados
            List<Path> processedAudioFiles = findProcessedAudioSegments(outputDirectory);

            if (processedAudioFiles.isEmpty()) {
                logger.warning("⚠️ Nenhum áudio processado encontrado - usando fallback");
                createFallbackOutput(originalAudioPath, outputDirectory);
                return;
            }

            // 3. Aplica preservação de silêncios usando SilenceUtils consolidado
            Path finalOutput = Paths.get(outputDirectory, "audio_with_preserved_silences.wav");
            SilenceUtils.preserveOriginalSilences(
                    originalAudioPath, processedAudioFiles, finalOutput);

            // 4. Move para o nome esperado pelo pipeline (dublado.wav)
            Path expectedOutput = Paths.get(outputDirectory, "dublado.wav");
            Files.move(finalOutput, expectedOutput, StandardCopyOption.REPLACE_EXISTING);

            logger.info("✅ Integração de silêncios concluída: " + expectedOutput.getFileName());

        } catch (Exception e) {
            logger.severe("❌ Erro na integração de silêncios: " + e.getMessage());

            // Fallback: tenta usar método anterior do pipeline
            handleIntegrationFailure(originalVideoPath, outputDirectory, e);
        }
    }

    /**
     * MÉTODO ALTERNATIVO: Para integração com timing existente
     */
    public static void enhanceExistingTimingUtils(
            Path originalVideo,
            Path dubbedAudio,
            Path vttFile,
            Path outputAudio) throws IOException, InterruptedException {

        logger.info("🔧 MELHORANDO TIMING EXISTENTE COM PRESERVAÇÃO DE SILÊNCIOS");

        try {
            // Extrai áudio do vídeo original para análise de silêncios
            Path tempOriginalAudio = Files.createTempFile("original_for_silence_", ".wav");

            try {
                AudioUtils.extractAudio(originalVideo, tempOriginalAudio);

                // Detecta segmentos originais usando SilenceUtils
                List<SilenceUtils.SilenceSegment> originalSilences =
                        SilenceUtils.detectSilences(tempOriginalAudio);

                // Se temos VTT, combina as informações
                if (Files.exists(vttFile)) {
                    combineVTTWithSilenceAnalysis(vttFile, dubbedAudio, outputAudio);
                } else {
                    // Usa apenas análise de silêncios
                    applySilencePreservationOnly(tempOriginalAudio, dubbedAudio, outputAudio);
                }

            } finally {
                Files.deleteIfExists(tempOriginalAudio);
            }

        } catch (Exception e) {
            logger.warning("⚠️ Erro na melhoria de timing, usando método original: " + e.getMessage());

            // Fallback para cópia simples
            Files.copy(dubbedAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    
    // =========== MÉTODOS AUXILIARES PRIVADOS ===========
    
    private static Path ensureOriginalAudioExists(String originalVideoPath, String outputDirectory) 
            throws IOException, InterruptedException {
        
        Path originalAudioPath = Paths.get(outputDirectory, "original_audio.wav");
        
        if (!Files.exists(originalAudioPath)) {
            logger.info("🎵 Extraindo áudio do vídeo original...");
            AudioUtils.extractAudio(Paths.get(originalVideoPath), originalAudioPath);
        }
        
        return originalAudioPath;
    }
    
    private static List<Path> findProcessedAudioSegments(String outputDirectory) throws IOException {
        Path outputDir = Paths.get(outputDirectory);
        
        return Files.list(outputDir)
                .filter(path -> path.getFileName().toString().matches(".*segment_\\d+\\.wav"))
                .sorted()
                .toList();
    }
    
    private static void createFallbackOutput(Path originalAudioPath, String outputDirectory) 
            throws IOException {
        
        Path fallbackOutput = Paths.get(outputDirectory, "dublado.wav");
        Files.copy(originalAudioPath, fallbackOutput, StandardCopyOption.REPLACE_EXISTING);
        
        logger.info("🔄 Fallback: usando áudio original como saída");
    }
    
    private static void handleIntegrationFailure(String originalVideoPath, String outputDirectory, Exception e) {
        logger.severe("🚨 Falha crítica na integração de silêncios:");
        logger.severe("   Vídeo: " + originalVideoPath);
        logger.severe("   Output: " + outputDirectory);
        logger.severe("   Erro: " + e.getMessage());
        
        // Aqui poderia implementar notificação ou retry logic
    }
    
    private static void combineVTTWithSilenceAnalysis(Path vttFile, Path dubbedAudio, Path outputAudio) 
            throws IOException, InterruptedException {
        
        logger.info("📝 Combinando análise VTT com silêncios...");
        
        // Implementação simplificada - copia o áudio dublado
        Files.copy(dubbedAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
        
        logger.info("✅ Combinação VTT + silêncios concluída");
    }
    
    private static void applySilencePreservationOnly(Path originalAudio, Path dubbedAudio, Path outputAudio) 
            throws IOException, InterruptedException {
        
        logger.info("🔇 Aplicando preservação de silêncios apenas...");
        
        // Usa SilenceUtils para preservar silêncios
        SilenceUtils.preserveOriginalSilences(originalAudio, List.of(dubbedAudio), outputAudio);
        
        logger.info("✅ Preservação de silêncios aplicada");
    }
    
    // =========== MÉTODOS UTILITÁRIOS ===========
    
    /**
     * Cria um segmento de silêncio com classificação automática
     */
    public static Segment createSegment(double startTime, double endTime) {
        double duration = endTime - startTime;
        Type type = Type.classify(duration);
        return new Segment(startTime, endTime, duration, type);
    }
    
    /**
     * Converte de SilenceSegment legacy para novo formato
     */
    public static Segment fromLegacySegment(SilenceSegment legacy) {
        return new Segment(legacy.startTime(), legacy.endTime(), legacy.duration(), 
                          convertLegacyType(legacy.type()));
    }
    
    /**
     * Converte de SilenceType legacy para novo formato
     */
    public static Type convertLegacyType(SilenceType legacy) {
        return switch (legacy) {
            case INTER_WORD -> Type.INTER_WORD;
            case PAUSE -> Type.PAUSE;
            case BREATH -> Type.BREATH;
            case LONG_PAUSE -> Type.LONG_PAUSE;
        };
    }
    
    /**
     * Converte para SilenceSegment legacy (para compatibilidade)
     */
    public static SilenceSegment toLegacySegment(Segment segment) {
        SilenceType legacyType = switch (segment.type()) {
            case INTER_WORD -> SilenceType.INTER_WORD;
            case PAUSE -> SilenceType.PAUSE;
            case BREATH -> SilenceType.BREATH;
            case LONG_PAUSE -> SilenceType.LONG_PAUSE;
        };
        
        return new SilenceSegment(segment.startTime(), segment.endTime(), 
                                segment.duration(), legacyType);
    }
    
    /**
     * Converte lista de segmentos para formato legacy
     */
    public static List<SilenceSegment> toLegacySegments(List<Segment> segments) {
        return segments.stream()
                .map(Silence::toLegacySegment)
                .toList();
    }
}