package br.com.vida.assinador.ui;

import javafx.animation.PauseTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Contorna um defeito do JavaFX no Linux (GTK): um diálogo modal trava a janela dona no último tamanho "pedido"
 * pelo app, e uma janela maximizada encolhia para o tamanho inicial sem deixar de estar maximizada. Ao maximizar,
 * pedimos de novo o tamanho atual. Só no Linux: em outros sistemas isso poderia desmaximizar a janela.
 */
public final class MaximizedSizeFix implements InvalidationListener {
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase().contains("linux");

    private final Stage stage;
    // Espera o gerenciador de janelas terminar de maximizar antes de pedir o tamanho.
    private final PauseTransition settle = new PauseTransition(Duration.millis(150));
    private double syncedWidth;
    private double syncedHeight;
    private boolean syncing;

    private MaximizedSizeFix(Stage stage) {
        this.stage = stage;
        settle.setOnFinished(event -> syncRequestedSize());
        stage.maximizedProperty().addListener(this);
        stage.widthProperty().addListener(this);
        stage.heightProperty().addListener(this);
    }

    public static void install(Stage stage) {
        if (LINUX) {
            new MaximizedSizeFix(stage);
        }
    }

    @Override
    public void invalidated(Observable observable) {
        if (syncing) {
            return;
        }
        if (stage.isMaximized()) {
            settle.playFromStart();
        } else if (observable == stage.maximizedProperty()) {
            // Desmaximizada, o JavaFX volta a guardar o tamanho normal: a próxima maximização sincroniza de novo.
            syncedWidth = 0;
            syncedHeight = 0;
        }
    }

    private void syncRequestedSize() {
        double width = stage.getWidth();
        double height = stage.getHeight();
        if (!stage.isMaximized() || (width == syncedWidth && height == syncedHeight)) {
            return;
        }
        syncedWidth = width;
        syncedHeight = height;
        // O JavaFX ignora um valor igual ao atual; o desvio de 1 px força o pedido do tamanho atual.
        syncing = true;
        stage.setWidth(width + 1);
        stage.setWidth(width);
        stage.setHeight(height + 1);
        stage.setHeight(height);
        syncing = false;
    }
}
