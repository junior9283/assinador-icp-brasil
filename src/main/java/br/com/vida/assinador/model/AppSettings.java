package br.com.vida.assinador.model;

public record AppSettings(boolean darkMode, BatchCorner batchCorner) {
    public static AppSettings defaults() {
        return new AppSettings(false, BatchCorner.INFERIOR_DIREITO);
    }
}
