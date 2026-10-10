package br.com.vida.assinador.ui;

import br.com.vida.assinador.config.AppPaths;
import br.com.vida.assinador.security.VaultService;
import br.com.vida.assinador.service.BatchSigningService;
import br.com.vida.assinador.service.CertificateRepository;
import br.com.vida.assinador.service.PdfPreviewService;
import br.com.vida.assinador.service.PdfSigningService;
import br.com.vida.assinador.service.SettingsRepository;

public final class AppContext implements AutoCloseable {
    private final VaultService vault = new VaultService(AppPaths.dataDirectory());
    private final CertificateRepository certificates = new CertificateRepository(vault);
    private final SettingsRepository settings = new SettingsRepository(vault);
    private final PdfPreviewService preview = new PdfPreviewService();
    private final BatchSigningService batchSigning = new BatchSigningService(new PdfSigningService());

    public VaultService vault() {
        return vault;
    }

    public CertificateRepository certificates() {
        return certificates;
    }

    public SettingsRepository settings() {
        return settings;
    }

    public PdfPreviewService preview() {
        return preview;
    }

    public BatchSigningService batchSigning() {
        return batchSigning;
    }

    @Override
    public void close() {
        preview.close();
        vault.close();
    }
}
