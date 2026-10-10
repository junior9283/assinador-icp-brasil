package br.com.vida.assinador.ui;

import atlantafx.base.controls.Spacer;
import atlantafx.base.layout.InputGroup;
import atlantafx.base.theme.Styles;
import br.com.vida.assinador.model.AppSettings;
import br.com.vida.assinador.model.BatchCorner;
import br.com.vida.assinador.model.CertificateInfo;
import br.com.vida.assinador.model.SignaturePlacement;
import br.com.vida.assinador.model.SigningResult;
import br.com.vida.assinador.service.BatchSigningService;
import br.com.vida.assinador.service.CertificateRepository;
import br.com.vida.assinador.service.OutputNamingService;
import br.com.vida.assinador.service.PdfPreviewService;
import br.com.vida.assinador.service.SettingsRepository;
import br.com.vida.assinador.service.SignaturePlacementService;
import br.com.vida.assinador.service.SigningCredential;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/** Tela de assinatura em "mesa de trabalho": miniaturas, página no centro e inspetor à direita. */
public final class SigningPane extends BorderPane {
    private static final double MAX_PREVIEW_WIDTH = 720;
    private static final double MAX_PREVIEW_HEIGHT = 640;
    private static final double CANVAS_PADDING = 24;
    // Zoom em % do tamanho real; 0 significa "ajustar à área visível".
    private static final int FIT = 0;
    private static final int[] ZOOM_STEPS = {50, 75, 100, 125, 150, 200, 300};
    private static final double THUMB_WIDTH = 64;
    private static final double THUMB_HEIGHT = 90;
    // Mesma margem do carimbo em lote (SignaturePlacementService), em pontos do PDF.
    private static final double STAMP_MARGIN_PT = 18;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CertificateRepository certificates;
    private final SettingsRepository settings;
    private final PdfPreviewService previewService;
    private final BatchSigningService batchSigningService;

    private final ObservableList<Path> documents = FXCollections.observableArrayList();

    private final Label documentTitle = new Label();
    private final Button previousPage = new Button("‹");
    private final Button nextPage = new Button("›");
    private final Label pageLabel = new Label();
    private final InputGroup pageNavigation = new InputGroup(previousPage, pageLabel, nextPage);
    private final Button zoomOut = new Button("−");
    private final Button zoomIn = new Button("+");
    private final Label zoomLabel = new Label();
    private final ToggleButton fitButton = new ToggleButton("Ajustar");
    private final HBox zoomControls = new HBox(6, new InputGroup(zoomOut, zoomLabel, zoomIn), fitButton);
    private final Button removeButton = new Button("Remover");

    private final ListView<Integer> thumbnails = new ListView<>();
    // Miniatura por página: a célula observa a propriedade, que recebe a imagem quando o render termina.
    private final Map<Integer, ObjectProperty<Image>> thumbnailImages = new HashMap<>();
    // Miniaturas esperando vez: a mais recente (a que acabou de aparecer na lista) sai primeiro.
    private final Deque<Integer> pendingThumbnails = new ArrayDeque<>();
    // Páginas com célula na tela agora; as que saíram de vista são puladas na fila.
    private final Set<Integer> visibleThumbnails = new HashSet<>();
    private boolean thumbnailRunning;

    private final VBox emptyView = new VBox(10);
    private final ScrollPane singleView = new ScrollPane();
    private final VBox batchView = new VBox(10);
    private final ListView<Path> batchList = new ListView<>(documents);
    private final Pane previewCanvas = new Pane();
    private final ImageView previewImage = new ImageView();
    private final StackPane stamp = new StackPane();

    private final ComboBox<CertificateInfo> certificateCombo = new ComboBox<>();
    private final Label certificateStatus = new Label();
    private final Label stampX = new Label();
    private final Label stampY = new Label();
    private final Spinner<Double> widthSpinner = new Spinner<>();
    private final Spinner<Double> heightSpinner = new Spinner<>();
    private final VBox singleStampControls = new VBox(8);
    private final VBox batchStampControls = new VBox(8);
    private final ComboBox<BatchCorner> batchCorner = new ComboBox<>();
    private final Label outputFolder = muted("");
    private final Label outputName = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = muted("");
    private final Button signButton = new Button();

