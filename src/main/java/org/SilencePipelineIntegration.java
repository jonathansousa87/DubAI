package org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Logger;

/**
 * SilencePipelineIntegration - Integração da preservação de silêncios no pipeline principal
 * CORRIGIDO para usar as classes consolidadas
 */
public class SilencePipelineIntegration {

    private static final Logger LOGGER = Logger.getLogger(SilencePipelineIntegration.class.getName());

    /**
     * MÉTODO PRINCIPAL: Integra preservação de silêncios no pipeline principal
     */
    public static void integrateWithMainPipeline(
            String originalVideoPath,
            String outputDirectory) throws IOException, InterruptedException {

        LOGGER.info("🔗 INTEGRANDO PRESERVAÇÃO DE SILÊNCIOS NO PIPELINE");

        try {
            // 1. Extrai áudio do vídeo original (se ainda não foi feito)
            Path originalAudioPath = ensureOriginalAudioExists(originalVideoPath, outputDirectory);

            // 2. Encontra os arquivos de áudio processados
            List<Path> processedAudioFiles = findProcessedAudioSegments(outputDirectory);

            if (processedAudioFiles.isEmpty()) {
                LOGGER.warning("⚠️ Nenhum áudio processado encontrado - usando fallback");
                createFallbackOutput(originalAudioPath, outputDirectory);
                return;
            }

            // 3. Aplica preservação de silêncios usando SilenceUtils consolidado
            Path finalOutput = Paths.get(outputDirectory, "audio_with_preserved_silences.wav");
            SilenceUtils.preserveOriginalSilences(
                    originalAudioPath, processedAudioFiles, finalOutput);

            // 4. Move para o nome esperado pelo pipeline (dublado.wav)
            Path expectedOutput = Paths.get(outputDirectory, "dublado.wav");
            Files.move(finalOutput, expectedOutput, StandardCopyOption.REPLACE_EXISTING);

            LOGGER.info("✅ Integração de silêncios concluída: " + expectedOutput.getFileName());

        } catch (Exception e) {
            LOGGER.severe("❌ Erro na integração de silêncios: " + e.getMessage());

            // Fallback: tenta usar método anterior do pipeline
            handleIntegrationFailure(originalVideoPath, outputDirectory, e);
        }
    }

