package br.com.vida.assinador.service;

import br.com.vida.assinador.model.CertificateInfo;
import br.com.vida.assinador.security.LocalFileSecurity;
import br.com.vida.assinador.security.VaultException;
import br.com.vida.assinador.security.VaultService;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CertificateRepository {
    private static final Path CERT_DIR = Path.of("certificates");
    private static final Pattern CERTIFICATE_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final VaultService vault;
    private final CertificateValidationService certificateValidation;

    public CertificateRepository(VaultService vault) {
        this(vault, CertificateValidationService.icpBrasil());
    }

    CertificateRepository(VaultService vault, CertificateValidationService certificateValidation) {
        this.vault = vault;
        this.certificateValidation = certificateValidation;
    }

    public CertificateInfo importCertificate(Path source, char[] password) {
        byte[] pfx;
        try {
            pfx = Files.readAllBytes(source);
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o certificado selecionado.", e);
        }

        byte[] normalizedPkcs12 = null;
        try {
            InspectedCertificate inspected = inspect(pfx, password, UUID.randomUUID().toString(), "");
            normalizedPkcs12 = normalizePkcs12(inspected, password);
            vault.writeEncrypted(dataPath(inspected.info().id()), normalizedPkcs12);
            writeMetadata(inspected.info());
            return inspected.info();
        } finally {
            Arrays.fill(pfx, (byte) 0);
            if (normalizedPkcs12 != null) {
                Arrays.fill(normalizedPkcs12, (byte) 0);
            }
        }
    }

    public List<CertificateInfo> list() {
        Path directory = vault.root().resolve(CERT_DIR);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<CertificateInfo> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .forEach(path -> result.add(readMetadata(path)));
        } catch (IOException e) {
            throw new VaultException("Não foi possível listar os certificados.", e);
        }
        return result.stream()
                .sorted(Comparator.comparing(CertificateInfo::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Decifra o A1 do cofre e o valida uma única vez; o resultado alimenta {@link PdfSigningService#open}. */
    public SigningCredential openForSigning(String id, char[] password) {
        CertificateInfo current = findById(id);
        byte[] pfx = vault.readEncrypted(dataPath(id));
        try {
            InspectedCertificate inspected = inspect(pfx, password, id, current.alias());
            KeyStore.PrivateKeyEntry privateKey = new KeyStore.PrivateKeyEntry(
                    privateKey(inspected, password),
                    inspected.keyStore().getCertificateChain(inspected.keyAlias())
            );
            return new SigningCredential(current, inspected.keyAlias(), privateKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Não foi possível abrir a chave privada do certificado A1.", e);
        } finally {
            Arrays.fill(pfx, (byte) 0);
        }
    }

    public void enableAutomaticSigning(String id, char[] password) {
        CertificateInfo current = openForSigning(id, password).info();
        byte[] encodedPassword = encodePassword(password);
        try {
            vault.writeEncrypted(passwordPath(id), encodedPassword);
            try {
                writeMetadata(current.withAutomaticSigning(true));
            } catch (RuntimeException e) {
                vault.delete(passwordPath(id));
                throw e;
            }
        } finally {
            Arrays.fill(encodedPassword, (byte) 0);
        }
    }

    public void disableAutomaticSigning(String id) {
        CertificateInfo current = findById(id);
        writeMetadata(current.withAutomaticSigning(false));
        vault.delete(passwordPath(id));
    }

    public char[] loadAutomaticPassword(String id) {
        CertificateInfo current = findById(id);
        if (!current.automaticSigning()) {
            throw new IllegalStateException("Este certificado está configurado para pedir a senha ao assinar.");
        }

        byte[] encodedPassword = vault.readEncrypted(passwordPath(id));
        try {
            return decodePassword(encodedPassword);
        } finally {
            Arrays.fill(encodedPassword, (byte) 0);
        }
    }

    public void rename(String id, String alias) {
        CertificateInfo current = findById(id);
        writeMetadata(current.withAlias(alias == null ? "" : alias.trim()));
    }

    public void remove(String id) {
        vault.delete(dataPath(id));
        vault.delete(passwordPath(id));
        try {
            Files.deleteIfExists(metadataPath(id));
        } catch (IOException e) {
            throw new VaultException("O certificado foi removido do cofre, mas os metadados não puderam ser apagados.", e);
        }
    }

    private InspectedCertificate inspect(byte[] pfx, char[] password, String id, String alias) {
        if (password == null) {
            throw new IllegalArgumentException("Informe a senha do certificado.");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (ByteArrayInputStream input = new ByteArrayInputStream(pfx)) {
                keyStore.load(input, password);
            }

            SigningCertificate signingCertificate = findSigningCertificate(keyStore);
            X509Certificate certificate = signingCertificate.leaf();
            List<X509Certificate> completedChain = certificateValidation.validateAndComplete(signingCertificate.chain());
            CertificateInfo info = new CertificateInfo(
                    id,
                    alias,
                    certificate.getSubjectX500Principal().getName(),
                    commonName(certificate),
                    certificate.getIssuerX500Principal().getName(),
                    certificate.getSerialNumber().toString(16).toUpperCase(),
                    certificate.getNotBefore().toInstant(),
                    certificate.getNotAfter().toInstant(),
                    false
            );
            return new InspectedCertificate(info, keyStore, signingCertificate.keyAlias(), completedChain);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException(
                    "Não foi possível abrir o A1. Verifique a senha, o arquivo e a validade do certificado.",
                    e
            );
        }
    }

    private byte[] normalizePkcs12(InspectedCertificate inspected, char[] password) {
        try {
            PrivateKey privateKey = privateKey(inspected, password);
            Certificate[] normalizedChain = inspected.completedChain().toArray(Certificate[]::new);
            inspected.keyStore().setKeyEntry(inspected.keyAlias(), privateKey, password, normalizedChain);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                inspected.keyStore().store(output, password);
                return output.toByteArray();
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("Não foi possível preparar a cadeia completa do certificado A1.", e);
        }
    }

    private static PrivateKey privateKey(InspectedCertificate inspected, char[] password)
            throws GeneralSecurityException {
        Key key = inspected.keyStore().getKey(inspected.keyAlias(), password);
        if (!(key instanceof PrivateKey privateKey)) {
            throw new GeneralSecurityException("A entrada selecionada não contém chave privada.");
        }
        return privateKey;
    }

    private SigningCertificate findSigningCertificate(KeyStore keyStore) throws GeneralSecurityException {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String keyAlias = aliases.nextElement();
            if (keyStore.isKeyEntry(keyAlias) && keyStore.getCertificate(keyAlias) instanceof X509Certificate certificate) {
                Certificate[] certificateChain = keyStore.getCertificateChain(keyAlias);
                if (certificateChain == null || certificateChain.length == 0) {
                    throw new GeneralSecurityException("O PKCS#12 não contém cadeia para a chave privada.");
                }
                List<X509Certificate> chain = new ArrayList<>(certificateChain.length);
                for (Certificate item : certificateChain) {
                    if (!(item instanceof X509Certificate x509)) {
                        throw new GeneralSecurityException("A cadeia do PKCS#12 contém certificado não X.509.");
                    }
                    chain.add(x509);
                }
                return new SigningCertificate(keyAlias, certificate, List.copyOf(chain));
            }
        }
        throw new GeneralSecurityException("O PKCS#12 não contém chave privada utilizável para assinatura.");
    }

    private String commonName(X509Certificate certificate) {
        try {
            LdapName ldapName = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : ldapName.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (Exception ignored) {
            // O subject completo continua disponível como fallback de exibição.
        }
        return certificate.getSubjectX500Principal().getName();
    }

    private CertificateInfo findById(String id) {
        Path path = metadataPath(id);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Certificado não encontrado.");
        }
        CertificateInfo info = readMetadata(path);
        if (!id.equals(info.id())) {
            throw new IllegalArgumentException("Certificado não encontrado.");
        }
        return info;
    }

    private void writeMetadata(CertificateInfo info) {
        Properties properties = new Properties();
        properties.setProperty("id", info.id());
        properties.setProperty("alias", info.alias() == null ? "" : info.alias());
        properties.setProperty("subject", info.subject());
        properties.setProperty("commonName", info.commonName());
        properties.setProperty("issuer", info.issuer());
        properties.setProperty("serialNumber", info.serialNumber());
        properties.setProperty("validFrom", info.validFrom().toString());
        properties.setProperty("validUntil", info.validUntil().toString());
        properties.setProperty("automaticSigning", Boolean.toString(info.automaticSigning()));

        Path path = metadataPath(info.id());
        try {
            LocalFileSecurity.createPrivateDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString() + ".", ".tmp");
            try {
                LocalFileSecurity.makePrivateFile(temp);
                try (OutputStream output = Files.newOutputStream(temp)) {
                    properties.store(output, "ASSINADOR certificate metadata");
                }
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LocalFileSecurity.makePrivateFile(path);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new VaultException("Não foi possível salvar os dados do certificado.", e);
        }
    }

    private CertificateInfo readMetadata(Path path) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return new CertificateInfo(
                    properties.getProperty("id"),
                    properties.getProperty("alias", ""),
                    properties.getProperty("subject", ""),
                    properties.getProperty("commonName", ""),
                    properties.getProperty("issuer", ""),
                    properties.getProperty("serialNumber", ""),
                    Instant.parse(properties.getProperty("validFrom")),
                    Instant.parse(properties.getProperty("validUntil")),
                    Boolean.parseBoolean(properties.getProperty("automaticSigning", "false"))
            );
        } catch (IOException | RuntimeException e) {
            throw new VaultException("Metadados de certificado inválidos: " + path.getFileName(), e);
        }
    }

    private Path dataPath(String id) {
        return CERT_DIR.resolve(requireCertificateId(id) + ".p12.enc");
    }

    private Path passwordPath(String id) {
        return CERT_DIR.resolve(requireCertificateId(id) + ".password.enc");
    }

    private Path metadataPath(String id) {
        return vault.root().resolve(CERT_DIR).resolve(requireCertificateId(id) + ".properties");
    }

    /** Ids são sempre UUIDs (ver importCertificate); qualquer outra forma, como "../x", não pode virar caminho. */
    private static String requireCertificateId(String id) {
        if (id == null || !CERTIFICATE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Certificado não encontrado.");
        }
        return id;
    }

    private byte[] encodePassword(char[] password) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] encoded = new byte[buffer.remaining()];
        buffer.get(encoded);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return encoded;
    }

    private char[] decodePassword(byte[] encodedPassword) {
        CharBuffer buffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(encodedPassword));
        char[] password = new char[buffer.remaining()];
        buffer.get(password);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
        return password;
    }

    private record SigningCertificate(String keyAlias, X509Certificate leaf, List<X509Certificate> chain) {
    }

    private record InspectedCertificate(
            CertificateInfo info,
            KeyStore keyStore,
            String keyAlias,
            List<X509Certificate> completedChain
    ) {
    }
}
