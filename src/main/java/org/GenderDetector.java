package org;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * GenderDetector - Detecta o gênero de cada speaker usando análise de áudio
 *
 * Usa o script Python gender_detector.py que implementa:
 * - inaSpeechSegmenter (CNN-based) para detecção precisa
 * - Análise de pitch como fallback
 */
public class GenderDetector {
    private static final Logger logger = Logger.getLogger(GenderDetector.class.getName());
    private static final Gson gson = new Gson();

    /**
     * Resultado da detecção de gênero
     */
    public record GenderResult(
        Map<String, String> speakers,  // {SPEAKER_00: "male", SPEAKER_01: "female"}
        int count
    ) {}

    /**
     * Detecta o gênero de cada speaker no áudio
     *
     * @param audioFile Caminho para o arquivo de áudio (vocals.wav)
     * @param vttFile Caminho para o arquivo VTT com marcadores [SPEAKER_XX]
     * @return Mapa com gênero de cada speaker
     * @throws IOException Se houver erro na execução
     */
    public static GenderResult detectSpeakerGenders(String audioFile, String vttFile) throws IOException, InterruptedException {
        logger.info("👥 Detectando gênero dos speakers...");
        logger.info("   Áudio: " + audioFile);
        logger.info("   VTT: " + vttFile);

        // Copiar script e arquivos para /tmp (único diretório compartilhado com container)
        String scriptPath = "python_scripts/gender_detector.py";
        String tmpScriptPath = "/tmp/gender_detector.py";
        String tmpAudioPath = "/tmp/vocals_gender_temp.wav";
        String tmpVttPath = "/tmp/transcription_gender_temp.vtt";

        java.io.File scriptFile = new java.io.File(scriptPath);
        if (!scriptFile.exists()) {
            throw new IOException("Script não encontrado: " + scriptPath);
        }

        // Copiar script para /tmp
        logger.info("📋 Copiando arquivos para /tmp...");
        java.nio.file.Files.copy(
            scriptFile.toPath(),
            new java.io.File(tmpScriptPath).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );

        // Copiar arquivos de áudio e VTT para /tmp
        java.nio.file.Files.copy(
            new java.io.File(audioFile).toPath(),
            new java.io.File(tmpAudioPath).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );

        java.nio.file.Files.copy(
            new java.io.File(vttFile).toPath(),
            new java.io.File(tmpVttPath).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );

        // Construir comando usando container Docker whisperx (tem as dependências instaladas)
        String[] command = {
            "docker", "exec", "-i", "whisperx-container",
            "python3", tmpScriptPath,
            tmpAudioPath,
            tmpVttPath
        };

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);  // Separar stdout e stderr

        Process process = pb.start();

        // Ler stderr (logs do Python)
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                stderr.append(line).append("\n");
                logger.info("   " + line);
            }
        }

        // Ler stdout (resultado JSON) - a última linha é o JSON válido
        String jsonOutput = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // A última linha não vazia é o JSON
                if (!line.trim().isEmpty()) {
                    jsonOutput = line.trim();
                }
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            logger.severe("❌ Erro na detecção de gênero:");
            logger.severe(stderr.toString());
            throw new IOException("Falha na detecção de gênero (código: " + exitCode + ")");
        }

        if (jsonOutput == null || jsonOutput.isEmpty()) {
            throw new IOException("Nenhum JSON retornado pelo script Python");
        }

        // Parse do resultado JSON
        logger.fine("JSON recebido: " + jsonOutput);

        JsonObject json = gson.fromJson(jsonOutput, JsonObject.class);

        // Converter para Map<String, String>
        Map<String, String> speakers = new HashMap<>();
        JsonObject speakersJson = json.getAsJsonObject("speakers");
        for (String key : speakersJson.keySet()) {
            speakers.put(key, speakersJson.get(key).getAsString());
        }

        int count = json.get("count").getAsInt();

        logger.info("✅ Detecção concluída:");
        for (Map.Entry<String, String> entry : speakers.entrySet()) {
            logger.info(String.format("   %s: %s", entry.getKey(), entry.getValue().toUpperCase()));
        }

        return new GenderResult(speakers, count);
    }

    /**
     * Detecta gênero com fallback para configuração padrão em caso de erro
     *
     * @param audioFile Caminho para o arquivo de áudio
     * @param vttFile Caminho para o arquivo VTT
     * @return Resultado da detecção ou configuração padrão
     */
    public static GenderResult detectWithFallback(String audioFile, String vttFile) {
        try {
            return detectSpeakerGenders(audioFile, vttFile);
        } catch (Exception e) {
            logger.warning("⚠️ Erro na detecção de gênero, usando configuração padrão: " + e.getMessage());

            // Fallback: criar configuração padrão baseada em quantos speakers existem no VTT
            try {
                int speakerCount = countSpeakersInVTT(vttFile);
                Map<String, String> defaultGenders = new HashMap<>();

                // Alternar male/female por padrão
                for (int i = 0; i < speakerCount; i++) {
                    String speakerId = String.format("SPEAKER_%02d", i);
                    String gender = (i % 2 == 0) ? "male" : "female";
                    defaultGenders.put(speakerId, gender);
                }

                logger.info("📋 Usando configuração padrão: " + defaultGenders);
                return new GenderResult(defaultGenders, speakerCount);

            } catch (Exception e2) {
                logger.severe("❌ Falha total na detecção de gênero: " + e2.getMessage());
                // Último fallback: 1 speaker masculino
                Map<String, String> emergency = new HashMap<>();
                emergency.put("SPEAKER_00", "male");
                return new GenderResult(emergency, 1);
            }
        }
    }

    /**
     * Conta quantos speakers diferentes existem no VTT
     */
    private static int countSpeakersInVTT(String vttFile) throws IOException {
        java.util.Set<String> speakers = new java.util.HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new java.io.FileReader(vttFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("[SPEAKER_")) {
                    String speakerId = line.split("]:")[0].replace("[", "").trim();
                    speakers.add(speakerId);
                }
            }
        }

        return speakers.size();
    }

    /**
     * Teste da funcionalidade
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Uso: java GenderDetector <audio.wav> <transcription.vtt>");
            System.exit(1);
        }

        try {
            GenderResult result = detectSpeakerGenders(args[0], args[1]);
            System.out.println("\n📊 Resultado:");
            System.out.println("   Total speakers: " + result.count());
            result.speakers().forEach((speaker, gender) ->
                System.out.println(String.format("   %s: %s", speaker, gender.toUpperCase()))
            );
        } catch (Exception e) {
            System.err.println("❌ Erro: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
