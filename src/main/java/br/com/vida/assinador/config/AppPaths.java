package br.com.vida.assinador.config;

import java.nio.file.Path;

public final class AppPaths {
    private AppPaths() {
    }

    public static Path dataDirectory() {
        return Path.of(System.getProperty("user.home"), ".assinador");
    }
}
