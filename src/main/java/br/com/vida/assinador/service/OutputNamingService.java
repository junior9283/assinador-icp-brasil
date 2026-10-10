package br.com.vida.assinador.service;

import java.nio.file.Files;
import java.nio.file.Path;

public final class OutputNamingService {
    private OutputNamingService() {
    }

    /** Próximo nome livre para a versão assinada, na mesma pasta do PDF original. */
    public static Path nextAvailable(Path input) {
        Path directory = input.toAbsolutePath().getParent();
        String fileName = input.getFileName().toString();
        String base = fileName.toLowerCase().endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;

        Path candidate = directory.resolve(base + "_assinado.pdf");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(base + "_assinado_" + suffix++ + ".pdf");
        }
        return candidate;
    }
}
