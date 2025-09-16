package org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ferramenta de Debug VTT para identificar problemas no parsing
 */
public class VTTDebugTool {

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    );


    public static void analyzeVTTFile(String vttFilePath) throws IOException {
        Path vttPath = Paths.get(vttFilePath);

        if (!Files.exists(vttPath)) {
            System.err.println("❌ Arquivo VTT não encontrado: " + vttPath);
            return;
        }

        List<String> lines = Files.readAllLines(vttPath);

        System.out.println("📁 Arquivo: " + vttPath.getFileName());
        System.out.println("📊 Total de linhas: " + lines.size());
        System.out.println("📦 Tamanho do arquivo: " + Files.size(vttPath) + " bytes");
        System.out.println();

        // Análise linha por linha
        boolean foundWebVTT = false;
        int timestampCount = 0;
        int textSegments = 0;
        int emptyLines = 0;

        System.out.println("🔍 ANÁLISE LINHA POR LINHA:");
        System.out.println("-".repeat(50));

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Mostra primeiras 30 linhas para debug
            if (i < 30) {
                System.out.printf("Linha %3d: '%s'%n", i + 1, line);

                if (line.startsWith("WEBVTT")) {
                    foundWebVTT = true;
                    System.out.println("         ✅ WEBVTT header encontrado");
                }

                if (isTimestampLine(line)) {
                    timestampCount++;
                    System.out.println("         🕐 TIMESTAMP detectado");

                    // Testa parsing
                    if (TIMESTAMP_PATTERN.matcher(line).matches()) {
                        System.out.println("         ✅ Parsing OK");
                    } else {
                        System.out.println("         ❌ Parsing FALHOU");
                        testAlternativeParsing(line);
                    }
                }

                if (line.isEmpty()) {
                    emptyLines++;
                } else if (!line.startsWith("WEBVTT") && !line.matches("^\\d+$") &&
                        !isTimestampLine(line) && !line.startsWith("NOTE")) {
                    textSegments++;
                    System.out.println("         📝 Texto detectado");
                }
            }

            // Conta estatísticas globais
            if (i >= 30) {
                if (line.startsWith("WEBVTT")) foundWebVTT = true;
                if (isTimestampLine(line)) timestampCount++;
                if (line.isEmpty()) emptyLines++;
                else if (!line.startsWith("WEBVTT") && !line.matches("^\\d+$") &&
                        !isTimestampLine(line) && !line.startsWith("NOTE")) {
                    textSegments++;
                }
            }
        }

        System.out.println();
        System.out.println("📊 ESTATÍSTICAS FINAIS:");
        System.out.println("-".repeat(30));
        System.out.println("WEBVTT header: " + (foundWebVTT ? "✅ Encontrado" : "❌ Ausente"));
        System.out.println("Timestamps: " + timestampCount);
        System.out.println("Segmentos de texto: " + textSegments);
        System.out.println("Linhas vazias: " + emptyLines);

        // Calcula segmentos esperados
        int expectedSegments = timestampCount;
        System.out.println("Segmentos esperados: " + expectedSegments);

        if (expectedSegments == 0) {
            System.out.println();
            System.out.println("❌ PROBLEMA IDENTIFICADO: Nenhum timestamp válido encontrado!");
            System.out.println("🔧 POSSÍVEIS CAUSAS:");
            System.out.println("   1. Formato de timestamp incorreto");
            System.out.println("   2. Arquivo VTT corrompido");
            System.out.println("   3. Encoding de caracteres inválido");

            // Tenta detectar padrões alternativos
            System.out.println();
            System.out.println("🔍 PROCURANDO PADRÕES ALTERNATIVOS:");
            detectAlternativePatterns(lines);
        } else {
            System.out.println();
            System.out.println("✅ ARQUIVO VTT PARECE VÁLIDO");
            System.out.println("🎯 Segmentos que serão processados: " + expectedSegments);
        }
    }

    private static boolean isTimestampLine(String line) {
        // Verificação mais flexível
        return line.contains("-->") &&
                line.matches(".*\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{3}.*");
    }

    private static void testAlternativeParsing(String line) {
        System.out.println("         🔧 Testando parsing alternativo:");

        // Teste 1: Split simples
        try {
            String[] parts = line.split("\\s*-->\\s*");
            if (parts.length == 2) {
                System.out.println("         ✅ Split por '-->' OK: " + parts.length + " partes");
                System.out.println("            Início: '" + parts[0].trim() + "'");
                System.out.println("            Fim: '" + parts[1].trim() + "'");

                // Testa parsing de cada parte
                testTimeParsing(parts[0].trim(), "início");
                testTimeParsing(parts[1].trim(), "fim");
            } else {
                System.out.println("         ❌ Split por '-->' falhou");
            }
        } catch (Exception e) {
            System.out.println("         ❌ Erro no split: " + e.getMessage());
        }
    }

    private static void testTimeParsing(String timeStr, String position) {
        try {
            // Normaliza vírgulas para pontos
            String normalized = timeStr.trim().replace(',', '.');

            String[] parts = normalized.split(":");
            if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                double seconds = Double.parseDouble(parts[2]);

                double totalSeconds = hours * 3600.0 + minutes * 60.0 + seconds;
                System.out.println(String.format("            ✅ %s: %02d:%02d:%.3f = %.3fs",
                        position, hours, minutes, seconds, totalSeconds));
            } else {
                System.out.println("            ❌ " + position + ": formato inválido (" + parts.length + " partes)");
            }
        } catch (Exception e) {
            System.out.println("            ❌ " + position + ": erro parsing (" + e.getMessage() + ")");
        }
    }

    private static void detectAlternativePatterns(List<String> lines) {
        String[] patterns = {
                "\\d{1,2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2},\\d{3}",
                "\\d{1,2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}\\.\\d{3}",
                "\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}",
                "\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}",
                ".*-->.*",
                "\\d+:\\d+:\\d+.*\\d+:\\d+:\\d+"
        };

        String[] names = {
                "VTT com vírgulas (1-2 dígitos hora)",
                "VTT com pontos (1-2 dígitos hora)",
                "VTT com vírgulas (2 dígitos hora)",
                "VTT com pontos (2 dígitos hora)",
                "Qualquer linha com -->",
                "Qualquer timestamp"
        };

        for (int p = 0; p < patterns.length; p++) {
            Pattern pattern = Pattern.compile(patterns[p]);
            int matches = 0;

            for (String line : lines) {
                if (pattern.matcher(line.trim()).matches()) {
                    matches++;
                    if (matches <= 3) { // Mostra até 3 exemplos
                        System.out.println("      Exemplo: '" + line.trim() + "'");
                    }
                }
            }

            if (matches > 0) {
                System.out.println("   " + names[p] + ": " + matches + " matches");
            }
        }
    }

}