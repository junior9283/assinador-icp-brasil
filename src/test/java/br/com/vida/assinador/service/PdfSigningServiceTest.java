package br.com.vida.assinador.service;

import br.com.vida.assinador.model.SignaturePlacement;
import br.com.vida.assinador.model.SigningResult;
import br.com.vida.assinador.model.TsaConfig;
import br.com.vida.assinador.support.TestCertificateChain;
import br.com.vida.assinador.support.TestPdfs;
import eu.europa.esig.dss.spi.x509.tsp.KeyEntityTSPSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.esf.SignaturePolicyIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfSigningServiceTest {
    private static final String PASSWORD = "senha-teste";
    private static final String AD_RB_OID = "2.16.76.1.7.1.11.1.3";
    private static final String AD_RT_OID = "2.16.76.1.7.1.12.1.3";
    private static final String TEST_TSA_POLICY_OID = "1.3.6.1.4.1.55555.1";

    @TempDir
    Path tempDir;

    @Test
    void signsPdfWithVisiblePadesSignatureAndIcpBrasilPolicy() throws Exception {
        Path input = createPdf("entrada.pdf");
        SignaturePlacement placement = new SignaturePlacement(1, 50, 60, 180, 60);

        SigningResult result = sign(new PdfSigningService(), input, placement, null);

        assertTrue(result.success(), result.message());
        assertTrue(Files.isRegularFile(result.output()));
        assertEquals(tempDir.resolve("entrada_assinado.pdf"), result.output(), "Deve salvar na pasta do original.");

        try (PDDocument signed = Loader.loadPDF(result.output().toFile())) {
            assertEquals(1, signed.getSignatureDictionaries().size());
            PDSignature signature = signed.getSignatureDictionaries().getFirst();
            assertEquals("ETSI.CAdES.detached", signature.getSubFilter());
            assertEquals(AD_RB_OID, signaturePolicyOid(signature, result.output()));

            var acroForm = signed.getDocumentCatalog().getAcroForm();
            assertNotNull(acroForm);
            var signatureField = acroForm.getFieldTree().iterator().next();
            PDRectangle rectangle = signatureField.getWidgets().getFirst().getRectangle();
            assertEquals(placement.x(), rectangle.getLowerLeftX(), 0.5f);
            assertEquals(placement.width(), rectangle.getWidth(), 0.5f);
            assertEquals(placement.height(), rectangle.getHeight(), 0.5f);
            assertEquals(
                    PDRectangle.A4.getHeight() - placement.y() - placement.height(),
                    rectangle.getLowerLeftY(),
                    0.5f
            );
        }
    }

    @Test
    void reportsClearMessageWhenOriginalFolderIsNotWritable() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path folder = Files.createDirectory(tempDir.resolve("somente-leitura"));
        Path input = TestPdfs.create(folder.resolve("entrada.pdf"), 1);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(folder), "Usuário com privilégio ignora a permissão da pasta.");

            SigningResult result = sign(new PdfSigningService(), input, new SignaturePlacement(1, 50, 60, 180, 60), null);

            assertFalse(result.success());
            assertTrue(result.message().contains("permissão de escrita"), result.message());
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void doesNotWriteThroughSymlinkPlantedAtTemporaryName() throws Exception {
        Path input = createPdf("entrada.pdf");
        Path victim = Files.writeString(tempDir.resolve("vitima.txt"), "conteudo original");
        Files.createSymbolicLink(tempDir.resolve("entrada_assinado.pdf.tmp"), victim);

        SigningResult result = sign(new PdfSigningService(), input, new SignaturePlacement(1, 50, 60, 180, 60), null);

        assertTrue(result.success(), result.message());
        assertEquals("conteudo original", Files.readString(victim));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.filter(f -> f.toString().endsWith(".tmp")).count(), "Só o link plantado deve restar.");
        }
    }

    @Test
    void preservesExistingSignatureWhenSigningAgain() throws Exception {
        Path input = createPdf("original.pdf");
        PdfSigningService service = new PdfSigningService();
        SignaturePlacement firstPlacement = new SignaturePlacement(1, 40, 40, 180, 60);
        SignaturePlacement secondPlacement = new SignaturePlacement(1, 40, 130, 180, 60);

        SigningResult first = sign(service, input, firstPlacement, null);
        assertTrue(first.success(), first.message());

        SigningResult second = sign(service, first.output(), secondPlacement, null);

        assertTrue(second.success(), second.message());
        assertEquals(2, TestPdfs.signatureCount(second.output()));
    }

    @Test
    void signsPdfWithTimestampAndIcpBrasilAdRtPolicy() throws Exception {
        TestTsa testTsa = createTsa();
        KeyEntityTSPSource tspSource = new KeyEntityTSPSource(
                testTsa.privateKey(),
                testTsa.certificate(),
                List.of(testTsa.certificate())
        );
        tspSource.setTsaPolicy(TEST_TSA_POLICY_OID);

        PdfSigningService service = new PdfSigningService(ignored -> tspSource);
        Path input = createPdf("entrada-ad-rt.pdf");
        TsaConfig tsa = new TsaConfig(true, "https://tsa.test", "", "");

        SigningResult result = sign(
                service,
                input,
                new SignaturePlacement(1, 50, 60, 180, 60),
                tsa
        );

        assertTrue(result.success(), result.message());
        try (PDDocument signed = Loader.loadPDF(result.output().toFile())) {
            PDSignature signature = signed.getSignatureDictionaries().getFirst();
            assertEquals(AD_RT_OID, signaturePolicyOid(signature, result.output()));
            assertTrue(hasSignatureTimestamp(signature, result.output()));
        }
    }

    private Path createPdf(String fileName) throws Exception {
        return TestPdfs.create(tempDir.resolve(fileName), 1);
    }

    private SigningCredential credential() throws Exception {
        return TestCertificateChain.create(KeyUsage.digitalSignature).credential();
    }

    private TestTsa createTsa() throws Exception {
        KeyPair keyPair = createRsaKeyPair();
        X500Name name = new X500Name("CN=ASSINADOR TSA Teste,O=VIDA,C=BR");
        X509Certificate certificate = createTsaCertificate(keyPair, name);
        return new TestTsa(keyPair.getPrivate(), certificate);
    }

    private KeyPair createRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private X509Certificate createTsaCertificate(KeyPair keyPair, X500Name name) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(System.nanoTime()).abs(),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(30, ChronoUnit.DAYS)),
                name,
                keyPair.getPublic()
        );
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(
                Extension.extendedKeyUsage,
                true,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping)
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private SigningResult sign(
            PdfSigningService service,
            Path input,
            SignaturePlacement placement,
            TsaConfig tsa
    ) throws Exception {
        return service.open(credential(), tsa).sign(input, document -> placement);
    }

    private char[] password() {
        return PASSWORD.toCharArray();
    }

    private String signaturePolicyOid(PDSignature signature, Path signedPdf) throws Exception {
        SignerInformation signer = signerInformation(signature, signedPdf);
        var attribute = signer.getSignedAttributes().get(PKCSObjectIdentifiers.id_aa_ets_sigPolicyId);
        assertNotNull(attribute, "A assinatura deve conter o atributo de política explícita.");
        SignaturePolicyIdentifier policy = SignaturePolicyIdentifier.getInstance(
                attribute.getAttrValues().getObjectAt(0)
        );
        return policy.getSignaturePolicyId().getSigPolicyId().getId();
    }

    private boolean hasSignatureTimestamp(PDSignature signature, Path signedPdf) throws Exception {
        SignerInformation signer = signerInformation(signature, signedPdf);
        return signer.getUnsignedAttributes() != null
                && signer.getUnsignedAttributes().get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken) != null;
    }

    private SignerInformation signerInformation(PDSignature signature, Path signedPdf) throws Exception {
        byte[] pdf = Files.readAllBytes(signedPdf);
        byte[] cmsContents = signature.getContents(pdf);
        CMSSignedData cms;
        try (ASN1InputStream input = new ASN1InputStream(cmsContents)) {
            cms = new CMSSignedData(input.readObject().getEncoded());
        }
        return cms.getSignerInfos().getSigners().iterator().next();
    }

    private record TestTsa(PrivateKey privateKey, X509Certificate certificate) {
    }
}
