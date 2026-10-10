package br.com.vida.assinador.model;

public enum BatchCorner {
    INFERIOR_DIREITO("Inferior direito"),
    INFERIOR_ESQUERDO("Inferior esquerdo"),
    SUPERIOR_DIREITO("Superior direito"),
    SUPERIOR_ESQUERDO("Superior esquerdo");

    private final String label;

    BatchCorner(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
