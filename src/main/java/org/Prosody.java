package org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.logging.Logger;

/**
 * Prosody - Sistema Consolidado de Análise Prosódica
 * 
 * Combina funcionalidades de:
 * - ProsodyAnalysisUtils (análise de áudio)
 * - ProsodyAnalysisResult (resultados)
 * - ProsodyData (dados prosódicos)
 * - ProsodyMetrics (métricas detalhadas)
 */
public class Prosody {

    private static final Logger logger = Logger.getLogger(Prosody.class.getName());
    
    // =========== CONFIGURAÇÕES ===========
    
    private static final double SILENCE_THRESHOLD_DB = -30.0;
    private static final double MIN_SILENCE_DURATION = 0.05; // 50ms
    private static final double PITCH_ANALYSIS_THRESHOLD = -20.0;
    
    // Classificação de pitch por gênero
    private static final Map<VoiceType, double[]> PITCH_RANGES = Map.of(
        VoiceType.BASS, new double[]{80, 160},
        VoiceType.BARITONE, new double[]{120, 200},
        VoiceType.TENOR, new double[]{160, 280},
        VoiceType.ALTO, new double[]{180, 320},
        VoiceType.SOPRANO, new double[]{250, 440}
    );
    
    // =========== ENUMS E CLASSES AUXILIARES ===========
    
    public enum VoiceType {
        BASS("Baixo"),
        BARITONE("Barítono"),
        TENOR("Tenor"),
        ALTO("Alto"),
        SOPRANO("Soprano");
        
        private final String description;
        
