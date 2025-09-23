package org;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Interface Gráfica Profissional para DubAI
 * Sistema de Dublagem Automática com IA
 */
public class DubAIGUI extends JFrame {

    private static final Logger logger = Logger.getLogger(DubAIGUI.class.getName());
    
    // Componentes da interface
    private JTextField videoDirField;
    // Removed modelComboBox - using only Google Gemma 3 API
    private JComboBox<String> translationMethodComboBox;
    private JTextField googleApiKeyField;
    private JLabel googleApiKeyLabel;
    private JTextArea logArea;
    private JProgressBar progressBar;
    private JButton processButton;
    // Removed refreshModelsButton - no longer needed without Ollama
    
    // Cores profissionais
    private static final Color PRIMARY_COLOR = new Color(33, 150, 243);
    private static final Color SECONDARY_COLOR = new Color(76, 175, 80);
    private static final Color BACKGROUND_COLOR = new Color(250, 250, 250);
    private static final Color PANEL_COLOR = Color.WHITE;

    public DubAIGUI() {
        initializeGUI();
        redirectConsoleToGUI(); // Redirecionar console para GUI
    }

    private void initializeGUI() {
        // Configurações da janela principal
        setTitle("DubAI - Sistema Profissional de Dublagem Automática");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 700);
        setLocationRelativeTo(null);
        setResizable(true);
        
        // Look and Feel moderno (usando padrão)
        // UIManager já usa look padrão do sistema

        // Layout principal
        setLayout(new BorderLayout());
        getContentPane().setBackground(BACKGROUND_COLOR);

        // Painel principal
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        mainPanel.setBackground(BACKGROUND_COLOR);

        // Cabeçalho
        JPanel headerPanel = createHeaderPanel();
        
        // Painel de configurações
        JPanel configPanel = createConfigPanel();
        
        // Painel de log e progresso
        JPanel bottomPanel = createBottomPanel();

        // Adicionar componentes
        mainPanel.add(headerPanel, BorderLayout.NORTH);
        mainPanel.add(configPanel, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        add(mainPanel);
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(PRIMARY_COLOR);
        headerPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel("DubAI - Dublagem Automática com IA", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);

        JLabel subtitleLabel = new JLabel("Transforme vídeos em inglês para português brasileiro", SwingConstants.CENTER);
        subtitleLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        subtitleLabel.setForeground(new Color(200, 230, 255));

        JPanel textPanel = new JPanel(new GridLayout(2, 1));
        textPanel.setOpaque(false);
        textPanel.add(titleLabel);
        textPanel.add(subtitleLabel);

        headerPanel.add(textPanel, BorderLayout.CENTER);
        return headerPanel;
    }

    private JPanel createConfigPanel() {
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBackground(BACKGROUND_COLOR);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Painel de arquivos
        JPanel filesPanel = createFilesPanel();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        configPanel.add(filesPanel, gbc);

        // Painel de modelos
        JPanel modelsPanel = createModelsPanel();
        gbc.gridy = 1;
        configPanel.add(modelsPanel, gbc);

        // Botão processar
        processButton = new JButton("🎬 PROCESSAR VÍDEO");
        processButton.setFont(new Font("Arial", Font.BOLD, 16));
        processButton.setBackground(SECONDARY_COLOR);
        processButton.setForeground(Color.WHITE);
        processButton.setPreferredSize(new Dimension(200, 50));
        processButton.addActionListener(this::processVideo);
        
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        configPanel.add(processButton, gbc);

        return configPanel;
    }

