package br.com.vida.assinador.support;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.nio.file.Path;

public final class TestPdfs {
    private TestPdfs() {
    }

    /** Cria um PDF A4 em branco com {@code pageCount} páginas. */
    public static Path create(Path path, int pageCount) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pageCount; page++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(path.toFile());
        }
        return path;
    }

    public static int signatureCount(Path pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return document.getSignatureDictionaries().size();
        }
    }
}
