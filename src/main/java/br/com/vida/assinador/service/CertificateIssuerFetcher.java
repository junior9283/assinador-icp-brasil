package br.com.vida.assinador.service;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;

@FunctionalInterface
interface CertificateIssuerFetcher {
    List<X509Certificate> fetch(URI uri) throws IOException, GeneralSecurityException, InterruptedException;
}
