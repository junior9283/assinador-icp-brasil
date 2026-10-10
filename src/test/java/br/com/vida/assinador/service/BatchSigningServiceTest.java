package br.com.vida.assinador.service;

import br.com.vida.assinador.model.BatchCorner;
import br.com.vida.assinador.model.SigningResult;
import br.com.vida.assinador.support.TestCertificateChain;
import br.com.vida.assinador.support.TestPdfs;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchSigningServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void continuesBatchAfterInvalidPdfAndSignsRemainingFiles() throws Exception {
        TestCertificateChain chain = TestCertificateChain.create(KeyUsage.digitalSignature);
        Path first = TestPdfs.create(tempDir.resolve("primeiro.pdf"), 1);
        Path invalid = tempDir.resolve("corrompido.pdf");
        Files.writeString(invalid, "isto não é um PDF", StandardCharsets.UTF_8);
        Path third = TestPdfs.create(tempDir.resolve("terceiro.pdf"), 2);

        BatchSigningService service = new BatchSigningService(new PdfSigningService());

        List<SigningResult> results = service.signAll(
                List.of(first, invalid, third),
                chain.credential(),
                null,
                document -> SignaturePlacementService.forLastPage(document, BatchCorner.INFERIOR_DIREITO),
                BatchSigningService.ProgressListener.NONE
        );

        assertEquals(3, results.size());
        assertTrue(results.get(0).success(), results.get(0).message());
        assertFalse(results.get(1).success());
        assertTrue(results.get(2).success(), results.get(2).message());
        assertEquals(tempDir.resolve("primeiro_assinado.pdf"), results.get(0).output());
        assertEquals(1, TestPdfs.signatureCount(results.get(0).output()));
        assertEquals(1, TestPdfs.signatureCount(results.get(2).output()));
    }
}
