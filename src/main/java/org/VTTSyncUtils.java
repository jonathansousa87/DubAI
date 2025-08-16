package org;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VTTSyncUtils CORRIGIDO - Usando apenas classes consolidadas
 */
public class VTTSyncUtils {

    private static final Logger LOGGER = Logger.getLogger(VTTSyncUtils.class.getName());

    // Configurações de áudio
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final double SAMPLE_RATE_DOUBLE = 48000.0;

    // Configurações de sincronização
    private static final double MAX_SPEED_FACTOR = 1.3;
    private static final double MIN_SPEED_FACTOR = 0.75;
    private static final double MIN_SILENCE_DURATION = 0.01;

    // PADRÃO CORRIGIDO para timestamps VTT
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
    );

    // Executor para processamento paralelo
    private static final ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(8, Runtime.getRuntime().availableProcessors())
    );

    /**
     * Record para segmento VTT com timing preciso (mantido para compatibilidade)
     */
    public record VTTSegment(
            double startTime,
            double endTime,
            double expectedDuration,
            String text,
            int index,
            String audioFile,
            double silenceBeforeSegment
    ) {
        public long startSample() {
            return Math.round(startTime * SAMPLE_RATE_DOUBLE);
        }

        public long endSample() {
            return Math.round(endTime * SAMPLE_RATE_DOUBLE);
        }

        public long expectedSamples() {
            return Math.round(expectedDuration * SAMPLE_RATE_DOUBLE);
        }
    }

    /**
     * MÉTODO PRINCIPAL CORRIGIDO: Usa SilenceUtils para correção
     */
    public static void synchronizeAudioWithVTT(Path vttFile, Path dubbedAudioDir, Path outputAudio)
            throws IOException, InterruptedException {

        LOGGER.info("🎯 Iniciando sincronização com CORREÇÃO USANDO CLASSES CONSOLIDADAS");

        try {
            // ✅ USA SilenceUtils para correção de gaps TTS
            LOGGER.info("🎯 Aplicando SilenceUtils para correção de gaps...");

            // Aplica correção de gaps TTS usando SilenceUtils
            SilenceUtils.fixTTSSilenceGaps(vttFile.toString(), dubbedAudioDir.toString());

            // Move o resultado para o local esperado
            Path correctedOutput = dubbedAudioDir.resolve("output.wav");
            if (Files.exists(correctedOutput)) {
                Files.move(correctedOutput, outputAudio, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("✅ Sincronização com correção consolidada concluída: " + outputAudio);
                return;
            }

        } catch (Exception e) {
            LOGGER.warning("⚠️ Correção consolidada falhou: " + e.getMessage());
            LOGGER.info("🔄 Aplicando fallback de sincronização simples...");
        }

        // FALLBACK: Sincronização simples se a correção falhar
        applySimpleFallbackSync(vttFile, dubbedAudioDir, outputAudio);
    }

    /**
     * FALLBACK CORRIGIDO: Procura output.wav primeiro
     */
    private static void applySimpleFallbackSync(Path vttFile, Path dubbedAudioDir, Path outputAudio)
            throws IOException, InterruptedException {

        LOGGER.info("🔄 Aplicando fallback de sincronização CORRIGIDO...");

        // ORDEM CORRETA: Procura output.wav primeiro (áudio dublado)
        Path[] possibleOutputs = {
                dubbedAudioDir.resolve("output.wav"),        // TTS gerado
                dubbedAudioDir.resolve("dubbed_audio.wav"),  // Possível nome alternativo
                dubbedAudioDir.resolve("vocals.wav"),        // Separação de áudio
                dubbedAudioDir.resolve("audio.wav")          // Original (ÚLTIMO RECURSO)
        };

        Path sourceAudio = null;
        for (Path candidate : possibleOutputs) {
            if (Files.exists(candidate)) {
                sourceAudio = candidate;
                LOGGER.info("📂 Encontrado áudio: " + candidate.getFileName());
                break;
            }
        }

        if (sourceAudio == null) {
            throw new IOException("❌ Nenhum arquivo de áudio encontrado para sincronização");
        }

        // Se encontrou output.wav, significa que o TTS funcionou
        if (sourceAudio.getFileName().toString().equals("output.wav")) {
            LOGGER.info("✅ Usando áudio dublado (output.wav)");
        } else {
            LOGGER.warning("⚠️ output.wav não encontrado, usando: " + sourceAudio.getFileName());
        }

        // Copia com ajuste básico de duração se necessário
        if (Files.exists(vttFile)) {
            double expectedDuration = getExpectedDurationFromVTT(vttFile);
            double currentDuration = getAudioDuration(sourceAudio);

            if (Math.abs(currentDuration - expectedDuration) > 2.0) { // Tolerância maior
                // Aplica ajuste simples de velocidade
                applySimpleSpeedAdjustment(sourceAudio, expectedDuration, outputAudio);
            } else {
                // Apenas copia
                Files.copy(sourceAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("✅ Áudio copiado sem ajuste de velocidade");
            }
        } else {
            // Apenas copia se não há VTT
            Files.copy(sourceAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("✅ Áudio copiado (sem VTT)");
        }

        LOGGER.info("✅ Fallback de sincronização concluído: " + outputAudio);
    }

    /**
     * Ajuste simples de velocidade
     */
    private static void applySimpleSpeedAdjustment(Path inputAudio, double targetDuration, Path outputAudio)
            throws IOException, InterruptedException {

        double currentDuration = getAudioDuration(inputAudio);
        double speedFactor = currentDuration / targetDuration;
        speedFactor = Math.max(MIN_SPEED_FACTOR, Math.min(MAX_SPEED_FACTOR, speedFactor));

        LOGGER.info(String.format("⚡ Ajuste simples de velocidade: %.3fx", speedFactor));

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputAudio.toString(),
                "-af", String.format("atempo=%.6f", speedFactor),
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(CHANNELS),
                "-c:a", "pcm_s24le",
                "-avoid_negative_ts", "make_zero",
                outputAudio.toString()
        );

        Process process = pb.start();

        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout no ajuste de velocidade");
        }

        if (process.exitValue() != 0) {
            // Se falhar, apenas copia o original
            LOGGER.warning("⚠️ Ajuste de velocidade falhou, copiando original");
            Files.copy(inputAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * MÉTODOS AUXILIARES SIMPLIFICADOS
     */

    /**
     * Obtém duração esperada do VTT (versão simplificada)
     */
    private static double getExpectedDurationFromVTT(Path vttFile) throws IOException {
        List<String> lines = Files.readAllLines(vttFile);
        double maxEndTime = 0.0;

        Pattern timestampPattern = Pattern.compile(
                "(\\d{1,2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2})[.,](\\d{3})"
        );

        for (String line : lines) {
            Matcher matcher = timestampPattern.matcher(line);
            if (matcher.find()) {
                try {
                    double endTime = parseSimpleTimestamp(matcher.group(4), matcher.group(5), matcher.group(6));
                    maxEndTime = Math.max(maxEndTime, endTime);
                } catch (Exception e) {
                    // Ignora linhas com erro de parsing
                }
            }
        }

        // Tenta também o padrão completo HH:MM:SS.mmm
        Pattern fullTimestampPattern = Pattern.compile(
                "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{3})"
        );

        for (String line : lines) {
            Matcher matcher = fullTimestampPattern.matcher(line);
            if (matcher.find()) {
                try {
                    double endTime = parseFullTimestamp(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
                    maxEndTime = Math.max(maxEndTime, endTime);
                } catch (Exception e) {
                    // Ignora linhas com erro de parsing
                }
            }
        }

        return maxEndTime;
    }

    /**
     * Parse simples de timestamp MM:SS.mmm
     */
    private static double parseSimpleTimestamp(String minutes, String seconds, String milliseconds) {
        try {
            int m = Integer.parseInt(minutes);
            int s = Integer.parseInt(seconds);
            int ms = Integer.parseInt(milliseconds);
            return m * 60.0 + s + ms / 1000.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Parse completo de timestamp HH:MM:SS.mmm
     */
    private static double parseFullTimestamp(String hours, String minutes, String seconds, String milliseconds) {
        try {
            int h = Integer.parseInt(hours);
            int m = Integer.parseInt(minutes);
            int s = Integer.parseInt(seconds);
            int ms = Integer.parseInt(milliseconds);
            return h * 3600.0 + m * 60.0 + s + ms / 1000.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Obtém duração do áudio
     */
    private static double getAudioDuration(Path audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                audioFile.toString()
        );

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String duration = reader.readLine();
            if (duration != null && !duration.trim().isEmpty()) {
                return Double.parseDouble(duration.trim());
            }
        }

        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }

        return 0.0;
    }

    /**
     * Validação simples da sincronização final
     */
    public static void validateSynchronization(Path vttFile, Path finalAudio) throws IOException, InterruptedException {
        LOGGER.info("🔍 Validando sincronização final");

        try {
            double expectedDuration = getExpectedDurationFromVTT(vttFile);
            double actualDuration = getAudioDuration(finalAudio);

            double difference = Math.abs(actualDuration - expectedDuration);
            double percentDifference = expectedDuration > 0 ? (difference / expectedDuration) * 100 : 0;

            LOGGER.info(String.format("📊 VALIDAÇÃO DE SINCRONIZAÇÃO:"));
            LOGGER.info(String.format("  Esperado: %.3fs", expectedDuration));
            LOGGER.info(String.format("  Atual: %.3fs", actualDuration));
            LOGGER.info(String.format("  Diferença: %.3fs (%.2f%%)", difference, percentDifference));

            if (percentDifference > 5.0) {
                LOGGER.warning("⚠️ Diferença de sincronização maior que 5%");
            } else {
                LOGGER.info("✅ Sincronização dentro da tolerância (<5%)");
            }
        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro na validação de sincronização: " + e.getMessage());
        }
    }

    /**
     * Shutdown do executor
     */
    public static void shutdown() {
        LOGGER.info("🔄 Finalizando VTTSyncUtils...");

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("✅ VTTSyncUtils finalizado");
    }

    /**
     * MÉTODO DE INTEGRAÇÃO CORRIGIDO para usar no pipeline principal
     */
    public static void integrateSyncWithPipeline(String vttFile, String outputDir) throws IOException, InterruptedException {
        try {
            LOGGER.info("🎯 Iniciando integração VTT Sync CORRIGIDA");

            Path vttPath = Paths.get(vttFile);
            Path dubbedAudioDir = Paths.get(outputDir);
            Path outputAudio = dubbedAudioDir.resolve("synchronized_output.wav");

            // Verifica se o arquivo VTT existe
            if (!Files.exists(vttPath)) {
                throw new IOException("Arquivo VTT não encontrado: " + vttPath);
            }

            LOGGER.info("📂 VTT encontrado: " + vttPath.getFileName());
            LOGGER.info("📂 Diretório de áudio: " + dubbedAudioDir);

            // Usa o método corrigido
            synchronizeAudioWithVTT(vttPath, dubbedAudioDir, outputAudio);

            // Validação da sincronização
            validateSynchronization(vttPath, outputAudio);

            // Move para o nome final esperado
            Path finalOutput = dubbedAudioDir.resolve("dublado.wav");
            Files.move(outputAudio, finalOutput, StandardCopyOption.REPLACE_EXISTING);

            LOGGER.info("✅ Integração de sincronização CORRIGIDA concluída: " + finalOutput);

        } catch (Exception e) {
            LOGGER.severe("❌ Erro na sincronização VTT corrigida: " + e.getMessage());

            // Fallback melhorado: tenta usar output.wav se existir
            Path fallbackPath = Paths.get(outputDir, "output.wav");
            Path finalOutput = Paths.get(outputDir, "dublado.wav");

            if (Files.exists(fallbackPath)) {
                Files.copy(fallbackPath, finalOutput, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("✅ Fallback aplicado: copiado output.wav para dublado.wav");
            } else {
                LOGGER.severe("❌ Nem sincronização nem fallback disponíveis");
                throw e;
            }
        }
    }

    /**
     * MÉTODOS DE DEBUG (mantidos para compatibilidade)
     */
    public static void debugListFiles(String outputDir) {
        try {
            Path dir = Paths.get(outputDir);
            LOGGER.info("🔍 DEBUG - Listando arquivos em: " + dir);

            Files.list(dir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            long size = Files.size(file);
                            LOGGER.info(String.format("  📄 %s (%.1f KB)",
                                    file.getFileName(), size / 1024.0));
                        } catch (IOException e) {
                            LOGGER.info("  📄 " + file.getFileName() + " (erro obtendo tamanho)");
                        }
                    });
        } catch (IOException e) {
            LOGGER.warning("❌ Erro listando arquivos: " + e.getMessage());
        }
    }

    public static void debugVTTFormat(String vttFile) {
        try {
            Path vttPath = Paths.get(vttFile);
            List<String> lines = Files.readAllLines(vttPath);

            LOGGER.info("🔍 DEBUG VTT - Arquivo: " + vttPath.getFileName());
            LOGGER.info("🔍 Total de linhas: " + lines.size());

            boolean hasWebVTT = false;
            int timestampCount = 0;

            for (int i = 0; i < Math.min(20, lines.size()); i++) {
                String line = lines.get(i).trim();
                LOGGER.info(String.format("  Linha %d: '%s'", i + 1, line));

                if (line.startsWith("WEBVTT")) {
                    hasWebVTT = true;
                }
                if (line.contains("-->")) {
                    timestampCount++;
                }
            }

            LOGGER.info("🔍 WEBVTT header: " + (hasWebVTT ? "✅ Encontrado" : "❌ Ausente"));
            LOGGER.info("🔍 Timestamps encontrados nas primeiras 20 linhas: " + timestampCount);

        } catch (IOException e) {
            LOGGER.warning("❌ Erro analisando VTT: " + e.getMessage());
        }
    }

    /**
     * MÉTODO PRINCIPAL para teste individual
     */
    public static void testVTTSync(String vttFile, String outputDir) {
        try {
            LOGGER.info("🧪 TESTE VTT SYNC CORRIGIDO");

            // Debug do formato VTT
            debugVTTFormat(vttFile);

            // Debug dos arquivos
            debugListFiles(outputDir);

            // Executa sincronização
            integrateSyncWithPipeline(vttFile, outputDir);

            LOGGER.info("✅ Teste VTT Sync concluído com sucesso");

        } catch (Exception e) {
            LOGGER.severe("❌ Teste VTT Sync falhou: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
