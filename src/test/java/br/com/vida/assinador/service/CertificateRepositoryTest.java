package br.com.vida.assinador.service;

import br.com.vida.assinador.security.VaultService;
import br.com.vida.assinador.support.TestCertificateChain;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateRepositoryTest {
    private static final String CERTIFICATE_PASSWORD = "senha-certificado";
    private static final String MASTER_PASSWORD = "senha-mestra-forte";

    @TempDir
    Path tempDir;

    @Test
    void importsCertificateAllowedForDigitalSignature() throws Exception {
        TestCertificateChain chain = TestCertificateChain.create(KeyUsage.digitalSignature);
        Path certificateFile = writePkcs12("assinatura.p12", chain);
        char[] masterPassword = MASTER_PASSWORD.toCharArray();
        char[] certificatePassword = CERTIFICATE_PASSWORD.toCharArray();

        try (VaultService vault = new VaultService(tempDir.resolve("vault"))) {
            vault.create(masterPassword);
            CertificateRepository repository = repository(vault, chain);

            var imported = repository.importCertificate(certificateFile, certificatePassword);

            assertEquals("ASSINADOR Teste", imported.commonName());
            assertFalse(imported.automaticSigning());
            assertEquals(1, repository.list().size());
        } finally {
            Arrays.fill(masterPassword, '\0');
            Arrays.fill(certificatePassword, '\0');
        }
    }

    @Test
    void opensStoredCertificateForSigningAndRejectsIdOutsideCertificateDirectory() throws Exception {
        TestCertificateChain chain = TestCertificateChain.create(KeyUsage.digitalSignature);
        Path certificateFile = writePkcs12("credencial.p12", chain);
        char[] masterPassword = MASTER_PASSWORD.toCharArray();
        char[] certificatePassword = CERTIFICATE_PASSWORD.toCharArray();

        try (VaultService vault = new VaultService(tempDir.resolve("vault-credencial"))) {
            vault.create(masterPassword);
            CertificateRepository repository = repository(vault, chain);
            var imported = repository.importCertificate(certificateFile, certificatePassword);

            SigningCredential credential = repository.openForSigning(imported.id(), certificatePassword);
            assertEquals(imported.id(), credential.info().id());
            assertEquals(2, credential.privateKey().getCertificateChain().length);

            // Metadados plantados fora da pasta de certificados não podem ser alcançados com "../".
            Path metadata = vault.root().resolve("certificates").resolve(imported.id() + ".properties");
            Files.copy(metadata, vault.root().resolve(imported.id() + ".properties"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.rename("../" + imported.id(), "fora")
            );
        } finally {
            Arrays.fill(masterPassword, '\0');
            Arrays.fill(certificatePassword, '\0');
        }
    }

    @Test
    void storesAutomaticSigningPasswordEncryptedAndCanDisableMode() throws Exception {
        TestCertificateChain chain = TestCertificateChain.create(KeyUsage.digitalSignature);
        Path certificateFile = writePkcs12("automatico.p12", chain);
        char[] masterPassword = MASTER_PASSWORD.toCharArray();
        char[] certificatePassword = CERTIFICATE_PASSWORD.toCharArray();

        try (VaultService vault = new VaultService(tempDir.resolve("vault-automatico"))) {
            vault.create(masterPassword);
            CertificateRepository repository = repository(vault, chain);
            var imported = repository.importCertificate(certificateFile, certificatePassword);

            repository.enableAutomaticSigning(imported.id(), certificatePassword);

            var automatic = repository.list().getFirst();
            assertTrue(automatic.automaticSigning());
            Path encryptedPassword = vault.root()
                    .resolve("certificates")
                    .resolve(imported.id() + ".password.enc");
            assertTrue(Files.isRegularFile(encryptedPassword));
            byte[] encryptedContent = Files.readAllBytes(encryptedPassword);
            byte[] clearPassword = CERTIFICATE_PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(containsSequence(encryptedContent, clearPassword));

            char[] loadedPassword = repository.loadAutomaticPassword(imported.id());
            try {
                assertArrayEquals(CERTIFICATE_PASSWORD.toCharArray(), loadedPassword);
            } finally {
                Arrays.fill(loadedPassword, '\0');
            }

            repository.disableAutomaticSigning(imported.id());
            assertFalse(repository.list().getFirst().automaticSigning());
            assertFalse(Files.exists(encryptedPassword));
        } finally {
            Arrays.fill(masterPassword, '\0');
            Arrays.fill(certificatePassword, '\0');
        }
    }

    @Test
    void rejectsCertificateWithoutSigningKeyUsage() throws Exception {
        TestCertificateChain chain = TestCertificateChain.create(KeyUsage.keyEncipherment);
        Path certificateFile = writePkcs12("cifragem.p12", chain);
        char[] masterPassword = MASTER_PASSWORD.toCharArray();
        char[] certificatePassword = CERTIFICATE_PASSWORD.toCharArray();

        try (VaultService vault = new VaultService(tempDir.resolve("vault"))) {
            vault.create(masterPassword);
            CertificateRepository repository = repository(vault, chain);

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.importCertificate(certificateFile, certificatePassword)
            );

            assertTrue(error.getMessage().contains("assinatura digital"));
            assertTrue(repository.list().isEmpty());
        } finally {
            Arrays.fill(masterPassword, '\0');
            Arrays.fill(certificatePassword, '\0');
        }
    }

    @Test
    void storesCompletedChainWhenImportedPkcs12OmitsIntermediate() throws Exception {
        URI issuerUri = URI.create("https://certs.example.test/intermediate.crt");
        TestCertificateChain.IntermediateCertificateChain chain =
                TestCertificateChain.createWithIntermediate(KeyUsage.digitalSignature, issuerUri);
        char[] masterPassword = MASTER_PASSWORD.toCharArray();
        char[] certificatePassword = CERTIFICATE_PASSWORD.toCharArray();
        Path certificateFile = tempDir.resolve("incompleto.p12");
        Files.write(certificateFile, chain.leafOnlyPkcs12(certificatePassword));

        try (VaultService vault = new VaultService(tempDir.resolve("vault-incompleto"))) {
            vault.create(masterPassword);
            CertificateValidationService validation = new CertificateValidationService(
                    List.of(chain.root()),
                    uri -> List.of(chain.intermediate())
            );
            CertificateRepository repository = new CertificateRepository(vault, validation);

            var imported = repository.importCertificate(certificateFile, certificatePassword);
            SigningCredential stored = repository.openForSigning(imported.id(), certificatePassword);
            assertEquals(3, stored.privateKey().getCertificateChain().length);
        } finally {
            Arrays.fill(masterPassword, '\0');
            Arrays.fill(certificatePassword, '\0');
        }
    }

    private CertificateRepository repository(VaultService vault, TestCertificateChain chain) {
        return new CertificateRepository(
                vault,
                new CertificateValidationService(List.of(chain.root()))
        );
    }

    private Path writePkcs12(String fileName, TestCertificateChain chain) throws Exception {
        char[] password = CERTIFICATE_PASSWORD.toCharArray();
        try {
            Path path = tempDir.resolve(fileName);
            Files.write(path, chain.pkcs12(password));
            return path;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private boolean containsSequence(byte[] data, byte[] sequence) {
        outer:
        for (int i = 0; i <= data.length - sequence.length; i++) {
            for (int j = 0; j < sequence.length; j++) {
                if (data[i + j] != sequence[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