    private JPanel createFilesPanel() {
        JPanel filesPanel = new JPanel(new GridBagLayout());
        filesPanel.setBorder(new TitledBorder("📁 Arquivos"));
        filesPanel.setBackground(PANEL_COLOR);
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Pasta de vídeos
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        filesPanel.add(new JLabel("Pasta de Vídeos:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0;
        videoDirField = new JTextField();
        videoDirField.setPreferredSize(new Dimension(300, 25));
        filesPanel.add(videoDirField, gbc);
        
        gbc.gridx = 2; gbc.weightx = 0.0;
        JButton browseVideoButton = new JButton("📂");
        browseVideoButton.addActionListener(e -> browseVideoDirectory());
        filesPanel.add(browseVideoButton, gbc);

        // Informação sobre saída (somente leitura)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        filesPanel.add(new JLabel("Saída:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        JLabel outputInfoLabel = new JLabel("output/ (automático) → vídeos substituídos com _dub.mp4");
        outputInfoLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        outputInfoLabel.setForeground(Color.GRAY);
        filesPanel.add(outputInfoLabel, gbc);
        gbc.gridwidth = 1; // Reset para próximas adições

        return filesPanel;
    }

    private JPanel createModelsPanel() {
        JPanel modelsPanel = new JPanel(new GridBagLayout());
        modelsPanel.setBorder(new TitledBorder("🤖 Configurações de IA"));
        modelsPanel.setBackground(PANEL_COLOR);
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Método de tradução
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        modelsPanel.add(new JLabel("Tradução:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        translationMethodComboBox = new JComboBox<>(new String[]{
            "Google Gemma 3 27B (API)"
        });
        translationMethodComboBox.setPreferredSize(new Dimension(200, 25));
        translationMethodComboBox.addActionListener(e -> onTranslationMethodChanged());
        modelsPanel.add(translationMethodComboBox, gbc);
        
        // Campo API Key do Google (inicialmente oculto)
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; gbc.gridwidth = 1;
        googleApiKeyLabel = new JLabel("Google AI Key:");
        googleApiKeyLabel.setVisible(false);
        modelsPanel.add(googleApiKeyLabel, gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        googleApiKeyField = new JTextField("AIzaSyA1pPJP2fhtFVAVRstdIOZfCZQlektuGpQ");
        googleApiKeyField.setPreferredSize(new Dimension(200, 25));
        googleApiKeyField.setVisible(false);
        googleApiKeyField.setToolTipText("API Key já configurada - pode usar diretamente ou inserir sua própria");
        modelsPanel.add(googleApiKeyField, gbc);

        return modelsPanel;
    }

    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.setBackground(BACKGROUND_COLOR);

        // Barra de progresso
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Pronto para processar");
        progressBar.setPreferredSize(new Dimension(0, 25));

        // Área de log
        logArea = new JTextArea(10, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(new Color(248, 248, 248));
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(new TitledBorder("📋 Log do Sistema"));

        bottomPanel.add(progressBar, BorderLayout.NORTH);
        bottomPanel.add(logScrollPane, BorderLayout.CENTER);

        return bottomPanel;
    }


    /**
     */

    /**
     * Limpeza preventiva /tmp ao abrir GUI
     */
    private void cleanTmpPreventive() {
        try {
            logMessage("🧹 Limpeza preventiva /tmp para evitar erros Piper...");
            
            // Remover arquivos runc-process de Docker
            ProcessBuilder cleanRunc = new ProcessBuilder("sudo", "rm", "-rf", "/tmp/runc-process*");
            Process cleanProcess = cleanRunc.start();
            if (cleanProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                logMessage("✅ Arquivos Docker temporários removidos");
            }
            
        } catch (Exception e) {
            logMessage("⚠️ Limpeza /tmp falhou: " + e.getMessage());
        }
    }


    private void browseVideo() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setAcceptAllFileFilterUsed(false);
        fileChooser.addChoosableFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().matches(".*\\.(mp4|mkv|avi|mov|wmv|flv|webm)$");
            }

            @Override
            public String getDescription() {
                return "Arquivos de Vídeo (*.mp4, *.mkv, *.avi, *.mov, *.wmv, *.flv, *.webm)";
            }
        });

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String selectedPath = fileChooser.getSelectedFile().getAbsolutePath();
            videoDirField.setText(selectedPath);
            
