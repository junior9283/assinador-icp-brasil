package br.com.vida.assinador.service;

import br.com.vida.assinador.model.SignaturePlacement;
import br.com.vida.assinador.model.SigningResult;
import br.com.vida.assinador.model.TsaConfig;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Assina um ou vários PDFs abrindo o certificado uma única vez. */
public final class BatchSigningService {
    private final PdfSigningService signingService;

    public BatchSigningService(PdfSigningService signingService) {
        this.signingService = signingService;
    }

    public List<SigningResult> signAll(
            List<Path> files,
            SigningCredential credential,
            TsaConfig tsa,
            Function<PDDocument, SignaturePlacement> placement,
            ProgressListener listener
    ) {
        PdfSigningService.Session session;
        try {
            session = signingService.open(credential, tsa);
        } catch (RuntimeException e) {
            // Sem serviço de assinatura utilizável nenhum arquivo pode ser assinado.
            return files.stream().map(file -> SigningResult.failure(file, e.getMessage())).toList();
        }

        List<SigningResult> results = new ArrayList<>(files.size());
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            listener.onFileStarted(file, index, files.size());
            SigningResult result = session.sign(file, placement);
            results.add(result);
            listener.onFileCompleted(file, index + 1, files.size(), result);
        }
        return List.copyOf(results);
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
        };

        default void onFileStarted(Path file, int index, int total) {
        }

        default void onFileCompleted(Path file, int completed, int total, SigningResult result) {
        }
    }
}
