package org;

public class TestTTS {
    public static void main(String[] args) {
        try {
            System.out.println("🧪 TESTE: Ativando modo automático de vozes...");

            // Simular o que DubAIGUI faz
            TTSUtils.setAutomaticVoiceSelection(true);
            System.out.println("✅ Modo automático ativado");

            System.out.println("\n🧪 TESTE: Processando VTT com TTSUtils...");
            String vttPath = "output/transcription.vtt";

            // Chamar processVttFile (igual DubAIGUI faz)
            TTSUtils.processVttFile(vttPath);

            System.out.println("\n✅ TESTE COMPLETO!");

        } catch (Exception e) {
            System.err.println("❌ ERRO NO TESTE:");
            e.printStackTrace();
        }
    }
}