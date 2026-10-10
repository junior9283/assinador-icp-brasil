package br.com.vida.assinador.model;

import java.time.Instant;

public record CertificateInfo(
        String id,
        String alias,
        String subject,
        String commonName,
        String issuer,
        String serialNumber,
        Instant validFrom,
        Instant validUntil,
        boolean automaticSigning
) {
    public String displayName() {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        if (commonName != null && !commonName.isBlank()) {
            return commonName;
        }
        return subject;
    }

    public CertificateInfo withAlias(String newAlias) {
        return new CertificateInfo(
                id, newAlias, subject, commonName, issuer, serialNumber, validFrom, validUntil, automaticSigning
        );
    }

    public CertificateInfo withAutomaticSigning(boolean enabled) {
        return new CertificateInfo(id, alias, subject, commonName, issuer, serialNumber, validFrom, validUntil, enabled);
    }

    public boolean isExpired(Instant now) {
        return validUntil.isBefore(now);
    }
}
