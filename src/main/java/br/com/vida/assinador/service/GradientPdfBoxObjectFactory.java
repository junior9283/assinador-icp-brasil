package br.com.vida.assinador.service;

import eu.europa.esig.dss.pdf.pdfbox.PdfBoxDefaultObjectFactory;
import eu.europa.esig.dss.pdf.pdfbox.visible.PdfBoxSignatureDrawerFactory;

final class GradientPdfBoxObjectFactory extends PdfBoxDefaultObjectFactory {
    @Override
    protected PdfBoxSignatureDrawerFactory getSignatureDrawerFactory() {
        return parameters -> new GradientPdfBoxVisibleSignatureDrawer();
    }
}
