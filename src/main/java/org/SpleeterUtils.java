package org;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class SpleeterUtils {

    private static final int MAX_CONCURRENT_TASKS = Runtime.getRuntime().availableProcessors();
    private static final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_TASKS);

    public static void divideAudioIntoChunks(String inputAudio, String outputDir) throws IOException, InterruptedException {
        String ffmpegCommand = String.format(
                "ffmpeg -i \"%s\" -f segment -segment_time 600 -c copy \"%s/chunk_%%03d.wav\"",
                inputAudio, outputDir
        );
        System.out.println("Executando comando FFMPEG: " + ffmpegCommand);
        executeCommand(new String[]{"bash", "-c", ffmpegCommand}, "Erro ao dividir o áudio em chunks.");
    }

    public static void removeBackgroundMusicInParallel(String inputDir, String outputDir) throws IOException, InterruptedException {
        File[] chunks = new File(inputDir).listFiles((dir, name) -> name.startsWith("chunk_") && name.endsWith(".wav"));
        if (chunks == null || chunks.length == 0) {
            throw new IOException("Nenhum chunk encontrado em " + inputDir);
        }

        // CORREÇÃO 1: Criar o diretório separado ANTES do processamento paralelo
        File separatedDir = new File(outputDir + "/separated/");
        if (!separatedDir.exists() && !separatedDir.mkdirs()) {
            throw new IOException("Falha ao criar diretório separado: " + separatedDir.getAbsolutePath());
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (File chunk : chunks) {
                semaphore.acquire();
                executor.submit(() -> {
                    try {
                        System.out.println("Processando chunk: " + chunk.getAbsolutePath());
                        removeBackgroundMusic(chunk.getAbsolutePath(), outputDir + "/separated/");
                    } catch (IOException | InterruptedException e) {
                        System.err.println("Erro ao processar chunk: " + chunk.getAbsolutePath());
                        e.printStackTrace();
                    } finally {
                        semaphore.release();
                    }
                });
            }
        }

        System.out.println("Todos os chunks foram processados.");
    }

    public static void removeBackgroundMusic(String inputAudio, String outputDir) throws IOException, InterruptedException {
        File inputFile = new File(inputAudio);
        if (!inputFile.exists()) {
            throw new IOException("Arquivo de entrada não encontrado: " + inputAudio);
        }

        // Comando spleeter atualizado para o novo formato
        String command = String.format("spleeter separate -o \"%s\" \"%s\"", outputDir, inputAudio);

        System.out.println("Executando comando Spleeter: " + command);
        executeCommand(new String[]{"bash", "-c", command}, "Erro ao remover a música de fundo.");
    }

    public static void concatenateVocals(String separatedDir, String outputFile) throws IOException, InterruptedException {
        concatenateAudioFiles(separatedDir, "vocals.wav", outputFile, "concat_list_vocals.txt");
    }

    public static void concatenateAccompaniment(String separatedDir, String outputFile) throws IOException, InterruptedException {
        System.out.println("🎵 Iniciando concatenação de accompaniment: " + separatedDir + " -> " + outputFile);
        concatenateAudioFiles(separatedDir, "accompaniment.wav", outputFile, "concat_list_accompaniment.txt");
        System.out.println("✅ Concatenação de accompaniment concluída: " + outputFile);
    }

    private static void concatenateAudioFiles(String separatedDir, String fileName, String outputFile, String listFileName) throws IOException, InterruptedException {
        System.out.println("🔧 Concatenando arquivos: " + fileName + " em " + separatedDir);
        File separatedDirectory = new File(separatedDir);
        File[] subDirs = separatedDirectory.listFiles(File::isDirectory);

        if (subDirs == null || subDirs.length == 0) {
            throw new IOException("Nenhum subdiretório encontrado em " + separatedDir);
        }
        
        System.out.println("📁 Encontrados " + subDirs.length + " subdiretórios para concatenar " + fileName);

        // CORREÇÃO 2: Ordenar os subdiretórios numericamente
        Arrays.sort(subDirs, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                String name1 = f1.getName();
                String name2 = f2.getName();

                if (name1.startsWith("chunk_") && name2.startsWith("chunk_")) {
                    try {
                        int num1 = Integer.parseInt(name1.replace("chunk_", ""));
                        int num2 = Integer.parseInt(name2.replace("chunk_", ""));
                        return Integer.compare(num1, num2);
                    } catch (NumberFormatException e) {
                        return name1.compareTo(name2);
                    }
                }
                return name1.compareTo(name2);
            }
        });

        File listFile = new File(separatedDir, listFileName);
        try (FileWriter writer = new FileWriter(listFile)) {
            for (File subDir : subDirs) {
                File audioFile = new File(subDir, fileName);
                if (audioFile.exists()) {
                    writer.write("file '" + audioFile.getAbsolutePath() + "'\n");
                } else {
                    System.out.println("Aviso: Arquivo " + fileName + " não encontrado em " + subDir.getAbsolutePath());
                }
            }
        }

        String ffmpegCommand = String.format(
                "ffmpeg -f concat -safe 0 -i \"%s\" -c copy \"%s\"",
                listFile.getAbsolutePath(), outputFile
        );

        System.out.println("Executando comando de concatenação FFMPEG: " + ffmpegCommand);
        executeCommand(new String[]{"bash", "-c", ffmpegCommand}, "Erro ao concatenar os arquivos " + fileName + ".");
    }

    private static void executeCommand(String[] command, String errorMessage) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        Thread.ofVirtual().start(() -> {
            try (var reader = process.inputReader()) {
                reader.lines().forEach(System.out::println);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(errorMessage + " Código de saída: " + exitCode);
        }
    }
}