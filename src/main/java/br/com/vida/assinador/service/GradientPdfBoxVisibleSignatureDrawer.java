package br.com.vida.assinador.service;

import eu.europa.esig.dss.pdf.AnnotationBox;
import eu.europa.esig.dss.pdf.pdfbox.visible.defaultdrawer.DefaultImageDrawerUtils;
import eu.europa.esig.dss.pdf.pdfbox.visible.defaultdrawer.DefaultPdfBoxVisibleSignatureDrawer;
import eu.europa.esig.dss.pdf.visible.SignatureFieldDimensionAndPosition;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSigProperties;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSignDesigner;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Espelha {@code DefaultPdfBoxVisibleSignatureDrawer.draw()} do DSS 6.5, que não tem hook entre
 * {@code mergeImages} e {@code rotate}; a única diferença é o fundo degradê. Reconferir ao subir {@code dss.version}.
 * O degradê só aparece se imagem e texto do carimbo tiverem fundo transparente (ver {@code PdfSigningService}).
 */
final class GradientPdfBoxVisibleSignatureDrawer extends DefaultPdfBoxVisibleSignatureDrawer {
    @Override
    public void draw() throws IOException {
        SignatureFieldDimensionAndPosition dimension = buildSignatureFieldBox();
        BufferedImage logo = parameters.getImage() == null
                ? null
                : DefaultImageDrawerUtils.toBufferedImage(parameters.getImage());
        BufferedImage text = parameters.getTextParameters() == null || parameters.getTextParameters().isEmpty()
                ? null
                : DefaultImageDrawerUtils.createTextImage(parameters, dimension, getDSSFontMetrics());

        if (logo == null && text == null) {
            throw new IllegalArgumentException("Image or text shall be defined in order to build a visual signature!");
        }

        BufferedImage foreground = DefaultImageDrawerUtils.mergeImages(logo, text, dimension, parameters);
        BufferedImage stamp = SignatureBackgroundRenderer.apply(foreground);
        stamp = DefaultImageDrawerUtils.rotate(stamp, dimension.getGlobalRotation());
        applyVisualSignature(stamp, dimension);
    }

    private void applyVisualSignature(
            BufferedImage stamp,
            SignatureFieldDimensionAndPosition dimension
    ) throws IOException {
        int pageNumber = parameters.getFieldParameters().getPage();
        PDVisibleSignDesigner designer = new PDVisibleSignDesigner(document, stamp, pageNumber);
        PDPage page = getPage();
        AnnotationBox pageBox = getPageAnnotationBox(page);
        AnnotationBox signatureBox = dimension.getAnnotationBox();

        designer.xAxis(signatureBox.getMinX());
        designer.yAxis(pageBox.getHeight() - signatureBox.getMaxY());
        designer.width(signatureBox.getWidth());
        designer.height(signatureBox.getHeight());

        PDVisibleSigProperties properties = new PDVisibleSigProperties();
        properties.visualSignEnabled(true);
        properties.setPdVisibleSignature(designer);
        properties.buildSignature();
        signatureOptions.setVisualSignature(properties);
    }
}
