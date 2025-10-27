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
 * Representa um item de vídeo na lista
 */
class VideoItem {
    private File videoFile;
    private boolean selected;
    
    public VideoItem(File videoFile) {
        this.videoFile = videoFile;
        this.selected = true; // Por padrão, selecionado
    }
    
    public File getVideoFile() { return videoFile; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    
    @Override
    public String toString() {
        String status = selected ? "✅" : "❌";
        String name = videoFile.getName();
        String size = formatFileSize(videoFile.length());
        return String.format("%s %s (%s)", status, name, size);
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        else if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        else return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

/**
 * Interface Gráfica Profissional para DubAI
 * Sistema de Dublagem Automática com IA
 */
public class DubAIGUI extends JFrame {

    private static final Logger logger = Logger.getLogger(DubAIGUI.class.getName());
    
    // Componentes da interface
    private JComboBox<String> translationMethodComboBox;
    private JTextField googleApiKeyField;
    private JLabel googleApiKeyLabel;
    private JTextField deepseekApiKeyField;
    private JLabel deepseekApiKeyLabel;
    private JCheckBox mixBackgroundAudioCheckBox;

    // Componentes TTS
    private JComboBox<String> ttsEngineComboBox;
    private JComboBox<String> kokoroVoiceComboBox;
    private JLabel kokoroVoiceLabel;
    private JCheckBox kokoroCombineVoicesCheckBox;
    private JList<String> kokoroVoiceList;
    private JScrollPane kokoroVoiceScrollPane;
    private JLabel kokoroCombineLabel;
    private JTextArea logArea;
    private JProgressBar progressBar;
    private JButton processButton;
    
    // Componentes para gerenciamento de vídeos
    private JList<VideoItem> videoList;
    private DefaultListModel<VideoItem> videoListModel;
    private JButton addFolderButton;
    private JButton clearListButton;
    private JButton selectAllButton;
    private JButton deselectAllButton;
    private JButton removeSelectedButton;
    private JLabel videoCountLabel;
    
    // Cores profissionais
    private static final Color PRIMARY_COLOR = new Color(33, 150, 243);
    private static final Color SECONDARY_COLOR = new Color(76, 175, 80);
    private static final Color BACKGROUND_COLOR = new Color(250, 250, 250);
    private static final Color PANEL_COLOR = Color.WHITE;

    public DubAIGUI() {
        initializeGUI();
        redirectConsoleToGUI(); // Redirecionar console para GUI
        checkAndLoadApiKey(); // Verificar e carregar API key criptografada
    }

    private void initializeGUI() {
        // Configurações da janela principal
        setTitle("DubAI - Sistema Profissional de Dublagem Automática");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 900);
        setLocationRelativeTo(null);
        setResizable(true);
        setMinimumSize(new Dimension(1200, 800));

        // Layout principal
        setLayout(new BorderLayout());
        getContentPane().setBackground(BACKGROUND_COLOR);

        // Painel principal
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        mainPanel.setBackground(BACKGROUND_COLOR);

        // Cabeçalho compacto
        JPanel headerPanel = createHeaderPanel();
        
        // SPLIT HORIZONTAL principal: Configurações | (Vídeos + Log)
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setResizeWeight(0.35); // 35% configurações, 65% direita
        mainSplitPane.setDividerSize(8);
        mainSplitPane.setBorder(null);
        mainSplitPane.setBackground(BACKGROUND_COLOR);

        // Painel esquerdo: configurações com scroll (ALTURA TOTAL)
        JPanel leftConfigPanel = createLeftConfigPanel();
        leftConfigPanel.setMinimumSize(new Dimension(400, 0));
        leftConfigPanel.setPreferredSize(new Dimension(480, 0));

        // Painel direito: SPLIT VERTICAL (Vídeos + Log)
        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setResizeWeight(0.55); // 55% vídeos, 45% log
        rightSplitPane.setDividerSize(8);
        rightSplitPane.setBorder(null);

        // Painel superior direito: gerenciamento de vídeos
        JPanel rightVideoPanel = createRightVideoPanel();
        rightVideoPanel.setMinimumSize(new Dimension(600, 300));

        // Painel inferior direito: log e progresso
        JPanel bottomPanel = createBottomPanel();
        bottomPanel.setMinimumSize(new Dimension(0, 200));
        bottomPanel.setPreferredSize(new Dimension(0, 280));

        rightSplitPane.setTopComponent(rightVideoPanel);
        rightSplitPane.setBottomComponent(bottomPanel);

        mainSplitPane.setLeftComponent(leftConfigPanel);
        mainSplitPane.setRightComponent(rightSplitPane);

        // Adicionar ao layout principal
        mainPanel.add(headerPanel, BorderLayout.NORTH);
        mainPanel.add(mainSplitPane, BorderLayout.CENTER);

        add(mainPanel);
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(PRIMARY_COLOR);
        headerPanel.setBorder(new EmptyBorder(15, 20, 15, 20));

        JLabel titleLabel = new JLabel("DubAI - Dublagem Automática com IA", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 20));
        titleLabel.setForeground(Color.WHITE);

        JLabel subtitleLabel = new JLabel("Interface Profissional - Seleção Individual de Vídeos", SwingConstants.CENTER);
        subtitleLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        subtitleLabel.setForeground(new Color(200, 230, 255));

        JPanel textPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        textPanel.setOpaque(false);
        textPanel.add(titleLabel);
        textPanel.add(subtitleLabel);

        headerPanel.add(textPanel, BorderLayout.CENTER);
        return headerPanel;
    }

    private JPanel createLeftConfigPanel() {
        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));
        leftPanel.setBackground(BACKGROUND_COLOR);
        leftPanel.setBorder(new TitledBorder("⚙️ Configurações de Processamento"));

        // Painel superior com configurações principais
        JPanel configContentPanel = new JPanel(new GridBagLayout());
        configContentPanel.setBackground(PANEL_COLOR);
        configContentPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 5, 8, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Seção: Modelo de IA
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JLabel modelSectionLabel = new JLabel("🤖 Modelo de Tradução:");
        modelSectionLabel.setFont(new Font("Arial", Font.BOLD, 13));
        configContentPanel.add(modelSectionLabel, gbc);

        gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        translationMethodComboBox = new JComboBox<>(new String[]{
            "DeepSeek V3.2-Exp (API - Pago $0.001/vídeo)",
            "Google Gemma 3 27B (API - Gratuito)",
            "Google Gemini 2.0 Flash (API - Gratuito)"
        });
        translationMethodComboBox.setSelectedIndex(0); // DeepSeek como padrão
        translationMethodComboBox.setPreferredSize(new Dimension(0, 28));
        translationMethodComboBox.addActionListener(e -> onTranslationMethodChanged());
        configContentPanel.add(translationMethodComboBox, gbc);

        // Seção: API Key
        gbc.gridy = 2; gbc.gridwidth = 2; gbc.weightx = 0.0;
        googleApiKeyLabel = new JLabel("🔑 Google AI API Key:");
        googleApiKeyLabel.setFont(new Font("Arial", Font.BOLD, 13));
        configContentPanel.add(googleApiKeyLabel, gbc);

        gbc.gridy = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JPanel apiKeyPanel = new JPanel(new BorderLayout(5, 0));
        apiKeyPanel.setBackground(PANEL_COLOR);
        googleApiKeyField = new JPasswordField();
        googleApiKeyField.setPreferredSize(new Dimension(0, 28));
        googleApiKeyField.setToolTipText("Insira sua Google AI API Key (será salva criptografada)");
        apiKeyPanel.add(googleApiKeyField, BorderLayout.CENTER);

        JButton saveKeyButton = new JButton("💾 Salvar");
        saveKeyButton.setPreferredSize(new Dimension(100, 28));
        saveKeyButton.setBackground(SECONDARY_COLOR);
        saveKeyButton.setForeground(Color.WHITE);
        saveKeyButton.setFocusPainted(false);
        saveKeyButton.addActionListener(e -> saveApiKey());
        apiKeyPanel.add(saveKeyButton, BorderLayout.EAST);

