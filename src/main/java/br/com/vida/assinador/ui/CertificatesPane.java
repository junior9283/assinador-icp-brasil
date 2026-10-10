package br.com.vida.assinador.ui;

import atlantafx.base.theme.Styles;
import br.com.vida.assinador.model.CertificateInfo;
import br.com.vida.assinador.service.CertificateRepository;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;

public final class CertificatesPane extends BorderPane {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CertificateRepository repository;
    private final TableView<CertificateInfo> table = new TableView<>();

    public CertificatesPane(CertificateRepository repository) {
        this.repository = repository;
        setPadding(new Insets(24));
        setTop(header());
        setCenter(buildTable());
        setBottom(actions());
        BorderPane.setMargin(table, new Insets(16, 0, 16, 0));
        refresh();
    }

    public void refresh() {
        table.setItems(FXCollections.observableArrayList(repository.list()));
    }

    private HBox header() {
        Label title = new Label("Certificados A1");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Importe e gerencie os certificados que ficarão protegidos no cofre local.");
        subtitle.getStyleClass().add(Styles.TEXT_MUTED);
        HBox box = new HBox(16, title, subtitle);
        box.setAlignment(javafx.geometry.Pos.BASELINE_LEFT);
        return box;
    }

    private TableView<CertificateInfo> buildTable() {
        TableColumn<CertificateInfo, String> name = new TableColumn<>("Certificado");
        name.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().displayName()));
        name.setPrefWidth(260);

        TableColumn<CertificateInfo, String> issuer = new TableColumn<>("Emissor");
        issuer.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().issuer()));
        issuer.setPrefWidth(340);

        TableColumn<CertificateInfo, String> serial = new TableColumn<>("Série");
        serial.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().serialNumber()));
        serial.setPrefWidth(180);

        TableColumn<CertificateInfo, String> validUntil = new TableColumn<>("Validade");
        validUntil.setCellValueFactory(cell -> new SimpleStringProperty(
                DATE.format(cell.getValue().validUntil().atZone(ZoneId.systemDefault()))
        ));
        validUntil.setPrefWidth(120);

        TableColumn<CertificateInfo, String> signingMode = new TableColumn<>("Assinatura");
        signingMode.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().automaticSigning() ? "Automática" : "Pedir senha"
        ));
        signingMode.setPrefWidth(120);

        table.getColumns().addAll(name, issuer, serial, validUntil, signingMode);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Nenhum certificado importado."));
        return table;
    }

    private HBox actions() {
        Button add = new Button("Importar A1");
        add.getStyleClass().add("accent");
        add.setOnAction(event -> importCertificate());

        Button rename = new Button("Renomear");
        rename.setOnAction(event -> renameSelected());

        Button signingMode = new Button("Modo de assinatura");
        signingMode.setOnAction(event -> changeSigningMode());

        Button remove = new Button("Remover");
        remove.setOnAction(event -> removeSelected());

        return new HBox(10, add, rename, signingMode, remove);
    }

    private void importCertificate() {
        Window window = getScene() == null ? null : getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Selecionar certificado A1");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Certificado PKCS#12 (*.pfx, *.p12)", "*.pfx", "*.p12")
        );
        java.io.File selected = chooser.showOpenDialog(window);
        if (selected == null) {
            return;
        }

        Optional<char[]> password = UiDialogs.password(
                "Senha do certificado",
                "Informe a senha do arquivo A1 para validar a importação."
        );
        if (password.isEmpty()) {
            return;
        }

        char[] value = password.get();
        try {
            CertificateInfo info = repository.importCertificate(selected.toPath(), value);
            refresh();
            table.getSelectionModel().select(info);
            UiDialogs.info("Certificado importado", info.displayName() + " foi adicionado ao cofre.");
        } catch (RuntimeException e) {
            UiDialogs.error("Importar certificado", e.getMessage());
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private void renameSelected() {
        CertificateInfo selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UiDialogs.info("Renomear certificado", "Selecione um certificado.");
            return;
        }

        Optional<String> alias = UiDialogs.text(
                "Renomear certificado",
                "Defina um apelido para facilitar a identificação.",
                selected.alias()
        );
        alias.ifPresent(value -> {
            repository.rename(selected.id(), value);
            refresh();
        });
    }

    private void changeSigningMode() {
        CertificateInfo selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UiDialogs.info("Modo de assinatura", "Selecione um certificado.");
            return;
        }

        if (selected.automaticSigning()) {
            if (!UiDialogs.confirm(
                    "Modo de assinatura",
                    "Passar a pedir a senha de " + selected.displayName() + " em cada assinatura?"
            )) {
                return;
            }
            repository.disableAutomaticSigning(selected.id());
            refresh();
            UiDialogs.info("Modo de assinatura", "A senha será solicitada em cada assinatura.");
            return;
        }

        Optional<char[]> password = UiDialogs.password(
                "Ativar assinatura automática",
                "Informe a senha de " + selected.displayName()
                        + ". Ela será armazenada cifrada no cofre local."
        );
        if (password.isEmpty()) {
            return;
        }

        char[] value = password.get();
        try {
            repository.enableAutomaticSigning(selected.id(), value);
            refresh();
            UiDialogs.info("Modo de assinatura", "Assinatura automática ativada para este certificado.");
        } catch (RuntimeException e) {
            UiDialogs.error("Modo de assinatura", e.getMessage());
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private void removeSelected() {
        CertificateInfo selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UiDialogs.info("Remover certificado", "Selecione um certificado.");
            return;
        }
        if (!UiDialogs.confirm(
                "Remover certificado",
                "Remover " + selected.displayName() + " do cofre local?"
        )) {
            return;
        }
        repository.remove(selected.id());
        refresh();
    }
}
