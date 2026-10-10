package br.com.vida.assinador.service;

import br.com.vida.assinador.model.CertificateInfo;
import br.com.vida.assinador.model.SignaturePlacement;
import br.com.vida.assinador.model.SigningResult;
import br.com.vida.assinador.model.TsaConfig;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.ImageScaling;
import eu.europa.esig.dss.enumerations.SignerTextPosition;
import eu.europa.esig.dss.enumerations.TextWrapping;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.Policy;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.pades.DSSJavaFont;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.SignatureFieldParameters;
import eu.europa.esig.dss.pades.SignatureImageParameters;
import eu.europa.esig.dss.pades.SignatureImageTextParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.token.AbstractSignatureTokenConnection;
import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import eu.europa.esig.dss.token.KSPrivateKeyEntry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class PdfSigningService {
    private static final DateTimeFormatter STAMP_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z");
    // Fundo transparente na imagem e no texto para não cobrir o degradê do GradientPdfBoxVisibleSignatureDrawer.
    private static final Color TRANSPARENT = new Color(255, 255, 255, 0);
    private final Function<TsaConfig, TSPSource> tspSourceFactory;

    public PdfSigningService() {
        this(PdfSigningService::createOnlineTspSource);
    }

    PdfSigningService(Function<TsaConfig, TSPSource> tspSourceFactory) {
        this.tspSourceFactory = Objects.requireNonNull(tspSourceFactory);
    }

    /**
     * Prepara a assinatura com o A1 já validado e o serviço PAdES (e a TSA) uma única vez, para vários PDFs.
     * Falhas na configuração (ex.: TSA inválida) viram {@link IllegalArgumentException} com mensagem pronta.
     */
    public Session open(SigningCredential credential, TsaConfig tsa) {
        try {
            boolean withTimestamp = tsa != null && tsa.isUsable();
            IcpBrasilPolicy policy = withTimestamp ? IcpBrasilPolicy.AD_RT : IcpBrasilPolicy.AD_RB;
            PAdESService service = createService(withTimestamp ? tsa : null);
            DSSPrivateKeyEntry privateKey = new KSPrivateKeyEntry(credential.keyAlias(), credential.privateKey());
            return new Session(privateKey, credential.info(), policy, service);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(userMessage(e), e);
        }
    }

    public static final class Session {
        private final PrivateKeyToken token;
        private final DSSPrivateKeyEntry privateKey;
        private final CertificateInfo certificateInfo;
        private final IcpBrasilPolicy policy;
        private final Policy dssPolicy;
        private final PAdESService service;

        private Session(
                DSSPrivateKeyEntry privateKey,
                CertificateInfo certificateInfo,
                IcpBrasilPolicy policy,
                PAdESService service
        ) {
            this.token = new PrivateKeyToken(privateKey);
            this.privateKey = privateKey;
            this.certificateInfo = certificateInfo;
            this.policy = policy;
            this.dssPolicy = policy.toDssPolicy();
            this.service = service;
        }

        /**
         * Assina e salva o resultado na mesma pasta do PDF original.
         * O {@code placement} recebe o PDF já aberto (aberto uma única vez antes da assinatura).
         */
        public SigningResult sign(Path input, Function<PDDocument, SignaturePlacement> placement) {
            Path output = OutputNamingService.nextAvailable(input);
            Path temp = output.resolveSibling(output.getFileName() + "." + UUID.randomUUID() + ".tmp");
            boolean outputCreated = false;

            try {
                int originalSignatureCount;
                SignaturePlacement resolvedPlacement;
                try (PDDocument document = loadPdf(input)) {
                    originalSignatureCount = document.getSignatureDictionaries().size();
                    resolvedPlacement = placement.apply(document);
                }
                PAdESSignatureParameters parameters = createParameters(resolvedPlacement);
                DSSDocument source = new FileDocument(input.toFile());
                ToBeSigned dataToSign = service.getDataToSign(source, parameters);
                SignatureValue signatureValue = token.sign(dataToSign, parameters.getDigestAlgorithm(), privateKey);
                DSSDocument signed = service.signDocument(source, parameters, signatureValue);

                writeAtomically(signed, temp, output);
                outputCreated = true;
                verifySavedSignature(output, originalSignatureCount);
                return SigningResult.success(input, output);
            } catch (Exception e) {
                deleteQuietly(temp);
                if (outputCreated) {
                    deleteQuietly(output);
                }
                return SigningResult.failure(input, userMessage(e));
            }
        }

        private PAdESSignatureParameters createParameters(SignaturePlacement placement) {
            PAdESSignatureParameters parameters = new PAdESSignatureParameters();
            parameters.setSignatureLevel(policy.signatureLevel());
            parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
            parameters.setSigningCertificate(privateKey.getCertificate());
            parameters.setCertificateChain(privateKey.getCertificateChain());
            parameters.setSignerName(certificateInfo.displayName());
            parameters.setReason("Assinatura digital ICP-Brasil");
            parameters.setAppName("ASSINADOR");
            parameters.bLevel().setSignaturePolicy(dssPolicy);
            parameters.setImageParameters(visibleSignature(placement));
            return parameters;
        }

        private SignatureImageParameters visibleSignature(SignaturePlacement placement) {
            SignatureFieldParameters field = new SignatureFieldParameters();
            field.setPage(placement.page());
            field.setOriginX(placement.x());
            field.setOriginY(placement.y());
            field.setWidth(placement.width());
            field.setHeight(placement.height());

            SignatureImageParameters image = new SignatureImageParameters();
            image.setFieldParameters(field);
            image.setImage(SignatureLogoRenderer.document());
            image.setImageScaling(ImageScaling.ZOOM_AND_CENTER);
            image.setLegacyDPIHandling(false);
            image.setBackgroundColor(TRANSPARENT);
            image.setTextParameters(signatureText());
            return image;
        }

        private SignatureImageTextParameters signatureText() {
            SignatureImageTextParameters text = new SignatureImageTextParameters();
            text.setText(stampText());
            text.setFont(new DSSJavaFont("SansSerif", Font.PLAIN, 7));
            text.setSignerTextPosition(SignerTextPosition.RIGHT);
            text.setTextWrapping(TextWrapping.FILL_BOX_AND_LINEBREAK);
            text.setPadding(4f);
            text.setBackgroundColor(TRANSPARENT);
            return text;
        }

        private String stampText() {
            String date = ZonedDateTime.now(ZoneId.systemDefault()).format(STAMP_DATE);
            return "Assinado digitalmente por " + certificateInfo.displayName()
                    + "\nICP-Brasil " + policy.label()
                    + "\nData da assinatura: " + date;
        }
    }

    /** Token DSS mínimo sobre a chave privada que o {@link CertificateRepository} já extraiu e validou. */
    private static final class PrivateKeyToken extends AbstractSignatureTokenConnection {
        private final DSSPrivateKeyEntry key;

        private PrivateKeyToken(DSSPrivateKeyEntry key) {
            this.key = key;
        }

        @Override
        public List<DSSPrivateKeyEntry> getKeys() {
            return List.of(key);
        }

        @Override
        public void close() {
            // Nada a liberar: o KeyStore e a senha ficam no CertificateRepository.
        }
    }

    private PAdESService createService(TsaConfig usableTsa) {
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        PAdESService service = new PAdESService(verifier);
        service.setPdfObjFactory(new GradientPdfBoxObjectFactory());

        if (usableTsa != null) {
            service.setTspSource(tspSourceFactory.apply(usableTsa));
        }
        return service;
    }

    private static TSPSource createOnlineTspSource(TsaConfig tsa) {
        TimestampDataLoader dataLoader = new TimestampDataLoader();
        configureTsaAuthentication(dataLoader, tsa);
        return new OnlineTSPSource(tsa.url(), dataLoader);
    }

    private static void configureTsaAuthentication(TimestampDataLoader dataLoader, TsaConfig tsa) {
        if (tsa.username().isBlank()) {
            return;
        }

        URI uri = URI.create(tsa.url());
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }

        char[] password = tsa.password().toCharArray();
        try {
            dataLoader.addAuthentication(uri.getHost(), port, uri.getScheme(), tsa.username(), password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Grava num temporário novo (CREATE_NEW não segue links simbólicos nem reaproveita arquivo existente; o nome
     * aleatório evita colidir com sobras de execuções interrompidas) e move para o destino sem sobrescrever.
     * Qualquer falha de escrita vira uma mensagem única e clara.
     */
    private static void writeAtomically(DSSDocument signed, Path temp, Path output) throws IOException {
        try {
            try (OutputStream stream = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW)) {
                signed.writeTo(stream);
            }
            Files.move(temp, output);
        } catch (IOException e) {
            throw new IOException(
                    "Não foi possível gravar o PDF assinado na pasta do arquivo original ("
                            + output.getParent() + "). Verifique se você tem permissão de escrita nela.",
                    e
            );
        }
    }

    private static void verifySavedSignature(Path output, int originalSignatureCount) throws IOException {
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("O PDF assinado não foi gravado corretamente.");
        }
        try (PDDocument document = loadPdf(output)) {
            if (document.getSignatureDictionaries().size() != originalSignatureCount + 1) {
                throw new IOException("O PDF salvo não contém a nova assinatura digital.");
            }
        }
    }

    private static PDDocument loadPdf(Path pdf) throws IOException {
        try {
            return Loader.loadPDF(pdf.toFile());
        } catch (InvalidPasswordException e) {
            throw new IOException("O PDF é protegido por senha e não pode ser assinado neste fluxo.", e);
        } catch (IOException e) {
            throw new IOException("Não foi possível abrir o PDF.", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // O erro original da assinatura é mais relevante que a limpeza do temporário.
        }
    }

    // Leitura (loadPdf) e gravação (writeAtomically) já chegam traduzidas; aqui só sobram os erros do DSS.
    private static String userMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "Não foi possível assinar o PDF.";
        }
        if (message.toLowerCase().contains("encrypted")) {
            return "O PDF é protegido por senha e não pode ser assinado neste fluxo.";
        }
        return message;
    }
}
