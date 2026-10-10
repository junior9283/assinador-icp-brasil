package br.com.vida.assinador.service;

import br.com.vida.assinador.support.TestCertificateChain;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateValidationServiceTest {
    @Test
    void loadsBundledIcpBrasilTrustRoots() {
        assertDoesNotThrow(CertificateValidationService::icpBrasil);
    }

    @Test
    void validatesTrustedCertificateChain() throws Exception {
        TestCertificateChain chain = TestCertificateChain.create(KeyUsage.digitalSignature);
        CertificateValidationService validation = new CertificateValidationService(List.of(chain.root()));

        assertDoesNotThrow(() -> validation.validate(List.of(chain.leaf(), chain.root())));
    }

    @Test
    void completesMissingIntermediateUsingAia() throws Exception {
        URI issuerUri = URI.create("https://certs.example.test/intermediate.crt");
        TestCertificateChain.IntermediateCertificateChain chain =
                TestCertificateChain.createWithIntermediate(KeyUsage.digitalSignature, issuerUri);
        CertificateValidationService validation = new CertificateValidationService(
                List.of(chain.root()),
                uri -> {
                    if (!issuerUri.equals(uri)) {
                        throw new IllegalArgumentException("URI AIA inesperada: " + uri);
                    }
                    return List.of(chain.intermediate());
                }
        );

        assertDoesNotThrow(() -> validation.validate(List.of(chain.leaf())));
    }

    @Test
    void prefersSuppliedIntermediateWithoutAiaRequest() throws Exception {
        URI issuerUri = URI.create("https://certs.example.test/intermediate.crt");
        TestCertificateChain.IntermediateCertificateChain chain =
                TestCertificateChain.createWithIntermediate(KeyUsage.digitalSignature, issuerUri);
        AtomicInteger fetchCount = new AtomicInteger();
        CertificateValidationService validation = new CertificateValidationService(
                List.of(chain.root()),
                uri -> {
                    fetchCount.incrementAndGet();
                    return List.of();
                }
        );

        assertDoesNotThrow(() -> validation.validate(List.of(chain.leaf(), chain.intermediate(), chain.root())));
        assertEquals(0, fetchCount.get());
    }

    @Test
    void rejectsCertificateFromUnknownRoot() throws Exception {
        TestCertificateChain trusted = TestCertificateChain.create(KeyUsage.digitalSignature);
        TestCertificateChain untrusted = TestCertificateChain.create(KeyUsage.digitalSignature);
        CertificateValidationService validation = new CertificateValidationService(List.of(trusted.root()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validation.validate(List.of(untrusted.leaf(), untrusted.root()))
        );

        assertTrue(error.getMessage().contains("ICP-Brasil"));
    }
}
