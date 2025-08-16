package org;

import java.util.Collections;
import java.util.List;

/**
 * Métricas prosódicas detalhadas extraídas do áudio
 */
public record ProsodyMetrics(
    List<PitchPoint> pitchContour,        // Contorno de pitch frame-by-frame
    List<IntensityPoint> intensityContour, // Contorno de intensidade frame-by-frame
    double averagePitch,                   // Pitch médio em Hz
    double pitchVariance,                  // Variância do pitch
    List<StressedSyllable> stressPattern   // Padrão de acentuação
) {
    
    public ProsodyMetrics {
        // Fazer cópias imutáveis
        pitchContour = pitchContour != null ? List.copyOf(pitchContour) : Collections.emptyList();
        intensityContour = intensityContour != null ? List.copyOf(intensityContour) : Collections.emptyList();
        stressPattern = stressPattern != null ? List.copyOf(stressPattern) : Collections.emptyList();
        
        // Validações
        if (averagePitch < 0) throw new IllegalArgumentException("averagePitch deve ser >= 0");
        if (pitchVariance < 0) throw new IllegalArgumentException("pitchVariance deve ser >= 0");
    }
    
    /**
     * Calcula a expressividade prosódica geral
     * Valores maiores indicam fala mais expressiva/dinâmica
     */
    public double getExpressiveness() {
        double pitchDynamics = Math.min(1.0, pitchVariance / 50.0); // Normalizar variance
        double intensityDynamics = calculateIntensityDynamics();
        double stressComplexity = Math.min(1.0, stressPattern.size() / 10.0);
        
        return (pitchDynamics * 0.5 + intensityDynamics * 0.3 + stressComplexity * 0.2);
    }
    
    private double calculateIntensityDynamics() {
        if (intensityContour.isEmpty()) return 0.5;
        
        double min = intensityContour.stream().mapToDouble(IntensityPoint::intensity).min().orElse(0);
        double max = intensityContour.stream().mapToDouble(IntensityPoint::intensity).max().orElse(0);
        
        return Math.min(1.0, (max - min) / 40.0); // Normalizar dynamic range
    }
    
    /**
     * Determina o tipo de voz baseado no pitch médio
     */
    public VoiceType getVoiceType() {
        if (averagePitch < 100) return VoiceType.BASS;
        if (averagePitch < 130) return VoiceType.BARITONE; 
        if (averagePitch < 165) return VoiceType.TENOR;
        if (averagePitch < 220) return VoiceType.ALTO;
        if (averagePitch < 300) return VoiceType.SOPRANO;
        return VoiceType.SOPRANO;
    }
    
    /**
     * Gera parâmetros de controle para o TTS baseados na prosódia original
     */
    public TTSControlParams generateTTSParams() {
        double expressiveness = getExpressiveness();
        VoiceType voiceType = getVoiceType();
        
        // Ajustar parâmetros baseado na expressividade
        double lengthScale = Math.max(0.8, Math.min(1.2, 1.0 - (expressiveness - 0.5) * 0.2));
        double noiseScale = Math.max(0.0, Math.min(0.3, expressiveness * 0.15));
        double noiseW = Math.max(0.0, Math.min(0.3, expressiveness * 0.1));
        
        return new TTSControlParams(lengthScale, noiseScale, noiseW, voiceType);
    }
    
    /**
     * Encontra momentos de ênfase alta na fala
     */
    public List<EmphasisMoment> findEmphasisMoments() {
        return stressPattern.stream()
                .filter(stress -> stress.intensity() > 0.7)
                .map(stress -> new EmphasisMoment(stress.timeStart(), stress.timeEnd(), stress.intensity()))
                .toList();
    }
    
    @Override
    public String toString() {
        return String.format("Prosody[avgPitch=%.1fHz, variance=%.1f, expressiveness=%.2f, voice=%s, emphasis=%d]",
                           averagePitch, pitchVariance, getExpressiveness(), 
                           getVoiceType(), findEmphasisMoments().size());
    }
    
    /**
     * Ponto de pitch no tempo
     */
    public record PitchPoint(double timeSeconds, double pitchHz) {}
    
    /**
     * Ponto de intensidade no tempo  
     */
    public record IntensityPoint(double timeSeconds, double intensity) {}
    
    /**
     * Sílaba acentuada com timing
     */
    public record StressedSyllable(double timeStart, double timeEnd, double intensity, String syllable) {}
    
    /**
     * Momento de ênfase detectado
     */
    public record EmphasisMoment(double timeStart, double timeEnd, double intensity) {}
    
    /**
     * Tipos de voz baseados no pitch
     */
    public enum VoiceType {
        BASS(50, 100),
        BARITONE(100, 130),
        TENOR(130, 165), 
        ALTO(165, 220),
        SOPRANO(220, 400);
        
        private final double minHz;
        private final double maxHz;
        
        VoiceType(double minHz, double maxHz) {
            this.minHz = minHz;
            this.maxHz = maxHz;
        }
        
        public double getMinHz() { return minHz; }
        public double getMaxHz() { return maxHz; }
    }
    
    /**
     * Parâmetros de controle para TTS
     */
    public record TTSControlParams(
        double lengthScale,  // Controle de velocidade (0.5-2.0)
        double noiseScale,   // Variabilidade prosódica (0.0-1.0)
        double noiseW,       // Variabilidade temporal (0.0-1.0)
        VoiceType voiceType  // Tipo de voz recomendado
    ) {}
}