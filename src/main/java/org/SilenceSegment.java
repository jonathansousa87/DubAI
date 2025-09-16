package org;

/**
 * SilenceSegment - Wrapper de compatibilidade para Silence.Segment
 * Mantém compatibilidade com código existente
 */
public record SilenceSegment(
    double startTime,
    double endTime, 
    double duration,
    SilenceType type
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
     * Converte para o novo formato consolidado
     */
    public Silence.Segment toConsolidated() {
        return new Silence.Segment(startTime, endTime, duration, 
                                 Silence.convertLegacyType(type));
    }
    
    /**
     * Cria a partir do formato consolidado
     */
    public static SilenceSegment fromConsolidated(Silence.Segment segment) {
        SilenceType legacyType = switch (segment.type()) {
            case INTER_WORD -> SilenceType.INTER_WORD;
            case PAUSE -> SilenceType.PAUSE;
            case BREATH -> SilenceType.BREATH;
            case LONG_PAUSE -> SilenceType.LONG_PAUSE;
        };
        
        return new SilenceSegment(segment.startTime(), segment.endTime(), 
                                segment.duration(), legacyType);
    }
    
    @Override
    public String toString() {
        return String.format("Silence[%.3f-%.3f, %.3fs, %s]", 
                           startTime, endTime, duration, type);
    }
}