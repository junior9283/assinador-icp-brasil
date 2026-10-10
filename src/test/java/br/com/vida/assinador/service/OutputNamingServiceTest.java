package br.com.vida.assinador.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputNamingServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void addsSignedSuffixAndAvoidsOverwrite() throws IOException {
        Path input = tempDir.resolve("contrato.pdf");
        assertEquals(tempDir.resolve("contrato_assinado.pdf"), OutputNamingService.nextAvailable(input));

        Files.createFile(tempDir.resolve("contrato_assinado.pdf"));
        assertEquals(tempDir.resolve("contrato_assinado_2.pdf"), OutputNamingService.nextAvailable(input));
    }
}