            // Verificar quantos vídeos .mp4 existem na pasta
            File dir = new File(selectedPath);
            File[] videoFiles = dir.listFiles((d, name) -> 
                name.toLowerCase().endsWith(".mp4") && !name.contains("_dub"));
            
            if (videoFiles != null && videoFiles.length > 0) {
                logMessage(String.format("📁 Pasta selecionada: %d vídeos encontrados", videoFiles.length));
                for (File video : videoFiles) {
                    logMessage("   📹 " + video.getName());
                }
            } else {
                logMessage("⚠️ Nenhum vídeo .mp4 encontrado na pasta selecionada");
            }
        }
    }

    private void browseVideoDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Selecione a pasta contendo os vídeos .mp4");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String selectedPath = fileChooser.getSelectedFile().getAbsolutePath();
            videoDirField.setText(selectedPath);
            
            // Verificar quantos vídeos .mp4 existem na pasta
            File dir = new File(selectedPath);
            File[] videoFiles = dir.listFiles((d, name) -> 
                name.toLowerCase().endsWith(".mp4") && !name.contains("_dub"));
            
            if (videoFiles != null && videoFiles.length > 0) {
                logMessage(String.format("📁 Pasta selecionada: %d vídeos encontrados", videoFiles.length));
                for (File video : videoFiles) {
                    logMessage("   📹 " + video.getName());
                }
            } else {
                logMessage("⚠️ Nenhum vídeo .mp4 encontrado na pasta selecionada");
            }
        }
    }

    private void processVideo(ActionEvent e) {
        // Configurar método de tradução
        boolean isGoogleGemma = translationMethodComboBox.getSelectedIndex() == 1;
        
        if (isGoogleGemma) {
            // Usar Google Gemma 3 27B
            String apiKey = googleApiKeyField.getText().trim();
            if (apiKey.isEmpty()) {
                JOptionPane.showMessageDialog(this, 
                    "Configure sua API Key do Google AI!\n\nObtenha gratuitamente em:\nhttps://aistudio.google.com/", 
                    "API Key Necessária", 
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            Translation.setGoogleApiKey(apiKey);
            Translation.setTranslationMethod(Translation.TranslationMethod.GOOGLE_GEMMA_3);
            logMessage("🤖 Configurado para usar Google Gemma 3 27B");
        }
        
        // Validação dos campos
        String videosDir = videoDirField.getText().trim();
        String selectedModel = "Google Gemma 3 27B (API)"; // Fixed model since Ollama was removed

        if (videosDir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Selecione a pasta contendo os vídeos!", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!Files.exists(Paths.get(videosDir)) || !Files.isDirectory(Paths.get(videosDir))) {
            JOptionPane.showMessageDialog(this, "A pasta de vídeos não existe!", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        // Verificar se há vídeos .mp4 na pasta
        File dir = new File(videosDir);
        File[] videoFiles = dir.listFiles((d, name) -> 
            name.toLowerCase().endsWith(".mp4") && !name.contains("_dub"));
        
        if (videoFiles == null || videoFiles.length == 0) {
            JOptionPane.showMessageDialog(this, "Nenhum vídeo .mp4 encontrado na pasta!", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Removed model validation since we're using fixed Google Gemma 3 model

        // Desabilitar controles durante processamento
        setProcessingState(true);

        // Extrair método de tradução
        String translationMethod = "auto"; // Método unificado

        // Processar em thread separada
        CompletableFuture.runAsync(() -> {
            try {
                logMessage("🚀 INICIANDO PROCESSAMENTO EM LOTE");
                logMessage("📁 Pasta: " + videosDir);
                logMessage(String.format("📹 Vídeos encontrados: %d", videoFiles.length));
                logMessage("🤖 Modelo: " + selectedModel);
                logMessage("🌐 Método: " + translationMethod);

                // Criar diretório output se não existir
                File outputDir = new File("output");
                Path outputPath = outputDir.toPath().toAbsolutePath();
                if (!Files.exists(outputPath)) {
                    Files.createDirectories(outputPath);
                    logMessage("📁 Diretório output/ criado: " + outputPath.toString());
                } else {
                    logMessage("📁 Usando diretório output existente: " + outputPath.toString());
                }

                // Processar todos os vídeos
                processAllVideosInDirectory(videosDir, selectedModel, translationMethod, videoFiles);

            } catch (Exception ex) {
                logMessage("❌ ERRO: " + ex.getMessage());
                JOptionPane.showMessageDialog(this, "Erro durante processamento: " + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            } finally {
                SwingUtilities.invokeLater(() -> setProcessingState(false));
            }
        });
    }

    private void processAllVideosInDirectory(String videosDir, String model, String translationMethod, File[] videoFiles) {
        try {
            logMessage(String.format("🎬 Processando %d vídeos...", videoFiles.length));
            
            int successCount = 0;
            int failureCount = 0;
            
            for (int i = 0; i < videoFiles.length; i++) {
                File videoFile = videoFiles[i];
                String videoName = videoFile.getName();
                
                try {
                    logMessage(String.format("\n🎯 Processando vídeo %d/%d: %s", i + 1, videoFiles.length, videoName));
                    
                    // Processar este vídeo específico usando métodos próprios
                    processVideoConsolidated(videoFile, videosDir, i + 1, videoFiles.length, model, translationMethod);
                    
                    // Mover vídeo final para pasta original com _dub
                    moveFinishedVideoToOriginalLocation(videoFile, videosDir);
                    
                    successCount++;
                    logMessage(String.format("✅ Vídeo %d/%d concluído: %s", i + 1, videoFiles.length, videoName));
                    
                } catch (Exception e) {
                    failureCount++;
                    logMessage(String.format("❌ Falha no vídeo %d/%d (%s): %s", i + 1, videoFiles.length, videoName, e.getMessage()));
                    
                    // Atualizar progresso mesmo com falha
                    int progress = (int) ((double) (i + 1) / videoFiles.length * 100);
                    updateProgress(progress, String.format("Erro no vídeo %s", videoName));
                }
            }
            
            // Relatório final
            logMessage(String.format("\n🎉 PROCESSAMENTO CONCLUÍDO!"));
            logMessage(String.format("✅ Sucessos: %d/%d vídeos", successCount, videoFiles.length));
            logMessage(String.format("❌ Falhas: %d/%d vídeos", failureCount, videoFiles.length));
            updateProgress(100, "Processamento em lote concluído!");
            
        } catch (Exception e) {
            logMessage("❌ ERRO GERAL: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }


    private void processVideoConsolidated(File videoFile, String videosDir, int currentVideo, int totalVideos, String model, String translationMethod) throws Exception {
        Instant startTime = Instant.now();
        logMessage(String.format("⏱️ Iniciando processamento cronometrado para: %s", videoFile.getName()));

        try {
            String videoPath = videoFile.getAbsolutePath();

            prepareOutputDirectory();

            String outputDir = new File("output").getAbsolutePath();

            int baseProgress = (currentVideo - 1) * 100 / totalVideos;
            int progressRange = 100 / totalVideos;

            updateProgress(baseProgress + progressRange * 10 / 100, "Extraindo áudio...");
            String audioPath = extractAudio(videoPath, outputDir);

            updateProgress(baseProgress + progressRange * 25 / 100, "Separando vocal...");
            String vocalsPath = separateAudio(audioPath, outputDir);
            // Limpeza após Spleeter
            System.gc();
            
            updateProgress(baseProgress + progressRange * 50 / 100, "Transcrevendo...");
            String vttPath = transcribeAudio(vocalsPath, outputDir);
            // Limpeza após Whisper
            try {
                org.ClearMemory.runClearNameThenThreshold("whisper");
                logMessage("🧹 Memória limpa após transcrição");
            } catch (Exception e) {
                logMessage("⚠️ Aviso: falha na limpeza pós-transcrição: " + e.getMessage());
            }

            updateProgress(baseProgress + progressRange * 70 / 100, "Traduzindo...");
            String translatedVttPath = translateVtt(vttPath, outputDir, model, translationMethod);

            updateProgress(baseProgress + progressRange * 85 / 100, "Gerando TTS...");
            String dubedAudioPath = generateTTS(translatedVttPath, outputDir);
            // Limpeza após TTS
            try {
                org.ClearMemory.cleanAIModelCaches();
                System.gc();
                logMessage("🧹 Cache AI limpo após TTS");
            } catch (Exception e) {
                logMessage("⚠️ Aviso: falha na limpeza pós-TTS: " + e.getMessage());
            }

            updateProgress(baseProgress + progressRange * 95 / 100, "Combinando vídeo...");
            String finalVideoPath = combineVideoWithAudio(videoPath, dubedAudioPath, outputDir);


        } finally {
            // Limpeza final completa antes do próximo vídeo
            try {
                logMessage("🧹 Limpeza final de memória...");
                org.ClearMemory.runClearNameThenThreshold("final_cleanup");
                org.ClearMemory.cleanAIModelCaches();
                System.gc();
                logMessage("✅ Memória totalmente limpa para próximo vídeo");
            } catch (Exception e) {
                logMessage("⚠️ Aviso: falha na limpeza final: " + e.getMessage());
            }
            
            // --- INÍCIO DO NOVO BLOCO DE CONTAGEM REGRESSIVA ---
            logMessage("✅ Etapa concluída. Preparando para o próximo vídeo...");
            try {
                for (int i = 10; i > 0; i--) {
                    // Atualiza o log com a contagem. O método logMessage já usa o a thread da GUI.
                    logMessage(String.format("... %d", i));

                    // Pausa a thread de processamento por 1 segundo
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ie) {
                // Se a thread for interrompida, restaura o status de interrupção e continua
                Thread.currentThread().interrupt();
                logMessage("⚠️ Contagem regressiva interrompida.");
            }
            // --- FIM DO NOVO BLOCO DE CONTAGEM REGRESSIVA ---

            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);

            long totalSeconds = duration.getSeconds();
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;

            logMessage(String.format("⏱️ Tempo total de processamento para '%s': %d minutos e %d segundos.",
                    videoFile.getName(), minutes, seconds));
        }
    }

    private void moveFinishedVideoToOriginalLocation(File originalVideo, String videosDir) throws Exception {
        // O vídeo final está em output/ com nome _dubbed.mp4
        String originalName = originalVideo.getName();
        String dubName = originalName.replace(".mp4", "_dub.mp4");

        String outputDir = new File("output").getAbsolutePath();
        Path sourcePath = Paths.get(outputDir + "/" + originalName.replace(".mp4", "_dubbed.mp4"));
        Path targetPath = Paths.get(videosDir + "/" + dubName);

        if (Files.exists(sourcePath)) {
            // 1. Mover o vídeo dublado para o local de destino
            Files.move(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logMessage("📁 Vídeo movido para: " + targetPath.toString());

            // 2. NOVO: Bloco para excluir o vídeo original após o sucesso da movimentação
            try {
                Files.delete(originalVideo.toPath());
                logMessage("🗑️ Vídeo original excluído: " + originalVideo.getAbsolutePath());
            } catch (IOException e) {
                // Logar um aviso se a exclusão falhar, mas não interromper o processo
                logMessage("⚠️ Falha ao excluir o vídeo original (" + originalVideo.getName() + "): " + e.getMessage());
            }

        } else {
            logMessage("⚠️ Arquivo final não encontrado, o vídeo original não será excluído: " + sourcePath.toString());
        }
    }

    private void processVideoWithModel(String videoPath, String outputDir, String model, String translationMethod) {
        try {
            updateProgress(5, "Preparando processamento...");
            
            updateProgress(10, "Extraindo áudio do vídeo...");
            String audioPath = extractAudio(videoPath, outputDir);
            
            updateProgress(20, "Separando vocal do áudio...");
            String vocalsPath = separateAudio(audioPath, outputDir);
            
            updateProgress(40, "Transcrevendo áudio com WhisperX...");
            String vttPath = transcribeAudio(vocalsPath, outputDir);
            
            updateProgress(60, "Traduzindo legendas com " + model + "...");
            String translatedVttPath = translateVtt(vttPath, outputDir, model, translationMethod);
            
            updateProgress(85, "Gerando áudio em português com Piper TTS...");
            String dubedAudioPath = generateTTS(translatedVttPath, outputDir);
            
            updateProgress(95, "Combinando vídeo com novo áudio...");
            String finalVideoPath = combineVideoWithAudio(videoPath, dubedAudioPath, outputDir);
            
            updateProgress(100, "✅ Processamento concluído!");
            
            logMessage("🎉 SUCESSO: Vídeo processado com sucesso!");
            logMessage("📁 Vídeo final: " + finalVideoPath);
            logMessage("📁 Legendas: " + translatedVttPath);

        } catch (Exception e) {
            throw new RuntimeException("Erro no processamento: " + e.getMessage(), e);
        }
    }
    
    
    private String extractAudio(String videoPath, String outputDir) throws Exception {
        String audioPath = outputDir + "/audio.wav";
        logMessage("🎵 Extraindo áudio para: " + audioPath);
        
        // USAR SISTEMA REAL - AudioUtils.extractAudio
        try {
            Path videoFilePath = Paths.get(videoPath);
            Path audioFilePath = Paths.get(audioPath);
            AudioUtils.extractAudio(videoFilePath, audioFilePath);
            logMessage("✅ Áudio extraído com sucesso");
        } catch (Exception e) {
            logMessage("❌ Erro na extração: " + e.getMessage());
            throw e;
        }
        
        return audioPath;
    }
    
    private String separateAudio(String audioPath, String outputDir) throws Exception {
        String vocalsPath = outputDir + "/vocals.wav";
        logMessage("🎵 Separando vocal do áudio...");
        
        // USAR SISTEMA REAL - SpleeterUtils
        try {
            // Dividir áudio em chunks se necessário
            SpleeterUtils.divideAudioIntoChunks(audioPath, outputDir);
            
            // Remover música de fundo
            SpleeterUtils.removeBackgroundMusicInParallel(outputDir, outputDir);
            
            // Concatenar vocais
            SpleeterUtils.concatenateVocals(outputDir + "/separated/", vocalsPath);
            
            // Concatenar accompaniment (sons de fundo)
            String accompanimentPath = outputDir + "/accompaniment.wav";
            SpleeterUtils.concatenateAccompaniment(outputDir + "/separated/", accompanimentPath);
            
            logMessage("✅ Separação de vocal e accompaniment concluída");
        } catch (Exception e) {
            logMessage("❌ Erro na separação: " + e.getMessage());
            // Fallback: usar áudio original se separação falhar
            logMessage("⚠️ Usando áudio original como fallback");
            Files.copy(Paths.get(audioPath), Paths.get(vocalsPath));
        }
        
        return vocalsPath;
    }
    
    private String transcribeAudio(String audioPath, String outputDir) throws Exception {
        String vttPath = outputDir + "/transcription.vtt";
        String tsvPath = outputDir + "/vocals.tsv";
        
        logMessage("🎤 Transcrevendo áudio...");
        
        // 🎤 TRANSCRIÇÃO ÚNICA - Evita duplicação do Whisper
        try {
            logMessage("🎯 Executando transcrição única (VTT + TSV)...");
            
            // ÚNICA execução do Whisper - gera VTT internamente e depois TSV
            Whisper.transcribeForTranslation(audioPath, outputDir);
            logMessage("✅ Gerados em uma execução: transcription.vtt + vocals.tsv");
            
        } catch (Exception e) {
            logMessage("❌ Erro na transcrição: " + e.getMessage());
            throw e;
        }
        
        return vttPath;
    }
    
    private String translateVtt(String vttPath, String outputDir, String model, String method) throws Exception {
        String translatedVttPath = outputDir + "/transcription_translated.vtt";
        String tsvPath = outputDir + "/vocals.tsv";
        
        logMessage("🌐 Traduzindo com TSV otimizado...");
        logMessage("📝 Modelo: " + model + " | Método: " + method);
        
        // Verificar se estamos usando Google Gemma 3
        boolean isGoogleGemma = method.equals("Google Gemma 3 27B (API)");
        
        if (!isGoogleGemma) {
            try {
                // Limpeza de GPU + caches AI
                org.ClearMemory.runClearNameThenThreshold("gui_pre_translation_intensive");
                org.ClearMemory.cleanAIModelCaches(); // Limpa caches de modelos AI
                
                Thread.sleep(3000); // Aguardar restart
                
                // Segunda limpeza
                logMessage("✅ GPU e caches AI totalmente limpos para tradução");
            } catch (Exception e) {
                logMessage("⚠️ Aviso: falha na limpeza pré-tradução: " + e.getMessage());
            }
        } else {
        }
        
        // INTEGRAÇÃO REAL com Translation usando TSV otimizado
        try {
            // Tentar TSV primeiro (formato otimizado)
            if (Files.exists(Paths.get(tsvPath))) {
                logMessage("📊 Usando TSV otimizado para tradução");
                org.Translation.translateFileWithModel(tsvPath, translatedVttPath, method, model);
                logMessage("✅ Tradução TSV → VTT concluída");
            } else if (Files.exists(Paths.get(vttPath))) {
                // Fallback para VTT se TSV não existir
                logMessage("📄 Fallback: usando VTT para tradução");
                org.Translation.translateFileWithModel(vttPath, translatedVttPath, method, model);
                logMessage("✅ Tradução VTT → VTT concluída");
            } else {
                throw new Exception("Nenhum arquivo de transcrição encontrado (TSV ou VTT)");
            }
            
        } catch (Exception e) {
            logMessage("❌ Erro na tradução: " + e.getMessage());
            throw e;
        }
        
        return translatedVttPath;
    }
    
    private String generateTTS(String vttPath, String outputDir) throws Exception {
        String audioPath = outputDir + "/output.wav"; // TTSUtils sempre gera aqui
        logMessage("🗣️ Gerando áudio dublado...");
        
        // USAR SISTEMA REAL - TTSUtils.processVttFile  
        try {
            // Verificar se arquivo VTT traduzido existe
            if (!Files.exists(Paths.get(vttPath))) {
                throw new Exception("Arquivo VTT traduzido não encontrado: " + vttPath);
            }
            
            // Copiar VTT para o diretório output/ onde TTSUtils espera
            String outputVttPath = outputDir + "/transcription.vtt";
            Files.createDirectories(Paths.get(outputDir));
            Files.copy(Paths.get(vttPath), Paths.get(outputVttPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            // Chamar o processamento TTS real
            TTSUtils.processVttFile(outputVttPath);
            
            // Verificar se arquivo foi gerado
            if (Files.exists(Paths.get(audioPath))) {
                logMessage("✅ Áudio dublado gerado com sucesso: " + audioPath);
            } else {
                throw new Exception("TTSUtils não gerou output.wav em: " + audioPath);
            }
        } catch (Exception e) {
            logMessage("❌ Erro no TTS: " + e.getMessage());
            throw e;
        }
        
        return audioPath;
    }
    
    private String combineVideoWithAudio(String videoPath, String audioPath, String outputDir) throws Exception {
        String finalVideoPath = outputDir + "/" + 
            Paths.get(videoPath).getFileName().toString().replace(".mp4", "_dubbed.mp4");
        logMessage("🎬 Combinando vídeo com áudio dublado: " + finalVideoPath);
        
        // USAR SISTEMA REAL - AudioUtils.replaceAudio
        try {
            // Verificar se áudio dublado existe
            if (!Files.exists(Paths.get(audioPath))) {
                throw new Exception("Áudio dublado não encontrado: " + audioPath);
            }
            
            // Chamar a combinação real
            Path originalVideoPath = Paths.get(videoPath);
            Path dubedAudioPath = Paths.get(audioPath);  
            Path finalVideoPath_Path = Paths.get(finalVideoPath);
            
            AudioUtils.replaceAudio(originalVideoPath, dubedAudioPath, finalVideoPath_Path);
            logMessage("✅ Vídeo final criado com sucesso");
        } catch (Exception e) {
            logMessage("❌ Erro na combinação: " + e.getMessage());
            throw e;
        }
        
        return finalVideoPath;
    }

    private void setProcessingState(boolean processing) {
        processButton.setEnabled(!processing);
        // Removed refreshModelsButton - no longer needed without Ollama
        videoDirField.setEnabled(!processing);
        // Removed modelComboBox - using only Google Gemma 3 API
        translationMethodComboBox.setEnabled(!processing);

        if (!processing) {
            progressBar.setValue(0);
            progressBar.setString("Pronto para processar");
        }
    }

    private void updateProgress(int value, String message) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(value);
            progressBar.setString(message);
            logMessage(message);
        });
    }

    private void logMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = java.time.LocalTime.now().toString().substring(0, 8);
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
    
    /**
     * Chamado quando o método de tradução é alterado
     */
    private void onTranslationMethodChanged() {
        boolean isGoogleGemma = translationMethodComboBox.getSelectedIndex() == 1;
        
        // Mostrar/ocultar campo API Key
        googleApiKeyLabel.setVisible(isGoogleGemma);
        googleApiKeyField.setVisible(isGoogleGemma);
        
        // Revalidar o layout
        revalidate();
        repaint();
        
        if (isGoogleGemma) {
            logMessage("🤖 Google Gemma 3 27B selecionado - API Key já configurada");
        } else {
        }
    }

    /**
     * Redireciona System.out e System.err para a área de log da GUI
     */
    private void redirectConsoleToGUI() {
        PrintStream guiOut = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                // Não usado - implementação via write(byte[])
            }
            
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                String text = new String(b, off, len);
                SwingUtilities.invokeLater(() -> {
                    logArea.append(text);
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
            }
        });
        
        // Redirecionar System.out e System.err para GUI
        System.setOut(guiOut);
        System.setErr(guiOut);
        
        logMessage("🖥️ Console redirecionado para interface gráfica");
    }

    private void prepareOutputDirectory() throws Exception {
        File outputDir = new File("output");

        if (outputDir.exists()) {
            clearDirectory(outputDir);
        } else {
            if (!outputDir.mkdirs()) {
                throw new Exception("Falha ao criar diretório de saída: " + outputDir.getAbsolutePath());
            }
        }
        logMessage("📁 Diretório preparado para pipeline consolidado: " + outputDir.getAbsolutePath());
    }

    private void clearDirectory(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    clearDirectory(file);
                    if (!file.delete()) {
                        logMessage("⚠️ Não foi possível deletar diretório: " + file.getAbsolutePath());
                    }
                } else {
                    if (!file.delete()) {
                        logMessage("⚠️ Não foi possível deletar arquivo: " + file.getAbsolutePath());
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new DubAIGUI().setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Erro ao inicializar interface: " + e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}