        configContentPanel.add(apiKeyPanel, gbc);

        // Seção: DeepSeek API Key
        gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 0.0;
        deepseekApiKeyLabel = new JLabel("🔑 DeepSeek API Key (opcional):");
        deepseekApiKeyLabel.setFont(new Font("Arial", Font.BOLD, 13));
        configContentPanel.add(deepseekApiKeyLabel, gbc);

        gbc.gridy = 5; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JPanel deepseekKeyPanel = new JPanel(new BorderLayout(5, 0));
        deepseekKeyPanel.setBackground(PANEL_COLOR);
        deepseekApiKeyField = new JPasswordField();
        deepseekApiKeyField.setPreferredSize(new Dimension(0, 28));
        deepseekApiKeyField.setToolTipText("Insira sua DeepSeek API Key (será salva criptografada)");
        deepseekKeyPanel.add(deepseekApiKeyField, BorderLayout.CENTER);

        JButton saveDeepSeekKeyButton = new JButton("💾 Salvar");
        saveDeepSeekKeyButton.setPreferredSize(new Dimension(100, 28));
        saveDeepSeekKeyButton.setBackground(SECONDARY_COLOR);
        saveDeepSeekKeyButton.setForeground(Color.WHITE);
        saveDeepSeekKeyButton.setFocusPainted(false);
        saveDeepSeekKeyButton.addActionListener(e -> saveApiKeys());
        deepseekKeyPanel.add(saveDeepSeekKeyButton, BorderLayout.EAST);

        configContentPanel.add(deepseekKeyPanel, gbc);

        // Seção: Text-to-Speech
        gbc.gridy = 6; gbc.gridwidth = 2; gbc.weightx = 0.0;
        JLabel ttsLabel = new JLabel("🗣️ Motor de Text-to-Speech:");
        ttsLabel.setFont(new Font("Arial", Font.BOLD, 13));
        configContentPanel.add(ttsLabel, gbc);

        gbc.gridy = 7; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        ttsEngineComboBox = new JComboBox<>(new String[]{
            "Piper TTS (CPU - Estável)",
            "Kokoro TTS (GPU - 6.7x Mais Rápido)"
        });
        ttsEngineComboBox.setSelectedIndex(0); // Piper como padrão
        ttsEngineComboBox.setPreferredSize(new Dimension(0, 28));
        ttsEngineComboBox.addActionListener(e -> onTTSEngineChanged());
        configContentPanel.add(ttsEngineComboBox, gbc);

        // Seção: Voz Kokoro (inicialmente oculta)
        gbc.gridy = 8; gbc.gridwidth = 2; gbc.weightx = 0.0;
        kokoroVoiceLabel = new JLabel("🎤 Voz Kokoro:");
        kokoroVoiceLabel.setFont(new Font("Arial", Font.BOLD, 13));
        kokoroVoiceLabel.setVisible(false);
        configContentPanel.add(kokoroVoiceLabel, gbc);

        gbc.gridy = 9; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        String[] kokoroVoices = new String[KokoroTTS.VozPTBR.values().length];
        for (int i = 0; i < KokoroTTS.VozPTBR.values().length; i++) {
            kokoroVoices[i] = KokoroTTS.VozPTBR.values()[i].getDisplayName();
        }
        kokoroVoiceComboBox = new JComboBox<>(kokoroVoices);
        kokoroVoiceComboBox.setSelectedIndex(7); // Heart como padrão (af_heart)
        kokoroVoiceComboBox.setPreferredSize(new Dimension(0, 28));
        kokoroVoiceComboBox.setVisible(false);
        kokoroVoiceComboBox.addActionListener(e -> onKokoroVoiceSelectionChanged());
        configContentPanel.add(kokoroVoiceComboBox, gbc);

        // Seção: Combinar múltiplas vozes (inicialmente oculta)
        gbc.gridy = 10; gbc.gridwidth = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST;
        kokoroCombineVoicesCheckBox = new JCheckBox("🎭 Combinar múltiplas vozes (experimental)", false);
        kokoroCombineVoicesCheckBox.setBackground(PANEL_COLOR);
        kokoroCombineVoicesCheckBox.setToolTipText("Combina características de 2-3 vozes diferentes para criar um timbre único");
        kokoroCombineVoicesCheckBox.setVisible(false);
        kokoroCombineVoicesCheckBox.addActionListener(e -> onCombineVoicesToggled());
        configContentPanel.add(kokoroCombineVoicesCheckBox, gbc);

        // Lista de vozes para combinar (inicialmente oculta)
        gbc.gridy = 11; gbc.gridwidth = 2; gbc.weightx = 0.0;
        kokoroCombineLabel = new JLabel("   Selecione 2-3 vozes (Ctrl+Click):");
        kokoroCombineLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        kokoroCombineLabel.setVisible(false);
        configContentPanel.add(kokoroCombineLabel, gbc);

        gbc.gridy = 12; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
        kokoroVoiceList = new JList<>(kokoroVoices);
        kokoroVoiceList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        kokoroVoiceList.setVisibleRowCount(10);
        kokoroVoiceScrollPane = new JScrollPane(kokoroVoiceList);
        kokoroVoiceScrollPane.setPreferredSize(new Dimension(0, 250));
        kokoroVoiceScrollPane.setMinimumSize(new Dimension(0, 200));
        kokoroVoiceScrollPane.setVisible(false);
        configContentPanel.add(kokoroVoiceScrollPane, gbc);

        // Seção: Opções Avançadas
        gbc.gridy = 13; gbc.gridwidth = 2; gbc.weightx = 0.0; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.NONE;
        JLabel optionsLabel = new JLabel("🎛️ Opções de Áudio:");
        optionsLabel.setFont(new Font("Arial", Font.BOLD, 13));
        configContentPanel.add(optionsLabel, gbc);

        gbc.gridy = 14; gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST;
        mixBackgroundAudioCheckBox = new JCheckBox("🎵 Mixar com áudio de fundo original", true);
        mixBackgroundAudioCheckBox.setBackground(PANEL_COLOR);
        mixBackgroundAudioCheckBox.setToolTipText("Combina voz dublada com música/sons de fundo do vídeo original");
        configContentPanel.add(mixBackgroundAudioCheckBox, gbc);

        // Adicionar barra de rolagem ao painel de configuração
        JScrollPane configScrollPane = new JScrollPane(configContentPanel);
        configScrollPane.setBorder(null);
        configScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        configScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        configScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        leftPanel.add(configScrollPane, BorderLayout.CENTER);