        VoiceType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public enum ExpressionLevel {
        MONOTONE("Monótono"),
        SLIGHTLY_EXPRESSIVE("Levemente expressivo"),
        MODERATELY_EXPRESSIVE("Moderadamente expressivo"),
        HIGHLY_EXPRESSIVE("Altamente expressivo");
        
        private final String description;
        
        ExpressionLevel(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    // =========== RECORDS CONSOLIDADOS ===========
    
    /**
     * Resultado completo da análise prosódica
     */
    public record AnalysisResult(
        Path audioFile,
        List<SilenceSegment> silences,
        EmotionMetrics emotions,
        Metrics prosody
    ) {
        public AnalysisResult {
            if (audioFile == null) throw new IllegalArgumentException("audioFile não pode ser null");
            if (silences == null) throw new IllegalArgumentException("silences não pode ser null");
            if (emotions == null) throw new IllegalArgumentException("emotions não pode ser null");
            if (prosody == null) throw new IllegalArgumentException("prosody não pode ser null");
            
            silences = List.copyOf(silences);
        }
        
        public double getTotalSilenceDuration() {
            return silences.stream().mapToDouble(SilenceSegment::duration).sum();
        }
        
        public List<SilenceSegment> getSilencesByType(SilenceType type) {
            return silences.stream()
                    .filter(silence -> silence.type() == type)
                    .toList();
        }
        
        public String generateSummary() {
            StringBuilder summary = new StringBuilder();
            summary.append("=== ANÁLISE PROSÓDICA ===\n");
            summary.append(String.format("Arquivo: %s\n", audioFile.getFileName()));
            summary.append(String.format("Total silêncios: %d (%.3fs)\n", 
                                        silences.size(), getTotalSilenceDuration()));
            
            summary.append(String.format("Estado emocional: %s\n", emotions.getEmotionalState()));
            summary.append(String.format("Pitch médio: %.1fHz (%s)\n", 
                                        prosody.averagePitch(), prosody.getVoiceType()));
            summary.append(String.format("Expressividade: %.1f%%\n", 
                                        prosody.getExpressiveness() * 100));
            
            return summary.toString();
        }
        
        public TTSRecommendations generateTTSRecommendations() {
            var ttsParams = prosody.generateTTSParams();
            var prosodyParams = emotions.toProsodyParams();
            String ssmlModulation = emotions.generateSSMLModulation();
            
            return new TTSRecommendations(
                ttsParams.lengthScale(),
                ttsParams.noiseScale(),
                ttsParams.noiseW(),
                prosodyParams.pitchAdjust(),
                prosodyParams.rateAdjust(),
                prosodyParams.volumeAdjust(),
                ssmlModulation,
                ttsParams.voiceType()
            );
        }
    }
    
    /**
     * Métricas prosódicas detalhadas
     */
    public record Metrics(
        double averagePitch,
        double pitchVariance,
        double pitchRange,
        List<EmphasisMoment> emphasisMoments,
        VoiceType voiceType,
        double expressiveness
    ) {
        public Metrics {
            if (averagePitch < 0) throw new IllegalArgumentException("averagePitch deve ser >= 0");
            if (pitchVariance < 0) throw new IllegalArgumentException("pitchVariance deve ser >= 0");
            if (expressiveness < 0.0 || expressiveness > 1.0) {
                throw new IllegalArgumentException("expressiveness deve estar entre 0.0 e 1.0");
            }
            
            emphasisMoments = emphasisMoments != null ? List.copyOf(emphasisMoments) : List.of();
        }
        
        public double getExpressiveness() {
            return expressiveness;
        }
        
        public VoiceType getVoiceType() {
            return voiceType;
        }
        
        public List<EmphasisMoment> findEmphasisMoments() {
            return emphasisMoments;
        }
        
        public TTSParams generateTTSParams() {
            double lengthScale = 1.0;
            double noiseScale = Math.min(0.3, expressiveness * 0.4);
            double noiseW = Math.min(0.3, pitchVariance / 200.0);
            
            return new TTSParams(lengthScale, noiseScale, noiseW, voiceType);
        }
    }
    
    /**
     * Dados prosódicos completos para uso no TTS
     */
    public record Data(
        double valence,
        double arousal,
        double dominance,
        double avgPitch,
        double pitchVariance,
        double expressiveness,
        String voiceType,
        String globalEmotion,
        double ttsRateAdjust,
        double ttsPitchAdjust,
        double ttsVolumeAdjust,
        String ssmlTemplate,
        int totalSilences,
        int interWordSilences,
        int pauses,
        int breaths
    ) {
        public Data {
            if (valence < -1.0 || valence > 1.0) {
                throw new IllegalArgumentException("Valence deve estar entre -1.0 e 1.0");
            }
            if (arousal < 0.0 || arousal > 1.0) {
                throw new IllegalArgumentException("Arousal deve estar entre 0.0 e 1.0");
            }
            if (dominance < 0.0 || dominance > 1.0) {
                throw new IllegalArgumentException("Dominance deve estar entre 0.0 e 1.0");
            }
            if (expressiveness < 0.0 || expressiveness > 1.0) {
                throw new IllegalArgumentException("Expressiveness deve estar entre 0.0 e 1.0");
            }
            
            voiceType = voiceType != null ? voiceType : "TENOR";
            globalEmotion = globalEmotion != null ? globalEmotion : "NEUTRAL";
            ssmlTemplate = ssmlTemplate != null ? ssmlTemplate : "";
        }
        
        public ExpressionLevel getExpressionLevel() {
            if (expressiveness > 0.7) return ExpressionLevel.HIGHLY_EXPRESSIVE;
            if (expressiveness > 0.4) return ExpressionLevel.MODERATELY_EXPRESSIVE;
            if (expressiveness > 0.2) return ExpressionLevel.SLIGHTLY_EXPRESSIVE;
            return ExpressionLevel.MONOTONE;
        }
        
        public double getSpeedFactor() {
            double emotionalFactor = switch (globalEmotion.toUpperCase()) {
                case "EXCITED", "AGITATED" -> 1.1;
                case "CALM", "DEPRESSED" -> 0.9;
                default -> 1.0;
            };
            
            double arousalFactor = 1.0 + (arousal - 0.5) * 0.2;
            
            return Math.max(0.7, Math.min(1.3, emotionalFactor * arousalFactor));
        }
        
        public boolean shouldUseAdvancedProsody() {
            return expressiveness > 0.3 || 
                   !globalEmotion.equals("NEUTRAL") || 
                   Math.abs(valence) > 0.3 ||
                   arousal > 0.5;
        }
    }
    
    /**
     * Parâmetros para TTS
     */
    public record TTSParams(
        double lengthScale,
        double noiseScale,
        double noiseW,
        VoiceType voiceType
    ) {}
    
    /**
     * Recomendações para TTS
     */
    public record TTSRecommendations(
        double lengthScale,
        double noiseScale,
        double noiseW,
        double pitchAdjust,
        double rateAdjust,
        double volumeAdjust,
        String ssmlModulation,
        VoiceType recommendedVoice
    ) {
        @Override
        public String toString() {
            return String.format("TTS Config[length=%.2f, noise=%.2f, noiseW=%.2f, " +
                               "pitch=%+.1f%%, rate=%.2f, volume=%.2f, voice=%s]",
                               lengthScale, noiseScale, noiseW, 
                               pitchAdjust * 100, rateAdjust, volumeAdjust, recommendedVoice);
        }
    }
    
    /**
     * Momento de ênfase na fala
     */
    public record EmphasisMoment(
        double startTime,
        double endTime,
        double intensity,
        String type
    ) {}
    
    /**
     * Configuração de noise
     */
    public record NoiseConfig(
        double noiseScale,
        double noiseW
    ) {}
    
    // =========== MÉTODOS PÚBLICOS PRINCIPAIS ===========
    
    /**
     * Análise prosódica completa de um arquivo de áudio
     */
    public static AnalysisResult analyzeAudio(Path audioFile) throws IOException, InterruptedException {
        logger.info("🎵 Iniciando análise prosódica: " + audioFile.getFileName());
        
        // Análises em paralelo
        CompletableFuture<List<SilenceSegment>> silencesAnalysis = 
                CompletableFuture.supplyAsync(() -> analyzeSilences(audioFile));
        
        CompletableFuture<EmotionMetrics> emotionAnalysis = 
                CompletableFuture.supplyAsync(() -> analyzeEmotionalState(audioFile));
        
        CompletableFuture<Metrics> prosodyAnalysis = 
                CompletableFuture.supplyAsync(() -> analyzeProsodyMetrics(audioFile));
        
        try {
            List<SilenceSegment> silences = silencesAnalysis.get(30, TimeUnit.SECONDS);
            EmotionMetrics emotions = emotionAnalysis.get(30, TimeUnit.SECONDS);
            Metrics prosody = prosodyAnalysis.get(30, TimeUnit.SECONDS);
            
            logger.info("✅ Análise prosódica concluída");
            return new AnalysisResult(audioFile, silences, emotions, prosody);
            
        } catch (Exception e) {
            logger.warning("⚠️ Falha na análise, usando valores padrão: " + e.getMessage());
            return createDefaultAnalysisResult(audioFile);
        }
    }
    
    /**
     * Carrega dados prosódicos de um arquivo de propriedades
     */
    public static Data loadProsodyData(String propertiesFile) throws IOException {
        logger.info("📖 Carregando dados prosódicos: " + propertiesFile);
        
        Path file = Paths.get(propertiesFile);
        if (!Files.exists(file)) {
            return createDefaultProsodyData();
        }
        
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            props.load(reader);
        }
        
        return new Data(
            getDoubleProperty(props, "VALENCE", 0.5),
            getDoubleProperty(props, "AROUSAL", 0.3),
            getDoubleProperty(props, "DOMINANCE", 0.5),
            getDoubleProperty(props, "AVG_PITCH", 200.0),
            getDoubleProperty(props, "PITCH_VARIANCE", 50.0),
            getDoubleProperty(props, "EXPRESSIVENESS", 0.5),
            props.getProperty("VOICE_TYPE", "TENOR"),
            props.getProperty("GLOBAL_EMOTION", "NEUTRAL"),
            getDoubleProperty(props, "TTS_RATE_ADJUST", 1.0),
            getDoubleProperty(props, "TTS_PITCH_ADJUST", 0.0),
            getDoubleProperty(props, "TTS_VOLUME_ADJUST", 1.0),
            props.getProperty("SSML_TEMPLATE", ""),
            getIntProperty(props, "TOTAL_SILENCES", 0),
            getIntProperty(props, "INTER_WORD_SILENCES", 0),
            getIntProperty(props, "PAUSES", 0),
            getIntProperty(props, "BREATHS", 0)
        );
    }
    
    // =========== MÉTODOS DE ANÁLISE ===========
    
    private static List<SilenceSegment> analyzeSilences(Path audioFile) {
        try {
            logger.fine("🔇 Analisando silêncios: " + audioFile.getFileName());
            
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioFile.toString(),
                "-af", String.format("silencedetect=noise=%fdB:duration=%f",
                                   SILENCE_THRESHOLD_DB, MIN_SILENCE_DURATION),
                "-f", "null", "-"
            );
            
            Process process = pb.start();
            List<SilenceSegment> silences = new ArrayList<>();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                
                String line;
                Pattern silencePattern = Pattern.compile("silence_start: ([\\d.]+)");
                Pattern silenceEndPattern = Pattern.compile("silence_end: ([\\d.]+) \\| silence_duration: ([\\d.]+)");
                
                double silenceStart = -1;
                
                while ((line = reader.readLine()) != null) {
                    Matcher startMatcher = silencePattern.matcher(line);
                    if (startMatcher.find()) {
                        silenceStart = Double.parseDouble(startMatcher.group(1));
                    }
                    
                    Matcher endMatcher = silenceEndPattern.matcher(line);
                    if (endMatcher.find() && silenceStart >= 0) {
                        double silenceEnd = Double.parseDouble(endMatcher.group(1));
                        double duration = Double.parseDouble(endMatcher.group(2));
                        
                        SilenceType type = classifySilence(duration);
                        silences.add(new SilenceSegment(silenceStart, silenceEnd, duration, type));
                        silenceStart = -1;
                    }
                }
            }
            
            process.waitFor(30, TimeUnit.SECONDS);
            logger.fine("✅ Silêncios analisados: " + silences.size());
            return silences;
            
        } catch (Exception e) {
            logger.warning("⚠️ Erro analisando silêncios: " + e.getMessage());
            return List.of();
        }
    }
    
