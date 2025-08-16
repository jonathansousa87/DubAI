package org;

import java.nio.file.Path;
import java.util.List;

/**
 * Resultado completo da análise prosódica de um arquivo de áudio
 */
public record ProsodyAnalysisResult(
    Path audioFile,                    // Arquivo de áudio analisado
    List<SilenceSegment> silences,     // Segmentos de silêncio detectados
    EmotionMetrics emotions,           // Métricas emocionais
    ProsodyMetrics prosody            // Métricas prosódicas detalhadas
) {
    
    public ProsodyAnalysisResult {
        if (audioFile == null) throw new IllegalArgumentException("audioFile não pode ser null");
        if (silences == null) throw new IllegalArgumentException("silences não pode ser null");
        if (emotions == null) throw new IllegalArgumentException("emotions não pode ser null");
        if (prosody == null) throw new IllegalArgumentException("prosody não pode ser null");
        
        // Fazer cópias imutáveis
        silences = List.copyOf(silences);
    }
    
    /**
     * Calcula a duração total de silêncios
     */
    public double getTotalSilenceDuration() {
        return silences.stream().mapToDouble(SilenceSegment::duration).sum();
    }
    
    /**
     * Retorna silêncios por tipo
     */
    public List<SilenceSegment> getSilencesByType(SilenceType type) {
        return silences.stream()
                .filter(silence -> silence.type() == type)
                .toList();
    }
    
    /**
     * Encontra silêncios em um intervalo de tempo específico
     */
    public List<SilenceSegment> getSilencesInRange(double startTime, double endTime) {
        return silences.stream()
                .filter(silence -> silence.overlaps(startTime, endTime))
                .toList();
    }
    
    /**
     * Gera resumo textual da análise
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== ANÁLISE PROSÓDICA ===\n");
        summary.append(String.format("Arquivo: %s\n", audioFile.getFileName()));
        summary.append(String.format("Total silêncios: %d (%.3fs)\n", 
                                    silences.size(), getTotalSilenceDuration()));
        
        // Breakdown por tipo de silêncio
        for (SilenceType type : SilenceType.values()) {
            List<SilenceSegment> typesilences = getSilencesByType(type);
            if (!typesilences.isEmpty()) {
                double totalDuration = typesilences.stream().mapToDouble(SilenceSegment::duration).sum();
                summary.append(String.format("  %s: %d (%.3fs)\n", 
                                            type.getDescription(), typesilences.size(), totalDuration));
            }
        }
        
        // Métricas emocionais
        summary.append(String.format("Estado emocional: %s\n", emotions.getEmotionalState()));
        summary.append(String.format("Emoção dominante: %s (%.1f%%)\n", 
                                    emotions.getDominantEmotion(), 
                                    emotions.getDominantEmotionIntensity() * 100));
        
        // Métricas prosódicas
        summary.append(String.format("Pitch médio: %.1fHz (%s)\n", 
                                    prosody.averagePitch(), prosody.getVoiceType()));
        summary.append(String.format("Expressividade: %.1f%%\n", 
                                    prosody.getExpressiveness() * 100));
        summary.append(String.format("Momentos de ênfase: %d\n", 
                                    prosody.findEmphasisMoments().size()));
        
        return summary.toString();
    }
    
    /**
     * Gera configurações recomendadas para TTS
     */
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
    
    /**
     * Recomendações específicas para TTS baseadas na análise
     */
    public record TTSRecommendations(
        double lengthScale,       // Controle de velocidade Piper
        double noiseScale,        // Variabilidade Piper  
        double noiseW,           // Variabilidade temporal Piper
        double pitchAdjust,      // Ajuste de pitch SSML
        double rateAdjust,       // Ajuste de rate SSML
        double volumeAdjust,     // Ajuste de volume SSML
        String ssmlModulation,   // Markup SSML gerado
        ProsodyMetrics.VoiceType recommendedVoice // Tipo de voz recomendado
    ) {
        
        @Override
        public String toString() {
            return String.format("TTS Config[length=%.2f, noise=%.2f, noiseW=%.2f, " +
                               "pitch=%+.1f%%, rate=%.2f, volume=%.2f, voice=%s]",
                               lengthScale, noiseScale, noiseW, 
                               pitchAdjust * 100, rateAdjust, volumeAdjust, recommendedVoice);
        }
    }
    
    @Override
    public String toString() {
        return String.format("ProsodyAnalysis[%s: %d silences, %s emotion, %.1f%% expressive]",
                           audioFile.getFileName(), silences.size(), 
                           emotions.getEmotionalState(), prosody.getExpressiveness() * 100);
    }
}