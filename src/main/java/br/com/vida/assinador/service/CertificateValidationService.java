package br.com.vida.assinador.service;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class CertificateValidationService {
    private static final int MAX_CHAIN_CERTIFICATES = 10;
    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();
    private static final List<TrustedRootResource> ICP_BRASIL_ROOTS = trustedRootResources();

    private final List<X509Certificate> trustRoots;
    private final Set<TrustAnchor> trustAnchors;
    private final CertificateIssuerFetcher issuerFetcher;

    public CertificateValidationService(List<X509Certificate> trustRoots) {
        this(trustRoots, new HttpCertificateIssuerFetcher());
    }

    CertificateValidationService(
            List<X509Certificate> trustRoots,
            CertificateIssuerFetcher issuerFetcher
    ) {
        if (trustRoots == null || trustRoots.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos uma raiz de confiança.");
        }
        if (issuerFetcher == null) {
            throw new IllegalArgumentException("Informe o carregador de certificados emissores.");
        }
        this.trustRoots = List.copyOf(trustRoots);
        this.trustAnchors = this.trustRoots.stream()
                .map(root -> new TrustAnchor(root, null))
                .collect(Collectors.toUnmodifiableSet());
        this.issuerFetcher = issuerFetcher;
    }

    public static CertificateValidationService icpBrasil() {
        return new CertificateValidationService(loadIcpBrasilRoots());
    }

    public void validate(List<X509Certificate> suppliedChain) {
        validateAndComplete(suppliedChain);
    }

    public List<X509Certificate> validateAndComplete(List<X509Certificate> suppliedChain) {
        if (suppliedChain == null || suppliedChain.isEmpty()) {
            throw new IllegalArgumentException("O PKCS#12 não contém cadeia de certificados.");
        }

        try {
            validateKeyUsage(suppliedChain.getFirst());
            List<X509Certificate> completedChain = completeChain(suppliedChain);
            validateCertificateDates(completedChain);
            validateTrustedPath(completedChain);
            return completedChain;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException(
                    "A cadeia do certificado não pôde ser completada ou validada na ICP-Brasil.",
                    e
            );
        }
    }

    private List<X509Certificate> completeChain(List<X509Certificate> suppliedChain)
            throws GeneralSecurityException, IOException {
        X509Certificate leaf = suppliedChain.getFirst();
        List<X509Certificate> availableIssuers = new ArrayList<>(suppliedChain.subList(1, suppliedChain.size()));
        List<X509Certificate> completed = new ArrayList<>();
        Set<URI> fetchedUris = new HashSet<>();
        completed.add(leaf);

        X509Certificate current = leaf;
        while (!isTrustRoot(current)) {
            if (completed.size() >= MAX_CHAIN_CERTIFICATES) {
                throw new GeneralSecurityException("A cadeia de certificados excede o limite permitido.");
            }

            X509Certificate issuer = resolveIssuer(current, availableIssuers, fetchedUris);
            if (issuer == null) {
                throw new GeneralSecurityException(
                        "Não foi possível localizar o certificado emissor de "
                                + current.getSubjectX500Principal().getName()
                );
            }
            if (completed.contains(issuer)) {
                throw new GeneralSecurityException("Foi detectado um ciclo na cadeia de certificados.");
            }

            completed.add(issuer);
            availableIssuers.remove(issuer);
            current = issuer;
        }
        return List.copyOf(completed);
    }

    private X509Certificate resolveIssuer(
            X509Certificate certificate,
            List<X509Certificate> availableIssuers,
            Set<URI> fetchedUris
    ) throws GeneralSecurityException, IOException {
        X509Certificate issuer = findIssuer(certificate, availableIssuers);
        if (issuer != null) {
            return issuer;
        }

        issuer = findIssuer(certificate, trustRoots);
        if (issuer != null) {
            return issuer;
        }

        addFetchedIssuerCandidates(certificate, availableIssuers, fetchedUris);
        return findIssuer(certificate, availableIssuers);
    }

    private void addFetchedIssuerCandidates(
            X509Certificate certificate,
            List<X509Certificate> availableIssuers,
            Set<URI> fetchedUris
    ) throws GeneralSecurityException, IOException {
        Exception lastFailure = null;
        boolean fetchedAnyCertificate = false;
        for (URI uri : caIssuerUris(certificate)) {
            if (!fetchedUris.add(uri)) {
                continue;
            }
            try {
                for (X509Certificate fetched : issuerFetcher.fetch(uri)) {
                    fetchedAnyCertificate = true;
                    if (!availableIssuers.contains(fetched) && !isTrustRoot(fetched)) {
                        availableIssuers.add(fetched);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GeneralSecurityException("A busca do certificado emissor foi interrompida.", e);
            } catch (IOException | GeneralSecurityException e) {
                lastFailure = e;
            }
        }

        if (!fetchedAnyCertificate && lastFailure != null) {
            if (lastFailure instanceof IOException ioException) {
                throw ioException;
            }
            throw (GeneralSecurityException) lastFailure;
        }
    }

    private X509Certificate findIssuer(X509Certificate certificate, List<X509Certificate> candidates) {
        for (X509Certificate candidate : candidates) {
            if (!certificate.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                continue;
            }
            try {
                certificate.verify(candidate.getPublicKey(), BOUNCY_CASTLE);
                return candidate;
            } catch (GeneralSecurityException ignored) {
                // O DN pode coincidir, mas a chave precisa validar a assinatura do certificado filho.
            }
        }
        return null;
    }

    private List<URI> caIssuerUris(X509Certificate certificate) throws GeneralSecurityException {
        byte[] encodedExtension = certificate.getExtensionValue(Extension.authorityInfoAccess.getId());
        if (encodedExtension == null) {
            return List.of();
        }

        try {
            ASN1OctetString outer = ASN1OctetString.getInstance(encodedExtension);
            AuthorityInformationAccess aia = AuthorityInformationAccess.getInstance(
                    ASN1Primitive.fromByteArray(outer.getOctets())
            );
            List<URI> uris = new ArrayList<>();
            for (AccessDescription accessDescription : aia.getAccessDescriptions()) {
                if (!AccessDescription.id_ad_caIssuers.equals(accessDescription.getAccessMethod())) {
                    continue;
                }
                GeneralName location = accessDescription.getAccessLocation();
                if (location.getTagNo() != GeneralName.uniformResourceIdentifier) {
                    continue;
                }
                URI uri = URI.create(location.getName().toString());
                if (HttpCertificateIssuerFetcher.supports(uri)) {
                    uris.add(uri);
                }
            }
            return List.copyOf(uris);
        } catch (IllegalArgumentException | IOException e) {
            throw new GeneralSecurityException("A extensão AIA do certificado é inválida.", e);
        }
    }

    private void validateCertificateDates(List<X509Certificate> chain) throws GeneralSecurityException {
        for (X509Certificate certificate : chain) {
            if (!isTrustRoot(certificate)) {
                certificate.checkValidity();
            }
        }
    }

    private void validateKeyUsage(X509Certificate certificate) {
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage == null) {
            return;
        }
        boolean digitalSignature = keyUsage.length > 0 && keyUsage[0];
        boolean contentCommitment = keyUsage.length > 1 && keyUsage[1];
        if (!digitalSignature && !contentCommitment) {
            throw new IllegalArgumentException("O certificado não permite uso para assinatura digital.");
        }
    }

    private void validateTrustedPath(List<X509Certificate> chain) throws GeneralSecurityException {
        List<X509Certificate> pathCertificates = new ArrayList<>(chain);
        if (isTrustRoot(pathCertificates.getLast())) {
            pathCertificates.removeLast();
        }
        if (pathCertificates.isEmpty()) {
            throw new GeneralSecurityException("Um certificado raiz não pode ser usado como certificado do assinante.");
        }

        CertificateFactory factory = x509CertificateFactory();
        CertPath certPath = factory.generateCertPath(pathCertificates);
        PKIXParameters parameters = new PKIXParameters(trustAnchors);
        parameters.setRevocationEnabled(false);
        pkixValidator().validate(certPath, parameters);
    }

    private boolean isTrustRoot(X509Certificate certificate) {
        return trustRoots.stream().anyMatch(certificate::equals);
    }

    private static List<X509Certificate> loadIcpBrasilRoots() {
        CertificateFactory factory;
        MessageDigest sha256;
        try {
            factory = x509CertificateFactory();
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("O Java não oferece os algoritmos necessários para validar certificados.", e);
        }

        List<X509Certificate> roots = new ArrayList<>();
        for (TrustedRootResource trustedRoot : ICP_BRASIL_ROOTS) {
            try (InputStream input = CertificateValidationService.class.getResourceAsStream(trustedRoot.resource())) {
                if (input == null) {
                    throw new IllegalStateException("Raiz ICP-Brasil ausente no aplicativo: " + trustedRoot.resource());
                }
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(input);
                String actualHash = HexFormat.of().formatHex(sha256.digest(certificate.getEncoded()));
                if (!trustedRoot.sha256().equals(actualHash)) {
                    throw new IllegalStateException(
                            "A raiz ICP-Brasil embutida não passou na verificação de integridade: "
                                    + trustedRoot.resource()
                    );
                }
                roots.add(certificate);
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException(
                        "Não foi possível carregar a raiz ICP-Brasil: " + trustedRoot.resource(),
                        e
                );
            }
        }
        return List.copyOf(roots);
    }

    private static CertificateFactory x509CertificateFactory() throws GeneralSecurityException {
        return CertificateFactory.getInstance("X.509", BOUNCY_CASTLE);
    }

    private static CertPathValidator pkixValidator() throws GeneralSecurityException {
        return CertPathValidator.getInstance("PKIX", BOUNCY_CASTLE);
    }

    private static List<TrustedRootResource> trustedRootResources() {
        return List.of(
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv4.crt",
                        "f0c15afd258fb674e7a96e1a50ff873149364b9ec70d4d93c7a9f1eb6060d020"
                ),
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv5.crt",
                        "caa53fc6091c6951887c976e378f6ef89aa6377c55d97b6475422b71ed7e9b17"
                ),
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv6.crt",
                        "3bdb9b509352f1d3d71c2bf64d9a38a4e6cebda27809d77f7ac476cbde6e314a"
                ),
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv7.crt",
                        "5657e70580eb678983f3ed7dfce091d84cae6549389a47fccda8d0e4dc2cf576"
                ),
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv10.crt",
                        "6e0bff069a26994c15de2c4888cc54af84882e5495b7fbf66be9ccffec7489f6"
                ),
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv11.crt",
                        "1406710058180fa4081aab3f246f1702429c552a11fa3143b84c88cb3ab8e5e7"
                ),
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv12.crt",
                        "d8478e37ce19c690cf657381e68fe600e4e1a042536830f06847e03e554c4b01"
                ),
                new TrustedRootResource(
                        "/trust/icpbrasil/ICP-Brasilv13.crt",
                        "2b07d0bc02c4a6e0478ed22d0d99e8f97e1827b269097696a7feb6aad30c3ac8"
                )
        );
    }

    private record TrustedRootResource(String resource, String sha256) {
    }
}
