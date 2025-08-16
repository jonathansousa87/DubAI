package org;

/**
 * Classificação dos tipos de silêncio detectados no áudio
 */
public enum SilenceType {
    
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
    
    SilenceType(String description, double minDuration, double maxDuration) {
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
    public static SilenceType classify(double duration) {
        for (SilenceType type : values()) {
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