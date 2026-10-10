package br.com.vida.assinador.support;

import br.com.vida.assinador.model.CertificateInfo;
import br.com.vida.assinador.service.SigningCredential;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public record TestCertificateChain(
        X509Certificate root,
        X509Certificate leaf,
        PrivateKey leafPrivateKey
) {
    private static final String KEY_ALIAS = "assinador-test";

    public static TestCertificateChain create(int leafKeyUsage) throws Exception {
        KeyPair rootKeys = rsaKeyPair();
        X500Name rootName = new X500Name("CN=ASSINADOR Test Root,O=VIDA,C=BR");
        X509Certificate root = certificate(
                rootName,
                rootName,
                rootKeys,
                rootKeys.getPrivate(),
                true,
                KeyUsage.keyCertSign | KeyUsage.cRLSign,
                365,
                null
        );

        KeyPair leafKeys = rsaKeyPair();
        X500Name leafName = new X500Name("CN=ASSINADOR Teste,O=VIDA,C=BR");
        X509Certificate leaf = certificate(
                leafName,
                rootName,
                leafKeys,
                rootKeys.getPrivate(),
                false,
                leafKeyUsage,
                30,
                null
        );
        return new TestCertificateChain(root, leaf, leafKeys.getPrivate());
    }

    public static IntermediateCertificateChain createWithIntermediate(int leafKeyUsage, URI issuerUri) throws Exception {
        KeyPair rootKeys = rsaKeyPair();
        X500Name rootName = new X500Name("CN=ASSINADOR Test Root,O=VIDA,C=BR");
        X509Certificate root = certificate(
                rootName,
                rootName,
                rootKeys,
                rootKeys.getPrivate(),
                true,
                KeyUsage.keyCertSign | KeyUsage.cRLSign,
                365,
                null
        );

        KeyPair intermediateKeys = rsaKeyPair();
        X500Name intermediateName = new X500Name("CN=ASSINADOR Test Intermediate,O=VIDA,C=BR");
        X509Certificate intermediate = certificate(
                intermediateName,
                rootName,
                intermediateKeys,
                rootKeys.getPrivate(),
                true,
                KeyUsage.keyCertSign | KeyUsage.cRLSign,
                180,
                null
        );

        KeyPair leafKeys = rsaKeyPair();
        X500Name leafName = new X500Name("CN=ASSINADOR Teste,O=VIDA,C=BR");
        X509Certificate leaf = certificate(
                leafName,
                intermediateName,
                leafKeys,
                intermediateKeys.getPrivate(),
                false,
                leafKeyUsage,
                30,
                issuerUri
        );
        return new IntermediateCertificateChain(root, intermediate, leaf, leafKeys.getPrivate());
    }

    public byte[] pkcs12(char[] password) throws Exception {
        return pkcs12(leafPrivateKey, password, leaf, root);
    }

    /** Credencial pronta para assinar, como a que o CertificateRepository entrega após validar o A1. */
    public SigningCredential credential() {
        CertificateInfo info = new CertificateInfo(
                "test",
                "",
                leaf.getSubjectX500Principal().getName(),
                "ASSINADOR Teste",
                leaf.getIssuerX500Principal().getName(),
                leaf.getSerialNumber().toString(16),
                leaf.getNotBefore().toInstant(),
                leaf.getNotAfter().toInstant(),
                false
        );
        return new SigningCredential(
                info,
                KEY_ALIAS,
                new KeyStore.PrivateKeyEntry(leafPrivateKey, new Certificate[]{leaf, root})
        );
    }

    private static byte[] pkcs12(PrivateKey key, char[] password, Certificate... chain) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry(KEY_ALIAS, key, password, chain);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            store.store(output, password);
            return output.toByteArray();
        }
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(
            X500Name subject,
            X500Name issuer,
            KeyPair subjectKeys,
            PrivateKey issuerPrivateKey,
            boolean certificateAuthority,
            int keyUsage,
            long validDays,
            URI issuerUri
    ) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(System.nanoTime()).abs(),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(validDays, ChronoUnit.DAYS)),
                subject,
                subjectKeys.getPublic()
        );
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(certificateAuthority));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
        if (issuerUri != null) {
            AccessDescription caIssuer = new AccessDescription(
                    AccessDescription.id_ad_caIssuers,
                    new GeneralName(GeneralName.uniformResourceIdentifier, issuerUri.toString())
            );
            builder.addExtension(
                    Extension.authorityInfoAccess,
                    false,
                    new AuthorityInformationAccess(caIssuer)
            );
        }
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerPrivateKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    public record IntermediateCertificateChain(
            X509Certificate root,
            X509Certificate intermediate,
            X509Certificate leaf,
            PrivateKey leafPrivateKey
    ) {
        public byte[] leafOnlyPkcs12(char[] password) throws Exception {
            return pkcs12(leafPrivateKey, password, leaf);
        }
    }
}
