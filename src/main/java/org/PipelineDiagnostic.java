package org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Diagnóstico rápido da pipeline para identificar problemas
 */
public class PipelineDiagnostic {
    
    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("🔍 Iniciando diagnóstico da pipeline...");
        
        // Inicializar logging
        PipelineDebugLogger.setEnabled(true);
        PipelineDebugLogger.logPipelineStart("diagnóstico");
        
        // 1. Testar tradução inteligente
        testTranslationSystem();
        
        // 2. Testar disponibilidade de modelos
        testModelAvailability();
        
        // 3. Analisar áudios existentes
        analyzeExistingAudios();
        
        // 4. Verificar prosódia
        testProsodySystem();
        
        System.out.println("🔍 Diagnóstico concluído! Verifique: " + PipelineDebugLogger.getLogFilePath());
    }
    
    private static void testTranslationSystem() {
        System.out.println("\n=== TESTE: Sistema de Tradução ===");
        
        try {
            // Testar se consegue acessar API
            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-m", "5", "http://localhost:11434/api/tags");
            Process process = pb.start();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            
            if (finished && process.exitValue() == 0) {
                PipelineDebugLogger.logTranslationAttempt("API_TEST", "SUCCESS", "Ollama API está rodando");
                System.out.println("✅ Ollama API está rodando");
            } else {
                PipelineDebugLogger.logTranslationAttempt("API_TEST", "FAILED", "Ollama API não responde");
                System.out.println("❌ Ollama API não responde");
            }
            
        } catch (Exception e) {
            PipelineDebugLogger.logTranslationAttempt("API_TEST", "ERROR", e.getMessage());
            System.out.println("❌ Erro testando API: " + e.getMessage());
        }
    }
    
    private static void testModelAvailability() {
        System.out.println("\n=== TESTE: Disponibilidade de Modelos ===");
        
        String[] models = {"qwen3:14b", "deepseek-r1:8b", "gemma3:12b"};
        
        for (String model : models) {
            try {
                ProcessBuilder pb = new ProcessBuilder("ollama", "list");
                Process process = pb.start();
                
                StringBuilder output = new StringBuilder();
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }
                
                boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    String modelBase = model.split(":")[0];
                    boolean available = output.toString().toLowerCase().contains(modelBase.toLowerCase());
                    
                    PipelineDebugLogger.logTranslationAttempt(model, available ? "AVAILABLE" : "UNAVAILABLE", 
                        "Modelo " + (available ? "encontrado" : "não encontrado") + " na lista");
                    
                    System.out.println((available ? "✅" : "❌") + " " + model + ": " + 
                                     (available ? "disponível" : "não disponível"));
                }
                
            } catch (Exception e) {
                PipelineDebugLogger.logTranslationAttempt(model, "ERROR", e.getMessage());
                System.out.println("❌ Erro verificando " + model + ": " + e.getMessage());
            }
        }
    }
    
    private static void analyzeExistingAudios() throws IOException {
        System.out.println("\n=== ANÁLISE: Áudios Existentes ===");
        
        Path outputDir = Paths.get("output");
        if (!Files.exists(outputDir)) {
            System.out.println("❌ Diretório output não existe");
            return;
        }
        
        List<Path> audioFiles = Files.list(outputDir)
            .filter(p -> p.getFileName().toString().matches("audio_\\d{3}\\.wav"))
            .sorted()
            .limit(10) // Analisar apenas os primeiros 10
            .toList();
            
        System.out.println("🔍 Analisando " + audioFiles.size() + " arquivos de áudio...");
        
        int silentFiles = 0;
        int problematicFiles = 0;
        
        for (Path audioFile : audioFiles) {
            try {
                long fileSize = Files.size(audioFile);
                
                // Usar ffprobe para obter duração
                ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "quiet", 
                    "-show_entries", "format=duration", "-of", "csv=p=0", audioFile.toString());
                
                Process process = pb.start();
                StringBuilder output = new StringBuilder();
                
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                }
                
                boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    double duration = Double.parseDouble(output.toString().trim());
                    
                    String fileName = audioFile.getFileName().toString();
                    int segmentIndex = Integer.parseInt(fileName.replaceAll("\\D+", ""));
                    
                    if (fileSize < 1000) {
                        PipelineDebugLogger.logSilenceIssue(segmentIndex, "TINY_FILE", duration, 
                            String.format("Arquivo muito pequeno: %d bytes", fileSize));
                        silentFiles++;
                    } else if (duration < 0.1) {
                        PipelineDebugLogger.logSilenceIssue(segmentIndex, "VERY_SHORT", duration,
                            String.format("Duração muito curta: %.3fs", duration));
                        problematicFiles++;
                    } else {
                        PipelineDebugLogger.logTTSGeneration(segmentIndex, "análise", duration, duration, 
                            String.format("OK - %.1fKB, %.3fs", fileSize/1024.0, duration));
                    }
                }
                
            } catch (Exception e) {
                System.out.println("❌ Erro analisando " + audioFile.getFileName() + ": " + e.getMessage());
            }
        }
        
        System.out.printf("📊 Resultados: %d OK, %d silenciosos, %d problemáticos\n", 
                         audioFiles.size() - silentFiles - problematicFiles, silentFiles, problematicFiles);
    }
    
    private static void testProsodySystem() {
        System.out.println("\n=== TESTE: Sistema de Prosódia ===");
        
        // Verificar se arquivos de prosódia existem
        String[] prosodyFiles = {
            "output/prosody_analysis.txt",
            "output/prosody_data.properties"
        };
        
        for (String file : prosodyFiles) {
            Path filePath = Paths.get(file);
            if (Files.exists(filePath)) {
                try {
                    long size = Files.size(filePath);
                    PipelineDebugLogger.logProsodyAnalysis("FILE_CHECK", 
                        String.format("%s existe (%.1fKB)", file, size/1024.0));
                    System.out.println("✅ " + file + " existe (" + size/1024.0 + "KB)");
                } catch (IOException e) {
                    System.out.println("❌ Erro lendo " + file + ": " + e.getMessage());
                }
            } else {
                PipelineDebugLogger.logProsodyAnalysis("FILE_MISSING", file + " não existe");
                System.out.println("❌ " + file + " não existe");
            }
        }
    }
}