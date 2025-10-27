package org;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/**
 * Gerenciador seguro de API keys com criptografia AES-256
 */
public class ApiKeyManager {
    private static final String KEY_FILE = ".api_keys.enc";
    private static final String ALGORITHM = "AES";
    private static final String GEMINI_KEY = "google.gemini.api.key";
    private static final String DEEPSEEK_KEY = "deepseek.api.key";

    /**
     * Salva as API keys de forma criptografada
     */
    public static void saveApiKeys(String password, String geminiKey, String deepseekKey) throws Exception {
        Properties props = new Properties();
        if (geminiKey != null && !geminiKey.isEmpty()) {
            props.setProperty(GEMINI_KEY, geminiKey);
        }
        if (deepseekKey != null && !deepseekKey.isEmpty()) {
            props.setProperty(DEEPSEEK_KEY, deepseekKey);
        }

        // Serializar properties
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        props.store(baos, "DubAI API Keys");
        byte[] plaintext = baos.toByteArray();

        // Criptografar
        SecretKey key = generateKey(password);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plaintext);

        // Salvar arquivo criptografado
        try (FileOutputStream fos = new FileOutputStream(KEY_FILE)) {
            fos.write(Base64.getEncoder().encode(encrypted));
        }
    }

    /**
     * Salva a API key do Google (compatibilidade com código antigo)
     */
    public static void saveApiKey(String password, String apiKey) throws Exception {
        saveApiKeys(password, apiKey, null);
    }

    /**
     * Carrega a API key do Google descriptografada
     */
    public static String loadApiKey(String password) throws Exception {
        Properties props = loadAllApiKeys(password);
        return props != null ? props.getProperty(GEMINI_KEY) : null;
    }

    /**
     * Carrega a API key do DeepSeek descriptografada
     */
    public static String loadDeepSeekApiKey(String password) throws Exception {
        Properties props = loadAllApiKeys(password);
        return props != null ? props.getProperty(DEEPSEEK_KEY) : null;
    }

    /**
     * Carrega todas as API keys descriptografadas
     */
    private static Properties loadAllApiKeys(String password) throws Exception {
        File file = new File(KEY_FILE);
        if (!file.exists()) {
            return null;
        }

        // Ler arquivo criptografado
        byte[] encrypted;
        try (FileInputStream fis = new FileInputStream(KEY_FILE)) {
            encrypted = Base64.getDecoder().decode(fis.readAllBytes());
        }

        // Descriptografar
        SecretKey key = generateKey(password);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] plaintext = cipher.doFinal(encrypted);

        // Deserializar properties
        Properties props = new Properties();
        props.load(new ByteArrayInputStream(plaintext));

        return props;
    }

    /**
     * Verifica se existe arquivo de keys
     */
    public static boolean hasApiKeyFile() {
        return new File(KEY_FILE).exists();
    }

    /**
     * Gera chave AES-256 a partir da senha
     */
    private static SecretKey generateKey(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, ALGORITHM);
    }
}
