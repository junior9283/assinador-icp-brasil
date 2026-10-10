package br.com.vida.assinador.service;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.Policy;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public enum IcpBrasilPolicy {
    AD_RB(
            SignatureLevel.PAdES_BASELINE_B,
            "2.16.76.1.7.1.11.1.3",
            "/policies/PA_PAdES_AD_RB_v1_3.der",
            "http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RB_v1_3.der",
            "POLITICA ICP-BRASIL PARA ASSINATURA DIGITAL COM REFERENCIA BASICA NO FORMATO PDF, versão 1.3"
    ),
    AD_RT(
            SignatureLevel.PAdES_BASELINE_T,
            "2.16.76.1.7.1.12.1.3",
            "/policies/PA_PAdES_AD_RT_v1_3.der",
            "http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RT_v1_3.der",
            "POLITICA ICP-BRASIL PARA ASSINATURA DIGITAL COM REFERENCIA DO TEMPO NO FORMATO PDF, versão 1.3"
    );

    private final SignatureLevel signatureLevel;
    private final String oid;
    private final String resource;
    private final String uri;
    private final String description;

    IcpBrasilPolicy(SignatureLevel signatureLevel, String oid, String resource, String uri, String description) {
        this.signatureLevel = signatureLevel;
        this.oid = oid;
        this.resource = resource;
        this.uri = uri;
        this.description = description;
    }

    public SignatureLevel signatureLevel() {
        return signatureLevel;
    }

    /** Rótulo exibido no carimbo, ex.: "AD-RT". */
    public String label() {
        return name().replace('_', '-');
    }

    public Policy toDssPolicy() {
        byte[] bytes = readPolicyBytes();
        try {
            Policy policy = new Policy();
            policy.setId(oid);
            policy.setDescription(description);
            policy.setDigestAlgorithm(DigestAlgorithm.SHA256);
            policy.setDigestValue(MessageDigest.getInstance("SHA-256").digest(bytes));
            policy.setSpuri(uri);
            return policy;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não está disponível na JVM.", e);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] readPolicyBytes() {
        try (InputStream input = IcpBrasilPolicy.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Política ICP-Brasil não encontrada no aplicativo: " + resource);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível ler a política ICP-Brasil.", e);
        }
    }
}
