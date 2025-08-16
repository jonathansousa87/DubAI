package org;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WhisperXPlusUtils - Extensão avançada do WhisperUtils com análise prosódica completa
 * Combina transcrição WhisperX com análise de silêncios, emoções e prosódia
 */
public class WhisperXPlusUtils extends WhisperUtils {
    
    private static final Logger logger = Logger.getLogger(WhisperXPlusUtils.class.getName());
    
    // Pattern para parsing de arquivos VTT
    private static final Pattern VTT_TIMESTAMP_PATTERN = Pattern.compile(
        "^(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})$"
    );
    
    /**
     * Transcrição completa com análise prosódica avançada
     */
    public static EnhancedTranscription transcribeWithProsody(String inputFile, String outputVtt) 
            throws IOException, InterruptedException {
        
        logger.info("🎯 Iniciando transcrição avançada com análise prosódica");
        
        // 1. TRANSCRIÇÃO BÁSICA (usando implementação existente)
        transcribeAudio(inputFile, outputVtt);
        
        Path audioPath = Paths.get(inputFile);
        if (!Files.exists(audioPath)) {
            throw new IOException("Arquivo de áudio não encontrado: " + inputFile);
        }
        
        // 2. ANÁLISES PARALELAS - execução simultânea para otimização
        logger.info("🔍 Iniciando análises prosódicas paralelas...");
        
        CompletableFuture<ProsodyAnalysisResult> prosodyAnalysis = 
            ProsodyAnalysisUtils.analyzeComplete(audioPath);
        
        // 3. PARSING DA TRANSCRIÇÃO VTT
        List<VTTEntry> basicTranscription = parseVTTFile(outputVtt);
        
        try {
            // 4. AGUARDAR CONCLUSÃO DAS ANÁLISES
            ProsodyAnalysisResult prosodyResult = prosodyAnalysis.get();
            
            // 5. ENRIQUECER TRANSCRIÇÃO COM DADOS PROSÓDICOS
            List<EnhancedVTTEntry> enhancedEntries = enrichTranscriptionWithProsody(
                basicTranscription, prosodyResult
            );
            
            // 6. CRIAR RESULTADO FINAL
            EnhancedTranscription result = new EnhancedTranscription(
                audioPath,
                enhancedEntries,
                prosodyResult.silences(),
                prosodyResult.emotions(),
                prosodyResult.prosody()
            );
            
            logger.info(String.format("✅ Transcrição avançada concluída: %d segmentos, %d silêncios", 
                       enhancedEntries.size(), prosodyResult.silences().size()));
            
            return result;
            
        } catch (ExecutionException e) {
            logger.warning("⚠️ Erro na análise prosódica: " + e.getMessage());
            // Fallback para transcrição básica
            return createBasicEnhancedTranscription(audioPath, basicTranscription);
        }
    }
    
