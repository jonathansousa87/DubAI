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
            "python", "whisperx", "pytorch", "tensorflow",
            "cuda", "nvidia", "tts", "spleeter", "ffmpeg"
    };

    // Lista de processos críticos para nunca matar
    private static final String[] PROTECTED_PROCESSES = {
            "nvidia-smi", "nvidia-persistenced", "Xorg", "gnome", "systemd",
            "idea", "intellij", "jetbrains", "java.*Main", "DubAIGUI",
            "plasmashell", "kwin", "plasma", "kde", "kioworker", "kded",
            "sddm", "pulseaudio", "pipewire", "wireplumber"
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

        // Mata processos principais de ML/AI
        String[] criticalTargets = {"python", "whisperx", "pytorch"};
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
            forceSystemCleanup();
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
        // Limpeza padrão de processos por nome
        
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
     * Força limpeza do sistema incluindo caches AI
     */
    private static void forceSystemCleanup() {
        try {
            // Força garbage collection múltiplas vezes
            for (int i = 0; i < 3; i++) {
                System.gc();
                // System.runFinalization(); // Deprecado no Java moderno
                Thread.sleep(100);
            }

            // Limpa caches de modelos AI em /tmp
            cleanupAIModelCaches();

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
     * Limpa caches de modelos AI que ocupam /tmp
     */
    private static void cleanupAIModelCaches() {
        try {
            LOGGER.info("🧹 Limpando caches de modelos AI em /tmp...");
            
            // Caminhos comuns de cache de modelos AI
            String[] cachePaths = {
                "/tmp/cache/hub",  // LIMPA TODO O HUB (modelos Whisper, etc)
                "/tmp/cache/xet",
                "/tmp/cache/hub/checkpoints",
                "/tmp/.cache",
                "/tmp/huggingface_cache",
                "/tmp/transformers_cache"
            };
            
            long totalFreed = 0;
            
            for (String cachePath : cachePaths) {
                try {
                    // Verifica se o diretório existe
                    ProcessBuilder duCheck = new ProcessBuilder("du", "-sb", cachePath);
                    Process duProcess = duCheck.start();
                    
                    if (duProcess.waitFor(5, TimeUnit.SECONDS) && duProcess.exitValue() == 0) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(duProcess.getInputStream()))) {
                            String line = reader.readLine();
                            if (line != null) {
                                long sizeBytes = Long.parseLong(line.split("\\s+")[0]);
                                long sizeMB = sizeBytes / (1024 * 1024);
                                
                                if (sizeMB > 100) { // Só limpa se > 100MB
                                    LOGGER.info(String.format("🗑️ Removendo cache: %s (%.1f GB)", 
                                        cachePath, sizeMB / 1024.0));
                                    
                                    ProcessBuilder rmProcess = new ProcessBuilder("rm", "-rf", cachePath);
                                    Process rm = rmProcess.start();
                                    
                                    if (rm.waitFor(30, TimeUnit.SECONDS)) {
                                        totalFreed += sizeMB;
                                        LOGGER.info(String.format("✅ Cache removido: %s", cachePath));
                                    } else {
                                        rm.destroyForcibly();
                                        LOGGER.warning(String.format("⏰ Timeout removendo: %s", cachePath));
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.fine(String.format("Erro limpando %s: %s", cachePath, e.getMessage()));
                }
            }
            
            if (totalFreed > 0) {
                LOGGER.info(String.format("🎯 Total liberado: %.2f GB de cache AI", totalFreed / 1024.0));
            } else {
                LOGGER.fine("💾 Nenhum cache AI grande encontrado para limpar");
            }
            
        } catch (Exception e) {
            LOGGER.warning("❌ Erro na limpeza de caches AI: " + e.getMessage());
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
     * Limpeza manual de caches AI (método público)
     */
    public static void cleanAIModelCaches() {
        try {
            LOGGER.info("🧹 Executando limpeza manual de caches AI...");
            cleanupAIModelCaches();
            
            // Força garbage collection após limpeza
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(100);
            }
            
            LOGGER.info("✅ Limpeza manual de caches AI concluída");
        } catch (Exception e) {
            LOGGER.warning("❌ Erro na limpeza manual de caches: " + e.getMessage());
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

}
