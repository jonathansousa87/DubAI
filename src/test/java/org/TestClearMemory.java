import org.ClearMemory;

public class TestClearMemory {
    public static void main(String[] args) {
        try {
            System.out.println("=== Testando otimizações ClearMemory ===");
            ClearMemory.logDockerGpuUsage();
            
            System.out.println("\n=== Testando limpeza de cache Ollama ===");
            ClearMemory.clearOllamaModelCache();
            
            System.out.println("\n=== Testando limpeza geral com foco em Ollama ===");
            ClearMemory.runClearNameThenThreshold("ollama");
            
            System.out.println("\n=== Testando método otimizado final ===");
            ClearMemory.optimizedOllamaCleanup();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}