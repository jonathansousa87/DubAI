import org.Whisper;
import java.io.File;

public class TestWhisperParsing {
    public static void main(String[] args) throws Exception {
        String inputFile = "/home/kadabra/Documentos/projetos/back/DubAI/output/vocals.wav";
        String outputVtt = "/home/kadabra/Documentos/projetos/back/DubAI/output/test_output.vtt";

        System.out.println("Testing transcription with vocals.wav...");

        // This will call the transcribeAudio method which includes our fixes
        Whisper.transcribeAudio(inputFile, outputVtt);

        System.out.println("\n=== Test Complete ===");
        System.out.println("Check output files:");
        System.out.println("  - " + outputVtt);
        System.out.println("  - whisperx_temp.vtt");
        System.out.println("  - reverb_temp.vtt");
    }
}