    private static SilenceType classifySilence(double duration) {
        if (duration < 0.1) return SilenceType.INTER_WORD;
        if (duration < 0.5) return SilenceType.PAUSE;
        return SilenceType.BREATH;
    }
    
    private static EmotionMetrics analyzeEmotionalState(Path audioFile) {
        try {
            logger.fine("😊 Analisando estado emocional: " + audioFile.getFileName());
            
            // Análise básica baseada em características do áudio
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioFile.toString(),
                "-af", "astats=metadata=1",
                "-f", "null", "-"
            );
            
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                
                String output = reader.lines().collect(Collectors.joining("\n"));
                return parseEmotionFeatures(output);
            }
            
        } catch (Exception e) {
            logger.warning("⚠️ Erro na análise emocional: " + e.getMessage());
            return createNeutralEmotion();
        }
    }
    
    private static Metrics analyzeProsodyMetrics(Path audioFile) {
        try {
            logger.fine("📊 Analisando métricas prosódicas: " + audioFile.getFileName());
            
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioFile.toString(),
                "-af", "astats=metadata=1:reset=1",
                "-f", "null", "-"
            );
            
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                
                String output = reader.lines().collect(Collectors.joining("\n"));
                return parseProsodyMetrics(output);
            }
            
        } catch (Exception e) {
            logger.warning("⚠️ Erro na análise prosódica: " + e.getMessage());
            return createDefaultMetrics();
        }
    }
    
    // =========== MÉTODOS AUXILIARES ===========
    
    private static EmotionMetrics parseEmotionFeatures(String ffmpegOutput) {
        try {
            // Parse básico de características emocionais do áudio
            double dynamicRange = extractNumericValue(ffmpegOutput, "Dynamic_range");
            double rmsLevel = extractNumericValue(ffmpegOutput, "RMS_level");
            
            double valence = Math.max(-1.0, Math.min(1.0, (dynamicRange - 20) / 40.0));
            double arousal = Math.max(0.0, Math.min(1.0, Math.abs(rmsLevel + 20) / 30.0));
            
            Map<String, Double> emotions = createEmotionMap(valence, arousal);
            
            return new EmotionMetrics(valence, arousal, 0.5, emotions);
            
        } catch (Exception e) {
            return createNeutralEmotion();
        }
    }
    
    private static Metrics parseProsodyMetrics(String ffmpegOutput) {
        try {
            double averagePitch = extractNumericValue(ffmpegOutput, "frequency", 200.0);
            double pitchVariance = extractNumericValue(ffmpegOutput, "variance", 50.0);
            double dynamicRange = extractNumericValue(ffmpegOutput, "Dynamic_range", 20.0);
            
            VoiceType voiceType = classifyVoiceType(averagePitch);
            double expressiveness = Math.max(0.0, Math.min(1.0, dynamicRange / 40.0));
            
            return new Metrics(averagePitch, pitchVariance, pitchVariance, 
                             List.of(), voiceType, expressiveness);
            
        } catch (Exception e) {
            return createDefaultMetrics();
        }
    }
    
    private static double extractNumericValue(String output, String key) {
        return extractNumericValue(output, key, 0.0);
    }
    
    private static double extractNumericValue(String output, String key, double defaultValue) {
        try {
            Pattern pattern = Pattern.compile(key + "\\s*:\\s*([\\d.-]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        } catch (Exception e) {
            // Ignora erros de parsing
        }
        return defaultValue;
    }
    
    private static VoiceType classifyVoiceType(double averagePitch) {
        for (Map.Entry<VoiceType, double[]> entry : PITCH_RANGES.entrySet()) {
            double[] range = entry.getValue();
            if (averagePitch >= range[0] && averagePitch <= range[1]) {
                return entry.getKey();
            }
        }
        return VoiceType.TENOR; // Padrão
    }
    
    private static Map<String, Double> createEmotionMap(double valence, double arousal) {
        Map<String, Double> emotions = new HashMap<>();
        emotions.put("neutral", 0.5);
        
        if (valence > 0.3 && arousal > 0.5) {
            emotions.put("happy", 0.7);
        } else if (valence < -0.3 && arousal > 0.5) {
            emotions.put("angry", 0.6);
        } else if (valence < -0.3 && arousal < 0.3) {
            emotions.put("sad", 0.6);
        } else if (arousal < 0.3) {
            emotions.put("calm", 0.8);
        }
        
        return emotions;
    }
    
    private static EmotionMetrics createNeutralEmotion() {
        Map<String, Double> neutral = Map.of("neutral", 0.9);
        return new EmotionMetrics(0.5, 0.3, 0.5, neutral);
    }
    
    private static Metrics createDefaultMetrics() {
        return new Metrics(200.0, 50.0, 50.0, List.of(), VoiceType.TENOR, 0.5);
    }
    
    private static AnalysisResult createDefaultAnalysisResult(Path audioFile) {
        return new AnalysisResult(
            audioFile,
            List.of(),
            createNeutralEmotion(),
            createDefaultMetrics()
        );
    }
    
    private static Data createDefaultProsodyData() {
        return new Data(0.5, 0.3, 0.5, 200.0, 50.0, 0.5, "TENOR", "NEUTRAL",
                       1.0, 0.0, 1.0, "", 0, 0, 0, 0);
    }
    
    private static double getDoubleProperty(Properties props, String key, double defaultValue) {
        try {
            String value = props.getProperty(key);
            return value != null ? Double.parseDouble(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    private static int getIntProperty(Properties props, String key, int defaultValue) {
        try {
            String value = props.getProperty(key);
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}