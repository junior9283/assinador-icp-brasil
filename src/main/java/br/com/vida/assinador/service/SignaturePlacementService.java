package br.com.vida.assinador.service;

import br.com.vida.assinador.model.BatchCorner;
import br.com.vida.assinador.model.SignaturePlacement;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

public final class SignaturePlacementService {
    public static final float DEFAULT_WIDTH = 180f;
    public static final float DEFAULT_HEIGHT = 40f;
    private static final float DEFAULT_MARGIN = 18f;

    private SignaturePlacementService() {
    }

    /** Carimbo no canto escolhido da última página (modo lote). */
    public static SignaturePlacement forLastPage(PDDocument document, BatchCorner corner) {
        int lastPage = document.getNumberOfPages();
        PDRectangle box = cropBox(document, lastPage);
        return forCorner(lastPage, box.getWidth(), box.getHeight(), corner);
    }

    public static PDRectangle cropBox(PDDocument document, int page) {
        if (page < 1 || page > document.getNumberOfPages()) {
            throw new IllegalArgumentException("Página fora do intervalo do documento.");
        }
        return document.getPage(page - 1).getCropBox();
    }

    public static SignaturePlacement forCorner(
            int page,
            float pageWidth,
            float pageHeight,
            BatchCorner corner
    ) {
        float x = switch (corner) {
            case INFERIOR_DIREITO, SUPERIOR_DIREITO ->
                    Math.max(DEFAULT_MARGIN, pageWidth - DEFAULT_WIDTH - DEFAULT_MARGIN);
            case INFERIOR_ESQUERDO, SUPERIOR_ESQUERDO -> DEFAULT_MARGIN;
        };
        float y = switch (corner) {
            case INFERIOR_DIREITO, INFERIOR_ESQUERDO ->
                    Math.max(DEFAULT_MARGIN, pageHeight - DEFAULT_HEIGHT - DEFAULT_MARGIN);
            case SUPERIOR_DIREITO, SUPERIOR_ESQUERDO -> DEFAULT_MARGIN;
        };
        return new SignaturePlacement(page, x, y, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }
}
