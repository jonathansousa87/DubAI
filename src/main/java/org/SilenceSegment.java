package org;

/**
 * Representa um segmento de silêncio detectado no áudio com classificação de tipo
 */
public record SilenceSegment(
    double startTime,     // Tempo de início em segundos
    double endTime,       // Tempo de fim em segundos  
    double duration,      // Duração em segundos
    SilenceType type      // Tipo de silêncio classificado
) {
    
    public SilenceSegment {
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