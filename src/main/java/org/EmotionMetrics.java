package org;

import java.util.Collections;
import java.util.Map;

/**
 * Métricas emocionais extraídas do áudio para controle prosódico do TTS
 */
public record EmotionMetrics(
    double valence,    // -1.0 (muito negativo) a +1.0 (muito positivo)
    double arousal,    // 0.0 (muito calmo) a 1.0 (muito excitado/agitado)
    double dominance,  // 0.0 (submisso) a 1.0 (dominante/assertivo)
    Map<String, Double> emotions  // Emoções específicas com intensidades
) {
    
    public EmotionMetrics {
        // Validações
        if (valence < -1.0 || valence > 1.0) {
            throw new IllegalArgumentException("Valence deve estar entre -1.0 e 1.0");
        }
        if (arousal < 0.0 || arousal > 1.0) {
            throw new IllegalArgumentException("Arousal deve estar entre 0.0 e 1.0");
        }
        if (dominance < 0.0 || dominance > 1.0) {
            throw new IllegalArgumentException("Dominance deve estar entre 0.0 e 1.0");
        }
        
        // Fazer cópia imutável do mapa
        emotions = emotions != null ? Map.copyOf(emotions) : Collections.emptyMap();
    }
    
    /**
     * Retorna a emoção dominante (com maior intensidade)
     */
    public String getDominantEmotion() {
        return emotions.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("neutral");
    }
    
    /**
     * Retorna intensidade da emoção dominante
     */
    public double getDominantEmotionIntensity() {
        return emotions.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getValue)
                .orElse(0.5);
    }
    
    /**
     * Classifica o estado emocional geral
     */
    public EmotionalState getEmotionalState() {
        if (arousal > 0.7) {
            return valence > 0.3 ? EmotionalState.EXCITED : EmotionalState.AGITATED;
        } else if (arousal < 0.3) {
            return valence > 0.3 ? EmotionalState.CALM : EmotionalState.DEPRESSED;
        } else {
            return valence > 0.3 ? EmotionalState.CONTENT : EmotionalState.NEUTRAL;
        }
    }
    
    /**
     * Gera parâmetros de controle prosódico para Piper TTS
     */
    public ProsodyControlParams toProsodyParams() {
        double pitchAdjust = valence * 0.2; // ±20% baseado em valence
        double rateAdjust = Math.max(0.7, Math.min(1.3, 1.0 + (arousal - 0.5) * 0.4)); // 70%-130%
        double volumeAdjust = Math.max(0.6, Math.min(1.4, 1.0 + arousal * 0.4)); // 60%-140%
        
        return new ProsodyControlParams(pitchAdjust, rateAdjust, volumeAdjust);
    }
    
    /**
     * Gera SSML markup baseado nas emoções
     */
    public String generateSSMLModulation() {
        EmotionalState state = getEmotionalState();
        double intensity = getDominantEmotionIntensity();
        
        return switch (state) {
            case EXCITED -> String.format("<prosody rate=\"%.1f\" pitch=\"+%.0f%%\" volume=\"loud\">", 
                                        1.0 + intensity * 0.2, intensity * 15);
            case AGITATED -> String.format("<prosody rate=\"%.1f\" pitch=\"+%.0f%%\" volume=\"medium\">", 
                                         1.1 + intensity * 0.1, intensity * 10);
            case CALM -> String.format("<prosody rate=\"%.1f\" pitch=\"-%.0f%%\" volume=\"soft\">", 
                                     0.9 - intensity * 0.1, intensity * 5);
            case DEPRESSED -> String.format("<prosody rate=\"%.1f\" pitch=\"-%.0f%%\" volume=\"soft\">", 
                                          0.8 - intensity * 0.1, intensity * 10);
            case CONTENT -> String.format("<prosody rate=\"%.1f\" pitch=\"+%.0f%%\">", 
                                        1.0, intensity * 5);
            default -> ""; // Neutral - sem modificação
        };
    }
    
    @Override
    public String toString() {
        return String.format("Emotion[valence=%.2f, arousal=%.2f, dominant=%s(%.2f), state=%s]",
                           valence, arousal, getDominantEmotion(), getDominantEmotionIntensity(), 
                           getEmotionalState());
    }
    
    /**
     * Estados emocionais categóricos
     */
    public enum EmotionalState {
        EXCITED,    // Alto arousal, valence positivo
        AGITATED,   // Alto arousal, valence negativo  
        CONTENT,    // Médio arousal, valence positivo
        NEUTRAL,    // Médio arousal, valence neutro
        CALM,       // Baixo arousal, valence positivo
        DEPRESSED   // Baixo arousal, valence negativo
    }
    
    /**
     * Parâmetros de controle prosódico
     */
    public record ProsodyControlParams(
        double pitchAdjust,  // Multiplicador de pitch
        double rateAdjust,   // Multiplicador de velocidade
        double volumeAdjust  // Multiplicador de volume
    ) {}
}