    /**
     * MÉTODO ALTERNATIVO: Para integração com timing existente
     */
    public static void enhanceExistingTimingUtils(
            Path originalVideo,
            Path dubbedAudio,
            Path vttFile,
            Path outputAudio) throws IOException, InterruptedException {

        LOGGER.info("🔧 MELHORANDO TIMING EXISTENTE COM PRESERVAÇÃO DE SILÊNCIOS");

        try {
            // Extrai áudio do vídeo original para análise de silêncios
            Path tempOriginalAudio = Files.createTempFile("original_for_silence_", ".wav");

            try {
                AudioUtils.extractAudio(originalVideo, tempOriginalAudio);

                // Detecta segmentos originais usando SilenceUtils
                List<SilenceUtils.SilenceSegment> originalSilences =
                        SilenceUtils.detectSilences(tempOriginalAudio);

                // Se temos VTT, combina as informações
                if (Files.exists(vttFile)) {
                    combineVTTWithSilenceAnalysis(vttFile, dubbedAudio, outputAudio);
                } else {
                    // Usa apenas análise de silêncios
                    applySilencePreservationOnly(tempOriginalAudio, dubbedAudio, outputAudio);
                }

            } finally {
                Files.deleteIfExists(tempOriginalAudio);
            }

        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro na melhoria de timing, usando método original: " + e.getMessage());

            // Fallback para cópia simples
            Files.copy(dubbedAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * MÉTODO PARA SUBSTITUIR/MELHORAR TTSUtils
     */
    public static void enhanceTTSWithSilencePreservation(String vttFile, String outputDir)
            throws IOException, InterruptedException {

        LOGGER.info("🎙️ MELHORANDO TTS COM PRESERVAÇÃO DE SILÊNCIOS");

        // Processa VTT normalmente primeiro
        TTSUtils.processVttFile(vttFile);

        // Verifica se temos áudio original para análise de silêncios
        Path originalAudio = findOriginalAudioInDirectory(outputDir);

        if (originalAudio != null) {
            LOGGER.info("📂 Áudio original encontrado, aplicando preservação de silêncios");

            // Aplica preservação de silêncios sobre o resultado do TTS
            Path ttsOutput = Paths.get(outputDir, "output.wav");

            if (Files.exists(ttsOutput)) {
                // Quebra o TTS output em segmentos baseados no VTT
                List<Path> ttsSegments = breakTTSIntoSegments(ttsOutput, vttFile, outputDir);

                // Reconstrói com silêncios preservados usando SilenceUtils
                Path finalOutput = Paths.get(outputDir, "output_with_silences.wav");
                SilenceUtils.preserveOriginalSilences(
                        originalAudio, ttsSegments, finalOutput);

                // Substitui o output original
                Files.move(finalOutput, ttsOutput, StandardCopyOption.REPLACE_EXISTING);

                LOGGER.info("✅ TTS melhorado com preservação de silêncios");
            }
        } else {
            LOGGER.info("ℹ️ Áudio original não encontrado, usando TTS padrão");
        }
    }

    // ===== MÉTODOS AUXILIARES =====

    /**
     * Garante que o áudio original existe
     */
    private static Path ensureOriginalAudioExists(String videoPath, String outputDir)
            throws IOException, InterruptedException {

        Path audioPath = Paths.get(outputDir, "original_audio.wav");

        if (!Files.exists(audioPath)) {
            LOGGER.info("🔊 Extraindo áudio original do vídeo...");
            AudioUtils.extractAudio(Paths.get(videoPath), audioPath);
        }

        return audioPath;
    }

    /**
     * Encontra arquivos de áudio processados no diretório
     */
    private static List<Path> findProcessedAudioSegments(String outputDir) throws IOException {
        Path directory = Paths.get(outputDir);

        LOGGER.info("🔍 Procurando arquivos de áudio processados em: " + outputDir);

        // Estratégia 1: Procura por padrão audio_XXX.wav (TTSUtils)
        List<Path> audioFiles = Files.list(directory)
                .filter(path -> path.getFileName().toString().matches("audio_\\d{3}\\.wav"))
                .sorted()
                .toList();

        if (!audioFiles.isEmpty()) {
            LOGGER.info("✅ Encontrados " + audioFiles.size() + " arquivos padrão TTSUtils");
            return audioFiles;
        }

        // Estratégia 2: Procura por outros padrões comuns
        String[] patterns = {
                "segment_\\d+\\.wav",
                "processed_\\d+\\.wav",
                "speech_\\d+\\.wav",
                "vocal_\\d+\\.wav"
        };

        for (String pattern : patterns) {
            audioFiles = Files.list(directory)
                    .filter(path -> path.getFileName().toString().matches(pattern))
                    .sorted()
                    .toList();

            if (!audioFiles.isEmpty()) {
                LOGGER.info("✅ Encontrados " + audioFiles.size() + " arquivos padrão: " + pattern);
                return audioFiles;
            }
        }

        // Estratégia 3: Lista todos os .wav e permite escolha manual
        audioFiles = Files.list(directory)
                .filter(path -> path.toString().endsWith(".wav"))
                .filter(path -> !path.getFileName().toString().equals("original_audio.wav"))
                .filter(path -> !path.getFileName().toString().equals("audio.wav"))
                .filter(path -> !path.getFileName().toString().equals("vocals.wav"))
                .filter(path -> !path.getFileName().toString().equals("accompaniment.wav"))
                .sorted()
                .toList();

        if (!audioFiles.isEmpty()) {
            LOGGER.info("⚠️ Encontrados " + audioFiles.size() + " arquivos .wav genéricos");

            // Log dos arquivos encontrados para debug
            audioFiles.forEach(file ->
                    LOGGER.info("  📄 " + file.getFileName()));
        }

        return audioFiles;
    }

    /**
     * Cria saída de fallback quando não há áudios processados
     */
    private static void createFallbackOutput(Path originalAudio, String outputDir)
            throws IOException, InterruptedException {

        LOGGER.warning("⚠️ Criando saída de fallback - copiando áudio original");

        Path fallbackOutput = Paths.get(outputDir, "dublado.wav");
        Files.copy(originalAudio, fallbackOutput, StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("✅ Fallback criado: " + fallbackOutput.getFileName());
    }

    /**
     * Trata falhas na integração
     */
    private static void handleIntegrationFailure(String originalVideoPath, String outputDir, Exception originalError)
            throws IOException, InterruptedException {

        LOGGER.warning("🔄 Aplicando estratégias de fallback...");

        try {
            // Fallback 1: Tenta usar VTTUtils se disponível
            Path outputAudio = Paths.get(outputDir, "output.wav");
            Path vttFile = Paths.get(outputDir, "transcription.vtt");
            Path finalOutput = Paths.get(outputDir, "dublado.wav");

            if (Files.exists(outputAudio)) {
                if (Files.exists(vttFile)) {
                    LOGGER.info("📄 Tentando fallback com VTT...");
                    // Usar VTTUtils para sincronização
                    VTTUtils.synchronizeWithVTT(vttFile.toString(), outputDir);
                } else {
                    LOGGER.info("📄 Tentando fallback simples...");
                    Files.copy(outputAudio, finalOutput, StandardCopyOption.REPLACE_EXISTING);
                }

                LOGGER.info("✅ Fallback aplicado com sucesso");
                return;
            }

            // Fallback 2: Procura por qualquer áudio no diretório
            List<Path> anyAudioFiles = Files.list(Paths.get(outputDir))
                    .filter(path -> path.toString().endsWith(".wav"))
                    .filter(path -> Files.exists(path))
                    .toList();

            if (!anyAudioFiles.isEmpty()) {
                Path bestCandidate = anyAudioFiles.stream()
                        .filter(path -> path.getFileName().toString().contains("output"))
                        .findFirst()
                        .orElse(anyAudioFiles.get(0));

                Files.copy(bestCandidate, Paths.get(outputDir, "dublado.wav"),
                        StandardCopyOption.REPLACE_EXISTING);

                LOGGER.info("✅ Fallback final aplicado: " + bestCandidate.getFileName());
                return;
            }

        } catch (Exception fallbackError) {
            LOGGER.severe("❌ Todos os fallbacks falharam: " + fallbackError.getMessage());
        }

        // Se chegou aqui, relança o erro original
        throw new RuntimeException("Falha na integração de silêncios e todos os fallbacks", originalError);
    }

    /**
     * Combina informações do VTT com análise de silêncios
     */
    private static void combineVTTWithSilenceAnalysis(
            Path vttFile,
            Path dubbedAudio,
            Path outputAudio) throws IOException, InterruptedException {

        LOGGER.info("🔗 Combinando VTT com análise de silêncios...");

        try {
            // Tenta usar VTTUtils primeiro
            VTTUtils.synchronizeWithVTT(vttFile.toString(), dubbedAudio.getParent().toString());

            // Se sucesso, move o resultado
            Path syncedOutput = dubbedAudio.getParent().resolve("vtt_synchronized.wav");
            if (Files.exists(syncedOutput)) {
                Files.move(syncedOutput, outputAudio, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("✅ VTT sync aplicado com sucesso");
                return;
            }
        } catch (Exception e) {
            LOGGER.warning("⚠️ VTT sync falhou, usando apenas análise de silêncios: " + e.getMessage());
        }

        // Fallback: usa apenas preservação de silêncios
        applySilencePreservationOnly(
                dubbedAudio.getParent().resolve("original_audio.wav"),
                dubbedAudio,
                outputAudio);
    }

    /**
     * Aplica apenas preservação de silêncios (sem VTT)
     */
    private static void applySilencePreservationOnly(Path originalAudio, Path dubbedAudio, Path outputAudio)
            throws IOException, InterruptedException {

        LOGGER.info("🔇 Aplicando apenas preservação de silêncios...");

        // Para simplificar, usamos apenas o áudio dublado como um único segmento
        List<Path> dubbedSegments = List.of(dubbedAudio);

        SilenceUtils.preserveOriginalSilences(originalAudio, dubbedSegments, outputAudio);
    }

    /**
     * Encontra áudio original no diretório
     */
    private static Path findOriginalAudioInDirectory(String outputDir) {
        String[] possibleNames = {
                "original_audio.wav",
                "audio.wav",
                "extracted_audio.wav",
                "source_audio.wav"
        };

        for (String name : possibleNames) {
            Path candidate = Paths.get(outputDir, name);
            if (Files.exists(candidate)) {
                LOGGER.info("📂 Áudio original encontrado: " + name);
                return candidate;
            }
        }

        return null;
    }

    /**
     * Quebra output do TTS em segmentos baseados no VTT
     */
    private static List<Path> breakTTSIntoSegments(Path ttsOutput, String vttFile, String outputDir)
            throws IOException, InterruptedException {

        LOGGER.info("✂️ Quebrando TTS em segmentos baseados no VTT...");

        // Por enquanto, retorna apenas o arquivo inteiro
        // Em implementação futura, pode quebrar baseado nos timestamps do VTT
        return List.of(ttsOutput);
    }

    // ===== MÉTODOS DE INTEGRAÇÃO ESPECÍFICOS =====

    /**
     * INTEGRAÇÃO ESPECÍFICA PARA Main.java
     */
    public static void replaceMainFinalizationStep(Path videoFile, String outputDir, boolean useAdvancedProcessing)
            throws IOException, InterruptedException {

        LOGGER.info("🎬 FINALIZAÇÃO MELHORADA COM PRESERVAÇÃO DE SILÊNCIOS");

        try {
            // Aplica integração de silêncios
            integrateWithMainPipeline(videoFile.toString(), outputDir);

            // Continua com criação do vídeo final
            Path dubAudioPath = Paths.get(outputDir, "dublado.wav");
            Path outputVideoPath = Paths.get(videoFile.toString().replace(".mp4", "_dub_silences_preserved.mp4"));

            if (Files.exists(dubAudioPath)) {
                if (useAdvancedProcessing) {
                    AudioUtils.replaceAudioAdvanced(videoFile, dubAudioPath, outputVideoPath);
                } else {
                    AudioUtils.replaceAudio(videoFile, dubAudioPath, outputVideoPath);
                }

                LOGGER.info("🎬 Vídeo final criado com silêncios preservados: " + outputVideoPath.getFileName());
            } else {
                throw new IOException("Áudio dublado não foi gerado: " + dubAudioPath);
            }

        } catch (Exception e) {
            LOGGER.severe("❌ Erro na finalização melhorada: " + e.getMessage());
            throw e;
        }
    }

    /**
     * INTEGRAÇÃO ESPECÍFICA PARA aplicação de mapa de silêncios
     */
    public static void replaceApplySilenceMap(Path dubbedAudio, Path outputAudio)
            throws IOException, InterruptedException {

        LOGGER.info("🔄 APLICANDO MAPA DE SILÊNCIOS MELHORADO");

        // Procura por áudio original no mesmo diretório
        Path originalAudio = findOriginalAudioInDirectory(dubbedAudio.getParent().toString());

        if (originalAudio != null) {
            // Usa preservação de silêncios completa
            List<Path> segments = List.of(dubbedAudio);
            SilenceUtils.preserveOriginalSilences(originalAudio, segments, outputAudio);
        } else {
            LOGGER.warning("⚠️ Áudio original não encontrado, copiando áudio dublado");
            Files.copy(dubbedAudio, outputAudio, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ===== MÉTODOS DE TESTE E DEBUG =====

    /**
     * Método de teste independente
     */
    public static void testIntegration(String videoPath, String outputDir) {
        try {
            LOGGER.info("🧪 TESTANDO INTEGRAÇÃO DE PRESERVAÇÃO DE SILÊNCIOS");

            integrateWithMainPipeline(videoPath, outputDir);

            LOGGER.info("✅ Teste de integração concluído com sucesso");

        } catch (Exception e) {
            LOGGER.severe("❌ Teste de integração falhou: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Debug de arquivos no diretório
     */
    public static void debugDirectoryContents(String outputDir) {
        try {
            LOGGER.info("🔍 DEBUG - Conteúdo do diretório: " + outputDir);

            Files.list(Paths.get(outputDir))
                    .forEach(path -> {
                        try {
                            long size = Files.size(path);
                            LOGGER.info(String.format("  📄 %s (%.1f KB)",
                                    path.getFileName(), size / 1024.0));
                        } catch (IOException e) {
                            LOGGER.info("  📄 " + path.getFileName() + " (erro obtendo tamanho)");
                        }
                    });

        } catch (IOException e) {
            LOGGER.warning("❌ Erro no debug do diretório: " + e.getMessage());
        }
    }

    /**
     * Valida se a integração pode ser aplicada
     */
    public static boolean canApplyIntegration(String outputDir) {
        try {
            List<Path> audioFiles = findProcessedAudioSegments(outputDir);
            Path originalAudio = findOriginalAudioInDirectory(outputDir);

            boolean hasProcessedAudio = !audioFiles.isEmpty();
            boolean hasOriginalAudio = originalAudio != null;

            LOGGER.info("🔍 VALIDAÇÃO DE INTEGRAÇÃO:");
            LOGGER.info("  Áudios processados: " + (hasProcessedAudio ? "✅ " + audioFiles.size() : "❌ Nenhum"));
            LOGGER.info("  Áudio original: " + (hasOriginalAudio ? "✅ " + originalAudio.getFileName() : "❌ Não encontrado"));

            return hasProcessedAudio || hasOriginalAudio;

        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro na validação: " + e.getMessage());
            return false;
        }
    }

    /**
     * Shutdown graceful
     */
    public static void shutdown() {
        LOGGER.info("🔄 Finalizando SilencePipelineIntegration...");
        // Removido: AudioSilencePreservationUtils.shutdown(); (classe consolidada)
        SilenceUtils.shutdown();
        LOGGER.info("✅ SilencePipelineIntegration finalizado");
    }
}