import org.Translation;

public class TestValidationFlow {
    public static void main(String[] args) {
        try {
            // Configurar Google Gemma 3 (como na GUI)
            Translation.setGoogleApiKey("AIzaSyA1pPJP2fhtFVAVRstdIOZfCZQlektuGpQ");
            Translation.setTranslationMethod(Translation.TranslationMethod.GOOGLE_GEMMA_3);
            
            System.out.println("🧪 Testando fluxo completo de validação de timestamp");
            System.out.println("📝 Entrada: 'I spent a lot of time carefully planning and designing every single aspect of this comprehensive programming course.'");
            System.out.println("⏱️ Tempo: 2.142 segundos (MUITO apertado!)");
            System.out.println("🎯 Esperado: Simplificação automática pelo Gemini");
            System.out.println();
            
            // Executar tradução completa
            Translation.translateFile("test_long_segment.tsv", "test_long_result.vtt");
            
            System.out.println("✅ Teste concluído! Verifique test_long_result.vtt");
            
        } catch (Exception e) {
            System.err.println("❌ Erro no teste: " + e.getMessage());
            e.printStackTrace();
        }
    }
}