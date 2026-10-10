package br.com.vida.assinador.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureBackgroundRendererTest {
    @Test
    void createsLightOpaqueGradientAcrossEntireStamp() {
        BufferedImage foreground = new BufferedImage(180, 40, BufferedImage.TYPE_INT_ARGB);

        BufferedImage result = SignatureBackgroundRenderer.apply(foreground);

        assertEquals(180, result.getWidth());
        assertEquals(40, result.getHeight());

        Set<Integer> colors = new HashSet<>();
        long totalBrightness = 0;
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int argb = result.getRGB(x, y);
                assertEquals(255, (argb >>> 24) & 0xff);
                colors.add(argb & 0x00ffffff);
                totalBrightness += ((argb >>> 16) & 0xff) + ((argb >>> 8) & 0xff) + (argb & 0xff);
            }
        }

        double averageChannelBrightness = totalBrightness / (double) (result.getWidth() * result.getHeight() * 3);
        assertTrue(colors.size() > 20, "O fundo deve ter variação suficiente para formar o degradê.");
        assertTrue(averageChannelBrightness > 200, "O fundo deve permanecer em tom claro.");
    }
}
