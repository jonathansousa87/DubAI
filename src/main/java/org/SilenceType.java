package org;

/**
 * SilenceType - Wrapper de compatibilidade para Silence.Type
 * Mantém compatibilidade com código existente
 */
public enum SilenceType {
    
    INTER_WORD("Inter-word pause", 0.05, 0.1),
    PAUSE("Natural pause", 0.1, 0.3),
    BREATH("Breath/Hesitation", 0.3, 1.0),
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
        return LONG_PAUSE;
    }
    
    /**
     * Retorna peso de importância para preservação no TTS
     */
    public double getPreservationWeight() {
        return switch (this) {
            case INTER_WORD -> 0.9;
            case PAUSE -> 0.7;
            case BREATH -> 0.8;
            case LONG_PAUSE -> 0.6;
        };
    }
    
    /**
     * Converte para o novo tipo consolidado
     */
    public Silence.Type toConsolidated() {
        return switch (this) {
            case INTER_WORD -> Silence.Type.INTER_WORD;
            case PAUSE -> Silence.Type.PAUSE;
            case BREATH -> Silence.Type.BREATH;
            case LONG_PAUSE -> Silence.Type.LONG_PAUSE;
        };
    }
    
    /**
     * Cria a partir do tipo consolidado
     */
    public static SilenceType fromConsolidated(Silence.Type type) {
        return switch (type) {
            case INTER_WORD -> INTER_WORD;
            case PAUSE -> PAUSE;
            case BREATH -> BREATH;
            case LONG_PAUSE -> LONG_PAUSE;
        };
    }
}