package br.com.vida.assinador.service;

import br.com.vida.assinador.model.CertificateInfo;

import java.security.KeyStore;

/**
 * A1 já decifrado do cofre e validado pelo {@link CertificateRepository}: a chave privada que passou na validação
 * e sua cadeia. Não carrega a senha, que fica restrita ao repositório.
 */
public record SigningCredential(CertificateInfo info, String keyAlias, KeyStore.PrivateKeyEntry privateKey) {
}
