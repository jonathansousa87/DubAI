package org;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * AutomaticVoiceSelector - Seleciona vozes automaticamente baseado em gênero e speakers
 * <p>
 * Presets definidos:
 * Feminino: jessica+river, dora+bella, nicole+aoede
 * Masculino: echo+fenrir+onyx, adam+puck, santa+eric
 */
public class AutomaticVoiceSelector {
    private static final Logger logger = Logger.getLogger(AutomaticVoiceSelector.class.getName());

    /**
     * Presets de vozes femininas (combinações)
     */
    private static final KokoroTTS.VozPTBR[][] FEMALE_PRESETS = {
            {KokoroTTS.VozPTBR.JESSICA_FEMININO, KokoroTTS.VozPTBR.RIVER_FEMININO}
    };

    /**
     * Presets de vozes masculinas (combinações)
     */
    private static final KokoroTTS.VozPTBR[][] MALE_PRESETS = {
            {KokoroTTS.VozPTBR.ECHO_MASCULINO, KokoroTTS.VozPTBR.FENRIR_MASCULINO, KokoroTTS.VozPTBR.ONYX_MASCULINO}
    };

    /**
     * Vozes femininas adicionais (para speakers extras)
     */
    private static final KokoroTTS.VozPTBR[] EXTRA_FEMALE_VOICES = {
            KokoroTTS.VozPTBR.HEART_FEMININO,
            KokoroTTS.VozPTBR.ALLOY_FEMININO,
            KokoroTTS.VozPTBR.KORE_FEMININO,
            KokoroTTS.VozPTBR.NOVA_FEMININO,
            KokoroTTS.VozPTBR.SARAH_FEMININO,
            KokoroTTS.VozPTBR.SKY_FEMININO,
            KokoroTTS.VozPTBR.DORA_FEMININO,
            KokoroTTS.VozPTBR.BELLA_FEMININO,
            KokoroTTS.VozPTBR.NICOLE_FEMININO,
            KokoroTTS.VozPTBR.AOEDE_FEMININO
    };

    /**
     * Vozes masculinas adicionais (para speakers extras)
     */
    private static final KokoroTTS.VozPTBR[] EXTRA_MALE_VOICES = {
            KokoroTTS.VozPTBR.LIAM_MASCULINO,
            KokoroTTS.VozPTBR.MICHAEL_MASCULINO,
            KokoroTTS.VozPTBR.ALEX_MASCULINO,
            KokoroTTS.VozPTBR.ADAM_MASCULINO,
            KokoroTTS.VozPTBR.PUCK_MASCULINO,
            KokoroTTS.VozPTBR.SANTA_MASCULINO,
            KokoroTTS.VozPTBR.ERIC_MASCULINO
    };

    /**
     * Configuração de voz para um speaker
     */
    public record VoiceConfig(
            String speakerId,
            String gender,
            KokoroTTS.VozPTBR[] voices
    ) {
        public String getVoiceDescription() {
            if (voices.length == 1) {
                return voices[0].getDisplayName();
            } else {
                String[] names = new String[voices.length];
                for (int i = 0; i < voices.length; i++) {
                    names[i] = voices[i].getCode();
                }
                return String.join("+", names) + " (combinação)";
            }
        }
    }

    /**
     * Seleciona vozes automaticamente baseado nos gêneros dos speakers
     *
     * @param vocalsWav        Arquivo de áudio (vocals.wav)
     * @param transcriptionVtt Arquivo VTT com marcadores [SPEAKER_XX]
     * @return Mapa de {SPEAKER_XX: VoiceConfig}
     */
    public static Map<String, VoiceConfig> selectVoices(String vocalsWav, String transcriptionVtt) {
        logger.info("🤖 Iniciando seleção automática de vozes...");

        // 1. Detectar gênero de cada speaker
        GenderDetector.GenderResult genderResult = GenderDetector.detectWithFallback(vocalsWav, transcriptionVtt);

        // 2. Organizar speakers por gênero
        List<String> maleSpeakers = new ArrayList<>();
        List<String> femaleSpeakers = new ArrayList<>();

        logger.info("🔍 DEBUG - Mapeamento de gêneros recebido:");
        for (Map.Entry<String, String> entry : genderResult.speakers().entrySet()) {
            String speaker = entry.getKey();
            String gender = entry.getValue();

            logger.info(String.format("   %s -> %s", speaker, gender.toUpperCase()));

            if ("male".equalsIgnoreCase(gender)) {
                maleSpeakers.add(speaker);
            } else {
                femaleSpeakers.add(speaker);
            }
        }

        logger.info(String.format("📊 Speakers detectados: %d masculinos, %d femininos",
                maleSpeakers.size(), femaleSpeakers.size()));
        logger.info("   Masculinos: " + maleSpeakers);
        logger.info("   Femininos: " + femaleSpeakers);

        // 3. Atribuir vozes usando presets + vozes extras se necessário
        Map<String, VoiceConfig> assignments = new HashMap<>();

        // Atribuir vozes femininas
        assignVoicesForGender(femaleSpeakers, "female", FEMALE_PRESETS, EXTRA_FEMALE_VOICES, assignments);

        // Atribuir vozes masculinas
        assignVoicesForGender(maleSpeakers, "male", MALE_PRESETS, EXTRA_MALE_VOICES, assignments);

        // 4. Log do resultado
        logger.info("✅ Seleção automática de vozes concluída:");
        for (Map.Entry<String, VoiceConfig> entry : assignments.entrySet()) {
            VoiceConfig config = entry.getValue();
            logger.info(String.format("   %s (%s): %s",
                    entry.getKey(),
                    config.gender().toUpperCase(),
                    config.getVoiceDescription()));
        }

        return assignments;
    }

