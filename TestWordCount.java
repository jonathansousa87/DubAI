import org.Translation;
import java.lang.reflect.Method;

public class TestWordCount {
    public static void main(String[] args) {
        try {
            String text = "Eu dediquei muito tempo ao planejamento deste curso.";
            
            // Usar reflexão para acessar método privado countWords
            Method countWordsMethod = Translation.class.getDeclaredMethod("countWords", String.class);
            countWordsMethod.setAccessible(true);
            
            int wordCount = (int) countWordsMethod.invoke(null, text);
            
            System.out.println("🔤 Texto: " + text);
            System.out.println("📊 Palavras contadas: " + wordCount);
            
            // Calcular tempo necessário
            double PORTUGUESE_SPEAKING_SPEED = 2.5; // palavras por segundo
            double requiredTime = wordCount / PORTUGUESE_SPEAKING_SPEED;
            double availableTime = 2.142;
            double ratio = requiredTime / availableTime;
            
            System.out.println("⏱️ Tempo necessário: " + String.format("%.2f", requiredTime) + "s");
            System.out.println("⏱️ Tempo disponível: " + availableTime + "s");
            System.out.println("📈 Ratio: " + String.format("%.2f", ratio));
            System.out.println("🚨 Deveria simplificar? " + (ratio > 0.95 ? "SIM" : "NÃO"));
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}