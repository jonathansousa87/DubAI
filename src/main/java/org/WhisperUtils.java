package org;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class WhisperUtils {

    private static final String WHISPERX_PATH = "/home/kadabra/miniconda3/envs/whisperx_env/bin/whisperx";
    private static final String[] MODELS = {
            "large-v3",
            "large-v3-turbo",
            "large-v2",
            "large",
            "medium",
            "small",
            "base"
    };

    // Limita quantidade de transcrições simultâneas (ex: 2)
    private static final Semaphore transcriptionSemaphore = new Semaphore(2);

    // Executor para virtual threads (Java 21+)
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Tenta transcrever áudio usando modelos diferentes.
     * Usa virtual threads e semaphore para controle de concorrência.
     */
    public static void transcribeAudio(String inputFile, String outputVtt) throws IOException, InterruptedException {
        for (String model : MODELS) {
            transcriptionSemaphore.acquire();
            try {
                System.out.println("[WhisperUtils] Tentando transcrever com modelo: " + model);
                executeWhisperX(inputFile, model, outputVtt);
                System.out.println("[WhisperUtils] Transcrição concluída com sucesso com o modelo: " + model);
                return; // sucesso, para aqui
            } catch (IOException e) {
                String msg = e.getMessage().toLowerCase();
                System.err.println("[WhisperUtils] Falha com o modelo " + model + ": " + e.getMessage());

                // Detecta erro relacionado a falta de memória CUDA (ajuste conforme sua saída)
                if (msg.contains("out of memory") || msg.contains("cuda") || msg.contains("memory")) {
                    System.err.println("[WhisperUtils] Erro detectado relacionado a memória GPU, tentando próximo modelo...");
                } else {
                    // Para erros que não são memória, pode querer lançar ou logar, aqui só continua
                    System.err.println("[WhisperUtils] Erro não relacionado à memória, mas tentando próximo modelo...");
                }
            } finally {
                transcriptionSemaphore.release();
            }
        }
        throw new IOException("Falha ao transcrever. Todos os modelos falharam.");
    }

    private static void executeWhisperX(String inputFile, String model, String outputVtt) throws IOException, InterruptedException {
        File outputFile = new File(outputVtt);
        String outputDir = outputFile.getParent();
        if (outputDir == null) outputDir = ".";

        String[] command = {
                WHISPERX_PATH,
                inputFile,
                "--model", model,
                "--device", "cuda",
                "--fp16", "True",
                "--batch_size", "1",  // Ajustado para 1
                "--threads", "1",
                "--output_dir", outputDir,
                "--output_format", "vtt"
        };

        System.out.println("[WhisperUtils] Executando: " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("LD_LIBRARY_PATH", "/usr/lib:/usr/local/cuda/lib64");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder outputLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append("\n");
                System.out.println("[WhisperUtils] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("[WhisperUtils] Comando falhou com código: " + exitCode + ". Saída: " + outputLog.toString());
        }

        File generatedFile = new File(outputDir, "vocals.vtt");
        if (!generatedFile.exists()) {
            throw new IOException("[WhisperUtils] vocals.vtt não encontrado. Saída:\n" + outputLog.toString());
        }

        // Criar backup do vocals.vtt original antes de renomear
        try {
            File backupFile = new File(outputDir, "transcription_whisperx_original.vtt");
            Files.copy(generatedFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[WhisperUtils] 📋 Backup da transcrição WhisperX criado: " + backupFile.getName());
        } catch (Exception e) {
            System.err.println("[WhisperUtils] ⚠️ Erro ao criar backup: " + e.getMessage());
        }

        // Tenta renomear ou copiar arquivo para destino final
        if (!generatedFile.renameTo(outputFile)) {
            System.out.println("[WhisperUtils] renameTo falhou, copiando arquivo...");
            Files.copy(generatedFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        System.out.println("[WhisperUtils] Arquivo salvo em: " + outputFile.getAbsolutePath());
    }

    /**
     * Fecha o executor ao finalizar o programa (chame no shutdown hook)
     */
    public static void shutdownExecutor() {
        executor.shutdown();
    }
}
