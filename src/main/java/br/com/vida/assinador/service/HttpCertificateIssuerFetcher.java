package br.com.vida.assinador.service;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class HttpCertificateIssuerFetcher implements CertificateIssuerFetcher {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();

    private final HttpClient client;

    HttpCertificateIssuerFetcher() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HttpCertificateIssuerFetcher(HttpClient client) {
        this.client = client;
    }

    static boolean supports(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        return "http".equals(normalizedScheme) || "https".equals(normalizedScheme);
    }

    @Override
    public List<X509Certificate> fetch(URI uri)
            throws IOException, GeneralSecurityException, InterruptedException {
        if (!supports(uri)) {
            throw new IOException("A URI do certificado emissor não usa HTTP ou HTTPS válido.");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/pkix-cert, application/pkcs7-mime, application/x-x509-ca-cert, */*")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("O servidor do certificado emissor respondeu HTTP " + response.statusCode() + ".");
            }
            byte[] encoded = readLimited(body);
            return parseCertificates(encoded);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        byte[] encoded = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (encoded.length > MAX_RESPONSE_BYTES) {
            throw new IOException("O certificado emissor excede o tamanho máximo permitido.");
        }
        return encoded;
    }

    private List<X509Certificate> parseCertificates(byte[] encoded) throws GeneralSecurityException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509", BOUNCY_CASTLE);
        Collection<? extends Certificate> parsed;
        try (ByteArrayInputStream input = new ByteArrayInputStream(encoded)) {
            parsed = factory.generateCertificates(input);
        } catch (IOException e) {
            throw new GeneralSecurityException("Não foi possível ler o certificado emissor.", e);
        }

        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : parsed) {
            if (certificate instanceof X509Certificate x509) {
                certificates.add(x509);
            }
        }
        if (certificates.isEmpty()) {
            throw new GeneralSecurityException("A resposta AIA não contém certificado X.509.");
        }
        return List.copyOf(certificates);
    }
}
