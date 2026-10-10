package br.com.vida.assinador.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;

final class SignatureBackgroundRenderer {
    private static final double WHITE_WEIGHT = 1.35;
    private static final double LIGHTEN_FACTOR = 0.78;
    // Manchas nas cores predominantes do logo ICP-Brasil (verde, azul e amarelo); centro e sigma normalizados.
    private static final List<Blob> BLOBS = List.of(
            new Blob(new Color(16, 144, 48), 0.08, 0.18, 0.42),
            new Blob(new Color(16, 48, 112), 0.58, 0.08, 0.36),
            new Blob(new Color(240, 208, 16), 0.92, 0.82, 0.48)
    );

    // O fundo só depende do tamanho, que se repete no lote e nas duas chamadas de draw() por PDF.
    private static volatile Background lastBackground;

    private SignatureBackgroundRenderer() {
    }

    static BufferedImage apply(BufferedImage foreground) {
        int width = foreground.getWidth();
        int height = foreground.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] target = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        System.arraycopy(background(width, height), 0, target, 0, target.length);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(foreground, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static int[] background(int width, int height) {
        Background cached = lastBackground;
        if (cached == null || cached.width() != width || cached.height() != height) {
            cached = new Background(width, height, renderBackground(width, height));
            lastBackground = cached;
        }
        return cached.pixels();
    }

    private static int[] renderBackground(int width, int height) {
        // A gaussiana é separável: exp(-(dx²+dy²)/2σ²) = gx(x) · gy(y).
        double[][] weightsX = blobWeights(width, true);
        double[][] weightsY = blobWeights(height, false);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = backgroundRgb(weightsX, weightsY, x, y);
            }
        }
        return pixels;
    }

    private static double[][] blobWeights(int size, boolean horizontal) {
        double scale = Math.max(1, size - 1);
        double[][] weights = new double[BLOBS.size()][size];
        for (int i = 0; i < BLOBS.size(); i++) {
            Blob blob = BLOBS.get(i);
            double center = horizontal ? blob.centerX() : blob.centerY();
            for (int p = 0; p < size; p++) {
                double d = p / scale - center;
                weights[i][p] = Math.exp(-(d * d) / (2.0 * blob.sigma() * blob.sigma()));
            }
        }
        return weights;
    }

    private static int backgroundRgb(double[][] weightsX, double[][] weightsY, int x, int y) {
        double red = 255.0 * WHITE_WEIGHT;
        double green = red;
        double blue = red;
        double totalWeight = WHITE_WEIGHT;

        for (int i = 0; i < BLOBS.size(); i++) {
            double weight = weightsX[i][x] * weightsY[i][y];
            Color color = BLOBS.get(i).color();
            red += color.getRed() * weight;
            green += color.getGreen() * weight;
            blue += color.getBlue() * weight;
            totalWeight += weight;
        }

        return 0xff000000
                | clamp(red / totalWeight) << 16
                | clamp(green / totalWeight) << 8
                | clamp(blue / totalWeight);
    }

    private static Color lighten(Color color) {
        return new Color(
                clamp(color.getRed() + (255 - color.getRed()) * LIGHTEN_FACTOR),
                clamp(color.getGreen() + (255 - color.getGreen()) * LIGHTEN_FACTOR),
                clamp(color.getBlue() + (255 - color.getBlue()) * LIGHTEN_FACTOR)
        );
    }

    private static int clamp(double value) {
        return Math.clamp(Math.round(value), 0, 255);
    }

    private record Background(int width, int height, int[] pixels) {
    }

    // Guarda a cor já clareada em direção ao branco.
    private record Blob(Color color, double centerX, double centerY, double sigma) {
        Blob {
            color = lighten(color);
        }
    }
}
