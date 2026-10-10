package br.com.vida.assinador.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.Optional;

public final class UiDialogs {
    private UiDialogs() {
    }

    public static Optional<char[]> password(String title, String message) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(message);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        PasswordField field = new PasswordField();
        field.setPromptText("Senha");
        dialog.getDialogPane().setContent(field);
        dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText().toCharArray() : null);
        return dialog.showAndWait();
    }

    public static Optional<String> text(String title, String message, String initialValue) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(message);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField field = new TextField(initialValue == null ? "" : initialValue);
        dialog.getDialogPane().setContent(field);
        dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText() : null);
        return dialog.showAndWait();
    }

    /** Mensagem da exceção, ou {@code fallback} quando ela não traz texto útil. */
    public static String message(Throwable error, String fallback) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    public static void error(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public static void info(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public static boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
}