    private int currentPage = 1;
    private int zoomPercent = FIT;
    // Pixels da prévia por ponto do PDF; 0 enquanto nenhuma página foi exibida.
    private double previewScale;
    // Posição do carimbo em pontos do PDF (origem no canto superior esquerdo); a tela é só uma projeção.
    private double stampXPt;
    private double stampYPt;
    private boolean timestampConfigured;
    // Muda a cada documento ou limpeza: miniaturas pedidas antes disso são descartadas sem renderizar.
    private volatile int previewGeneration;
    private double dragAnchorX;
    private double dragAnchorY;
    private final PauseTransition refit = new PauseTransition(Duration.millis(200));

    // Uma thread só para a prévia: renders e fechamento do PDF ficam em ordem, e renders cancelados na fila são pulados.
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("assinador-preview").factory()
    );
    // restart() cancela o render anterior: só a pré-visualização mais recente é aplicada.
    private final Service<PdfPreviewService.PdfPreview> previewLoader = new Service<>() {
        @Override
        protected Task<PdfPreviewService.PdfPreview> createTask() {
            Path pdf = documents.getFirst();
            int page = currentPage;
            boolean fit = zoomPercent == FIT;
            double maxScale = fit ? PdfPreviewService.ACTUAL_SIZE_SCALE : zoomPercent / 100.0 * PdfPreviewService.ACTUAL_SIZE_SCALE;
            double maxWidth = fit ? fitWidth() : Double.POSITIVE_INFINITY;
            double maxHeight = fit ? fitHeight() : Double.POSITIVE_INFINITY;
            return new Task<>() {
                @Override
                protected PdfPreviewService.PdfPreview call() {
                    return previewService.render(pdf, page, maxScale, maxWidth, maxHeight);
                }
            };
        }
    };

    public SigningPane(AppContext context) {
        this.certificates = context.certificates();
        this.settings = context.settings();
        this.previewService = context.preview();
        this.batchSigningService = context.batchSigning();

        setTop(toolbar());
        setLeft(thumbnailStrip());
        setCenter(centerArea());
        setRight(inspector());
        configureDragAndDrop();
        configurePreview();
        documents.addListener((ListChangeListener<Path>) change -> updateMode());
        refresh();
        updateMode();
    }

    /** Recarrega certificados e configurações; os documentos abertos e a prévia ficam como estão. */
    public void refresh() {
        CertificateInfo selected = certificateCombo.getValue();
        certificateCombo.setItems(FXCollections.observableArrayList(certificates.list()));
        if (selected != null) {
            certificateCombo.getItems().stream()
                    .filter(item -> item.id().equals(selected.id()))
                    .findFirst()
                    .ifPresent(certificateCombo::setValue);
        }
        if (certificateCombo.getValue() == null && !certificateCombo.getItems().isEmpty()) {
            certificateCombo.getSelectionModel().selectFirst();
        }
        updateCertificateStatus(certificateCombo.getValue());

        batchCorner.setValue(settings.loadSettings().batchCorner());
        timestampConfigured = settings.loadTsa().isUsable();
        updateSignButton();
    }

    // ---------- Montagem ----------

    private HBox toolbar() {
        documentTitle.getStyleClass().add("document-title");
        pageLabel.getStyleClass().add("mono");
        previousPage.setOnAction(event -> showPage(currentPage - 1));
        nextPage.setOnAction(event -> showPage(currentPage + 1));
        zoomLabel.getStyleClass().add("mono");
        zoomLabel.setMinWidth(48);
        zoomLabel.setAlignment(Pos.CENTER);
        zoomOut.setOnAction(event -> nextZoomStep(-1).ifPresent(this::setZoom));
        zoomIn.setOnAction(event -> nextZoomStep(1).ifPresent(this::setZoom));
        fitButton.setOnAction(event -> setZoom(FIT));
        zoomControls.setAlignment(Pos.CENTER_LEFT);
        removeButton.setOnAction(event -> removeSelectedDocument());
        Button clear = new Button("Limpar");
        clear.setOnAction(event -> documents.clear());

        HBox bar = new HBox(documentTitle, new Separator(Orientation.VERTICAL), pageNavigation, zoomControls,
                new Spacer(), addButton(), removeButton, clear);
        bar.getStyleClass().add("work-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Button addButton() {
        Button add = new Button("Adicionar PDFs");
        add.setOnAction(event -> chooseDocuments());
        return add;
    }

    private ListView<Integer> thumbnailStrip() {
        thumbnails.getStyleClass().add("thumbnails");
        thumbnails.setPrefWidth(100);
        thumbnails.setCellFactory(list -> new ListCell<>() {
            private final ImageView image = new ImageView();
            private final StackPane frame = new StackPane(image);
            private final Label number = new Label();
            private final VBox box = new VBox(4, frame, number);
            private Integer shownPage;

            {
                box.setAlignment(Pos.CENTER);
                image.setFitWidth(THUMB_WIDTH);
                image.setFitHeight(THUMB_HEIGHT);
                image.setPreserveRatio(true);
                frame.getStyleClass().add("page-sheet");
                frame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            }

            @Override
            protected void updateItem(Integer page, boolean empty) {
                super.updateItem(page, empty);
                image.imageProperty().unbind();
                if (shownPage != null) {
                    visibleThumbnails.remove(shownPage);
                }
                shownPage = empty ? null : page;
                if (shownPage == null) {
                    setGraphic(null);
                    return;
                }
                visibleThumbnails.add(page);
                image.imageProperty().bind(thumbnail(page));
                number.setText(String.valueOf(page));
                setGraphic(box);
            }
        });
        thumbnails.getSelectionModel().selectedItemProperty().addListener((obs, oldPage, page) -> {
            if (page != null && page != currentPage) {
                showPage(page);
            }
        });
        return thumbnails;
    }

    private StackPane centerArea() {
        Label dropTitle = new Label("Arraste PDFs para cá");
        dropTitle.getStyleClass().add("empty-title");
        emptyView.getChildren().addAll(dropTitle, muted("ou use Adicionar PDFs na barra acima."), addButton());
        emptyView.setAlignment(Pos.CENTER);

        Label stampLabel = new Label("Carimbo ICP-Brasil");
        stampLabel.getStyleClass().add("stamp-label");
        stamp.getChildren().add(stampLabel);
        stamp.getStyleClass().add("signature-stamp");
        stamp.setMinSize(1, 1);
        previewCanvas.getStyleClass().add("page-sheet");
        previewCanvas.getChildren().addAll(previewImage, stamp);
        previewCanvas.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        previewCanvas.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane canvasHolder = new StackPane(previewCanvas);
        canvasHolder.setPadding(new Insets(CANVAS_PADDING));
        singleView.setContent(canvasHolder);
        singleView.setFitToWidth(true);
        singleView.setFitToHeight(true);
        singleView.setPannable(true);

        Label batchHint = muted("Em lote, o carimbo vai na última página de cada PDF, no canto escolhido ao lado.");
        batchHint.setWrapText(true);
        batchList.getStyleClass().add("batch-list");
        batchList.setCellFactory(list -> new ListCell<>() {
            private final Label name = new Label();
            private final Label folder = muted("");
            private final VBox box = new VBox(2, name, folder);

            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                name.setText(item.getFileName().toString());
                folder.setText(String.valueOf(item.toAbsolutePath().getParent()));
                setGraphic(box);
            }
        });
        VBox.setVgrow(batchList, Priority.ALWAYS);
        batchView.getChildren().addAll(batchHint, batchList);
        batchView.setPadding(new Insets(20));

        StackPane center = new StackPane(emptyView, singleView, batchView);
        center.getStyleClass().addAll("canvas-area", Styles.BG_INSET);
        return center;
    }

    private VBox inspector() {
        certificateCombo.setMaxWidth(Double.MAX_VALUE);
        certificateCombo.setCellFactory(list -> certificateCell());
        certificateCombo.setButtonCell(certificateCell());
        certificateCombo.valueProperty().addListener((obs, oldValue, value) -> updateCertificateStatus(value));
        certificateStatus.setWrapText(true);

        configureSizeSpinner(widthSpinner, 60, 400, SignaturePlacementService.DEFAULT_WIDTH, 10);
        configureSizeSpinner(heightSpinner, 24, 200, SignaturePlacementService.DEFAULT_HEIGHT, 5);
        stampX.getStyleClass().add("mono");
        stampY.getStyleClass().add("mono");
        GridPane measures = new GridPane();
        measures.setHgap(10);
        measures.setVgap(8);
        measures.add(labeled("X", stampX), 0, 0);
        measures.add(labeled("Y", stampY), 1, 0);
        measures.add(labeled("Largura (pt)", widthSpinner), 0, 1);
        measures.add(labeled("Altura (pt)", heightSpinner), 1, 1);
        singleStampControls.getChildren().addAll(measures, muted("Arraste o carimbo sobre a página."));

        batchCorner.getItems().setAll(BatchCorner.values());
        batchCorner.setMaxWidth(Double.MAX_VALUE);
        batchStampControls.getChildren().add(labeled("Canto da última página", batchCorner));

        outputName.getStyleClass().add("mono");
        outputName.setWrapText(true);

        progress.setMaxWidth(Double.MAX_VALUE);
        show(progress, false);
        progressLabel.setWrapText(true);
        signButton.getStyleClass().addAll(Styles.ACCENT, "sign-button");
        signButton.setMaxWidth(Double.MAX_VALUE);
        signButton.setOnAction(event -> sign());

        VBox box = new VBox(18,
                section("Certificado", certificateCombo, certificateStatus),
                section("Carimbo", singleStampControls, batchStampControls),
                section("Saída", outputFolder, outputName),
                new Spacer(Orientation.VERTICAL),
                progressLabel, progress, signButton);
        box.getStyleClass().add("inspector");
        box.setPrefWidth(290);
        return box;
    }

    private static VBox section(String title, Node... content) {
        Label label = new Label(title);
        label.getStyleClass().addAll("inspector-title", Styles.TEXT_MUTED);
        VBox box = new VBox(8, label);
        box.getChildren().addAll(content);
        return box;
    }

    private static VBox labeled(String label, Node value) {
        return new VBox(3, muted(label), value);
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add(Styles.TEXT_MUTED);
        return label;
    }

    private ListCell<CertificateInfo> certificateCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(CertificateInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }

    private void configureSizeSpinner(Spinner<Double> spinner, double min, double max, double initial, double step) {
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, initial, step));
        spinner.setEditable(true);
        spinner.setPrefWidth(118);
        spinner.valueProperty().addListener((obs, oldValue, newValue) -> layoutStamp());
    }

    private void configureDragAndDrop() {
        setOnDragOver(event -> {
            if (event.getGestureSource() != this && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean accepted = false;
            if (dragboard.hasFiles()) {
                List<Path> pdfs = dragboard.getFiles().stream()
                        .map(File::toPath)
                        .filter(this::isPdf)
                        .toList();
                if (!pdfs.isEmpty()) {
                    addDocuments(pdfs);
                    accepted = true;
                }
            }
            event.setDropCompleted(accepted);
            event.consume();
        });
    }

    private void configurePreview() {
        previewLoader.setExecutor(previewExecutor);
        // Miniaturas só renderizam com a página principal parada. O runLater deixa o restart() terminar:
        // ao cancelar o render anterior o serviço para por um instante, e a miniatura passaria na frente.
        previewLoader.runningProperty().addListener((obs, wasRunning, running) -> {
            if (!running) {
                Platform.runLater(this::pumpThumbnails);
            }
        });
        refit.setOnFinished(event -> {
            if (zoomPercent == FIT && previewScale > 0 && Math.abs(fitScale() - previewScale) > 0.005) {
                renderPreview();
            }
        });
        singleView.viewportBoundsProperty().addListener((obs, oldBounds, bounds) -> {
            boolean resized = oldBounds.getWidth() != bounds.getWidth() || oldBounds.getHeight() != bounds.getHeight();
            if (zoomPercent == FIT && previewScale > 0 && resized) {
                refit.playFromStart();
            }
        });
        updateScrollBars();
        previewLoader.setOnSucceeded(event -> showPreview(previewLoader.getValue()));
        previewLoader.setOnFailed(event -> {
            clearPreview();
            UiDialogs.error(
                    "Pré-visualização",
                    UiDialogs.message(previewLoader.getException(), "Não foi possível processar o PDF.")
            );
        });

        stamp.setOnMousePressed(event -> {
            dragAnchorX = event.getSceneX() - stamp.getLayoutX();
            dragAnchorY = event.getSceneY() - stamp.getLayoutY();
            event.consume();
        });
        stamp.setOnMouseDragged(event -> {
            double maxX = Math.max(0, previewCanvas.getPrefWidth() - stamp.getPrefWidth());
            double maxY = Math.max(0, previewCanvas.getPrefHeight() - stamp.getPrefHeight());
            stampXPt = Math.clamp(event.getSceneX() - dragAnchorX, 0, maxX) / previewScale;
            stampYPt = Math.clamp(event.getSceneY() - dragAnchorY, 0, maxY) / previewScale;
            layoutStamp();
            event.consume();
        });
    }

    // ---------- Documentos ----------

    private void chooseDocuments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Adicionar PDFs");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF (*.pdf)", "*.pdf"));
        List<File> selected = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (selected != null) {
            addDocuments(selected.stream().map(File::toPath).toList());
        }
    }

    private void addDocuments(List<Path> paths) {
        // Um único addAll gera um só evento: evita iniciar a prévia do 1º arquivo de um lote só para descartá-la.
        Set<Path> known = new HashSet<>(documents);
        documents.addAll(paths.stream()
                .filter(this::isPdf)
                .filter(known::add)
                .toList());
    }

    private void removeSelectedDocument() {
        Path selected = documents.size() == 1 ? documents.getFirst() : batchList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            documents.remove(selected);
        }
    }

    private boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".pdf");
    }

    private void updateMode() {
        boolean single = documents.size() == 1;
        show(emptyView, documents.isEmpty());
        show(singleView, single);
        show(batchView, documents.size() > 1);
        show(thumbnails, single);
        show(pageNavigation, single);
        show(zoomControls, single);
        show(singleStampControls, single);
        show(batchStampControls, !single);
        removeButton.setDisable(documents.isEmpty());
        documentTitle.setText(switch (documents.size()) {
            case 0 -> "Nenhum documento";
            case 1 -> documents.getFirst().getFileName().toString();
            default -> documents.size() + " documentos";
        });

        if (single) {
            configureSingleDocument();
        } else {
            clearPreview();
        }
        updateOutput();
        updateSignButton();
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void updateOutput() {
        if (documents.size() == 1) {
            outputFolder.setText("Mesma pasta do original");
            outputName.setText(OutputNamingService.nextAvailable(documents.getFirst()).getFileName().toString());
        } else {
            outputFolder.setText("Na pasta de cada arquivo");
            outputName.setText("nome_assinado.pdf");
        }
    }

    private void updateSignButton() {
        String policy = timestampConfigured ? "AD-RT" : "AD-RB";
        signButton.setText(documents.size() > 1
                ? "Assinar " + documents.size() + " documentos · " + policy
                : "Assinar · " + policy);
    }

    private void updateCertificateStatus(CertificateInfo certificate) {
        certificateStatus.getStyleClass().removeAll(Styles.SUCCESS, Styles.WARNING, Styles.DANGER);
        if (certificate == null) {
            certificateStatus.setText("Importe um certificado A1 em Certificados.");
            return;
        }
        LocalDate validUntil = certificate.validUntil().atZone(ZoneId.systemDefault()).toLocalDate();
        long days = ChronoUnit.DAYS.between(LocalDate.now(), validUntil);
        if (certificate.isExpired(Instant.now())) {
            certificateStatus.setText("● Vencido em " + DATE.format(validUntil));
            certificateStatus.getStyleClass().add(Styles.DANGER);
        } else if (days <= 30) {
            certificateStatus.setText("● Vence em " + days + (days == 1 ? " dia" : " dias")
                    + " (" + DATE.format(validUntil) + ")");
            certificateStatus.getStyleClass().add(Styles.WARNING);
        } else {
            certificateStatus.setText("● Válido até " + DATE.format(validUntil));
            certificateStatus.getStyleClass().add(Styles.SUCCESS);
        }
    }

    // ---------- Pré-visualização ----------

    private void configureSingleDocument() {
        resetThumbnails();
        showPage(1);
    }

    private void showPage(int page) {
        refit.stop();
        currentPage = page;
        renderPreview();
        updatePageNavigation();
    }

    private void renderPreview() {
        if (documents.size() == 1) {
            previewLoader.restart();
        }
    }

    private void setZoom(int percent) {
        refit.stop();
        zoomPercent = percent;
        updateScrollBars();
        renderPreview();
    }

    // No modo ajustar a página sempre cabe: sem barras, que mudariam a área visível e pediriam outro render.
    private void updateScrollBars() {
        ScrollPane.ScrollBarPolicy policy = zoomPercent == FIT
                ? ScrollPane.ScrollBarPolicy.NEVER
                : ScrollPane.ScrollBarPolicy.AS_NEEDED;
        singleView.setHbarPolicy(policy);
        singleView.setVbarPolicy(policy);
        fitButton.setSelected(zoomPercent == FIT);
    }

    /** Zoom exibido agora, em %, como aparece no rótulo. */
    private int displayedZoom() {
        return (int) Math.round(previewScale / PdfPreviewService.ACTUAL_SIZE_SCALE * 100);
    }

    /** Próximo degrau acima (+1) ou abaixo (-1) do zoom exibido, inclusive quando a página está ajustada. */
    private OptionalInt nextZoomStep(int direction) {
        int current = displayedZoom();
        IntStream steps = Arrays.stream(ZOOM_STEPS);
        return direction > 0
                ? steps.filter(step -> step > current).findFirst()
                : steps.filter(step -> step < current).reduce((first, second) -> second);
    }

    private double fitWidth() {
        double viewport = singleView.getViewportBounds().getWidth();
        return viewport > 2 * CANVAS_PADDING ? viewport - 2 * CANVAS_PADDING : MAX_PREVIEW_WIDTH;
    }

    private double fitHeight() {
        double viewport = singleView.getViewportBounds().getHeight();
        return viewport > 2 * CANVAS_PADDING ? viewport - 2 * CANVAS_PADDING : MAX_PREVIEW_HEIGHT;
    }

    /** Escala que o modo ajustar daria para a página exibida, com a área visível atual. */
    private double fitScale() {
        double pageWidth = previewCanvas.getPrefWidth() / previewScale;
        double pageHeight = previewCanvas.getPrefHeight() / previewScale;
        return Math.min(PdfPreviewService.ACTUAL_SIZE_SCALE, Math.min(fitWidth() / pageWidth, fitHeight() / pageHeight));
    }

    private void showPreview(PdfPreviewService.PdfPreview preview) {
        if (thumbnails.getItems().size() != preview.pageCount()) {
            thumbnails.getItems().setAll(IntStream.rangeClosed(1, preview.pageCount()).boxed().toList());
        }
        thumbnails.getSelectionModel().select(Integer.valueOf(currentPage));
        thumbnails.scrollTo(currentPage - 1);

        // Primeira página de um documento novo: o carimbo começa no canto inferior direito.
        boolean firstPage = previewScale == 0;
        Image image = preview.image();
        previewScale = preview.scale();
        previewImage.setImage(image);
        previewCanvas.setPrefSize(image.getWidth(), image.getHeight());
        if (firstPage) {
            stampXPt = image.getWidth() / previewScale - widthSpinner.getValue() - STAMP_MARGIN_PT;
            stampYPt = image.getHeight() / previewScale - heightSpinner.getValue() - STAMP_MARGIN_PT;
        }
        layoutStamp();
        updatePageNavigation();
        updateZoomControls();
    }

    private void updateZoomControls() {
        zoomLabel.setText(previewScale > 0 ? displayedZoom() + "%" : "–");
        zoomOut.setDisable(previewScale <= 0 || nextZoomStep(-1).isEmpty());
        zoomIn.setDisable(previewScale <= 0 || nextZoomStep(1).isEmpty());
    }

    private void updatePageNavigation() {
        int pageCount = thumbnails.getItems().size();
        pageLabel.setText(currentPage + " / " + (pageCount > 0 ? pageCount : "–"));
        previousPage.setDisable(currentPage <= 1);
        nextPage.setDisable(pageCount == 0 || currentPage >= pageCount);
    }

    private ObjectProperty<Image> thumbnail(int page) {
        ObjectProperty<Image> image = thumbnailImages.get(page);
        if (image == null) {
            image = new SimpleObjectProperty<>();
            thumbnailImages.put(page, image);
            pendingThumbnails.push(page);
            pumpThumbnails();
        }
        return image;
    }

    /** Renderiza a próxima miniatura visível da fila, uma por vez e só com a página principal parada. */
    private void pumpThumbnails() {
        if (thumbnailRunning || previewLoader.isRunning() || documents.size() != 1) {
            return;
        }
        Integer next = null;
        while (next == null && !pendingThumbnails.isEmpty()) {
            int page = pendingThumbnails.pop();
            if (visibleThumbnails.contains(page)) {
                next = page;
            } else {
                thumbnailImages.remove(page); // será pedida de novo se a página voltar a aparecer
            }
        }
        if (next == null) {
            return;
        }
        int page = next;
        Path pdf = documents.getFirst();
        int generation = previewGeneration;
        ObjectProperty<Image> target = thumbnailImages.get(page);
        Task<Image> task = new Task<>() {
            @Override
            protected Image call() {
                return generation == previewGeneration
                        ? previewService.render(pdf, page, PdfPreviewService.ACTUAL_SIZE_SCALE, THUMB_WIDTH, THUMB_HEIGHT).image()
                        : null;
            }
        };
        task.setOnSucceeded(event -> {
            if (generation == previewGeneration) {
                target.set(task.getValue());
            }
            thumbnailFinished();
        });
        task.setOnFailed(event -> thumbnailFinished());
        thumbnailRunning = true;
        previewExecutor.execute(task);
    }

    private void thumbnailFinished() {
        thumbnailRunning = false;
        pumpThumbnails();
    }

    private void resetThumbnails() {
        previewGeneration++;
        thumbnails.getItems().clear();
        thumbnailImages.clear();
        pendingThumbnails.clear();
    }

    private void clearPreview() {
        refit.stop();
        previewLoader.cancel();
        // reset() solta a última imagem guardada no serviço (pode ser grande com zoom alto).
        previewLoader.reset();
        previewExecutor.execute(previewService::close);
        resetThumbnails();
        previewImage.setImage(null);
        previewCanvas.setPrefSize(0, 0);
        previewScale = 0;
        stampXPt = 0;
        stampYPt = 0;
        layoutStamp();
        updateZoomControls();
    }

    /** Projeta na tela o carimbo guardado em pontos, mantendo-o dentro da página. */
    private void layoutStamp() {
        if (previewScale <= 0 || widthSpinner.getValue() == null || heightSpinner.getValue() == null) {
            stampX.setText("–");
            stampY.setText("–");
            return;
        }
        double pageWidth = previewCanvas.getPrefWidth() / previewScale;
        double pageHeight = previewCanvas.getPrefHeight() / previewScale;
        double width = Math.min(widthSpinner.getValue(), pageWidth);
        double height = Math.min(heightSpinner.getValue(), pageHeight);
        stampXPt = Math.clamp(stampXPt, 0, pageWidth - width);
        stampYPt = Math.clamp(stampYPt, 0, pageHeight - height);
        stamp.setPrefSize(width * previewScale, height * previewScale);
        stamp.setLayoutX(stampXPt * previewScale);
        stamp.setLayoutY(stampYPt * previewScale);
        stampX.setText(Math.round(stampXPt) + " pt");
        stampY.setText(Math.round(stampYPt) + " pt");
    }

    private SignaturePlacement currentPlacement() {
        return new SignaturePlacement(
                currentPage,
                (float) stampXPt,
                (float) stampYPt,
                (float) (stamp.getPrefWidth() / previewScale),
                (float) (stamp.getPrefHeight() / previewScale)
        );
    }

    // ---------- Assinatura ----------

    private void sign() {
        CertificateInfo selectedCertificate = certificateCombo.getValue();
        if (selectedCertificate == null) {
            UiDialogs.info("Assinar", "Importe e selecione um certificado A1.");
            return;
        }
        if (documents.isEmpty()) {
            UiDialogs.info("Assinar", "Adicione pelo menos um PDF.");
            return;
        }
        if (documents.size() == 1 && previewScale <= 0) {
            UiDialogs.info("Assinar", "Aguarde a pré-visualização da página carregar para posicionar o carimbo.");
            return;
        }

        char[] password = signingPassword(selectedCertificate);
        if (password != null) {
            startSigning(selectedCertificate.id(), password);
        }
    }

    private char[] signingPassword(CertificateInfo certificate) {
        if (certificate.automaticSigning()) {
            try {
                return certificates.loadAutomaticPassword(certificate.id());
            } catch (RuntimeException e) {
                UiDialogs.error("Assinar", e.getMessage());
                return null;
            }
        }

        Optional<char[]> passwordDialog = UiDialogs.password(
                "Senha do certificado",
                "Informe a senha de " + certificate.displayName() + "."
        );
        return passwordDialog.orElse(null);
    }

    private void startSigning(String certificateId, char[] password) {
        List<Path> files = List.copyOf(documents);
        SignaturePlacement singlePlacement = files.size() == 1 ? currentPlacement() : null;
        BatchCorner corner = batchCorner.getValue() == null
                ? AppSettings.defaults().batchCorner()
                : batchCorner.getValue();
        Task<List<SigningResult>> task = new Task<>() {
            @Override
            protected List<SigningResult> call() {
                try {
                    // Cofre, validação da cadeia (pode acessar a rede) e PKCS#12 ficam fora da thread da interface.
                    updateMessage("Validando certificado…");
                    SigningCredential credential = certificates.openForSigning(certificateId, password);
                    return batchSigningService.signAll(
                            files,
                            credential,
                            settings.loadTsa(),
                            document -> singlePlacement != null
                                    ? singlePlacement
                                    : SignaturePlacementService.forLastPage(document, corner),
                            progressListener()
                    );
                } finally {
                    Arrays.fill(password, '\0');
                }
            }

            private BatchSigningService.ProgressListener progressListener() {
                return new BatchSigningService.ProgressListener() {
                    @Override
                    public void onFileStarted(Path file, int index, int total) {
                        updateMessage("Assinando " + file.getFileName() + "…");
                    }

                    @Override
                    public void onFileCompleted(Path file, int completed, int total, SigningResult result) {
                        updateProgress(completed, total);
                    }
                };
            }
        };

        progress.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        show(progress, true);
        signButton.setDisable(true);
        task.setOnSucceeded(event -> {
            finishTask();
            showResults(task.getValue());
        });
        task.setOnFailed(event -> {
            finishTask();
            UiDialogs.error("Assinar", UiDialogs.message(task.getException(), "Falha inesperada na assinatura."));
        });
        Thread.ofPlatform().daemon().name("assinador-signing").start(task);
    }

    private void finishTask() {
        progress.progressProperty().unbind();
        progressLabel.textProperty().unbind();
        show(progress, false);
        progressLabel.setText("");
        signButton.setDisable(false);
        updateOutput();
    }

    private void showResults(List<SigningResult> results) {
        long success = results.stream().filter(SigningResult::success).count();
        long failures = results.size() - success;
        StringBuilder message = new StringBuilder()
                .append(success).append(" arquivo(s) assinado(s) com sucesso.");
        results.stream()
                .filter(SigningResult::success)
                .forEach(result -> message.append("\n• Salvo em: ")
                        .append(result.output().toAbsolutePath().normalize()));
        if (failures > 0) {
            message.append("\n").append(failures).append(" falha(s):");
            results.stream()
                    .filter(result -> !result.success())
                    .limit(8)
                    .forEach(result -> message.append("\n• ")
                            .append(result.input().getFileName())
                            .append(": ")
                            .append(result.message()));
        }
        UiDialogs.info("Resultado da assinatura", message.toString());
    }
}
