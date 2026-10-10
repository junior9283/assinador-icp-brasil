package br.com.vida.assinador.service;

import eu.europa.esig.dss.enumerations.MimeTypeEnum;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class SignatureLogoRenderer {
    private static final String LOGO_RESOURCE = "/images/icp-brasil.png";
    // O recurso é fixo: decodifica/recorta/codifica uma única vez por execução (relevante no lote).
    private static DSSDocument document;

    private SignatureLogoRenderer() {
    }

    static synchronized DSSDocument document() {
        if (document == null) {
            byte[] png = toPng(cropTransparentMargins(loadLogo()));
            document = new InMemoryDocument(png, "icp-brasil.png", MimeTypeEnum.PNG);
        }
        return document;
    }

    private static BufferedImage loadLogo() {
        try (InputStream input = SignatureLogoRenderer.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("A imagem ICP-Brasil do carimbo não foi encontrada.");
            }
            BufferedImage logo = ImageIO.read(input);
            if (logo == null) {
                throw new IllegalStateException("A imagem ICP-Brasil do carimbo é inválida.");
            }
            return logo;
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível carregar a imagem ICP-Brasil do carimbo.", e);
        }
    }

    private static BufferedImage cropTransparentMargins(BufferedImage source) {
        int minX = source.getWidth();
        int minY = source.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if (source.getRGB(x, y) >>> 24 == 0) {
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        if (maxX < minX || maxY < minY) {
            throw new IllegalStateException("A imagem ICP-Brasil do carimbo está totalmente transparente.");
        }
        return source.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("Não foi possível codificar a imagem do carimbo em PNG.");
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível montar a imagem do carimbo ICP-Brasil.", e);
        }
    }
}