    /**
     * Parse de arquivo VTT para extrair entradas com timing
     */
    private static List<VTTEntry> parseVTTFile(String vttPath) throws IOException {
        List<VTTEntry> entries = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(vttPath))) {
            String line;
            VTTEntry currentEntry = null;
            StringBuilder textBuilder = new StringBuilder();
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.isEmpty()) {
                    if (currentEntry != null && textBuilder.length() > 0) {
                        currentEntry = new VTTEntry(
                            currentEntry.startTime(),
                            currentEntry.endTime(),
                            textBuilder.toString().trim()
                        );
                        entries.add(currentEntry);
                        textBuilder.setLength(0);
                        currentEntry = null;
                    }
                    continue;
                }
                
                if (line.equals("WEBVTT") || line.matches("\\d+")) {
                    continue; // Skip header and sequence numbers
                }
                
                Matcher matcher = VTT_TIMESTAMP_PATTERN.matcher(line);
                if (matcher.matches()) {
                    double startTime = parseTimestamp(matcher, 1);
                    double endTime = parseTimestamp(matcher, 5);
                    currentEntry = new VTTEntry(startTime, endTime, "");
                } else if (currentEntry != null) {
                    if (textBuilder.length() > 0) {
                        textBuilder.append(" ");
                    }
                    textBuilder.append(line);
                }
            }
            
            // Handle last entry
            if (currentEntry != null && textBuilder.length() > 0) {
                currentEntry = new VTTEntry(
                    currentEntry.startTime(),
                    currentEntry.endTime(),
                    textBuilder.toString().trim()
                );
                entries.add(currentEntry);
            }
        }
        
        logger.info(String.format("📝 Parsed %d VTT entries from %s", entries.size(), vttPath));
        return entries;
    }
    
    private static double parseTimestamp(Matcher matcher, int startGroup) {
        int hours = Integer.parseInt(matcher.group(startGroup));
        int minutes = Integer.parseInt(matcher.group(startGroup + 1));
        int seconds = Integer.parseInt(matcher.group(startGroup + 2));
        int milliseconds = Integer.parseInt(matcher.group(startGroup + 3));
        
        return hours * 3600.0 + minutes * 60.0 + seconds + milliseconds / 1000.0;
    }
    
    /**
     * Enriquece entradas VTT com dados prosódicos
     */
    private static List<EnhancedVTTEntry> enrichTranscriptionWithProsody(
            List<VTTEntry> transcription, ProsodyAnalysisResult prosodyResult) {
        
        List<EnhancedVTTEntry> enhanced = new ArrayList<>();
        
        for (VTTEntry entry : transcription) {
            // Encontrar silêncios relacionados a este segmento
            List<SilenceSegment> relatedSilences = prosodyResult.getSilencesInRange(
                entry.startTime(), entry.endTime()
            );
            
            // Encontrar momentos de ênfase neste segmento
            List<ProsodyMetrics.EmphasisMoment> emphasis = prosodyResult.prosody()
                .findEmphasisMoments().stream()
                .filter(em -> em.timeStart() >= entry.startTime() && em.timeEnd() <= entry.endTime())
                .toList();
            
            // Determinar características prosódicas do segmento
            double avgPitchInSegment = calculateSegmentAveragePitch(
                entry, prosodyResult.prosody().pitchContour()
            );
            
            double avgIntensityInSegment = calculateSegmentAverageIntensity(
                entry, prosodyResult.prosody().intensityContour()
            );
            
            // Criar entrada enriquecida
            enhanced.add(new EnhancedVTTEntry(
                entry.startTime(),
                entry.endTime(), 
                entry.text(),
                relatedSilences,
                emphasis,
                avgPitchInSegment,
                avgIntensityInSegment,
                prosodyResult.emotions() // Emoções globais do áudio
            ));
        }
        
        return enhanced;
    }
    
    private static double calculateSegmentAveragePitch(VTTEntry entry, List<ProsodyMetrics.PitchPoint> pitchContour) {
        if (pitchContour.isEmpty()) return 150.0; // Default
        
        List<Double> segmentPitches = pitchContour.stream()
            .filter(point -> point.timeSeconds() >= entry.startTime() && 
                           point.timeSeconds() <= entry.endTime())
            .map(ProsodyMetrics.PitchPoint::pitchHz)
            .filter(pitch -> pitch > 0) // Filtrar unvoiced frames
            .toList();
            
        return segmentPitches.isEmpty() ? 150.0 : 
               segmentPitches.stream().mapToDouble(Double::doubleValue).average().orElse(150.0);
    }
    
    private static double calculateSegmentAverageIntensity(VTTEntry entry, List<ProsodyMetrics.IntensityPoint> intensityContour) {
        if (intensityContour.isEmpty()) return -20.0; // Default
        
        List<Double> segmentIntensities = intensityContour.stream()
            .filter(point -> point.timeSeconds() >= entry.startTime() && 
                           point.timeSeconds() <= entry.endTime())
            .map(ProsodyMetrics.IntensityPoint::intensity)
            .toList();
            
        return segmentIntensities.isEmpty() ? -20.0 : 
               segmentIntensities.stream().mapToDouble(Double::doubleValue).average().orElse(-20.0);
    }
    
    /**
     * Cria transcrição básica em caso de fallback
     */
    private static EnhancedTranscription createBasicEnhancedTranscription(
            Path audioFile, List<VTTEntry> basicTranscription) {
        
        // Criar dados prosódicos neutros
        EmotionMetrics neutralEmotion = new EmotionMetrics(0.5, 0.3, 0.5, Map.of("neutral", 0.9));
        ProsodyMetrics basicProsody = new ProsodyMetrics(
            Collections.emptyList(), Collections.emptyList(), 150.0, 20.0, Collections.emptyList()
        );
        
        // Converter para entradas enriquecidas básicas
        List<EnhancedVTTEntry> enhanced = basicTranscription.stream()
            .map(entry -> new EnhancedVTTEntry(
                entry.startTime(), entry.endTime(), entry.text(),
                Collections.emptyList(), // Sem silêncios
                Collections.emptyList(), // Sem ênfases
                150.0, -20.0, neutralEmotion
            ))
            .toList();
        
        return new EnhancedTranscription(audioFile, enhanced, Collections.emptyList(), 
                                       neutralEmotion, basicProsody);
    }
    
    /**
     * Geração de SSML avançado baseado na análise prosódica
     */
    public static String generateAdvancedSSML(EnhancedVTTEntry entry) {
        StringBuilder ssml = new StringBuilder();
        
        // Aplicar modulação emocional global
        String emotionalModulation = entry.emotions().generateSSMLModulation();
        if (!emotionalModulation.isEmpty()) {
            ssml.append(emotionalModulation);
        }
        
        // Processar texto com ênfases locais
        String processedText = applyEmphasisToText(entry.text(), entry.emphasis());
        
        // Adicionar pausas baseadas em silêncios
        processedText = insertSilenceMarkers(processedText, entry.relatedSilences());
        
        ssml.append(processedText);
        
        // Fechar tags emocionais
        if (!emotionalModulation.isEmpty()) {
            ssml.append("</prosody>");
        }
        
        return ssml.toString();
    }
    
    private static String applyEmphasisToText(String text, List<ProsodyMetrics.EmphasisMoment> emphasis) {
        if (emphasis.isEmpty()) return text;
        
        // Implementação simplificada - em produção seria mais sofisticada
        // Aplicar <emphasis> nas partes mais importantes
        for (ProsodyMetrics.EmphasisMoment em : emphasis) {
            if (em.intensity() > 0.8) {
                // Encontrar palavras aproximadas no tempo e aplicar ênfase
                // Esta é uma implementação básica
                text = text.replaceFirst("\\b\\w+\\b", "<emphasis level=\"strong\">$0</emphasis>");
            }
        }
        
        return text;
    }
    
    private static String insertSilenceMarkers(String text, List<SilenceSegment> silences) {
        if (silences.isEmpty()) return text;
        
        StringBuilder result = new StringBuilder(text);
        
        // Inserir <break> baseado nos tipos de silêncio
        for (SilenceSegment silence : silences) {
            String breakTag = switch (silence.type()) {
                case INTER_WORD -> "<break time=\"50ms\"/>";
                case PAUSE -> String.format("<break time=\"%.0fms\"/>", silence.duration() * 1000);
                case BREATH -> String.format("<break time=\"%.0fms\"/>", silence.duration() * 1000);
                case LONG_PAUSE -> String.format("<break time=\"%.0fms\"/>", Math.min(silence.duration() * 1000, 2000));
            };
            
            // Inserir em posição apropriada (implementação simplificada)
            result.append(" ").append(breakTag);
        }
        
        return result.toString();
    }
}

