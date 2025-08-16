package org;

import java.io.File;
import java.io.IOException;

public class ErrorHandler {
    /**
     * Verifica se o arquivo existe.
     *
     * @param filePath Caminho do arquivo.
     * @throws IOException Se o arquivo não existir.
     */
    public static void checkFileExists(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Arquivo não encontrado: " + filePath);
        }
    }
}