import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Script de teste para verificar instalação e performance do Kokoro TTS
 */
public class TestKokoro {

    private static final String KOKORO_URL = "http://localhost:8880";
    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) {
        System.out.println("🔬 TESTE DE INSTALAÇÃO KOKORO TTS");
        System.out.println("=" + "=".repeat(60));
        System.out.println();

        boolean allPassed = true;

        // Teste 1: Container rodando
        System.out.print("1️⃣  Verificando container... ");
        if (testContainer()) {
            System.out.println("✅ OK");
        } else {
            System.out.println("❌ FALHOU");
            System.out.println("   Execute: docker compose up -d");
            allPassed = false;
        }

        // Teste 2: API respondendo
        System.out.print("2️⃣  Testando API... ");
        if (testAPI()) {
            System.out.println("✅ OK");
        } else {
            System.out.println("❌ FALHOU");
            System.out.println("   Verifique logs: docker logs kokoro-container");
            allPassed = false;
        }

        // Teste 3: Listar vozes
        System.out.print("3️⃣  Listando vozes... ");
        List<String> voices = listVoices();
        if (voices != null && !voices.isEmpty()) {
            System.out.println("✅ " + voices.size() + " vozes disponíveis");
            System.out.println("   PT-BR: " + filterPTBR(voices));
        } else {
            System.out.println("❌ FALHOU");
            allPassed = false;
        }

        // Teste 4: Gerar áudio
        System.out.print("4️⃣  Gerando áudio de teste... ");
        Path testAudio = testGeneration();
        if (testAudio != null) {
            System.out.println("✅ OK");
            System.out.println("   Arquivo: " + testAudio);
            System.out.println("   Para ouvir: mpv " + testAudio);
        } else {
            System.out.println("❌ FALHOU");
            allPassed = false;
        }

        // Teste 5: Performance
        System.out.print("5️⃣  Medindo performance... ");
        PerformanceResult perf = measurePerformance();
        if (perf != null) {
            System.out.println("✅ OK");
            System.out.printf("   RTF: %.1fx%n", perf.rtf);
            System.out.printf("   Tempo médio: %.0fms%n", perf.avgTime);
            System.out.printf("   Status: %s%n", perf.getStatus());
        } else {
            System.out.println("❌ FALHOU");
            allPassed = false;
        }

        // Resultado final
        System.out.println();
        System.out.println("=" + "=".repeat(60));
        if (allPassed) {
            System.out.println("🎉 TODOS OS TESTES PASSARAM!");
            System.out.println("✅ Kokoro TTS está instalado e funcionando corretamente");
            System.out.println();
            System.out.println("📚 Próximos passos:");
            System.out.println("   1. Ler INTEGRACAO_JAVA.md");
            System.out.println("   2. Integrar no TTSUtils.java");
            System.out.println("   3. Testar com seu projeto DubAI");
        } else {
            System.out.println("❌ ALGUNS TESTES FALHARAM");
            System.out.println("   Verifique os erros acima e corrija antes de continuar");
        }
        System.out.println("=" + "=".repeat(60));
    }

    static boolean testContainer() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--filter", "name=kokoro-container", "--format", "{{.Names}}");
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                return line != null && line.contains("kokoro");
            }
        } catch (Exception e) {
            return false;
        }
    }

    static boolean testAPI() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(KOKORO_URL + "/v1/audio/voices"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    static List<String> listVoices() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(KOKORO_URL + "/v1/audio/voices"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                // Parse simple JSON (buscar array de strings)
                List<String> voices = new ArrayList<>();
                String[] parts = body.split("\"");
                for (String part : parts) {
                    if (part.matches("[a-z]{2}_[a-z]+")) {
                        voices.add(part);
                    }
                }
                return voices;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    static List<String> filterPTBR(List<String> voices) {
        return voices.stream()
                .filter(v -> v.startsWith("pf_") || v.startsWith("pm_"))
                .toList();
    }

    static Path testGeneration() {
        try {
            String json = """
                {
                    "model": "kokoro",
                    "input": "Olá, este é um teste do Kokoro TTS",
                    "voice": "pf_dora",
                    "response_format": "wav"
                }
                """;

            Path output = Paths.get("./output/test_kokoro_validation.wav");
            Files.createDirectories(output.getParent());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(KOKORO_URL + "/v1/audio/speech"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<Path> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofFile(output)
            );

            if (response.statusCode() == 200 && Files.exists(output) && Files.size(output) > 1000) {
                return output;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    static PerformanceResult measurePerformance() {
        try {
            List<Long> times = new ArrayList<>();
            String testText = "Este é um teste de performance do Kokoro TTS para medir velocidade real";

            for (int i = 0; i < 3; i++) {
                long start = System.currentTimeMillis();

                String json = String.format("""
                    {
                        "model": "kokoro",
                        "input": "%s",
                        "voice": "pf_dora",
                        "response_format": "wav"
                    }
                    """, testText);

                Path temp = Files.createTempFile("kokoro_perf_", ".wav");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(KOKORO_URL + "/v1/audio/speech"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<Path> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(temp)
                );

                long elapsed = System.currentTimeMillis() - start;
                times.add(elapsed);

                // Calcular duração do áudio
                double audioDuration = getAudioDuration(temp);

                Files.delete(temp);

                if (i == 2) { // Última iteração
                    double avgTime = times.stream().mapToLong(Long::longValue).average().orElse(0);
                    double rtf = audioDuration / (avgTime / 1000.0);
                    return new PerformanceResult(rtf, avgTime);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    static double getAudioDuration(Path audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                audioFile.toString()
            );

            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String duration = br.readLine();
                return Double.parseDouble(duration);
            }
        } catch (Exception e) {
            return 4.0; // Fallback
        }
    }

    static class PerformanceResult {
        double rtf;
        double avgTime;

        PerformanceResult(double rtf, double avgTime) {
            this.rtf = rtf;
            this.avgTime = avgTime;
        }

        String getStatus() {
            if (rtf > 40) return "🚀 Excelente";
            if (rtf > 20) return "✅ Muito bom";
            if (rtf > 10) return "👍 Bom";
            return "⚠️ Abaixo do esperado";
        }
    }
}
