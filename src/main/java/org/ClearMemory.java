package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gestão otimizada de memória GPU para RTX 2080 Ti
 * Melhorias específicas:
 * - Thresholds otimizados para 11GB VRAM
 * - Limpeza agressiva para processos de ML/AI
 * - Monitoramento contínuo de memória
 * - Prevenção proativa de OOM
 */
public class ClearMemory {

    private static final Logger LOGGER = Logger.getLogger(ClearMemory.class.getName());

    // Configurações otimizadas para RTX 2080 Ti (11GB VRAM)
    private static final int RTX_2080_TI_MEMORY_MB = 11264; // 11GB em MB
    private static final int SAFE_MEMORY_THRESHOLD_MB = 8500; // 75% da VRAM
    private static final int CRITICAL_MEMORY_THRESHOLD_MB = 9500; // 85% da VRAM
    private static final int EMERGENCY_MEMORY_THRESHOLD_MB = 10500; // 93% da VRAM

    // Configurações de sistema
    private static final String NVIDIA_SMI_COMMAND = "nvidia-smi";
    private static final String KILL_COMMAND = "kill";
    private static final String PKILL_COMMAND = "pkill";

    // Timeouts otimizados
    private static final int NVIDIA_SMI_TIMEOUT_SEC = 5; // Reduzido para mais responsividade
    private static final int KILL_TIMEOUT_SEC = 3;
    private static final int WAIT_AFTER_KILL_SECONDS = 2; // Reduzido

