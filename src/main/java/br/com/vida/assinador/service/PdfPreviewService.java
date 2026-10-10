package br.com.vida.assinador.service;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Mantém aberto o PDF da última pré-visualização, para trocar de página sem reprocessar o arquivo.
 * A interface usa uma única thread para renderizar e fechar; o synchronized cobre o fechamento ao sair do app.
 */
public final class PdfPreviewService implements AutoCloseable {
    /** Escala do zoom 100%: tamanho real da página numa tela de 96 dpi (pixels por ponto do PDF). */
    public static final double ACTUAL_SIZE_SCALE = 96.0 / 72.0;
    // Teto do lado maior da imagem: evita bitmaps de centenas de MB em plantas e páginas grandes com zoom alto.
    private static final double MAX_SIDE_PIXELS = 6000;

    private OpenPdf open;

    /**
     * Renderiza a página na maior escala (pixels por ponto) que não passa de {@code maxScale} nem de
     * {@code maxWidth x maxHeight} pixels, e devolve também o total de páginas.
     * A imagem tem fundo transparente: quem a exibe desenha a folha branca atrás dela (classe CSS {@code page-sheet}).
     */
    public synchronized PdfPreview render(Path pdf, int pageNumber, double maxScale, double maxWidth, double maxHeight) {
        try {
            OpenPdf current = open(pdf);
            PDRectangle box = SignaturePlacementService.cropBox(current.document(), pageNumber);
            double fit = Math.min(maxWidth / box.getWidth(), maxHeight / box.getHeight());
            double cap = MAX_SIDE_PIXELS / Math.max(box.getWidth(), box.getHeight());
            float scale = (float) Math.min(maxScale, Math.min(fit, cap));
            // ARGB vai direto para a imagem JavaFX, sem cópia intermediária (relevante com zoom alto).
            BufferedImage image = current.renderer().renderImage(pageNumber - 1, scale, ImageType.ARGB);
            return new PdfPreview(SwingFXUtils.toFXImage(image, null), current.document().getNumberOfPages(), scale);
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível gerar a pré-visualização do PDF.", e);
        }
    }

    /** Fecha o PDF aberto e libera o arquivo no disco. */
    @Override
    public synchronized void close() {
        if (open == null) {
            return;
        }
        try {
            open.document().close();
        } catch (IOException ignored) {
            // O documento só era lido; não há o que salvar nem desfazer.
        }
        open = null;
    }

    private OpenPdf open(Path pdf) throws IOException {
        if (open == null || !open.path().equals(pdf)) {
            close();
            PDDocument document = Loader.loadPDF(pdf.toFile());
            PDFRenderer renderer = new PDFRenderer(document);
            // Imagens do PDF são decodificadas só na resolução necessária (miniaturas e zoom baixo ficam mais leves).
            renderer.setSubsamplingAllowed(true);
            open = new OpenPdf(pdf, document, renderer);
        }
        return open;
    }

    private record OpenPdf(Path path, PDDocument document, PDFRenderer renderer) {
    }

    /** {@code image} com fundo transparente; {@code scale} em pixels por ponto do PDF. */
    public record PdfPreview(Image image, int pageCount, double scale) {
    }
}
