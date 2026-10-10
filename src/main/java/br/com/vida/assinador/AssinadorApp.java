package br.com.vida.assinador;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Theme;
import br.com.vida.assinador.ui.AppContext;
import br.com.vida.assinador.ui.CertificatesPane;
import br.com.vida.assinador.ui.MaximizedSizeFix;
import br.com.vida.assinador.ui.SettingsPane;
import br.com.vida.assinador.ui.SigningPane;
import br.com.vida.assinador.ui.UiDialogs;
import br.com.vida.assinador.ui.VaultUnlocker;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Objects;

public final class AssinadorApp extends Application {
    private static final double WINDOW_WIDTH = 1240;
    private static final double WINDOW_HEIGHT = 800;
    // Nord do AtlantaFX com destaque âmbar; como tema global, vale também para diálogos e listas suspensas.
    private static final Theme LIGHT_THEME = theme("Assinador claro", "/styles/theme-light.css", false);
    private static final Theme DARK_THEME = theme("Assinador escuro", "/styles/theme-dark.css", true);

    private AppContext context;
    private final BorderPane root = new BorderPane();

    @Override
    public void start(Stage stage) {
        context = new AppContext();
        applyTheme(context.settings().loadSettings().darkMode());
        if (!VaultUnlocker.unlock(context.vault())) {
            closeContext();
            Platform.exit();
            return;
        }

        try {
            buildInterface(stage);
        } catch (RuntimeException e) {
            closeContext();
            UiDialogs.error("ASSINADOR", "Não foi possível iniciar o aplicativo.\n" + UiDialogs.message(e, "Falha inesperada."));
            Platform.exit();
        }
    }

    private void buildInterface(Stage stage) {
        root.setTop(topBar(
                new SigningPane(context),
                new CertificatesPane(context.certificates()),
                new SettingsPane(context.settings(), AssinadorApp::applyTheme)
        ));

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(resource("/styles/app.css"));

        stage.setTitle("ASSINADOR — ICP-Brasil");
        stage.setMinWidth(980);
        stage.setMinHeight(660);
        stage.setScene(scene);
        MaximizedSizeFix.install(stage);
        stage.setOnShown(event -> fitToScreen(stage));
        stage.show();
    }

    /** Em telas pequenas (ex.: 1366x768) o tamanho inicial passaria da área útil e cortaria a parte de baixo. */
    private static void fitToScreen(Stage stage) {
        Rectangle2D area = Screen
                .getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                .stream().findFirst().orElseGet(Screen::getPrimary).getVisualBounds();
        stage.setWidth(Math.min(stage.getWidth(), area.getWidth()));
        stage.setHeight(Math.min(stage.getHeight(), area.getHeight()));
        stage.setX(Math.clamp(stage.getX(), area.getMinX(), area.getMaxX() - stage.getWidth()));
        stage.setY(Math.clamp(stage.getY(), area.getMinY(), area.getMaxY() - stage.getHeight()));
    }

    private HBox topBar(SigningPane signing, CertificatesPane certificates, SettingsPane settings) {
        Label title = new Label("Assinador");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("ICP-Brasil");
        subtitle.getStyleClass().add(Styles.TEXT_MUTED);

        ToggleGroup group = new ToggleGroup();
        ToggleButton sign = navigationButton("Assinar", group, signing, signing::refresh);
        HBox navigation = new HBox(4,
                sign,
                navigationButton("Certificados", group, certificates, certificates::refresh),
                navigationButton("Configurações", group, settings, settings::refresh));
        HBox.setMargin(navigation, new Insets(0, 0, 0, 20));
        sign.fire();

        Label privacy = new Label("Processamento local");
        privacy.getStyleClass().add("privacy-badge");

        HBox bar = new HBox(title, subtitle, navigation, new Spacer(), privacy);
        bar.getStyleClass().add("top-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private ToggleButton navigationButton(String text, ToggleGroup group, Node page, Runnable refresh) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.getStyleClass().add("nav-button");
        button.setOnAction(event -> {
            // Clicar de novo no botão ativo não deve deixá-lo desmarcado.
            button.setSelected(true);
            refresh.run();
            root.setCenter(page);
        });
        return button;
    }

    private static void applyTheme(boolean darkMode) {
        Application.setUserAgentStylesheet((darkMode ? DARK_THEME : LIGHT_THEME).getUserAgentStylesheet());
    }

    private static Theme theme(String name, String stylesheet, boolean dark) {
        return Theme.of(name, resource(stylesheet), dark);
    }

    private static String resource(String path) {
        return Objects.requireNonNull(
                AssinadorApp.class.getResource(path),
                "Folha de estilo do aplicativo não encontrada: " + path
        ).toExternalForm();
    }

    private void closeContext() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    @Override
    public void stop() {
        closeContext();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