    // Controle de concorrência
    private static final Semaphore gpuSemaphore = new Semaphore(1);
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1);

    // Cache de processos conhecidos
    private static final ConcurrentHashMap<Integer, ProcessInfo> knownProcesses = new ConcurrentHashMap<>();
    private static volatile long lastCleanupTime = 0;
    private static volatile boolean monitoringEnabled = false;

    // Padrões otimizados para parsing
    private static final Pattern PROCESS_LINE_PATTERN = Pattern.compile(
            "\\|\\s*\\d+\\s+N/A\\s+N/A\\s+(\\d+)\\s+\\w\\s+(.+?)\\s+(\\d+)MiB\\s*\\|");

    // Lista de processos alvo para limpeza agressiva
    private static final String[] TARGET_PROCESSES = {
            "python", "ollama", "whisperx", "pytorch", "tensorflow",
            "cuda", "nvidia", "tts", "spleeter", "ffmpeg"
    };

    // Configurações para Docker/Ollama
    private static final String OLLAMA_CONTAINER_NAME = "ollama-container";
    private static final String OLLAMA_DOCKER_IMAGE = "ollama/ollama";
    private static final int OLLAMA_DOCKER_PORT = 11434;

    // Lista de processos críticos para nunca matar
    private static final String[] PROTECTED_PROCESSES = {
            "nvidia-smi", "nvidia-persistenced", "Xorg", "gnome", "systemd",
            "idea", "intellij", "jetbrains", "java.*Main", "DubAIGUI"
    };

    private static record ProcessInfo(int pid, String name, int usedMemory, long timestamp) {
        boolean isStale() {
            return System.currentTimeMillis() - timestamp > 30000; // 30 segundos
        }

        boolean isTarget() {
            String lowerName = name.toLowerCase();
            for (String target : TARGET_PROCESSES) {
                if (lowerName.contains(target)) {
                    return true;
                }
            }
            return false;
        }

        boolean isProtected() {
            String lowerName = name.toLowerCase();
            for (String protected_ : PROTECTED_PROCESSES) {
                if (lowerName.contains(protected_)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Método principal otimizado com diferentes níveis de agressividade
     */
    public static void runClearNameThenThreshold(String nomeFor) throws IOException, InterruptedException {
        if (!gpuSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            LOGGER.fine("Limpeza GPU já em andamento, pulando...");
            return;
        }

        try {
            long currentTime = System.currentTimeMillis();

            // Evita limpezas muito frequentes (mínimo 5 segundos entre limpezas)
            if (currentTime - lastCleanupTime < 5000) {
                LOGGER.fine("Limpeza muito recente, pulando...");
                return;
            }

            GPUMemoryStatus status = getCurrentMemoryStatus();
            LOGGER.info(String.format("GPU Status: %d/%d MB (%.1f%%)",
                    status.usedMemory, status.totalMemory, status.usagePercentage));

            // Estratégia baseada no nível de uso de memória
            if (status.usedMemory >= EMERGENCY_MEMORY_THRESHOLD_MB) {
                runEmergencyCleanup(nomeFor);
            } else if (status.usedMemory >= CRITICAL_MEMORY_THRESHOLD_MB) {
                runCriticalCleanup(nomeFor);
            } else if (status.usedMemory >= SAFE_MEMORY_THRESHOLD_MB) {
                runStandardCleanup(nomeFor);
            } else if (nomeFor != null && !nomeFor.isBlank()) {
                runTargetedCleanup(nomeFor);
            } else {
                LOGGER.fine("Memória GPU em nível seguro, limpeza desnecessária");
            }

            lastCleanupTime = currentTime;

        } finally {
            gpuSemaphore.release();
        }
    }

    /**
     * Limpeza de emergência - máxima agressividade
     */
    private static void runEmergencyCleanup(String nomeFor) throws IOException, InterruptedException {
        LOGGER.warning("🚨 EMERGÊNCIA: Memória GPU crítica! Executando limpeza agressiva...");

        // 1. Mata processos específicos primeiro
        if (nomeFor != null && !nomeFor.isBlank()) {
            killProcessesByName(nomeFor, true);
        }

        // 2. Mata todos os processos alvo
        for (String target : TARGET_PROCESSES) {
            killProcessesByName(target, true);
        }

        // 3. Mata processos por threshold muito baixo
        killProcessesByThreshold(1000, true); // Qualquer processo > 1GB

        // 4. Força garbage collection e limpeza CUDA
        forceSystemCleanup();

        LOGGER.warning("🧹 Limpeza de emergência concluída");
    }

    /**
     * Limpeza crítica - alta agressividade
     */
    private static void runCriticalCleanup(String nomeFor) throws IOException, InterruptedException {
        LOGGER.warning("⚠️ CRÍTICO: Memória GPU alta! Executando limpeza crítica...");

        if (nomeFor != null && !nomeFor.isBlank()) {
            killProcessesByName(nomeFor, true);
        }

        // NOVO: Limpeza específica do Ollama Docker se detectado
        if (isOllamaRunningInDocker()) {
            LOGGER.info("🐳 Ollama Docker detectado, aplicando limpeza específica...");
            forceOllamaMemoryCleanup();
        }

        // Mata processos principais de ML/AI
        String[] criticalTargets = {"python", "ollama", "whisperx", "pytorch"};
        for (String target : criticalTargets) {
            killProcessesByName(target, false);
        }

        killProcessesByThreshold(SAFE_MEMORY_THRESHOLD_MB, false);
        forceSystemCleanup();

        LOGGER.info("🧹 Limpeza crítica concluída");
    }

    /**
     * Limpeza padrão - agressividade moderada
     */
    private static void runStandardCleanup(String nomeFor) throws IOException, InterruptedException {
        LOGGER.info("🔄 Executando limpeza padrão...");

        if (nomeFor != null && !nomeFor.isBlank()) {
            killProcessesByName(nomeFor, false);
        }

        killProcessesByThreshold(CRITICAL_MEMORY_THRESHOLD_MB, false);
        cleanupStaleProcesses();

        LOGGER.info("✅ Limpeza padrão concluída");
    }

    /**
     * Limpeza direcionada - baixa agressividade
     */
    private static void runTargetedCleanup(String nomeFor) throws IOException, InterruptedException {
        LOGGER.info("🎯 Executando limpeza direcionada para: " + nomeFor);

        if (nomeFor.equalsIgnoreCase("cleanup") || nomeFor.equalsIgnoreCase("warmup")) {
            cleanupStaleProcesses();
            
            // NOVO: Limpeza específica de cache do Ollama se detectado
            if (isOllamaRunningInDocker()) {
                clearOllamaModelCache();
            }
            
            forceSystemCleanup();
        } else if (nomeFor.equalsIgnoreCase("ollama")) {
            // Limpeza gentil específica para Ollama
            if (isOllamaRunningInDocker()) {
                clearOllamaModelCache();
                Thread.sleep(1000);
                cleanOllamaMemoryInsideContainer();
            } else {
                killProcessesByName(nomeFor, false);
            }
        } else {
            killProcessesByName(nomeFor, false);
            cleanupStaleProcesses();
        }

        LOGGER.info("✅ Limpeza direcionada concluída");
    }

    /**
     * Mata processos por nome com opção de força
     */
    private static void killProcessesByName(String namePattern, boolean forceKill) throws IOException, InterruptedException {
        // Primeiro tenta parar serviços Docker/systemd se aplicável
        if (namePattern.equalsIgnoreCase("ollama")) {
            if (isOllamaRunningInDocker()) {
                // Para Docker, usa limpeza de memória sem reiniciar completamente
                cleanOllamaMemoryInsideContainer();
            } else {
                tryStopSystemdService("ollama");
            }
        }
        
        List<ProcessInfo> processes = getCurrentProcesses();
        List<ProcessInfo> toKill = new ArrayList<>();

        for (ProcessInfo process : processes) {
            if (process.name().toLowerCase().contains(namePattern.toLowerCase()) && !process.isProtected()) {
                toKill.add(process);
            }
        }

        if (!toKill.isEmpty()) {
            LOGGER.info(String.format("Matando %d processos com nome '%s'", toKill.size(), namePattern));
            killProcessList(toKill, forceKill);
        }
    }

    /**
     * Mata processos por threshold de memória
     */
    private static void killProcessesByThreshold(int thresholdMB, boolean forceKill) throws IOException, InterruptedException {
        List<ProcessInfo> processes = getCurrentProcesses();
        List<ProcessInfo> toKill = new ArrayList<>();

        for (ProcessInfo process : processes) {
            if (process.usedMemory() > thresholdMB && !process.isProtected() && process.isTarget()) {
                toKill.add(process);
            }
        }

        if (!toKill.isEmpty()) {
            LOGGER.info(String.format("Matando %d processos acima de %d MB", toKill.size(), thresholdMB));
            killProcessList(toKill, forceKill);
        }
    }

    /**
     * Limpa processos obsoletos do cache
     */
    private static void cleanupStaleProcesses() {
        knownProcesses.entrySet().removeIf(entry -> entry.getValue().isStale());
    }

    /**
     * Força limpeza do sistema
     */
    private static void forceSystemCleanup() {
        try {
            // Força garbage collection múltiplas vezes
            for (int i = 0; i < 3; i++) {
                System.gc();
                // System.runFinalization(); // Deprecado no Java moderno
                Thread.sleep(100);
            }

            // Tenta limpar cache CUDA se disponível
            ProcessBuilder cudaReset = new ProcessBuilder("nvidia-smi", "--gpu-reset");
            Process process = cudaReset.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

        } catch (Exception e) {
            LOGGER.fine("Erro na limpeza do sistema: " + e.getMessage());
        }
    }

    /**
     * Obtém status atual da memória GPU
     */
    private static GPUMemoryStatus getCurrentMemoryStatus() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(NVIDIA_SMI_COMMAND,
                "--query-gpu=memory.used,memory.total",
                "--format=csv,noheader,nounits");

        Process process = pb.start();
        if (!process.waitFor(NVIDIA_SMI_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout obtendo status GPU");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split(",");
                if (parts.length >= 2) {
                    int used = Integer.parseInt(parts[0].trim());
                    int total = Integer.parseInt(parts[1].trim());
                    double percentage = (double) used / total * 100;
                    return new GPUMemoryStatus(used, total, percentage);
                }
            }
        }

        throw new IOException("Erro parseando status GPU");
    }

    private static record GPUMemoryStatus(int usedMemory, int totalMemory, double usagePercentage) {}

    /**
     * Obtém lista atual de processos GPU
     */
    private static List<ProcessInfo> getCurrentProcesses() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(NVIDIA_SMI_COMMAND).start();
        if (!process.waitFor(NVIDIA_SMI_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout obtendo processos GPU");
        }

        List<ProcessInfo> processes = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = PROCESS_LINE_PATTERN.matcher(line);
                if (matcher.find()) {
                    int pid = Integer.parseInt(matcher.group(1));
                    String name = matcher.group(2).trim();
                    int memory = Integer.parseInt(matcher.group(3));

                    ProcessInfo processInfo = new ProcessInfo(pid, name, memory, currentTime);
                    processes.add(processInfo);
                    knownProcesses.put(pid, processInfo);
                }
            }
        }

        return processes;
    }

    /**
     * Mata lista de processos de forma otimizada
     */
    private static void killProcessList(List<ProcessInfo> processes, boolean forceKill) {
        if (processes.isEmpty()) return;

        // Agrupa processos por nome para otimizar pkill
        var processesByName = processes.stream()
                .collect(java.util.stream.Collectors.groupingBy(ProcessInfo::name));

        for (var entry : processesByName.entrySet()) {
            String processName = entry.getKey();
            List<ProcessInfo> sameNameProcesses = entry.getValue();

            try {
                // Tenta pkill primeiro (mais eficiente)
                if (tryPkill(processName, forceKill)) {
                    LOGGER.info(String.format("Pkill bem-sucedido para %s (%d processos)",
                            processName, sameNameProcesses.size()));
                    continue;
                }

                // Fallback para kill individual
                for (ProcessInfo proc : sameNameProcesses) {
                    try {
                        killSingleProcess(proc, forceKill);
                    } catch (Exception e) {
                        LOGGER.warning(String.format("Falha matando PID %d: %s", proc.pid(), e.getMessage()));
                    }
                }

            } catch (Exception e) {
                LOGGER.warning(String.format("Erro processando %s: %s", processName, e.getMessage()));
            }
        }

        // Aguarda término dos processos
        try {
            Thread.sleep(WAIT_AFTER_KILL_SECONDS * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tenta usar pkill para maior eficiência
     */
    private static boolean tryPkill(String processName, boolean forceKill) {
        try {
            String signal = forceKill ? "-9" : "-15";
            ProcessBuilder pb = new ProcessBuilder(PKILL_COMMAND, signal, "-f", processName);
            Process process = pb.start();

            return process.waitFor(KILL_TIMEOUT_SEC, TimeUnit.SECONDS) &&
                    (process.exitValue() == 0 || process.exitValue() == 1);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Mata processo individual
     */
    private static void killSingleProcess(ProcessInfo proc, boolean forceKill) throws IOException, InterruptedException {
        String signal = forceKill ? "-9" : "-15";
        LOGGER.info(String.format("Matando PID %d (%s) - %d MB", proc.pid(), proc.name(), proc.usedMemory()));

        ProcessBuilder pb = new ProcessBuilder(KILL_COMMAND, signal, String.valueOf(proc.pid()));
        Process process = pb.start();

        if (!process.waitFor(KILL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout matando processo " + proc.pid());
        }

        if (process.exitValue() != 0 && !forceKill) {
            // Tenta kill -9 se SIGTERM falhou
            killSingleProcess(proc, true);
        }
    }

    /**
     * Inicia monitoramento contínuo de GPU
     */
    public static void startContinuousMonitoring() {
        if (monitoringEnabled) return;

        monitoringEnabled = true;
        monitor.scheduleAtFixedRate(() -> {
            try {
                GPUMemoryStatus status = getCurrentMemoryStatus();

                if (status.usedMemory >= EMERGENCY_MEMORY_THRESHOLD_MB) {
                    LOGGER.warning("🚨 Memória GPU em emergência, executando limpeza automática!");
                    runClearNameThenThreshold(null);
                }

            } catch (Exception e) {
                LOGGER.fine("Erro no monitoramento automático: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS); // Monitora a cada 30 segundos

        LOGGER.info("🔍 Monitoramento contínuo de GPU iniciado");
    }

    /**
     * Para monitoramento contínuo
     */
    public static void stopContinuousMonitoring() {
        monitoringEnabled = false;
        monitor.shutdown();
        LOGGER.info("🛑 Monitoramento contínuo de GPU parado");
    }

    /**
     * Versão assíncrona para não bloquear
     */
    public static CompletableFuture<Void> runClearNameThenThresholdAsync(String nomeFor) {
        return CompletableFuture.runAsync(() -> {
            try {
                runClearNameThenThreshold(nomeFor);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erro na limpeza assíncrona GPU", e);
            }
        }, executor);
    }

    /**
     * Obtém estatísticas de uso da GPU
     */
    public static String getGPUStats() {
        try {
            GPUMemoryStatus status = getCurrentMemoryStatus();
            List<ProcessInfo> processes = getCurrentProcesses();

            return String.format("""
                    📊 RTX 2080 Ti Status:
                    💾 Memória: %d/%d MB (%.1f%%)
                    🔧 Processos GPU: %d
                    ⚠️ Nível: %s
                    🕒 Última limpeza: %d segundos atrás
                    """,
                    status.usedMemory, status.totalMemory, status.usagePercentage,
                    processes.size(),
                    getMemoryLevelDescription(status.usedMemory),
                    (System.currentTimeMillis() - lastCleanupTime) / 1000);

        } catch (Exception e) {
            return "❌ Erro obtendo stats GPU: " + e.getMessage();
        }
    }

    private static String getMemoryLevelDescription(int usedMemory) {
        if (usedMemory >= EMERGENCY_MEMORY_THRESHOLD_MB) return "🚨 EMERGÊNCIA";
        if (usedMemory >= CRITICAL_MEMORY_THRESHOLD_MB) return "⚠️ CRÍTICO";
        if (usedMemory >= SAFE_MEMORY_THRESHOLD_MB) return "🟡 ALTO";
        return "🟢 SEGURO";
    }

    /**
     * Tenta parar serviço systemd antes de matar processo
     */
    private static void tryStopSystemdService(String serviceName) {
        try {
            LOGGER.info(String.format("🔄 Parando serviço systemd: %s", serviceName));
            ProcessBuilder pb = new ProcessBuilder("sudo", "systemctl", "stop", serviceName + ".service");
            Process process = pb.start();
            
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                if (process.exitValue() == 0) {
                    LOGGER.info(String.format("✅ Serviço %s parado com sucesso", serviceName));
                    // Aguarda um pouco para garantir que o processo foi realmente parado
                    Thread.sleep(2000);
                } else {
                    LOGGER.warning(String.format("⚠️ Falha ao parar serviço %s (exit code: %d)", serviceName, process.exitValue()));
                }
            } else {
                LOGGER.warning(String.format("⏱️ Timeout parando serviço %s", serviceName));
                process.destroyForcibly();
            }
        } catch (Exception e) {
            LOGGER.warning(String.format("❌ Erro parando serviço %s: %s", serviceName, e.getMessage()));
        }
    }

    /**
     * FORÇA LIMPEZA CUDA REAL - mata processos Python/PyTorch que seguram VRAM
     */
    public static void forceCudaCleanup() {
        try {
            LOGGER.info("🧹 FORÇA LIMPEZA CUDA - matando processos Python/PyTorch...");
            
            // 1. Matar todos os processos whisperx que consomem CUDA
            ProcessBuilder killWhisper = new ProcessBuilder("pkill", "-f", "whisperx");
            killWhisper.start().waitFor(3, TimeUnit.SECONDS);
            
            // 2. Matar processos Python que usam CUDA/PyTorch  
            ProcessBuilder killPython = new ProcessBuilder("pkill", "-f", "python.*torch");
            killPython.start().waitFor(3, TimeUnit.SECONDS);
            
            // 3. Limpar cache CUDA via nvidia-ml
            ProcessBuilder nvidiaSmi = new ProcessBuilder("nvidia-smi", "--gpu-reset", "-i", "0");
            Process resetProcess = nvidiaSmi.start();
            if (!resetProcess.waitFor(5, TimeUnit.SECONDS)) {
                resetProcess.destroyForcibly();
                LOGGER.warning("⏰ Timeout no nvidia-smi reset");
            }
            
            // 4. Forçar coleta de lixo do JVM 
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(200);
            }
            
            Thread.sleep(2000); // Aguardar limpeza
            
            GPUMemoryStatus status = getCurrentMemoryStatus();
            int freeMemory = status.totalMemory - status.usedMemory;
            LOGGER.info(String.format("🧹 CUDA limpo: %d MB livres de %d MB", 
                freeMemory, status.totalMemory));
            
        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro na limpeza CUDA forçada: " + e.getMessage());
        }
    }

    /**
     * INICIA container Ollama Docker SOMENTE para tradução
     */
    public static void startOllamaForTranslation() {
        try {
            // Verificar se já está rodando
            ProcessBuilder checkOllama = new ProcessBuilder("docker", "ps", "--filter", "name=ollama-container", "--format", "{{.Names}}");
            Process checkProcess = checkOllama.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && line.trim().equals("ollama-container")) {
                    LOGGER.info("✅ Container Ollama já está rodando");
                    return;
                }
            }
            
            LOGGER.info("🐳 Iniciando Ollama Docker para tradução...");
            ProcessBuilder startOllama = new ProcessBuilder("docker", "start", "ollama-container");
            Process startProcess = startOllama.start();
            if (startProcess.waitFor(10, TimeUnit.SECONDS)) {
                LOGGER.info("✅ Container Ollama iniciado para tradução");
                
                // Aguardar API estar pronta (máximo 30s)
                boolean apiReady = false;
                for (int i = 0; i < 30; i++) {
                    try {
                        ProcessBuilder testApi = new ProcessBuilder("curl", "-s", "--max-time", "2", "http://localhost:11434/api/tags");
                        Process testProcess = testApi.start();
                        if (testProcess.waitFor(3, TimeUnit.SECONDS) && testProcess.exitValue() == 0) {
                            apiReady = true;
                            LOGGER.info("✅ API Ollama pronta após " + (i + 1) + " segundos");
                            break;
                        }
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        // Continuar tentando
                    }
                }
                
                if (!apiReady) {
                    LOGGER.warning("⚠️ API Ollama não ficou pronta em 30 segundos");
                }
            } else {
                startProcess.destroyForcibly();
                LOGGER.warning("⏰ Timeout ao iniciar Ollama Docker");
            }
        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro ao iniciar Ollama Docker: " + e.getMessage());
        }
    }

    /**
     * PARA container Ollama Docker para liberar VRAM
     */
    public static void stopOllamaAfterTranslation() {
        try {
            LOGGER.info("🐳 Parando Ollama Docker para liberar VRAM...");
            
            ProcessBuilder stopOllama = new ProcessBuilder("docker", "stop", "ollama-container");
            Process stopProcess = stopOllama.start();
            if (stopProcess.waitFor(10, TimeUnit.SECONDS)) {
                LOGGER.info("🧹 Container Ollama parado - VRAM liberada");
                
                // LIMPEZA AUTOMÁTICA /tmp para Piper TTS
                cleanTmpForPiper();
                
                Thread.sleep(2000); // Aguardar liberação completa
            } else {
                stopProcess.destroyForcibly();
                LOGGER.warning("⏰ Timeout ao parar Ollama Docker");
            }
        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro ao parar Ollama Docker: " + e.getMessage());
        }
    }

    /**
     * Limpa arquivos temporários Docker que impedem Piper TTS
     */
    private static void cleanTmpForPiper() {
        try {
            LOGGER.info("🧹 Limpando /tmp para garantir funcionamento do Piper...");
            
            // Remover arquivos runc-process que lotam /tmp
            ProcessBuilder cleanRunc = new ProcessBuilder("sudo", "rm", "-rf", "/tmp/runc-process*");
            Process cleanProcess = cleanRunc.start();
            cleanProcess.waitFor(5, TimeUnit.SECONDS);
            
            // Remover outros arquivos Docker temporários
            ProcessBuilder cleanDocker = new ProcessBuilder("sudo", "find", "/tmp", "-name", "*docker*", "-type", "f", "-delete");
            Process dockerProcess = cleanDocker.start();
            dockerProcess.waitFor(5, TimeUnit.SECONDS);
            
            LOGGER.info("✅ Limpeza /tmp concluída - Piper TTS deve funcionar");
            
        } catch (Exception e) {
            LOGGER.warning("⚠️ Erro na limpeza /tmp: " + e.getMessage());
        }
    }

    /**
     * Gerencia container Docker do Ollama para liberação de memória GPU
     */
    public static void restartOllamaService() {
        try {
            // Primeiro tenta detectar se é Docker ou systemd
            if (isOllamaRunningInDocker()) {
                restartOllamaDocker();
            } else {
                restartOllamaSystemd();
            }
        } catch (Exception e) {
            LOGGER.warning(String.format("❌ Erro gerenciando Ollama: %s", e.getMessage()));
        }
    }

    /**
     * Verifica se Ollama está rodando em Docker
     */
    private static boolean isOllamaRunningInDocker() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--format", "{{.Names}}", "--filter", "name=" + OLLAMA_CONTAINER_NAME);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                return line != null && line.trim().equals(OLLAMA_CONTAINER_NAME);
            }
        } catch (Exception e) {
            LOGGER.fine("Erro verificando Docker: " + e.getMessage());
            return false;
        }
    }

    /**
     * Força limpeza de memória GPU do container Ollama
     */
    public static void forceOllamaMemoryCleanup() {
        try {
            LOGGER.info("🧹 Forçando limpeza de memória GPU do Ollama...");
            
            if (isOllamaRunningInDocker()) {
                // Método 1: Executa comando dentro do container para liberar memória
                cleanOllamaMemoryInsideContainer();
                
                // Método 2: Se não funcionar, reinicia o container
                Thread.sleep(2000);
                GPUMemoryStatus status = getCurrentMemoryStatus();
                if (status.usedMemory > CRITICAL_MEMORY_THRESHOLD_MB) {
                    LOGGER.warning("🔄 Memoria ainda alta, reiniciando container Ollama...");
                    restartOllamaDocker();
                }
            } else {
                restartOllamaSystemd();
            }
        } catch (Exception e) {
            LOGGER.severe("❌ Erro na limpeza forçada do Ollama: " + e.getMessage());
        }
    }

    /**
     * Limpa memória GPU dentro do container Ollama
     */
    private static void cleanOllamaMemoryInsideContainer() throws IOException, InterruptedException {
        LOGGER.info("🧠 Limpando memória GPU dentro do container Ollama...");
        
        // Comando para liberar cache de modelos no Ollama
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "exec", OLLAMA_CONTAINER_NAME, 
                "sh", "-c", "pkill -f ollama || true"
        );
        
        Process process = pb.start();
        if (process.waitFor(10, TimeUnit.SECONDS)) {
            if (process.exitValue() == 0) {
                LOGGER.info("✅ Processos Ollama limpos no container");
            } else {
                LOGGER.warning("⚠️ Falha parcial na limpeza de processos Ollama");
            }
        } else {
            LOGGER.warning("⏱️ Timeout na limpeza de processos Ollama");
            process.destroyForcibly();
        }
        
        // Força garbage collection dentro do container se possível
        try {
            pb = new ProcessBuilder(
                    "docker", "exec", OLLAMA_CONTAINER_NAME,
                    "sh", "-c", "echo 'Liberando recursos...' && sync"
            );
            pb.start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.fine("Info: " + e.getMessage());
        }
    }

    /**
     * Reinicia container Docker do Ollama
     */
    private static void restartOllamaDocker() throws IOException, InterruptedException {
        LOGGER.info("🔄 Reiniciando container Docker do Ollama...");
        
        // Para o container
        ProcessBuilder pb = new ProcessBuilder("docker", "stop", OLLAMA_CONTAINER_NAME);
        Process process = pb.start();
        
        if (process.waitFor(30, TimeUnit.SECONDS)) {
            if (process.exitValue() == 0) {
                LOGGER.info("🛑 Container Ollama parado");
                
                // Aguarda limpeza completa da GPU
                Thread.sleep(3000);
                
                // Reinicia o container
                pb = new ProcessBuilder("docker", "start", OLLAMA_CONTAINER_NAME);
                process = pb.start();
                
                if (process.waitFor(30, TimeUnit.SECONDS)) {
                    if (process.exitValue() == 0) {
                        LOGGER.info("✅ Container Ollama reiniciado com sucesso");
                        
                        // Aguarda o container ficar pronto
                        waitForOllamaReady();
                    } else {
                        LOGGER.warning("⚠️ Falha ao reiniciar container Ollama");
                    }
                } else {
                    LOGGER.warning("⏱️ Timeout reiniciando container Ollama");
                    process.destroyForcibly();
                }
            } else {
                LOGGER.warning("⚠️ Falha ao parar container Ollama");
            }
        } else {
            LOGGER.warning("⏱️ Timeout parando container Ollama");
            process.destroyForcibly();
        }
    }

    /**
     * Aguarda o container Ollama ficar pronto para uso
     */
    private static void waitForOllamaReady() {
        LOGGER.info("⏳ Aguardando Ollama ficar pronto...");
        
        for (int i = 0; i < 10; i++) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", OLLAMA_CONTAINER_NAME,
                        "curl", "-f", "http://localhost:11434/api/tags"
                );
                Process process = pb.start();
                
                if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    LOGGER.info("✅ Ollama está pronto!");
                    return;
                }
            } catch (Exception e) {
                // Ignora e tenta novamente
            }
            
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        LOGGER.warning("⚠️ Timeout aguardando Ollama ficar pronto");
    }

    /**
     * Reinicia serviço systemd tradicional (fallback)
     */
    private static void restartOllamaSystemd() throws IOException, InterruptedException {
        LOGGER.info("🔄 Reiniciando container Docker Ollama...");
        ProcessBuilder pb = new ProcessBuilder("docker", "start", "ollama-container");
        Process process = pb.start();
        
        if (process.waitFor(15, TimeUnit.SECONDS)) {
            if (process.exitValue() == 0) {
                LOGGER.info("✅ Container Ollama reiniciado com sucesso");
                // Aguardar o serviço estar pronto
                Thread.sleep(5000);
            } else {
                LOGGER.warning(String.format("⚠️ Falha ao reiniciar container Ollama (exit code: %d)", process.exitValue()));
            }
        } else {
            LOGGER.warning("⏱️ Timeout reiniciando container Ollama");
            process.destroyForcibly();
        }
    }

    /**
     * Shutdown graceful
     */
    public static void shutdownExecutor() {
        LOGGER.info("🔄 Finalizando gestão GPU...");

        stopContinuousMonitoring();
        executor.shutdown();

        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        knownProcesses.clear();
        LOGGER.info("✅ Gestão GPU finalizada");
    }

    /**
     * Método de conveniência para limpeza otimizada do Ollama Docker
     * Usa as melhores práticas para Docker container
     */
    public static void optimizedOllamaCleanup() {
        try {
            LOGGER.info("🚀 Iniciando limpeza otimizada do Ollama Docker...");
            
            GPUMemoryStatus initialStatus = getCurrentMemoryStatus();
            LOGGER.info(String.format("📊 Memória inicial: %d MB (%.1f%%)", 
                       initialStatus.usedMemory, initialStatus.usagePercentage));
            
            if (isOllamaRunningInDocker()) {
                // 1. Limpeza gentil via API primeiro
                clearOllamaModelCache();
                Thread.sleep(2000);
                
                // 2. Verifica se precisa de limpeza mais agressiva
                GPUMemoryStatus afterCache = getCurrentMemoryStatus();
                if (afterCache.usedMemory > SAFE_MEMORY_THRESHOLD_MB) {
                    LOGGER.info("💪 Memória ainda alta, aplicando limpeza mais agressiva...");
                    forceOllamaMemoryCleanup();
                }
                
                // 3. Relatório final
                Thread.sleep(1000);
                GPUMemoryStatus finalStatus = getCurrentMemoryStatus();
                int memoryFreed = initialStatus.usedMemory - finalStatus.usedMemory;
                LOGGER.info(String.format("✅ Limpeza concluída! Liberados: %d MB (%.1f%% → %.1f%%)", 
                           memoryFreed, initialStatus.usagePercentage, finalStatus.usagePercentage));
                
            } else {
                LOGGER.info("ℹ️ Ollama não está rodando em Docker, usando limpeza tradicional");
                runClearNameThenThreshold("ollama");
            }
            
        } catch (Exception e) {
            LOGGER.severe("❌ Erro na limpeza otimizada: " + e.getMessage());
        }
    }

    /**
     * Método utilitário para verificar uso de memória GPU por containers Docker
     */
    public static void logDockerGpuUsage() {
        try {
            if (isOllamaRunningInDocker()) {
                // Verifica estatísticas do container
                ProcessBuilder pb = new ProcessBuilder("docker", "stats", OLLAMA_CONTAINER_NAME, "--no-stream", "--format", "table {{.Container}}\t{{.MemUsage}}\t{{.CPUPerc}}");
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    reader.lines().forEach(line -> LOGGER.info("🐳 Docker: " + line));
                }

                if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    // Verifica também a GPU
                    GPUMemoryStatus gpuStatus = getCurrentMemoryStatus();
                    LOGGER.info(String.format("🎮 GPU: %d/%d MB (%.1f%%) com Ollama Docker ativo", 
                               gpuStatus.usedMemory, gpuStatus.totalMemory, gpuStatus.usagePercentage));
                }
            }
        } catch (Exception e) {
            LOGGER.fine("Erro verificando Docker GPU usage: " + e.getMessage());
        }
    }

    /**
     * Método específico para liberar cache de modelos do Ollama via API
     */
    public static void clearOllamaModelCache() {
        try {
            LOGGER.info("🗑️ Tentando limpar cache de modelos Ollama via API...");
            
            // Tenta parar todos os modelos carregados
            ProcessBuilder pb = new ProcessBuilder(
                    "curl", "-s", "-X", "POST", 
                    "http://localhost:" + OLLAMA_DOCKER_PORT + "/api/generate",
                    "-H", "Content-Type: application/json",
                    "-d", "{\"model\":\"deepseek-r1:8b\",\"keep_alive\":0}"
            );
            
            Process process = pb.start();
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                LOGGER.info("✅ Comando para descarregar modelo enviado");
                
                // Aguarda um pouco para o modelo ser descarregado
                Thread.sleep(3000);
                
                // Verifica se a memória foi liberada
                GPUMemoryStatus status = getCurrentMemoryStatus();
                LOGGER.info(String.format("📊 Memória GPU após limpeza de cache: %d MB", status.usedMemory));
            }
        } catch (Exception e) {
            LOGGER.warning("❌ Erro limpando cache de modelos: " + e.getMessage());
        }
    }
}