        // Painel inferior com botão de processamento
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 15));
        buttonPanel.setBackground(BACKGROUND_COLOR);
        
        processButton = new JButton("🎬 PROCESSAR VÍDEOS SELECIONADOS");
        processButton.setFont(new Font("Arial", Font.BOLD, 14));
        processButton.setBackground(SECONDARY_COLOR);
        processButton.setForeground(Color.WHITE);
        processButton.setPreferredSize(new Dimension(320, 45));
        processButton.addActionListener(this::processVideo);
        
        buttonPanel.add(processButton);
        leftPanel.add(buttonPanel, BorderLayout.SOUTH);

        return leftPanel;
    }

    private JPanel createRightVideoPanel() {
        JPanel rightPanel = new JPanel(new BorderLayout(8, 8));
        rightPanel.setBackground(BACKGROUND_COLOR);
        rightPanel.setBorder(new TitledBorder("🎬 Gerenciamento de Vídeos"));

        // Painel superior com controles de pasta e ações
        JPanel topControls = new JPanel(new BorderLayout(8, 8));
        topControls.setBackground(PANEL_COLOR);
        topControls.setBorder(new EmptyBorder(10, 15, 10, 15));

        // Sub-painel: Adicionar pastas
        JPanel folderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        folderPanel.setBackground(PANEL_COLOR);
        
        JLabel folderLabel = new JLabel("📁 Pastas:");
        folderLabel.setFont(new Font("Arial", Font.BOLD, 12));
        folderPanel.add(folderLabel);
        
        addFolderButton = new JButton("➕ Adicionar Pasta(s)");
        addFolderButton.setFont(new Font("Arial", Font.PLAIN, 11));
        addFolderButton.setBackground(new Color(63, 81, 181));
        addFolderButton.setForeground(Color.WHITE);
        addFolderButton.setToolTipText("Selecione uma ou múltiplas pastas (Ctrl+Click)");
        addFolderButton.addActionListener(e -> addVideoFolder());
        folderPanel.add(addFolderButton);
        
        clearListButton = new JButton("🧹 Limpar Lista");
        clearListButton.setFont(new Font("Arial", Font.PLAIN, 11));
        clearListButton.addActionListener(e -> clearVideoList());
        folderPanel.add(clearListButton);

        // Sub-painel: Controles de seleção
        JPanel selectionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        selectionPanel.setBackground(PANEL_COLOR);
        
        selectAllButton = new JButton("✅ Marcar Todos");
        selectAllButton.setFont(new Font("Arial", Font.PLAIN, 11));
        selectAllButton.addActionListener(e -> selectAllVideos(true));
        selectionPanel.add(selectAllButton);
        
        deselectAllButton = new JButton("❌ Desmarcar Todos");
        deselectAllButton.setFont(new Font("Arial", Font.PLAIN, 11));
        deselectAllButton.addActionListener(e -> selectAllVideos(false));
        selectionPanel.add(deselectAllButton);
        
        removeSelectedButton = new JButton("🗑️ Remover Selecionados");
        removeSelectedButton.setFont(new Font("Arial", Font.PLAIN, 11));
        removeSelectedButton.addActionListener(e -> removeSelectedFromList());
        selectionPanel.add(removeSelectedButton);

        topControls.add(folderPanel, BorderLayout.WEST);
        topControls.add(selectionPanel, BorderLayout.EAST);

        // Lista de vídeos com scroll
        videoListModel = new DefaultListModel<>();
        videoList = new JList<>(videoListModel);
        videoList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        videoList.setFont(new Font("Consolas", Font.PLAIN, 11));
        videoList.setBackground(Color.WHITE);
        videoList.setBorder(new EmptyBorder(5, 10, 5, 10));
        
        // Duplo clique para alternar seleção
        videoList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    toggleVideoSelection();
                }
            }
        });

        JScrollPane videoScrollPane = new JScrollPane(videoList);
        videoScrollPane.setPreferredSize(new Dimension(0, 400));
        videoScrollPane.setBorder(new TitledBorder("Lista de Vídeos - Duplo clique para alternar ✅/❌"));
        videoScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Painel inferior com status e informações
        JPanel bottomInfo = new JPanel(new BorderLayout(8, 5));
        bottomInfo.setBackground(PANEL_COLOR);
        bottomInfo.setBorder(new EmptyBorder(10, 15, 12, 15));
        
        videoCountLabel = new JLabel("Nenhum vídeo carregado", SwingConstants.CENTER);
        videoCountLabel.setFont(new Font("Arial", Font.BOLD, 12));
        videoCountLabel.setForeground(new Color(102, 102, 102));
        
        JLabel instructionsLabel = new JLabel(
            "<html><center><small>💡 <b>Instruções:</b> Duplo clique para ✅/❌ | " +
            "Ctrl+clique para seleção múltipla | Arraste divisória para redimensionar</small></center></html>"
        );
        instructionsLabel.setFont(new Font("Arial", Font.PLAIN, 9));
        instructionsLabel.setForeground(Color.GRAY);
        instructionsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        
        bottomInfo.add(videoCountLabel, BorderLayout.CENTER);
        bottomInfo.add(instructionsLabel, BorderLayout.SOUTH);

        // Adicionar tudo ao painel direito
        rightPanel.add(topControls, BorderLayout.NORTH);
        rightPanel.add(videoScrollPane, BorderLayout.CENTER);
        rightPanel.add(bottomInfo, BorderLayout.SOUTH);

        return rightPanel;
    }


    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBackground(BACKGROUND_COLOR);
        bottomPanel.setBorder(new EmptyBorder(5, 8, 8, 8));

        // Painel de progresso com informações
        JPanel progressPanel = new JPanel(new BorderLayout(10, 0));
        progressPanel.setBackground(BACKGROUND_COLOR);
        progressPanel.setBorder(new EmptyBorder(5, 0, 5, 0));
        
        // Barra de progresso
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Pronto para processar vídeos selecionados");
        progressBar.setPreferredSize(new Dimension(0, 24));
        progressBar.setFont(new Font("Arial", Font.PLAIN, 11));
        
        JLabel progressLabel = new JLabel("📊 Progresso:");
        progressLabel.setFont(new Font("Arial", Font.BOLD, 11));
        
        progressPanel.add(progressLabel, BorderLayout.WEST);
        progressPanel.add(progressBar, BorderLayout.CENTER);

        // Área de log mais compacta
        logArea = new JTextArea(12, 0);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 10));
        logArea.setBackground(new Color(252, 252, 252));
        logArea.setLineWrap(false);
        logArea.setWrapStyleWord(false);
        
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(new TitledBorder("📋 Log de Processamento"));
        logScrollPane.getVerticalScrollBar().setUnitIncrement(12);
        logScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        bottomPanel.add(progressPanel, BorderLayout.NORTH);
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
            // videoDirField removido - usando nova interface
            
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
            // videoDirField removido - usando nova interface
            
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
        // Configurar mixagem de áudio de fundo para TODOS os vídeos
        boolean mixBackground = mixBackgroundAudioCheckBox.isSelected();
        TTSUtils.setMixBackgroundAudio(mixBackground);
        logMessage(mixBackground ? "🎵 Mixagem de áudio de fundo ATIVADA para todos os vídeos" : "🎙️ Apenas voz dublada será usada (sem áudio de fundo)");

        // Configurar engine TTS
        int ttsEngineIndex = ttsEngineComboBox.getSelectedIndex();
        boolean useKokoro = (ttsEngineIndex == 1);
        TTSUtils.setUseKokoroTTS(useKokoro);

        if (useKokoro) {
            // Verificar se está em modo de combinação de vozes
            boolean combineMode = kokoroCombineVoicesCheckBox.isSelected();

            if (combineMode) {
                // Modo de combinação: usar vozes selecionadas na lista
                int[] selectedIndices = kokoroVoiceList.getSelectedIndices();

                if (selectedIndices.length < 2 || selectedIndices.length > 3) {
                    JOptionPane.showMessageDialog(this,
                        "⚠️ Selecione entre 2 e 3 vozes para combinar!\n\n" +
                        "Use Ctrl+Click para selecionar múltiplas vozes.",
                        "Seleção Inválida",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }

                // Configurar vozes combinadas
                KokoroTTS.VozPTBR[] selectedVoices = new KokoroTTS.VozPTBR[selectedIndices.length];
                for (int i = 0; i < selectedIndices.length; i++) {
                    selectedVoices[i] = KokoroTTS.VozPTBR.values()[selectedIndices[i]];
                }

                TTSUtils.setKokoroVoiceCombination(selectedVoices);
                logMessage("🎭 Vozes combinadas: " + String.join(" + ",
                    java.util.Arrays.stream(selectedVoices)
                        .map(KokoroTTS.VozPTBR::getDisplayName)
                        .toArray(String[]::new)));

            } else {
                // Modo de voz única
                int voiceIndex = kokoroVoiceComboBox.getSelectedIndex();
                KokoroTTS.VozPTBR selectedVoice = KokoroTTS.VozPTBR.values()[voiceIndex];
                TTSUtils.setKokoroVoice(selectedVoice);
            }

            // Verificar se Kokoro está disponível
            if (!KokoroTTS.isAvailable()) {
                int result = JOptionPane.showConfirmDialog(this,
                    "⚠️ Kokoro TTS não está rodando!\n\n" +
                    "Para iniciar o Kokoro, execute:\n" +
                    "cd kokoro-install && docker compose up -d\n\n" +
                    "Deseja continuar com Piper TTS?",
                    "Kokoro Indisponível",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

                if (result == JOptionPane.NO_OPTION) {
                    return;
                }
                // Fallback para Piper
                TTSUtils.setUseKokoroTTS(false);
                logMessage("⚠️ Usando Piper TTS como fallback");
            }
        }

        // Configurar método baseado na seleção do ComboBox
        int selectedIndex = translationMethodComboBox.getSelectedIndex();

        if (selectedIndex == 0) {
            // DeepSeek V3.2-Exp
            String deepseekKey = deepseekApiKeyField.getText().trim();
            if (deepseekKey.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Configure sua API Key do DeepSeek!\n\nObtenha em:\nhttps://platform.deepseek.com/api_keys",
                    "API Key Necessária",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            Translation.setDeepSeekApiKey(deepseekKey);
            Translation.setTranslationMethod(Translation.TranslationMethod.DEEPSEEK_V3);
            logMessage("🤖 Configurado para usar DeepSeek V3.2-Exp");

        } else if (selectedIndex == 1) {
            // Google Gemma 3
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

        } else if (selectedIndex == 2) {
            // Google Gemini
            String apiKey = googleApiKeyField.getText().trim();
            if (apiKey.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Configure sua API Key do Google AI!\n\nObtenha gratuitamente em:\nhttps://aistudio.google.com/",
                    "API Key Necessária",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            Translation.setGoogleApiKey(apiKey);
            Translation.setTranslationMethod(Translation.TranslationMethod.GOOGLE_GEMINI);
            logMessage("🤖 Configurado para usar Google Gemini 2.0 Flash");
        }
        
        // Validação da lista de vídeos selecionados
        List<File> selectedVideos = getSelectedVideos();
        String selectedModel = "Google Gemma 3 27B (API)";

        if (selectedVideos.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "Nenhum vídeo selecionado para processamento!\n\n" +
                "1. Clique em '➕ Adicionar Pasta' para carregar vídeos\n" +
                "2. Marque os vídeos que deseja processar (✅)\n" +
                "3. Clique em 'PROCESSAR VÍDEOS SELECIONADOS'", 
                "Nenhum Vídeo Selecionado", 
                JOptionPane.WARNING_MESSAGE);
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
                logMessage("🚀 INICIANDO PROCESSAMENTO DE VÍDEOS SELECIONADOS");
                logMessage(String.format("📹 Vídeos selecionados: %d", selectedVideos.size()));
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

                // Processar vídeos selecionados
                processSelectedVideos(selectedVideos, selectedModel, translationMethod);

            } catch (Exception ex) {
                logMessage("❌ ERRO: " + ex.getMessage());
                JOptionPane.showMessageDialog(this, "Erro durante processamento: " + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            } finally {
                SwingUtilities.invokeLater(() -> setProcessingState(false));
            }
        });
    }

    // MÉTODO ORIGINAL QUE FUNCIONAVA - ADAPTADO PARA LISTA DE VÍDEOS SELECIONADOS
    private void processSelectedVideos(List<File> selectedVideos, String model, String translationMethod) {
        try {
            logMessage(String.format("🎬 Processando %d vídeos selecionados...", selectedVideos.size()));
            
            int successCount = 0;
            int failureCount = 0;
            
            for (int i = 0; i < selectedVideos.size(); i++) {
                File videoFile = selectedVideos.get(i);
                String videosDir = videoFile.getParent(); // Diretório do vídeo atual
                String videoName = videoFile.getName();
                
                try {
                    logMessage(String.format("\n🎯 Processando vídeo %d/%d: %s", i + 1, selectedVideos.size(), videoName));
                    logMessage(String.format("🔍 DEBUG - Caminho completo: %s", videoFile.getAbsolutePath()));
                    logMessage(String.format("🔍 DEBUG - Existe: %s | Legível: %s | Tamanho: %d bytes", 
                        videoFile.exists(), videoFile.canRead(), videoFile.length()));
                    
                    // Usar o processamento consolidado original que funcionava
                    processVideoConsolidated(videoFile, videosDir, i + 1, selectedVideos.size(), model, translationMethod);
                    
                    // Mover vídeo final para pasta original com _dub
                    moveFinishedVideoToOriginalLocation(videoFile, videosDir);
                    
                    successCount++;
                    logMessage(String.format("✅ Vídeo %d/%d processado com sucesso: %s", i + 1, selectedVideos.size(), videoName));
                    
                } catch (Exception e) {
                    failureCount++;
                    logMessage(String.format("❌ Falha no vídeo %d/%d (%s): %s", i + 1, selectedVideos.size(), videoName, e.getMessage()));
                    
                    // Atualizar progresso mesmo com falha
                    int progress = (int) ((double) (i + 1) / selectedVideos.size() * 100);
                    updateProgress(progress, String.format("Erro no vídeo %s", videoName));
                }
            }
            
            // Relatório final
            logMessage(String.format("\n🎉 PROCESSAMENTO CONCLUÍDO!"));
            logMessage(String.format("✅ Sucessos: %d/%d vídeos", successCount, selectedVideos.size()));
            logMessage(String.format("❌ Falhas: %d/%d vídeos", failureCount, selectedVideos.size()));
            updateProgress(100, "Processamento de vídeos selecionados concluído!");
            
        } catch (Exception e) {
            logMessage("❌ ERRO GERAL: " + e.getMessage());
            throw new RuntimeException(e);
        }
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

        // Variáveis para rastreamento de arquivos temporários
        String audioPath = null;
        String vocalsPath = null;
        String vttPath = null;
        String translatedVttPath = null;
        String dubedAudioPath = null;

        try {
            String videoPath = videoFile.getAbsolutePath();

            // VERIFICAÇÃO: Vídeo tem áudio?
            if (!hasAudioStreamCheck(videoPath)) {
                logMessage("⏭️ PULANDO: Vídeo não contém áudio para dublar");
                updateProgress((currentVideo) * 100 / totalVideos, "Vídeo sem áudio - renomeando");

                // Mesmo sem áudio, renomear o arquivo com _dub
                renameVideoWithoutProcessing(videoFile, videosDir);

                updateProgress((currentVideo) * 100 / totalVideos, "Vídeo sem áudio - concluído");
                return; // Pula todo o processamento
            }

            prepareOutputDirectory();

            String outputDir = new File("output").getAbsolutePath();

            int baseProgress = (currentVideo - 1) * 100 / totalVideos;
            int progressRange = 100 / totalVideos;

            // 🧹 Limpeza inicial simples
            updateProgress(baseProgress + progressRange * 5 / 100, "Limpando caches...");
            try {
                org.ClearMemory.cleanAIModelCaches();
                System.gc();
                logMessage("✅ Caches limpos");
            } catch (Exception e) {
                logMessage("⚠️ Aviso limpeza inicial: " + e.getMessage());
            }

            updateProgress(baseProgress + progressRange * 10 / 100, "Extraindo áudio...");
            audioPath = extractAudio(videoPath, outputDir);

            updateProgress(baseProgress + progressRange * 25 / 100, "Separando vocal...");
            vocalsPath = separateAudio(audioPath, outputDir);

            // Limpeza leve após Spleeter (não agressiva)
            deleteFileIfExists(audioPath);
            audioPath = null;
            System.gc();
            logMessage("🧹 Áudio deletado após Spleeter");

            // 🧹 LIMPEZA ANTES DO WHISPER - só caches, não matar CUDA
            updateProgress(baseProgress + progressRange * 48 / 100, "Preparando GPU para WhisperX...");
            try {
                org.ClearMemory.cleanAIModelCaches(); // Limpa /tmp/cache
                System.gc();
                Thread.sleep(500);
                logMessage("🧹 Caches limpos antes do WhisperX");
            } catch (Exception e) {
                logMessage("⚠️ Aviso limpeza pré-Whisper: " + e.getMessage());
            }

            updateProgress(baseProgress + progressRange * 50 / 100, "Transcrevendo...");
            vttPath = transcribeAudio(vocalsPath, outputDir);

            // Limpeza LEVE após Whisper - SEM matar processos CUDA
            deleteFileIfExists(vocalsPath);
            vocalsPath = null;
            try {
                Thread.sleep(1000);
                org.ClearMemory.cleanAIModelCaches(); // Só limpa /tmp, não mata processos
                System.gc();
                logMessage("🧹 Vocals deletados, caches limpos após WhisperX");
            } catch (Exception e) {
                logMessage("⚠️ Aviso: falha na limpeza pós-Whisper: " + e.getMessage());
            }

            updateProgress(baseProgress + progressRange * 70 / 100, "Traduzindo...");
            translatedVttPath = translateVtt(vttPath, outputDir, model, translationMethod);

            // Limpar referências após tradução
            vttPath = null;
            System.gc();

            updateProgress(baseProgress + progressRange * 85 / 100, "Gerando TTS...");
            dubedAudioPath = generateTTS(translatedVttPath, outputDir);

            // Limpeza leve após TTS
            translatedVttPath = null;
            try {
                Thread.sleep(1000); // Aguardar TTS terminar
                org.ClearMemory.cleanAIModelCaches(); // Limpa caches de modelos AI
                logMessage("🧹 Caches limpos após TTS");
            } catch (Exception e) {
                logMessage("⚠️ Aviso: falha na limpeza pós-TTS: " + e.getMessage());
            }

            updateProgress(baseProgress + progressRange * 95 / 100, "Combinando vídeo...");
            String finalVideoPath = combineVideoWithAudio(videoPath, dubedAudioPath, outputDir);

            // 📋 Copiar legendas ANTES da limpeza (para que não sejam deletadas)
            try {
                String videoName = videoFile.getName();
                String baseNameDub = videoName;

                // Remover extensão corretamente
                if (videoName.endsWith(".mp4")) {
                    baseNameDub = videoName.replace(".mp4", "_dub");
                } else if (videoName.endsWith(".mkv")) {
                    baseNameDub = videoName.replace(".mkv", "_dub");
                } else if (videoName.endsWith(".avi")) {
                    baseNameDub = videoName.replace(".avi", "_dub");
                } else if (videoName.endsWith(".mov")) {
                    baseNameDub = videoName.replace(".mov", "_dub");
                }

                // Legenda PT-BR
                Path ptSubtitleSource = Paths.get(outputDir + "/transcription.vtt");
                Path ptSubtitleTarget = Paths.get(videosDir + "/" + baseNameDub + ".pt-BR.vtt");

                if (Files.exists(ptSubtitleSource)) {
                    Files.copy(ptSubtitleSource, ptSubtitleTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logMessage("📄 Legenda PT-BR copiada: " + ptSubtitleTarget.getFileName());
                } else {
                    logMessage("⚠️ Legenda PT-BR não encontrada: " + ptSubtitleSource);
                }

                // Legenda EN
                Path enSubtitleSource = Paths.get(outputDir + "/temp_vocals.vtt");
                Path enSubtitleTarget = Paths.get(videosDir + "/" + baseNameDub + ".en.vtt");

                if (Files.exists(enSubtitleSource)) {
                    Files.copy(enSubtitleSource, enSubtitleTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logMessage("📄 Legenda EN copiada: " + enSubtitleTarget.getFileName());
                } else {
                    logMessage("⚠️ Legenda EN não encontrada: " + enSubtitleSource);
                }
            } catch (Exception e) {
                logMessage("⚠️ Aviso: erro ao copiar legendas: " + e.getMessage());
            }

            // Limpar áudio dublado após combinação
            deleteFileIfExists(dubedAudioPath);
            dubedAudioPath = null;


        } finally {
            // Limpeza agressiva de arquivos temporários restantes
            try {
                logMessage("🧹 Limpeza agressiva de arquivos temporários...");
                deleteFileIfExists(audioPath);
                deleteFileIfExists(vocalsPath);
                deleteFileIfExists(vttPath);
                deleteFileIfExists(translatedVttPath);
                deleteFileIfExists(dubedAudioPath);

                // Limpar diretórios temporários
                String outputDir = new File("output").getAbsolutePath();
                cleanTemporaryFiles(outputDir);

                logMessage("✅ Arquivos temporários deletados");
            } catch (Exception e) {
                logMessage("⚠️ Aviso: falha na limpeza de arquivos: " + e.getMessage());
            }

            // Limpeza final MODERADA (sem matar CUDA desnecessariamente)
            try {
                logMessage("🧹 Limpeza final de caches e memória...");

                // 1. Limpa caches de modelos AI em /tmp
                org.ClearMemory.cleanAIModelCaches();
                Thread.sleep(500);

                // 2. Força GC múltiplas vezes
                for (int i = 0; i < 3; i++) {
                    System.gc();
                    Thread.sleep(300);
                }

                // 3. Mostrar estatísticas GPU após limpeza
                try {
                    String stats = org.ClearMemory.getGPUStats();
                    logMessage(stats);
                } catch (Exception ex) {
                    // Ignora erro de stats
                }

                logMessage("✅ Caches e memória limpos para próximo vídeo");
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
        // O vídeo final está em output/ com nome _dubbed.{extensão}
        String originalName = originalVideo.getName();

        // Detectar extensão original
        String extension = "";
        if (originalName.endsWith(".mp4")) {
            extension = ".mp4";
        } else if (originalName.endsWith(".mkv")) {
            extension = ".mkv";
        } else if (originalName.endsWith(".avi")) {
            extension = ".avi";
        } else if (originalName.endsWith(".mov")) {
            extension = ".mov";
        } else {
            // Fallback para .mp4
            extension = ".mp4";
        }

        String dubName = originalName.replace(extension, "_dub" + extension);

        String outputDir = new File("output").getAbsolutePath();
        Path sourcePath = Paths.get(outputDir + "/" + originalName.replace(extension, "_dubbed" + extension));
        Path targetPath = Paths.get(videosDir + "/" + dubName);

        if (Files.exists(sourcePath)) {
            // Mover o vídeo dublado para o local de destino
            Files.move(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logMessage("📁 Vídeo movido para: " + targetPath.toString());

            // Excluir o vídeo original após o sucesso da movimentação
            try {
                Files.delete(originalVideo.toPath());
                logMessage("🗑️ Vídeo original excluído: " + originalVideo.getAbsolutePath());
            } catch (IOException e) {
                logMessage("⚠️ Falha ao excluir o vídeo original (" + originalVideo.getName() + "): " + e.getMessage());
            }

        } else {
            logMessage("⚠️ Arquivo final não encontrado: " + sourcePath.toString());
        }
    }

    private void processVideoWithModel(String videoPath, String outputDir, String model, String translationMethod) {
        try {
            updateProgress(5, "Preparando processamento...");

            // 🧹 Limpeza simples antes de começar
            try {
                org.ClearMemory.cleanAIModelCaches();
                System.gc();
                logMessage("✅ Caches limpos");
            } catch (Exception e) {
                logMessage("⚠️ Aviso na limpeza inicial: " + e.getMessage());
            }

            updateProgress(10, "Extraindo áudio do vídeo...");
            String audioPath = extractAudio(videoPath, outputDir);

            updateProgress(20, "Separando vocal do áudio...");
            String vocalsPath = separateAudio(audioPath, outputDir);

            // 🧹 LIMPEZA ANTES DO WHISPER - só caches, não matar CUDA
            updateProgress(38, "Preparando GPU para WhisperX...");
            try {
                org.ClearMemory.cleanAIModelCaches(); // Limpa /tmp/cache
                System.gc();
                Thread.sleep(500);
                logMessage("✅ GPU preparada para WhisperX");
            } catch (Exception e) {
                logMessage("⚠️ Aviso limpeza pré-Whisper: " + e.getMessage());
            }

            updateProgress(40, "Transcrevendo áudio com WhisperX...");
            String vttPath = transcribeAudio(vocalsPath, outputDir);

            // 🧹 APÓS WHISPER - limpeza LEVE (sem matar CUDA)
            updateProgress(58, "Liberando caches após WhisperX...");
            try {
                Thread.sleep(1000);
                org.ClearMemory.cleanAIModelCaches(); // Só limpa /tmp
                System.gc();
                logMessage("✅ Caches limpos após WhisperX");
            } catch (Exception e) {
                logMessage("⚠️ Aviso limpeza pós-Whisper: " + e.getMessage());
            }

            updateProgress(60, "Traduzindo legendas com " + model + "...");
            String translatedVttPath = translateVtt(vttPath, outputDir, model, translationMethod);

            // Limpeza leve antes do TTS (só GC, não matar processos)
            updateProgress(83, "Preparando para TTS...");
            System.gc();

            updateProgress(85, "Gerando áudio em português com Piper TTS...");
            String dubedAudioPath = generateTTS(translatedVttPath, outputDir);

            // 🧹 APÓS TTS - limpar caches
            try {
                Thread.sleep(1000); // Aguardar TTS terminar
                org.ClearMemory.cleanAIModelCaches();
                logMessage("✅ Caches limpos após TTS");
            } catch (Exception e) {
                logMessage("⚠️ Aviso limpeza pós-TTS: " + e.getMessage());
            }
            
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
        
        // Limpeza leve pré-tradução (a pesada já foi feita após Whisper)
        try {
            System.gc();
            logMessage("✅ Memória preparada para tradução");
        } catch (Exception e) {
            logMessage("⚠️ Aviso: falha na preparação pré-tradução: " + e.getMessage());
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

            // 🔄 Copiar traduzido para transcription.vtt (formato esperado pelo pipeline final)
            String finalVttPath = outputDir + "/transcription.vtt";
            Files.copy(Paths.get(translatedVttPath), Paths.get(finalVttPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logMessage("📋 Legenda PT-BR copiada: transcription.vtt");

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
        String videoFileName = Paths.get(videoPath).getFileName().toString();
        String extension = "";

        // Detectar extensão
        if (videoFileName.endsWith(".mp4")) {
            extension = ".mp4";
        } else if (videoFileName.endsWith(".mkv")) {
            extension = ".mkv";
        } else if (videoFileName.endsWith(".avi")) {
            extension = ".avi";
        } else if (videoFileName.endsWith(".mov")) {
            extension = ".mov";
        } else {
            extension = ".mp4"; // Fallback
        }

        String finalVideoPath = outputDir + "/" +
            videoFileName.replace(extension, "_dubbed" + extension);
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
        addFolderButton.setEnabled(!processing);
        clearListButton.setEnabled(!processing);
        selectAllButton.setEnabled(!processing);
        deselectAllButton.setEnabled(!processing);
        removeSelectedButton.setEnabled(!processing);
        videoList.setEnabled(!processing);
        translationMethodComboBox.setEnabled(!processing);
        mixBackgroundAudioCheckBox.setEnabled(!processing);
        googleApiKeyField.setEnabled(!processing);

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
     * Chamado quando o engine TTS é alterado
     */
    private void onTTSEngineChanged() {
        int selectedIndex = ttsEngineComboBox.getSelectedIndex();
        boolean isKokoro = (selectedIndex == 1);

        // Mostrar/ocultar seleção de voz Kokoro
        kokoroVoiceLabel.setVisible(isKokoro);
        kokoroVoiceComboBox.setVisible(isKokoro);
        kokoroCombineVoicesCheckBox.setVisible(isKokoro);

        // Ocultar opções de combinação se não for Kokoro
        if (!isKokoro) {
            kokoroCombineLabel.setVisible(false);
            kokoroVoiceScrollPane.setVisible(false);
            kokoroCombineVoicesCheckBox.setSelected(false);
        }

        // Verificar se Kokoro está disponível
        if (isKokoro) {
            if (KokoroTTS.isAvailable()) {
                logMessage("✅ Kokoro TTS selecionado e disponível (http://localhost:8880)");
                logMessage("   💡 21 vozes disponíveis + opção de combinar vozes");
            } else {
                logMessage("⚠️ Kokoro TTS selecionado mas não está rodando!");
                logMessage("   Execute: cd kokoro-install && docker compose up -d");
            }
        } else {
            logMessage("💻 Piper TTS selecionado (CPU)");
        }

        // Revalidar o layout
        revalidate();
        repaint();
    }

    /**
     * Chamado quando o checkbox de combinar vozes é alterado
     */
    private void onCombineVoicesToggled() {
        boolean combine = kokoroCombineVoicesCheckBox.isSelected();

        // Alternar entre combobox e list
        kokoroVoiceComboBox.setVisible(!combine);
        kokoroCombineLabel.setVisible(combine);
        kokoroVoiceScrollPane.setVisible(combine);

        if (combine) {
            logMessage("🎭 Modo de combinação de vozes ativado - selecione 2-3 vozes");
        } else {
            logMessage("🎤 Modo de voz única ativado");
        }

        revalidate();
        repaint();
    }

    /**
     * Chamado quando a voz Kokoro é alterada no combobox
     */
    private void onKokoroVoiceSelectionChanged() {
        if (kokoroVoiceComboBox.isVisible()) {
            int index = kokoroVoiceComboBox.getSelectedIndex();
            String voiceName = KokoroTTS.VozPTBR.values()[index].getDisplayName();
            logMessage("🎤 Voz selecionada: " + voiceName);
        }
    }

    /**
     * Chamado quando o método de tradução é alterado
     */
    private void onTranslationMethodChanged() {
        int selectedIndex = translationMethodComboBox.getSelectedIndex();

        // 0 = DeepSeek, 1 = Gemma, 2 = Gemini
        boolean isDeepSeek = (selectedIndex == 0);
        boolean isGoogleAPI = (selectedIndex == 1 || selectedIndex == 2);

        // Mostrar/ocultar campos de API Key apropriados
        googleApiKeyLabel.setVisible(isGoogleAPI);
        googleApiKeyField.setVisible(isGoogleAPI);
        deepseekApiKeyLabel.setVisible(isDeepSeek);
        deepseekApiKeyField.setVisible(isDeepSeek);

        // Revalidar o layout
        revalidate();
        repaint();

        // Log da seleção
        if (selectedIndex == 0) {
            logMessage("🤖 DeepSeek V3.2-Exp selecionado - API Key já configurada");
        } else if (selectedIndex == 1) {
            logMessage("🤖 Google Gemma 3 27B selecionado - API Key já configurada");
        } else if (selectedIndex == 2) {
            logMessage("🤖 Google Gemini 2.0 Flash selecionado - API Key já configurada");
        }
    }

    // ========== MÉTODOS DE GERENCIAMENTO DE VÍDEOS ==========

    private void addVideoFolder() {
        JFileChooser folderChooser = new JFileChooser();
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        folderChooser.setMultiSelectionEnabled(true); // Habilita seleção múltipla
        folderChooser.setDialogTitle("Selecionar Pasta(s) com Vídeos - Use Ctrl+Click para múltiplas pastas");
        
        if (folderChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] selectedFolders = folderChooser.getSelectedFiles(); // Obtém todas as pastas selecionadas
            
            // Carrega vídeos de todas as pastas selecionadas
            for (File folder : selectedFolders) {
                loadVideosFromFolder(folder);
            }
            
            // Mostra quantidade de pastas adicionadas
            if (selectedFolders.length > 1) {
                String message = String.format("✅ %d pastas adicionadas com sucesso!", selectedFolders.length);
                JOptionPane.showMessageDialog(this, message, "Sucesso", JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }

    private void loadVideosFromFolder(File folder) {
        File[] videoFiles = folder.listFiles((dir, name) -> {
            String lowerName = name.toLowerCase();
            return (lowerName.endsWith(".mp4") || lowerName.endsWith(".avi") || 
                    lowerName.endsWith(".mov") || lowerName.endsWith(".mkv")) && 
                   !lowerName.contains("_dub"); // Excluir vídeos já dublados
        });

        if (videoFiles != null && videoFiles.length > 0) {
            // Ordenar arquivos numericamente primeiro, depois alfabeticamente
            java.util.Arrays.sort(videoFiles, (f1, f2) -> {
                String name1 = f1.getName();
                String name2 = f2.getName();
                
                // Extrair números no início do nome
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^(\\d+)");
                java.util.regex.Matcher matcher1 = pattern.matcher(name1);
                java.util.regex.Matcher matcher2 = pattern.matcher(name2);
                
                if (matcher1.find() && matcher2.find()) {
                    // Ambos têm números - comparar numericamente
                    int num1 = Integer.parseInt(matcher1.group(1));
                    int num2 = Integer.parseInt(matcher2.group(1));
                    if (num1 != num2) {
                        return Integer.compare(num1, num2);
                    }
                    // Se números iguais, comparar alfabeticamente
                    return name1.compareToIgnoreCase(name2);
                } else if (matcher1.find()) {
                    // Apenas o primeiro tem número - vem primeiro
                    return -1;
                } else if (matcher2.find()) {
                    // Apenas o segundo tem número - vem primeiro
                    return 1;
                } else {
                    // Nenhum tem número - ordenação alfabética
                    return name1.compareToIgnoreCase(name2);
                }
            });
            
            int newVideos = 0;
            for (File videoFile : videoFiles) {
                // Verificar se já não está na lista
                boolean alreadyExists = false;
                for (int i = 0; i < videoListModel.getSize(); i++) {
                    if (videoListModel.getElementAt(i).getVideoFile().equals(videoFile)) {
                        alreadyExists = true;
                        break;
                    }
                }
                
                if (!alreadyExists) {
                    videoListModel.addElement(new VideoItem(videoFile));
                    newVideos++;
                }
            }
            
            updateVideoCountLabel();
            logMessage(String.format("📁 Carregados %d novos vídeos de: %s (ordenados numericamente)", newVideos, folder.getName()));
        } else {
            JOptionPane.showMessageDialog(this, 
                "Nenhum vídeo encontrado na pasta selecionada!\n\nFormatos suportados: .mp4, .avi, .mov, .mkv\n(Vídeos com '_dub' no nome são ignorados)", 
                "Pasta sem Vídeos", 
                JOptionPane.WARNING_MESSAGE);
        }
    }

    private void toggleVideoSelection() {
        int[] selectedIndices = videoList.getSelectedIndices();
        if (selectedIndices.length > 0) {
            for (int index : selectedIndices) {
                VideoItem item = videoListModel.getElementAt(index);
                item.setSelected(!item.isSelected());
            }
            videoList.repaint();
            updateVideoCountLabel();
            logMessage(String.format("⚡ Alternada seleção de %d vídeo(s)", selectedIndices.length));
        }
    }

    private void removeSelectedFromList() {
        int[] selectedIndices = videoList.getSelectedIndices();
        if (selectedIndices.length > 0) {
            int result = JOptionPane.showConfirmDialog(this, 
                String.format("Remover %d vídeo(s) da lista?", selectedIndices.length), 
                "Confirmar Remoção", 
                JOptionPane.YES_NO_OPTION);
            
            if (result == JOptionPane.YES_OPTION) {
                // Remover em ordem decrescente para não alterar índices
                for (int i = selectedIndices.length - 1; i >= 0; i--) {
                    videoListModel.removeElementAt(selectedIndices[i]);
                }
                updateVideoCountLabel();
                logMessage(String.format("🗑️ Removidos %d vídeo(s) da lista", selectedIndices.length));
            }
        } else {
            JOptionPane.showMessageDialog(this, 
                "Selecione os vídeos que deseja remover da lista.\n\nDica: Use Ctrl+Clique para seleção múltipla", 
                "Nenhum Vídeo Selecionado", 
                JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void selectAllVideos(boolean selected) {
        for (int i = 0; i < videoListModel.getSize(); i++) {
            videoListModel.getElementAt(i).setSelected(selected);
        }
        videoList.repaint();
        updateVideoCountLabel();
        logMessage(selected ? "✅ Todos os vídeos marcados para processamento" : "❌ Todos os vídeos desmarcados");
    }

    private void clearVideoList() {
        if (videoListModel.getSize() > 0) {
            int result = JOptionPane.showConfirmDialog(this, 
                "Tem certeza que deseja limpar toda a lista de vídeos?", 
                "Confirmar Limpeza", 
                JOptionPane.YES_NO_OPTION);
            
            if (result == JOptionPane.YES_OPTION) {
                videoListModel.clear();
                updateVideoCountLabel();
                logMessage("🧹 Lista de vídeos limpa");
            }
        }
    }

    private void updateVideoCountLabel() {
        int total = videoListModel.getSize();
        int selected = 0;
        for (int i = 0; i < total; i++) {
            if (videoListModel.getElementAt(i).isSelected()) {
                selected++;
            }
        }
        
        if (total == 0) {
            videoCountLabel.setText("Nenhum vídeo carregado");
            videoCountLabel.setForeground(Color.GRAY);
        } else {
            videoCountLabel.setText(String.format("Total: %d vídeos | Marcados: %d | Processarão: %d", total, selected, selected));
            videoCountLabel.setForeground(selected > 0 ? new Color(76, 175, 80) : Color.GRAY);
        }
    }

    private List<File> getSelectedVideos() {
        List<File> selectedVideos = new ArrayList<>();
        for (int i = 0; i < videoListModel.getSize(); i++) {
            VideoItem item = videoListModel.getElementAt(i);
            if (item.isSelected()) {
                selectedVideos.add(item.getVideoFile());
            }
        }
        return selectedVideos;
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

    /**
     * Verifica se o vídeo tem stream de áudio usando ffprobe
     */
    private boolean hasAudioStreamCheck(String videoPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "quiet", "-show_streams", 
                "-select_streams", "a", videoPath);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                boolean hasAudio = line != null && !line.trim().isEmpty();
                process.waitFor();
                return hasAudio;
            }
        } catch (Exception e) {
            logMessage("⚠️ Erro verificando áudio, assumindo que tem: " + e.getMessage());
            return true; // Assume que tem áudio por segurança
        }
    }

    /**
     * Renomeia vídeo sem áudio adicionando _dub no nome
     */
    private void renameVideoWithoutProcessing(File originalVideo, String videosDir) {
        try {
            String originalName = originalVideo.getName();
            String dubName = originalName.replace(".mp4", "_dub.mp4")
                                        .replace(".avi", "_dub.avi")
                                        .replace(".mov", "_dub.mov")
                                        .replace(".mkv", "_dub.mkv");
            
            Path targetPath = Paths.get(videosDir + "/" + dubName);
            
            // Renomear o arquivo original
            Files.move(originalVideo.toPath(), targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            logMessage("📝 Vídeo renomeado (sem áudio): " + dubName);
            
        } catch (Exception e) {
            logMessage("❌ Erro ao renomear vídeo sem áudio: " + e.getMessage());
        }
    }

    /**
     * Verifica e carrega a API key criptografada ao iniciar
     */
    private void checkAndLoadApiKey() {
        try {
            if (ApiKeyManager.hasApiKeyFile()) {
                // Solicitar senha para descriptografar
                JPasswordField passwordField = new JPasswordField();
                int option = JOptionPane.showConfirmDialog(
                    this,
                    passwordField,
                    "🔐 Digite sua senha para carregar a API Key",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
                );

                if (option == JOptionPane.OK_OPTION) {
                    String password = new String(passwordField.getPassword());
                    String googleKey = ApiKeyManager.loadApiKey(password);
                    String deepseekKey = ApiKeyManager.loadDeepSeekApiKey(password);

                    boolean loaded = false;
                    if (googleKey != null && !googleKey.isEmpty()) {
                        googleApiKeyField.setText(googleKey);
                        Translation.setGoogleApiKey(googleKey);
                        loaded = true;
                    }
                    if (deepseekKey != null && !deepseekKey.isEmpty()) {
                        deepseekApiKeyField.setText(deepseekKey);
                        Translation.setDeepSeekApiKey(deepseekKey);
                        loaded = true;
                    }

                    if (loaded) {
                        logMessage("✅ API Keys carregadas com sucesso!");
                    } else {
                        logMessage("⚠️ Senha incorreta ou arquivo corrompido");
                    }
                }
            } else {
                logMessage("⚠️ Nenhuma API Key configurada. Por favor, insira e salve sua chave.");
            }
        } catch (Exception e) {
            logMessage("❌ Erro ao carregar API Key: " + e.getMessage());
        }
    }

    /**
     * Salva a API key de forma criptografada
     */
    private void saveApiKey() {
        try {
            String apiKey = googleApiKeyField.getText().trim();

            if (apiKey.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this,
                    "Por favor, insira uma API Key válida",
                    "Aviso",
                    JOptionPane.WARNING_MESSAGE
                );
                return;
            }

            // Solicitar senha para criptografar
            JPasswordField passwordField = new JPasswordField();
            JPasswordField confirmPasswordField = new JPasswordField();

            JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));
            panel.add(new JLabel("Senha:"));
            panel.add(passwordField);
            panel.add(new JLabel("Confirmar senha:"));
            panel.add(confirmPasswordField);

            int option = JOptionPane.showConfirmDialog(
                this,
                panel,
                "🔐 Crie uma senha para proteger sua API Key",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );

            if (option == JOptionPane.OK_OPTION) {
                String password = new String(passwordField.getPassword());
                String confirmPassword = new String(confirmPasswordField.getPassword());

                if (password.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "A senha não pode estar vazia",
                        "Erro",
                        JOptionPane.ERROR_MESSAGE
                    );
                    return;
                }

                if (!password.equals(confirmPassword)) {
                    JOptionPane.showMessageDialog(
                        this,
                        "As senhas não coincidem",
                        "Erro",
                        JOptionPane.ERROR_MESSAGE
                    );
                    return;
                }

                // Salvar criptografado
                ApiKeyManager.saveApiKey(password, apiKey);
                Translation.setGoogleApiKey(apiKey);

                logMessage("✅ API Key do Google salva com sucesso! (criptografada em .api_keys.enc)");
                JOptionPane.showMessageDialog(
                    this,
                    "API Key do Google salva e criptografada com sucesso!\nMantenha sua senha em local seguro.",
                    "Sucesso",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        } catch (Exception e) {
            logMessage("❌ Erro ao salvar API Key: " + e.getMessage());
            JOptionPane.showMessageDialog(
                this,
                "Erro ao salvar API Key: " + e.getMessage(),
                "Erro",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Salva ambas as API keys de forma criptografada
     */
    private void saveApiKeys() {
        try {
            String googleKey = googleApiKeyField.getText().trim();
            String deepseekKey = deepseekApiKeyField.getText().trim();

            if (googleKey.isEmpty() && deepseekKey.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this,
                    "Por favor, insira pelo menos uma API Key",
                    "Aviso",
                    JOptionPane.WARNING_MESSAGE
                );
                return;
            }

            // Solicitar senha para criptografar
            JPasswordField passwordField = new JPasswordField();
            JPasswordField confirmPasswordField = new JPasswordField();

            JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));
            panel.add(new JLabel("Senha:"));
            panel.add(passwordField);
            panel.add(new JLabel("Confirmar senha:"));
            panel.add(confirmPasswordField);

            int option = JOptionPane.showConfirmDialog(
                this,
                panel,
                "🔐 Crie uma senha para proteger suas API Keys",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );

            if (option == JOptionPane.OK_OPTION) {
                String password = new String(passwordField.getPassword());
                String confirmPassword = new String(confirmPasswordField.getPassword());

                if (password.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "A senha não pode estar vazia",
                        "Erro",
                        JOptionPane.ERROR_MESSAGE
                    );
                    return;
                }

                if (!password.equals(confirmPassword)) {
                    JOptionPane.showMessageDialog(
                        this,
                        "As senhas não coincidem",
                        "Erro",
                        JOptionPane.ERROR_MESSAGE
                    );
                    return;
                }

                // Salvar ambas criptografadas
                ApiKeyManager.saveApiKeys(password,
                    googleKey.isEmpty() ? null : googleKey,
                    deepseekKey.isEmpty() ? null : deepseekKey);

                if (!googleKey.isEmpty()) {
                    Translation.setGoogleApiKey(googleKey);
                }
                if (!deepseekKey.isEmpty()) {
                    Translation.setDeepSeekApiKey(deepseekKey);
                }

                logMessage("✅ API Keys salvas com sucesso! (criptografadas em .api_keys.enc)");
                JOptionPane.showMessageDialog(
                    this,
                    "API Keys salvas e criptografadas com sucesso!\nMantenha sua senha em local seguro.",
                    "Sucesso",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        } catch (Exception e) {
            logMessage("❌ Erro ao salvar API Keys: " + e.getMessage());
            JOptionPane.showMessageDialog(
                this,
                "Erro ao salvar API Keys: " + e.getMessage(),
                "Erro",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Deleta arquivo se existir, silenciosamente ignora erros
     */
    private void deleteFileIfExists(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return;
        }
        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                Files.delete(path);
                logMessage("🗑️ Deletado: " + path.getFileName());
            }
        } catch (Exception e) {
            // Silenciosamente ignora erros de deleção
        }
    }

    /**
     * Limpa arquivos temporários do diretório output mantendo apenas o vídeo final
     */
    private void cleanTemporaryFiles(String outputDir) {
        try {
            File dir = new File(outputDir);
            if (!dir.exists() || !dir.isDirectory()) {
                return;
            }

            File[] files = dir.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file.isFile()) {
                    String name = file.getName().toLowerCase();
                    // Deletar arquivos temporários mas manter vídeos finais _dubbed.{extensão}
                    if (name.equals("audio.wav") ||
                        name.equals("vocals.wav") ||
                        name.equals("accompaniment.wav") ||
                        name.equals("output.wav") ||
                        name.equals("transcription.vtt") ||
                        name.equals("transcription_translated.vtt") ||
                        name.equals("vocals.tsv") ||
                        name.equals("vocals.json") ||
                        name.equals("whisperx_temp.vtt") ||
                        name.startsWith("chunk_") ||
                        name.contains("_backup")) {
                        try {
                            Files.delete(file.toPath());
                        } catch (Exception e) {
                            // Ignora erros
                        }
                    }
                } else if (file.isDirectory() && file.getName().equals("separated")) {
                    // Deletar diretório separated recursivamente
                    try {
                        deleteDirectoryRecursively(file);
                    } catch (Exception e) {
                        // Ignora erros
                    }
                }
            }
        } catch (Exception e) {
            // Silenciosamente ignora erros
        }
    }

    /**
     * Deleta diretório recursivamente
     */
    private void deleteDirectoryRecursively(File dir) throws IOException {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectoryRecursively(file);
                }
            }
        }
        Files.delete(dir.toPath());
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