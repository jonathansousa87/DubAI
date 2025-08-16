package org;

/**
 * Dados prosódicos carregados do arquivo de análise para uso no TTS
 */
public record ProsodyData(
    // Métricas emocionais
    double valence,           // -1.0 a +1.0 (negativo/positivo)
    double arousal,           // 0.0 a 1.0 (calmo/excitado)
    double dominance,         // 0.0 a 1.0 (submisso/dominante)
    
    // Características prosódicas  
    double avgPitch,          // Pitch médio em Hz
    double pitchVariance,     // Variabilidade do pitch
    double expressiveness,    // 0.0 a 1.0 (monotone/expressivo)
    String voiceType,         // BASS, BARITONE, TENOR, ALTO, SOPRANO
    String globalEmotion,     // EXCITED, CALM, NEUTRAL, etc.
    
    // Parâmetros TTS recomendados
    double ttsRateAdjust,     // Ajuste de velocidade
    double ttsPitchAdjust,    // Ajuste de pitch  
    double ttsVolumeAdjust,   // Ajuste de volume
    String ssmlTemplate,      // Template SSML gerado
    
    // Estatísticas de silêncios
    int totalSilences,        // Total de silêncios detectados
    int interWordSilences,    // Silêncios inter-palavra
    int pauses,              // Pausas naturais
    int breaths              // Respirações/hesitações
) {
    
    public ProsodyData {
        // Validações básicas
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
        if (avgPitch < 0) throw new IllegalArgumentException("avgPitch deve ser >= 0");
        if (pitchVariance < 0) throw new IllegalArgumentException("pitchVariance deve ser >= 0");
        
        // Valores padrão para nulls
        voiceType = voiceType != null ? voiceType : "TENOR";
        globalEmotion = globalEmotion != null ? globalEmotion : "NEUTRAL";
        ssmlTemplate = ssmlTemplate != null ? ssmlTemplate : "";
    }
    
    /**
     * Classifica se a prosódia é expressiva ou monotone
     */
    public ExpressionLevel getExpressionLevel() {
        if (expressiveness > 0.7) return ExpressionLevel.HIGHLY_EXPRESSIVE;
        if (expressiveness > 0.4) return ExpressionLevel.MODERATELY_EXPRESSIVE;
        if (expressiveness > 0.2) return ExpressionLevel.SLIGHTLY_EXPRESSIVE;
        return ExpressionLevel.MONOTONE;
    }
    
    /**
     * Retorna fator de ajuste de velocidade baseado na emoção e arousal
     */
    public double getSpeedFactor() {
        double emotionalFactor = switch (globalEmotion.toUpperCase()) {
            case "EXCITED", "AGITATED" -> 1.1;
            case "CALM", "DEPRESSED" -> 0.9;
            default -> 1.0;
        };
        
        double arousalFactor = 1.0 + (arousal - 0.5) * 0.2; // ±10% baseado em arousal
        
        return Math.max(0.7, Math.min(1.3, emotionalFactor * arousalFactor));
    }
    
    /**
     * Determina se deve usar prosódia avançada no TTS
     */
    public boolean shouldUseAdvancedProsody() {
        return expressiveness > 0.3 || 
               !globalEmotion.equals("NEUTRAL") || 
               Math.abs(valence) > 0.3 ||
               arousal > 0.5;
    }
    
    /**
     * Gera configuração de noise para Piper baseada na expressividade
     */
    public NoiseConfig getNoiseConfig() {
        double baseNoise = Math.min(0.3, expressiveness * 0.4);
        double noiseW = Math.min(0.3, pitchVariance / 200.0);
        
        return new NoiseConfig(baseNoise, noiseW);
    }
    
    /**
     * Densidade de silêncios por minuto
     */
    public double getSilenceDensity() {
        if (totalSilences == 0) return 0.0;
        // Assumindo áudio típico de 1-3 minutos
        return totalSilences / 2.0; // Estimativa
    }
    
    /**
     * Indica se a fala tem muitas pausas/respirações (estilo conversacional)
     */
    public boolean isConversationalStyle() {
        return breaths > 2 || getSilenceDensity() > 5.0;
    }
    
    @Override
    public String toString() {
        return String.format("Prosody[emotion=%s, expressiveness=%.1f%%, pitch=%.1fHz, " +
                           "silences=%d, arousal=%.2f, valence=%+.2f]",
                           globalEmotion, expressiveness * 100, avgPitch, 
                           totalSilences, arousal, valence);
    }
    
    /**
     * Níveis de expressividade
     */
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
    
    /**
     * Configuração de noise para Piper
     */
    public record NoiseConfig(
        double noiseScale,   // Variabilidade prosódica (0.0-0.3)
        double noiseW        // Variabilidade temporal (0.0-0.3)
    ) {}
}