package br.com.vida.assinador.model;

public record SignaturePlacement(int page, float x, float y, float width, float height) {
    public SignaturePlacement {
        if (page < 1) {
            throw new IllegalArgumentException("A página deve ser maior ou igual a 1.");
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A posição e o tamanho do carimbo são inválidos.");
        }
    }
}
