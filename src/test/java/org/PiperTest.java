import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class PiperTest {
    public static void main(String[] args) {
        try {
            String text = "Olá mundo, este é um teste.";
            Path outputFile = Paths.get("test_java_piper.wav");
            
            ProcessBuilder pb = new ProcessBuilder(
                    "piper",
                    "--output", outputFile.toString(),
                    "--length-scale", "1.0",
                    text
            );
            
            System.out.println("🔧 Comando: " + String.join(" ", pb.command()));
            System.out.println("📝 Texto: " + text);
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    System.out.println("📤 " + line);
                }
            }
            
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            
            System.out.println("⏱️ Finished: " + finished);
            System.out.println("🔢 Exit code: " + exitCode);
            System.out.println("📁 File exists: " + Files.exists(outputFile));
            if (Files.exists(outputFile)) {
                System.out.println("📊 File size: " + Files.size(outputFile) + " bytes");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}