package br.com.vida.assinador.ui;

import atlantafx.base.theme.Styles;
import br.com.vida.assinador.model.AppSettings;
import br.com.vida.assinador.model.BatchCorner;
import br.com.vida.assinador.model.TsaConfig;
import br.com.vida.assinador.service.SettingsRepository;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public final class SettingsPane extends VBox {
    private final SettingsRepository repository;
    private final Consumer<Boolean> themeUpdater;

    private final CheckBox darkMode = new CheckBox("Usar tema escuro");
    private final ComboBox<BatchCorner> batchCorner = new ComboBox<>();
    private final CheckBox tsaEnabled = new CheckBox("Usar carimbo do tempo quando assinar");
    private final TextField tsaUrl = new TextField();
    private final TextField tsaUser = new TextField();
    private final PasswordField tsaPassword = new PasswordField();

    public SettingsPane(SettingsRepository repository, Consumer<Boolean> themeUpdater) {
        this.repository = repository;
        this.themeUpdater = themeUpdater;
        setPadding(new Insets(24));
        setSpacing(18);

        Label title = new Label("Configurações");
        title.getStyleClass().add("page-title");

        Label generalTitle = new Label("Geral");
        generalTitle.getStyleClass().add("section-title");

        batchCorner.getItems().setAll(BatchCorner.values());

        GridPane general = new GridPane();
        general.setHgap(12);
        general.setVgap(12);
        general.addRow(0, new Label("Carimbo no lote:"), batchCorner);
        general.addRow(1, new Label("Aparência:"), darkMode);

        Label tsaTitle = new Label("Carimbo do tempo (TSA)");
        tsaTitle.getStyleClass().add("section-title");
        Label tsaHelp = new Label(
                "Sem TSA configurada o app usa AD-RB. Com TSA habilitada e acessível, usa AD-RT."
        );
        tsaHelp.getStyleClass().add(Styles.TEXT_MUTED);

        tsaUrl.setPromptText("https://tsa.exemplo.br/");
        tsaUser.setPromptText("Usuário, se exigido");
        tsaPassword.setPromptText("Senha, se exigida");

        GridPane tsa = new GridPane();
        tsa.setHgap(12);
        tsa.setVgap(12);
        tsa.add(tsaEnabled, 0, 0, 2, 1);
        tsa.addRow(1, new Label("URL:"), tsaUrl);
        tsa.addRow(2, new Label("Usuário:"), tsaUser);
        tsa.addRow(3, new Label("Senha:"), tsaPassword);
        GridPane.setHgrow(tsaUrl, Priority.ALWAYS);
        GridPane.setHgrow(tsaUser, Priority.ALWAYS);
        GridPane.setHgrow(tsaPassword, Priority.ALWAYS);

        Button save = new Button("Salvar configurações");
        save.getStyleClass().add("accent");
        save.setOnAction(event -> save());

        getChildren().addAll(title, generalTitle, general, tsaTitle, tsaHelp, tsa, save);
        refresh();
    }

    public void refresh() {
        AppSettings settings = repository.loadSettings();
        TsaConfig tsa = repository.loadTsa();
        darkMode.setSelected(settings.darkMode());
        batchCorner.setValue(settings.batchCorner());
        tsaEnabled.setSelected(tsa.enabled());
        tsaUrl.setText(tsa.url());
        tsaUser.setText(tsa.username());
        tsaPassword.setText(tsa.password());
    }

    private void save() {
        try {
            BatchCorner corner = batchCorner.getValue() == null
                    ? AppSettings.defaults().batchCorner()
                    : batchCorner.getValue();

            AppSettings settings = new AppSettings(darkMode.isSelected(), corner);
            TsaConfig tsa = new TsaConfig(
                    tsaEnabled.isSelected(),
                    tsaUrl.getText(),
                    tsaUser.getText(),
                    tsaPassword.getText()
            );

            repository.saveSettings(settings);
            repository.saveTsa(tsa);
            themeUpdater.accept(settings.darkMode());
            UiDialogs.info("Configurações", "Configurações salvas.");
        } catch (RuntimeException e) {
            UiDialogs.error("Configurações", e.getMessage());
        }
    }
}