    /**
     * Atribui vozes para speakers de um gênero específico
     */
    private static void assignVoicesForGender(
            List<String> speakers,
            String gender,
            KokoroTTS.VozPTBR[][] presets,
            KokoroTTS.VozPTBR[] extraVoices,
            Map<String, VoiceConfig> assignments) {

        // Ordenar speakers alfabeticamente para consistência
        Collections.sort(speakers);

        logger.info(String.format("🎯 Atribuindo vozes para gênero: %s", gender.toUpperCase()));
        logger.info(String.format("   Speakers (ordenados): %s", speakers));

        int speakerIndex = 0;

        // Primeiro, usar presets
        for (int i = 0; i < speakers.size() && i < presets.length; i++) {
            String speaker = speakers.get(i);
            KokoroTTS.VozPTBR[] voiceCombination = presets[i];

            logger.info(String.format("   ✓ %s (%s) -> Preset %d: %s",
                    speaker,
                    gender,
                    i,
                    String.join("+", java.util.Arrays.stream(voiceCombination)
                            .map(v -> v.getCode())
                            .toArray(String[]::new))));

            assignments.put(speaker, new VoiceConfig(speaker, gender, voiceCombination));
            speakerIndex++;
        }

        // Se ainda há speakers, usar vozes extras (individuais)
        int extraIndex = 0;
        while (speakerIndex < speakers.size() && extraIndex < extraVoices.length) {
            String speaker = speakers.get(speakerIndex);
            KokoroTTS.VozPTBR voice = extraVoices[extraIndex];

            assignments.put(speaker, new VoiceConfig(speaker, gender, new KokoroTTS.VozPTBR[]{voice}));
            speakerIndex++;
            extraIndex++;
        }

        // Se AINDA há speakers restantes (muito raro), reutilizar vozes extras ciclicamente
        while (speakerIndex < speakers.size()) {
            String speaker = speakers.get(speakerIndex);
            KokoroTTS.VozPTBR voice = extraVoices[extraIndex % extraVoices.length];

            logger.warning(String.format("⚠️ Reutilizando voz para %s: %s (total speakers > vozes disponíveis)",
                    speaker, voice.getDisplayName()));

            assignments.put(speaker, new VoiceConfig(speaker, gender, new KokoroTTS.VozPTBR[]{voice}));
            speakerIndex++;
            extraIndex++;
        }
    }

    /**
     * Obtém a voz configurada para um speaker específico
     *
     * @param speakerId   ID do speaker (ex: SPEAKER_00)
     * @param assignments Mapa de atribuições
     * @return Array de vozes configuradas, ou null se não encontrado
     */
    public static KokoroTTS.VozPTBR[] getVoiceForSpeaker(String speakerId, Map<String, VoiceConfig> assignments) {
        VoiceConfig config = assignments.get(speakerId);
        if (config != null) {
            return config.voices();
        }

        // Fallback: voz padrão
        logger.warning("⚠️ Speaker não encontrado nas atribuições: " + speakerId + ", usando voz padrão");
        return new KokoroTTS.VozPTBR[]{KokoroTTS.VozPTBR.HEART_FEMININO};
    }

    /**
     * Extrai o speaker ID de um texto com marcador [SPEAKER_XX]:
     *
     * @param text Texto com possível marcador
     * @return Speaker ID (ex: "SPEAKER_00") ou null se não encontrado
     */
    public static String extractSpeakerFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        // Procurar padrão [SPEAKER_XX]: no início do texto
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^\\[SPEAKER_(\\d+)\\]:");
        java.util.regex.Matcher matcher = pattern.matcher(text.trim());

        if (matcher.find()) {
            int speakerNum = Integer.parseInt(matcher.group(1));
            return String.format("SPEAKER_%02d", speakerNum);
        }

        return null;
    }

    /**
     * Teste da funcionalidade
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Uso: java AutomaticVoiceSelector <vocals.wav> <transcription.vtt>");
            System.exit(1);
        }

        try {
            Map<String, VoiceConfig> assignments = selectVoices(args[0], args[1]);

            System.out.println("\n📋 Atribuições de voz:");
            for (Map.Entry<String, VoiceConfig> entry : assignments.entrySet()) {
                VoiceConfig config = entry.getValue();
                System.out.println(String.format("   %s (%s): %s",
                        entry.getKey(),
                        config.gender().toUpperCase(),
                        config.getVoiceDescription()));
            }

            System.out.println("\n✅ Seleção concluída!");

        } catch (Exception e) {
            System.err.println("❌ Erro: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
