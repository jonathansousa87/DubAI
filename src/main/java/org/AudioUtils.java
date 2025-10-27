package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Utilitário avançado para processamento de áudio com foco em qualidade profissional
 * e compatibilidade robusta com diferentes formatos.
 */
public class AudioUtils {

    // Controle de concorrência otimizado
    private static final Semaphore ffmpegSemaphore = new Semaphore(2);
    private static final ExecutorService audioProcessingExecutor =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // Configurações de qualidade
    private static final int SAMPLE_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final String AUDIO_CODEC = "aac";
    private static final String AUDIO_BITRATE = "256k";

    // Timeouts de segurança
    private static final int FFMPEG_TIMEOUT_SECONDS = 300; // 5 minutos
    private static final int FFPROBE_TIMEOUT_SECONDS = 30;

    // Padrão para parsing de duração mais robusto
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FFPROBE_DURATION_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)$");

    /**
     * Extrai áudio de vídeo com validação robusta
     */
    public static void extractAudio(Path inputVideo, Path outputAudio) throws IOException, InterruptedException {
        validateInputFile(inputVideo, "Vídeo de entrada");

        System.out.printf("🎵 Extraindo áudio: %s -> %s\n",
                inputVideo.getFileName(), outputAudio.getFileName());

        // Criar nome temporário único para evitar conflitos
        String tempName = "temp_audio_" + System.currentTimeMillis() + ".wav";
        Path tempAudio = outputAudio.getParent().resolve(tempName);

        try {
            // Extração com configurações otimizadas
            extractAudioHighQuality(inputVideo, tempAudio);

            // Pós-processamento para otimização
            enhanceExtractedAudio(tempAudio, outputAudio);

            validateOutputFile(outputAudio, "Áudio extraído");
            System.out.printf("✅ Áudio extraído: %s (%.1fs)\n",
                    outputAudio.getFileName(), getAudioDurationSafe(outputAudio));

        } finally {
            Files.deleteIfExists(tempAudio);
        }
    }

    /**
     * Substitui áudio de vídeo com processamento profissional
     */
    public static void replaceAudio(Path inputVideo, Path inputDub, Path outputVideo)
            throws IOException, InterruptedException {

        validateInputFile(inputVideo, "Vídeo de entrada");
        validateInputFile(inputDub, "Áudio dublado");

        System.out.printf("🎬 Substituindo áudio: %s\n", outputVideo.getFileName());

        Path processedDub = inputDub.getParent().resolve("processed_dub_final.wav");

        try {
            // Processamento do áudio dublado
            processAudioForDubbing(inputDub, processedDub);

            // Criação do vídeo final
            createOptimizedVideo(inputVideo, processedDub, outputVideo);

            validateOutputFile(outputVideo, "Vídeo de saída");
            System.out.printf("✅ Vídeo criado: %s\n", outputVideo.getFileName());

        } finally {
            Files.deleteIfExists(processedDub);
        }
    }

    /**
     * Versão avançada com dual track de áudio
     */
    public static void replaceAudioAdvanced(Path inputVideo, Path inputDub, Path outputVideo)
            throws IOException, InterruptedException {

        validateInputFile(inputVideo, "Vídeo de entrada");
        validateInputFile(inputDub, "Áudio dublado");

        System.out.printf("🎭 Criando dual áudio: %s\n", outputVideo.getFileName());

        Path originalAudio = inputDub.getParent().resolve("original_extracted.wav");
        Path processedDub = inputDub.getParent().resolve("processed_dub.wav");
        Path enhancedOriginal = inputDub.getParent().resolve("enhanced_original.wav");

        try {
            // Processamento em paralelo
            CompletableFuture<Void> extractionTask = CompletableFuture.runAsync(() -> {
                try {
                    extractAudioWithNormalization(inputVideo, originalAudio);
                    enhanceOriginalAudioForMix(originalAudio, enhancedOriginal);
                } catch (Exception e) {
                    throw new RuntimeException("Erro na extração de áudio original", e);
                }
            }, audioProcessingExecutor);

            CompletableFuture<Void> processingTask = CompletableFuture.runAsync(() -> {
                try {
                    processAudioForMaximumNaturalness(inputDub, processedDub);
                } catch (Exception e) {
                    throw new RuntimeException("Erro no processamento do áudio dublado", e);
                }
            }, audioProcessingExecutor);

            // Aguarda ambos os processamentos
            CompletableFuture.allOf(extractionTask, processingTask).join();

            validateOutputFile(enhancedOriginal, "Áudio original processado");
            validateOutputFile(processedDub, "Áudio dublado processado");

            // Cria vídeo com dual áudio
            createDualAudioVideo(inputVideo, processedDub, enhancedOriginal, outputVideo);

            validateOutputFile(outputVideo, "Vídeo dual áudio");
            System.out.printf("✅ Vídeo dual áudio criado: %s\n", outputVideo.getFileName());

        } finally {
            // Limpeza de arquivos temporários
            Files.deleteIfExists(originalAudio);
            Files.deleteIfExists(processedDub);
            Files.deleteIfExists(enhancedOriginal);
        }
    }

    /**
     * Extrai áudio com qualidade máxima
     */
    private static void extractAudioHighQuality(Path inputVideo, Path outputAudio)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputVideo.toString(),
                "-vn", // Remove vídeo
                "-acodec", "pcm_s16le",
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(AUDIO_CHANNELS),
                "-af", "volume=1.0", // Normalização básica
                outputAudio.toString()
        );

        executeProcessBuilder(pb, "Extração de áudio");
    }

    /**
     * Melhora qualidade do áudio extraído
     */
    private static void enhanceExtractedAudio(Path inputAudio, Path outputAudio)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputAudio.toString(),
                "-af", buildEnhancementFilter(),
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(AUDIO_CHANNELS),
                outputAudio.toString()
        );

        executeProcessBuilder(pb, "Melhoria de áudio");
    }

    /**
     * Constrói filtro de melhoria de áudio
     */
    private static String buildEnhancementFilter() {
        return String.join(",",
                "highpass=f=20",           // Remove frequências muito baixas
                "lowpass=f=20000",         // Remove frequências muito altas
                "volume=1.0"               // Normaliza volume apenas
        );
    }

    /**
     * Processa áudio para dublagem
     */
    private static void processAudioForDubbing(Path inputDub, Path outputDub)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputDub.toString(),
                "-af", buildDubbingFilter(),
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(AUDIO_CHANNELS),
                outputDub.toString()
        );

        executeProcessBuilder(pb, "Processamento para dublagem");
    }

    /**
     * Constrói filtro para dublagem
     */
    private static String buildDubbingFilter() {
        return String.join(",",
                "highpass=f=80",           // Remove ruído de baixa frequência
                "lowpass=f=8000",          // Foca na fala humana
                "volume=0.9"               // Volume ligeiramente reduzido
        );
    }

    /**
     * Processamento para naturalidade máxima
     */
    private static void processAudioForMaximumNaturalness(Path inputDub, Path outputDub)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputDub.toString(),
                "-af", buildNaturalnessFilter(),
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(AUDIO_CHANNELS),
                outputDub.toString()
        );

        executeProcessBuilder(pb, "Processamento para naturalidade");
    }

    /**
     * Constrói filtro para naturalidade
     */
    private static String buildNaturalnessFilter() {
        return String.join(",",
                "highpass=f=85",
                "lowpass=f=8500",
                "volume=0.85"
        );
    }

    /**
     * Extrai áudio com normalização
     */
    private static void extractAudioWithNormalization(Path inputVideo, Path outputAudio)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputVideo.toString(),
                "-vn",
                "-af", "volume=1.0",
                "-acodec", "pcm_s16le",
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(AUDIO_CHANNELS),
                outputAudio.toString()
        );

        executeProcessBuilder(pb, "Extração com normalização");
    }

    /**
     * Melhora áudio original para mixagem
     */
    private static void enhanceOriginalAudioForMix(Path inputAudio, Path outputAudio)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputAudio.toString(),
                "-af", "volume=0.25", // Volume bem baixo para background
                "-ar", String.valueOf(SAMPLE_RATE),
                "-ac", String.valueOf(AUDIO_CHANNELS),
                outputAudio.toString()
        );

        executeProcessBuilder(pb, "Preparação do áudio original");
    }

    /**
     * Cria vídeo otimizado com áudio único
     */
    private static void createOptimizedVideo(Path inputVideo, Path dubAudio, Path outputVideo)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputVideo.toString(),
                "-i", dubAudio.toString(),
                "-map", "0:v:0",  // Vídeo do primeiro input
                "-map", "1:a:0",  // Áudio do segundo input
                "-c:v", "copy",   // Copia vídeo sem recodificar
                "-c:a", AUDIO_CODEC,
                "-b:a", AUDIO_BITRATE,
                "-ar", String.valueOf(SAMPLE_RATE),
                "-movflags", "+faststart", // Otimização para streaming
                outputVideo.toString()
        );

        executeProcessBuilder(pb, "Criação de vídeo otimizado");
    }

    /**
     * Cria vídeo com dual áudio
     */
    private static void createDualAudioVideo(Path inputVideo, Path dubAudio,
                                             Path originalAudio, Path outputVideo)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputVideo.toString(),
                "-i", dubAudio.toString(),
                "-i", originalAudio.toString(),
                "-map", "0:v:0",    // Vídeo
                "-map", "1:a:0",    // Áudio dublado (principal)
                "-map", "2:a:0",    // Áudio original (secundário)
                "-c:v", "copy",
                "-c:a", AUDIO_CODEC,
                "-b:a", AUDIO_BITRATE,
                "-ar", String.valueOf(SAMPLE_RATE),
                // Metadados dos áudios
                "-metadata:s:a:0", "title=Português (Dublado)",
                "-metadata:s:a:0", "language=por",
                "-metadata:s:a:1", "title=Original",
                "-metadata:s:a:1", "language=eng",
                // Configurações de disposição
                "-disposition:a:0", "default",    // Dublado como padrão
                "-disposition:a:1", "0",          // Original como alternativo
                "-movflags", "+faststart",
                outputVideo.toString()
        );

        executeProcessBuilder(pb, "Criação de vídeo dual áudio");
    }

    /**
     * Valida qualidade de áudio com critérios rigorosos
     */
    public static boolean validateAudioQuality(Path audioFile) throws IOException, InterruptedException {
        if (!Files.exists(audioFile)) {
            return false;
        }

        try {
            AudioInfo info = extractAudioInfo(audioFile);

            // Critérios de validação
            boolean validDuration = info.duration > 0.1 && info.duration < 7200; // Max 2 horas
            boolean validSampleRate = info.sampleRate >= 16000 && info.sampleRate <= 96000;
            boolean validChannels = info.channels >= 1 && info.channels <= 8;
            boolean validBitRate = info.bitRate > 0; // Qualquer bitrate > 0

            // Verifica tamanho do arquivo
            long fileSize = Files.size(audioFile);
            boolean validSize = fileSize > 1024; // Mínimo 1KB

            boolean isValid = validDuration && validSampleRate && validChannels && validSize;

            if (!isValid) {
                System.err.printf("⚠️ Áudio falhou na validação: dur=%.1fs, sr=%d, ch=%d, br=%d, size=%d\n",
                        info.duration, info.sampleRate, info.channels, info.bitRate, fileSize);
            }

            return isValid;

        } catch (Exception e) {
            System.err.println("⚠️ Erro na validação de áudio: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ajusta velocidade do áudio de forma inteligente
     */
    public static void adjustAudioSpeedDynamic(Path inputFile, Path outputFile,
                                               double targetDuration, double currentDuration)
            throws IOException, InterruptedException {

        double speedFactor = currentDuration / targetDuration;

        // Limites conservadores para preservar naturalidade
        speedFactor = Math.max(0.75, Math.min(1.25, speedFactor));

        System.out.printf("🔧 Ajustando velocidade: %.3fx (%.1fs -> %.1fs)\n",
                speedFactor, currentDuration, targetDuration);

        if (speedFactor > 0.95 && speedFactor < 1.05) {
            // Mudança mínima, apenas copia
            Files.copy(inputFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("✅ Velocidade mantida (mudança insignificante)");
        } else {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", inputFile.toString(),
                    "-af", String.format("atempo=%.3f", speedFactor),
                    "-ar", String.valueOf(SAMPLE_RATE),
                    outputFile.toString()
            );

            executeProcessBuilder(pb, "Ajuste de velocidade");
        }
    }

    /**
     * Extrai informações detalhadas do áudio com parsing robusto
     */
    public static AudioInfo extractAudioInfo(Path audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "quiet",
                "-show_entries", "format=duration,bit_rate",
                "-show_entries", "stream=sample_rate,channels,codec_name",
                "-of", "csv=p=0",
                audioFile.toString()
        );

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(FFPROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timeout ao extrair informações do áudio");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Erro ao analisar áudio com ffprobe");
        }

        return parseAudioInfo(output.toString(), audioFile);
    }

    /**
     * Parse robusto das informações de áudio
     */
// Substitua o método parseAudioInfo na classe AudioUtils por esta versão corrigida:

    private static AudioInfo parseAudioInfo(String ffprobeOutput, Path audioFile) throws IOException {
        String[] lines = ffprobeOutput.trim().split("\n");

        if (lines.length < 1) {
            throw new IOException("Saída inesperada do ffprobe: " + ffprobeOutput);
        }

        try {
            // Busca informações em qualquer linha que contenha vírgulas
            String[] formatInfo = null;
            String[] streamInfo = null;

            for (String line : lines) {
                if (line.contains(",")) {
                    String[] parts = line.split(",");

                    // Tenta identificar se é linha de stream ou format
                    if (parts.length >= 3) {
                        // Verifica se parece com dados de stream (sample_rate, channels, codec)
                        try {
                            Integer.parseInt(parts[0]); // sample_rate
                            Integer.parseInt(parts[1]); // channels
                            streamInfo = parts;
                        } catch (NumberFormatException e) {
                            // Se não conseguir parsear os primeiros como números,
                            // pode ser linha de formato
                            formatInfo = parts;
                        }
                    } else if (parts.length >= 2) {
                        // Linha com menos parâmetros, provavelmente format
                        formatInfo = parts;
                    }
                }
            }

            // Valores padrão se não conseguir extrair
            double duration = 0.0;
            int sampleRate = 22050; // padrão do sistema
            int channels = 1;
            int bitRate = 0;
            String codecName = "unknown";

            // Extrai informações do stream se disponível
            if (streamInfo != null && streamInfo.length >= 3) {
                try {
                    sampleRate = Integer.parseInt(streamInfo[0].trim());
                    channels = Integer.parseInt(streamInfo[1].trim());
                    if (streamInfo.length > 2) {
                        codecName = streamInfo[2].trim();
                    }
                } catch (NumberFormatException e) {
                    System.err.println("⚠️ Erro parsando stream info, usando padrões");
                }
            }

            // Extrai informações do formato se disponível
            if (formatInfo != null && formatInfo.length >= 1) {
                try {
                    duration = Double.parseDouble(formatInfo[0].trim());
                    if (formatInfo.length > 1 && !formatInfo[1].trim().isEmpty()) {
                        bitRate = Integer.parseInt(formatInfo[1].trim());
                    }
                } catch (NumberFormatException e) {
                    System.err.println("⚠️ Erro parsando format info, usando padrões");
                }
            }

            // Se ainda não temos duração, tenta extrair do arquivo
            if (duration <= 0) {
                try {
                    duration = getAudioDurationFallback(audioFile.toString());
                } catch (Exception e) {
                    duration = 1.0; // fallback final
                }
            }

            return new AudioInfo(duration, sampleRate, channels, bitRate, codecName);

        } catch (Exception e) {
            System.err.println("⚠️ Erro no parsing de áudio, usando valores padrão: " + e.getMessage());

            // Fallback com valores seguros
            try {
                double duration = getAudioDurationFallback(audioFile.toString());
                return new AudioInfo(duration, 22050, 1, 0, "unknown");
            } catch (Exception fallbackError) {
                return new AudioInfo(1.0, 22050, 1, 0, "unknown");
            }
        }
    }

    // Método auxiliar para obter duração como fallback
    private static double getAudioDurationFallback(String audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                audioFile
        );

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line.trim());
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timeout obtendo duração");
        }

        if (process.exitValue() == 0 && output.length() > 0) {
            try {
                return Double.parseDouble(output.toString());
            } catch (NumberFormatException e) {
                throw new IOException("Duração inválida: " + output.toString());
            }
        }

        throw new IOException("Não foi possível obter duração");
    }
    /**
     * Obtém duração do áudio de forma segura
     */
    public static double getAudioDurationSafe(Path audioFile) {
        try {
            return extractAudioInfo(audioFile).duration;
        } catch (Exception e) {
            System.err.println("⚠️ Erro ao obter duração: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Executa ProcessBuilder com controle avançado e logging
     */
    private static void executeProcessBuilder(ProcessBuilder pb, String operation)
            throws IOException, InterruptedException {

        ffmpegSemaphore.acquire();
        try {
            System.out.printf("⚡ %s...\n", operation);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Monitor de progresso em thread separada
            CompletableFuture<List<String>> outputHandler = CompletableFuture.supplyAsync(() -> {
                List<String> errors = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Filtra apenas erros importantes
                        if (line.toLowerCase().contains("error") &&
                                !line.contains("deprecated") &&
                                !line.contains("warning")) {
                            errors.add(line);
                            System.err.println("❌ FFmpeg: " + line);
                        }
                        // Log de progresso ocasional
                        else if (line.contains("time=") && Math.random() < 0.1) {
                            System.out.println("🔄 " + operation + ": " +
                                    line.replaceAll(".*time=([0-9:.-]+).*", "time=$1"));
                        }
                    }
                } catch (IOException e) {
                    System.err.println("⚠️ Erro lendo saída do processo: " + e.getMessage());
                }
                return errors;
            }, audioProcessingExecutor);

            boolean finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                System.err.printf("⏰ Timeout em %s após %ds\n", operation, FFMPEG_TIMEOUT_SECONDS);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("Timeout: " + operation);
            }

            List<String> errors = outputHandler.join();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                String errorMsg = String.format("Falha em %s (código %d)", operation, exitCode);
                if (!errors.isEmpty()) {
                    errorMsg += ". Erros: " + String.join("; ", errors);
                }
                throw new IOException(errorMsg);
            }

            System.out.printf("✅ %s concluído\n", operation);

        } finally {
            ffmpegSemaphore.release();
        }
    }

    /**
     * Valida arquivo de entrada
     */
    private static void validateInputFile(Path filePath, String description) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException(description + " não encontrado: " + filePath);
        }
        if (!Files.isRegularFile(filePath)) {
            throw new IOException(description + " não é um arquivo válido: " + filePath);
        }
        if (Files.size(filePath) == 0) {
            throw new IOException(description + " está vazio: " + filePath);
        }
    }

    /**
     * Valida arquivo de saída
     */
    private static void validateOutputFile(Path filePath, String description) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException(description + " não foi gerado: " + filePath);
        }
        if (Files.size(filePath) < 1024) {
            throw new IOException(description + " muito pequeno (possivelmente corrompido): " + filePath);
        }
    }

    /**
     * Limpeza de recursos
     */
    public static void cleanup() {
        System.out.println("🧹 Finalizando processamento de áudio...");

        audioProcessingExecutor.shutdown();
        try {
            if (!audioProcessingExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                audioProcessingExecutor.shutdownNow();
                if (!audioProcessingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    System.err.println("⚠️ Executor de áudio não finalizou completamente");
                }
            }
        } catch (InterruptedException e) {
            audioProcessingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("✅ Recursos de áudio liberados");
    }

    /**
     * Record para informações de áudio
     */
    public record AudioInfo(double duration, int sampleRate, int channels, int bitRate, String codecName) {

        public boolean isHighQuality() {
            return sampleRate >= 44100 && bitRate >= 128000 && channels >= 1;
        }

        public String getQualityDescription() {
            if (sampleRate >= 48000 && bitRate >= 256000) return "Premium";
            if (sampleRate >= 44100 && bitRate >= 192000) return "High";
            if (sampleRate >= 44100 && bitRate >= 128000) return "Standard";
            return "Low";
        }

        @Override
        public String toString() {
            return String.format("AudioInfo[%.1fs, %dHz, %dch, %dk, %s, %s]",
                    duration, sampleRate, channels, bitRate/1000, codecName, getQualityDescription());
        }
    }

    /**
     * Verifica se o vídeo tem stream de áudio
     */
    private static boolean hasAudioStream(Path videoFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "quiet", "-show_streams", 
                "-select_streams", "a", videoFile.toString());
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                boolean hasAudio = line != null && !line.trim().isEmpty();
                process.waitFor();
                return hasAudio;
            }
        } catch (Exception e) {
            System.err.println("⚠️ Erro verificando áudio: " + e.getMessage());
            return true; // Assume que tem áudio por segurança
        }
    }

    /**
     * Cria áudio silencioso com a mesma duração do vídeo
     */
    private static void createSilentAudio(Path videoFile, Path outputAudio) throws IOException, InterruptedException {
        // Primeiro obter a duração do vídeo
        double duration = getVideoDuration(videoFile);
        
        System.out.printf("🔇 Criando áudio silencioso de %.1fs para vídeo sem áudio\n", duration);
        
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-y",
            "-f", "lavfi",
            "-i", String.format("anullsrc=channel_layout=stereo:sample_rate=%d", SAMPLE_RATE),
            "-t", String.valueOf(duration),
            "-acodec", "pcm_s16le",
            outputAudio.toString()
        );
        
        executeProcessBuilder(pb, "Criação de áudio silencioso");
    }

    /**
     * Obtém duração do vídeo usando ffprobe
     */
    private static double getVideoDuration(Path videoFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "quiet", "-show_entries", 
                "format=duration", "-of", "csv=p=0", videoFile.toString());
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return Double.parseDouble(line.trim());
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.err.println("⚠️ Erro obtendo duração do vídeo: " + e.getMessage());
        }
        
        return 10.0; // Fallback: 10 segundos
    }
}