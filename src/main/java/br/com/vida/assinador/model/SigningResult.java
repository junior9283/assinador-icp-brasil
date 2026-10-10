package br.com.vida.assinador.model;

import java.nio.file.Path;

public record SigningResult(Path input, Path output, boolean success, String message) {
    public static SigningResult success(Path input, Path output) {
        return new SigningResult(input, output, true, "Assinado com sucesso");
    }

    public static SigningResult failure(Path input, String message) {
        return new SigningResult(input, null, false, message);
    }
}