/**
 * Entrada VTT básica com timing
 */
record VTTEntry(double startTime, double endTime, String text) {}

/**
 * Entrada VTT enriquecida com dados prosódicos
 */
record EnhancedVTTEntry(
    double startTime,
    double endTime,
    String text,
    List<SilenceSegment> relatedSilences,
    List<ProsodyMetrics.EmphasisMoment> emphasis,
    double averagePitch,
    double averageIntensity,
    EmotionMetrics emotions
) {}

/**
 * Transcrição completa enriquecida com análise prosódica
 */
record EnhancedTranscription(
    Path audioFile,
    List<EnhancedVTTEntry> entries,
    List<SilenceSegment> silences,
    EmotionMetrics emotions,
    ProsodyMetrics prosody
) {
    
    public EnhancedTranscription {
        if (audioFile == null) throw new IllegalArgumentException("audioFile não pode ser null");
        entries = entries != null ? List.copyOf(entries) : Collections.emptyList();
        silences = silences != null ? List.copyOf(silences) : Collections.emptyList();
        if (emotions == null) throw new IllegalArgumentException("emotions não pode ser null");
        if (prosody == null) throw new IllegalArgumentException("prosody não pode ser null");
    }
    
    /**
     * Gera relatório completo da transcrição
     */
    public String generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== ENHANCED TRANSCRIPTION REPORT ===\n");
        report.append(String.format("Audio: %s\n", audioFile.getFileName()));
        report.append(String.format("Segments: %d\n", entries.size()));
        report.append(String.format("Total silences: %d (%.3fs)\n", 
                     silences.size(), silences.stream().mapToDouble(SilenceSegment::duration).sum()));
        report.append(String.format("Emotional state: %s\n", emotions.getEmotionalState()));
        report.append(String.format("Average pitch: %.1fHz\n", prosody.averagePitch()));
        report.append(String.format("Expressiveness: %.1f%%\n", prosody.getExpressiveness() * 100));
        
        return report.toString();
    }
}