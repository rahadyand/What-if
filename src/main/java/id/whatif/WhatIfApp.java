package id.whatif;

import id.whatif.Course.Assessment;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.skin.TableViewSkin;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class WhatIfApp extends Application {
    private static final String CSS = "/whatif.css";
    private static final String NONE = "—";
    private static String headingFamily;

    private final ObservableList<Course> courses = FXCollections.observableArrayList();
    private final ListView<Course> courseList = new ListView<>(courses);
    private final BorderPane root = new BorderPane();
    private final ExecutorService io = Executors.newSingleThreadExecutor(job -> {
        Thread worker = new Thread(job, "whatif-sqlite");
        worker.setDaemon(true);
        return worker;
    });

    private Stage stage;
    private CourseStore store;
    private Course course;
    private Simulation simulation;
    private Scenario openedScenario;
    private List<Scenario> scenarios = List.of();
    private Label scenarioStatus;
    private boolean selectingCourse;
    private boolean syncingTarget;
    private boolean invalidDraft;
    private boolean busy;
    private Button addCourse;
    private Label courseCount;

    private TextField targetField;
    private ComboBox<Grade> targetLetter;
    private Label targetError;
    private Label metricTitle;
    private Label metricValue;
    private Label metricGrade;
    private Label resultStatus;
    private Label resultDetail;
    private Label tableCount;
    private TableView<ViewRow> table;
    private TargetSummary targetSummary;

    public static void main(String[] arguments) { launch(arguments); }

    @Override public void start(Stage window) {
        stage = window;
        store = new CourseStore(CourseStore.defaultDatabase());
        loadTypography();
        root.getStyleClass().add("workspace");
        root.setLeft(sidebar());
        style(root);
        stage.setTitle("What If");
        stage.setScene(new Scene(root, 1170, 700));
        stage.setMinWidth(880);
        stage.setMinHeight(590);
        stage.setOnCloseRequest(event -> {
            if (busy || !confirmTransition("Tutup What If")) event.consume();
        });
        stage.show();
        initialize();
        root.applyCss();
        Label heading = (Label) root.lookup(".empty-page .page-title");
        Label body = (Label) root.lookup(".empty-page .muted");
        System.out.println("What If rendered heading: " + heading.getFont().getName() + " (" + heading.getFont().getStyle() + ")");
        System.out.println("What If rendered body: " + body.getFont().getName() + " (" + body.getFont().getStyle() + ")");
    }

    @Override public void stop() { io.shutdown(); }

    private static void loadTypography() {
        Font bold = Font.loadFont(WhatIfApp.class.getResource("/fonts/Merriweather-Bold.ttf").toExternalForm(), 20);
        headingFamily = bold == null ? "Serif" : bold.getFamily();

    }

    static void style(Parent pane) {
        pane.getStylesheets().add(WhatIfApp.class.getResource(CSS).toExternalForm());
        pane.setStyle("-fx-font-family: '" + bodyFamily() + "';");
    }

    private static String bodyFamily() {
        List<String> available = Font.getFamilies();
        if (available.contains("Helvetica")) return "Helvetica";
        if (available.contains("Arial")) return "Arial";
        return "System";
    }

    private VBox sidebar() {
        ImageView logo = new ImageView(new Image(WhatIfApp.class.getResource("/branding/Logo.png").toExternalForm()));
        logo.getStyleClass().add("brand-logo");
        logo.setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        logo.setAccessibleText("What If");
        logo.setFitWidth(176);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        HBox identity = new HBox(logo);
        identity.setAlignment(Pos.CENTER_LEFT);

        courseCount = label("0", "sidebar-count");
        HBox listHeader = new HBox(8, label("MATA KULIAH", "eyebrow"), spacer(), courseCount);
        listHeader.setAlignment(Pos.CENTER_LEFT);
        courseList.setPlaceholder(label("Belum ada mata kuliah", "muted"));
        courseList.setCellFactory(unused -> new ListCell<>() {
            { setPrefWidth(0); }

            @Override protected void updateItem(Course item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item.name());
                setTooltip(empty ? null : new Tooltip(item.name()));
            }
        });
        courseList.getSelectionModel().selectedItemProperty().addListener((unused, before, next) -> selectCourse(next));
        VBox.setVgrow(courseList, Priority.ALWAYS);
        addCourse = button("+ Mata kuliah", () -> editCourse(null));
        addCourse.getStyleClass().add("sidebar-add");
        addCourse.setMaxWidth(Double.MAX_VALUE);
        VBox sidebar = new VBox(18, identity, listHeader, courseList, addCourse);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(212);
        return sidebar;
    }

    private void initialize() {
        busy = true;
        addCourse.setDisable(true);
        showEmpty("Memuat mata kuliah…", "Membuka penyimpanan lokal.", null);
        runIo(() -> {
            store.initialize();
            return new Workspace(store.load(), store.loadScenarios());
        }, loaded -> {
            busy = false;
            addCourse.setDisable(false);
            courses.setAll(loaded.courses());
            scenarios = loaded.scenarios();
            updateCount();
            if (courses.isEmpty()) showCourse(null);
            else courseList.getSelectionModel().selectFirst();
        }, failure -> {
            busy = false;
            Button retry = button("Coba lagi", this::initialize);
            showEmpty("Data belum bisa dibuka", failure, retry);
        });
    }

    private void selectCourse(Course next) {
        if (selectingCourse || next == course) return;
        if (!confirmTransition("Ganti mata kuliah")) {
            selectInSidebar(course);
            return;
        }
        course = next;
        selectInSidebar(course);
        simulation = null;
        openedScenario = null;
        invalidDraft = false;
        showCourse(null);
    }

    private void selectInSidebar(Course selection) {
        selectingCourse = true;
        if (selection == null) courseList.getSelectionModel().clearSelection();
        else courseList.getSelectionModel().select(selection);
        selectingCourse = false;
    }

    private void showCourse(UUID selectRow) {
        if (course == null) {
            Button create = button("+ Tambah mata kuliah", () -> editCourse(null));
            showEmpty("Mulai dengan mata kuliah", "Tambahkan mata kuliah, lalu masukkan penilaian dan hitung target Anda.", create);
            return;
        }

        Label name = heading(course.name(), "page-title");
        name.setWrapText(true);
        name.setMaxHeight(74);
        name.setTooltip(new Tooltip(course.name()));
        Label context = label(simulation == null ? "Pengelolaan nilai mata kuliah" : "Simulasi sementara", "eyebrow");
        VBox title = new VBox(4, context, name);
        HBox.setHgrow(title, Priority.ALWAYS);
        Button settings = button("Pengaturan", () -> editCourse(course));
        settings.setVisible(simulation == null);
        settings.setManaged(settings.isVisible());
        Button deleteCourse = button("Hapus mata kuliah", this::deleteSelectedCourse);
        deleteCourse.setVisible(simulation == null);
        deleteCourse.setManaged(deleteCourse.isVisible());
        HBox top = new HBox(8, title, settings, deleteCourse);
        top.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup modes = new ToggleGroup();
        ToggleButton actual = mode("Nilai aktual", modes, simulation == null, false);
        ToggleButton whatIf = mode("What If", modes, simulation != null, true);
        HBox tabs = new HBox(6, actual, whatIf);
        tabs.getStyleClass().add("mode-tabs");

        Course calculationCourse = simulation == null ? course : simulation.baseline();
        Label method = label("Dalam kategori · " + calculationCourse.method(), "muted");
        method.setTooltip(new Tooltip(Aggregation.EXAMPLE));
        Button categories = button(course.legacyWeights() ? "Tinjau bobot lama" : "Kelola kategori", this::manageCategories);
        categories.setVisible(simulation == null);
        categories.setManaged(categories.isVisible());
        HBox navigation = new HBox(12, tabs, spacer(), method, categories);
        navigation.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(simulation == null ? 14 : 10, top, navigation);
        page.getStyleClass().add("page");
        if (simulation != null) {
            page.getChildren().addAll(scenarioControls(), targetControls());
        } else if (course.legacyWeights()) {
            page.getChildren().add(Editor.note("Bobot relatif lama masih digunakan. Tinjau usulan konversi ke 100% di pengaturan kategori.", "validation"));
        }
        page.getChildren().addAll(summary(), assessmentArea());
        if (simulation == null) {
            root.setCenter(page);
        } else {
            targetSummary = new TargetSummary(course, courseScenarios());
            page.getChildren().add(targetSummary);
            ScrollPane scroll = new ScrollPane(page);
            scroll.getStyleClass().add("simulation-page-scroll");
            scroll.setFocusTraversable(true);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            root.setCenter(scroll);
        }
        refreshResults(selectRow);
    }

    private ToggleButton mode(String text, ToggleGroup group, boolean current, boolean enter) {
        ToggleButton tab = new ToggleButton(text);
        tab.setToggleGroup(group);
        tab.setSelected(current);
        tab.getStyleClass().add("mode-tab");
        tab.setOnAction(event -> {
            if (enter == (simulation != null)) {
                tab.setSelected(true);
                return;
            }
            if (!enter && !confirmTransition("Keluar dari What If")) {
                group.getToggles().getLast().setSelected(true);
                return;
            }
            simulation = enter ? new Simulation(course) : null;
            openedScenario = null;
            showCourse(null);
            if (enter) targetField.requestFocus();
            else table.requestFocus();
        });
        return tab;
    }

    private HBox scenarioControls() {
        List<Scenario> saved = courseScenarios();
        ComboBox<Scenario> picker = new ComboBox<>(FXCollections.observableArrayList(saved));
        picker.setPromptText("Buka skenario…");
        picker.setAccessibleText("Buka skenario tersimpan");
        picker.setPrefWidth(175);
        picker.setValue(openedScenario);
        picker.setCellFactory(unused -> new ListCell<>() {
            @Override protected void updateItem(Scenario item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + (item.stale(course) ? " · data lama" : ""));
            }
        });
        picker.valueProperty().addListener((unused, before, next) -> {
            if (next == null || next.equals(openedScenario)) return;
            if (!confirmTransition("Buka skenario lain")) {
                Platform.runLater(() -> picker.setValue(openedScenario));
                return;
            }
            openScenario(next);
        });
        Button add = button("Baru", () -> {
            if (confirmTransition("Mulai skenario baru")) openScenario(null);
        });
        Button save = button("Simpan Simulasi", () -> saveScenario(false, true));
        save.getStyleClass().add("scenario-save");
        MenuButton options = new MenuButton("Opsi");
        MenuItem copy = new MenuItem("Simpan sebagai salinan");
        copy.setOnAction(event -> saveScenario(true, true));
        MenuItem compare = new MenuItem("Bandingkan skenario");
        compare.setDisable(saved.size() < 2);
        compare.setOnAction(event -> ScenarioComparison.show(stage, course, courseScenarios()));
        MenuItem rebase = new MenuItem("Tinjau data aktual terbaru");
        rebase.setDisable(simulation.baseline().sameGradeInputs(course));
        rebase.setOnAction(event -> reviewBaseline());
        MenuItem inspect = new MenuItem("Lihat data dasar");
        inspect.setOnAction(event -> inspectBaseline());
        options.getItems().setAll(copy, compare, inspect, rebase);
        scenarioStatus = label("", "muted");
        scenarioStatus.setWrapText(true);
        HBox.setHgrow(scenarioStatus, Priority.ALWAYS);
        HBox controls = new HBox(7, picker, add, save, options, scenarioStatus);
        controls.setAlignment(Pos.CENTER_LEFT);
        return controls;
    }

    private List<Scenario> courseScenarios() {
        return scenarios.stream().filter(saved -> saved.courseId().equals(course.id())).toList();
    }

    private void openScenario(Scenario saved) {
        openedScenario = saved;
        simulation = saved == null ? new Simulation(course) : Simulation.from(saved.state());
        showCourse(null);
    }

    private boolean saveScenario(boolean copy, boolean redraw) {
        BigDecimal target;
        try {
            target = simulation.target();
        } catch (IllegalArgumentException invalid) {
            setTargetError(invalid.getMessage());
            targetField.requestFocus();
            return false;
        }
        Scenario previous = copy ? null : openedScenario;
        UUID id = previous == null ? UUID.randomUUID() : previous.id();
        boolean suggestName = openedScenario == null;
        TextField name = new TextField(suggestName ? Scenario.suggestedName(target)
                : copy ? openedScenario.name() + " – salinan" : previous.name());
        name.setPromptText("Nama skenario");
        Simulation.State state = simulation.state();
        var saved = new java.util.concurrent.atomic.AtomicBoolean();
        Editor.show(stage, copy ? "Simpan sebagai salinan" : "Simpan Simulasi", "Simpan",
                new VBox(9, Editor.field("Nama skenario", name),
                        Editor.note("Skenario disimpan untuk mata kuliah ini. Nilai aktual tetap tersimpan terpisah.", "muted")),
                () -> new Scenario(id, name.getText(), state), (draft, success, failure) -> {
                    busy = true;
                    runIo(() -> { store.saveScenario(previous, draft); return draft; }, result -> {
                        busy = false;
                        scenarios = replace(scenarios, item -> item.id().equals(id), result);
                        openedScenario = result;
                        saved.set(true);
                        success.run();
                    }, message -> {
                        busy = false;
                        failure.accept(message);
                    });
                }, unused -> {}, name, () -> {
                    if (suggestName) Platform.runLater(() -> {
                        name.requestFocus();
                        name.selectAll();
                    });
                }, name.textProperty());
        if (redraw && saved.get()) showCourse(selectedRowId());
        return saved.get();
    }

    private boolean scenarioModified() {
        if (openedScenario == null) return simulation.modified() || !simulation.baseline().sameGradeInputs(course);
        Simulation.State original = openedScenario.state();
        var current = simulation.state();
        if (!current.baseline().sameGradeInputs(original.baseline()) || !current.rows().equals(original.rows())) return true;
        try {
            return simulation.target().compareTo(Numbers.read(original.target(), "Target", false, false)) != 0;
        } catch (IllegalArgumentException invalid) {
            return true;
        }
    }

    private void inspectBaseline() {
        Course baseline = simulation.baseline();
        StringBuilder text = new StringBuilder(calculationConfiguration(baseline));
        text.append("\n\nPenilaian aktual pada data dasar");
        for (Assessment assessment : baseline.assessments()) {
            text.append("\n").append(assessment.name()).append(" · ")
                    .append(baseline.categoryName(assessment.categoryId())).append(" · ")
                    .append(score(assessment.earned())).append(" / ").append(Numbers.display(assessment.maximum()));
        }
        if (baseline.assessments().isEmpty()) text.append("\nBelum ada penilaian.");
        Dialog<?> dialog = Editor.dialog(stage, "Data dasar skenario");
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Tutup", ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(detailScroll(text.toString()));
        dialog.showAndWait();
    }

    private void reviewBaseline() {
        var review = simulation.reviewBaseline(course);
        String message = "Gunakan nilai, struktur, dan aturan aktual terbaru sebagai dasar draf. "
                + "Versi tersimpan tetap berlaku sampai draf disimpan."
                + "\n\nData dasar skenario\n" + calculationConfiguration(simulation.baseline())
                + "\n\nData aktual terbaru\n" + calculationConfiguration(course)
                + "\n\n" + (review.notes().isEmpty() ? "Tidak ada edit skenario yang perlu dilepas."
                                                : String.join("\n", review.notes()));
        ButtonType apply = new ButtonType("Perbarui draf", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Batal", ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, "", cancel, apply);
        dialog.initOwner(stage);
        dialog.setTitle("Tinjau data dasar");
        dialog.setHeaderText("Tinjau data dasar");
        style(dialog.getDialogPane());
        dialog.getDialogPane().setPrefWidth(490);
        dialog.getDialogPane().setContent(detailScroll(message));
        if (dialog.showAndWait().filter(apply::equals).isEmpty()) return;
        simulation = review.draft();
        showCourse(selectedRowId());
    }

    private static ScrollPane detailScroll(String text) {
        var content = Editor.note(text, "muted");
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(235);
        return scroll;
    }

    private static String calculationConfiguration(Course baseline) {
        String kind = baseline.legacyWeights() ? "Bobot relatif lama" : "Persentase kategori";
        String weights = String.join(", ", baseline.categories().stream().map(category -> category.name()
                + " " + Numbers.display(category.weight()) + (baseline.legacyWeights() ? "" : "%")).toList());
        return "Metode dalam kategori: " + baseline.method() + "\n" + kind + ": " + weights;
    }

    private VBox targetControls() {
        targetField = new TextField(simulation.targetInput());
        targetField.setAccessibleText("Target persentase minimum");
        targetField.setPrefColumnCount(7);
        targetField.setMaxWidth(98);
        targetLetter = new ComboBox<>(FXCollections.observableArrayList(Grade.values()));
        targetLetter.setAccessibleText("Huruf target");
        targetLetter.setPrefWidth(78);
        targetError = label("", "validation");
        targetError.setWrapText(true);
        targetError.managedProperty().bind(targetError.visibleProperty());
        targetError.setVisible(false);
        targetField.textProperty().addListener((unused, before, next) -> {
            if (syncingTarget) return;
            simulation.targetInput(next);
            refreshResults(selectedRowId());
        });
        targetLetter.setOnAction(event -> applyTargetLetter());
        targetLetter.setCellFactory(unused -> {
            ListCell<Grade> cell = new ListCell<>() {
                @Override protected void updateItem(Grade item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item.toString());
                }
            };
            cell.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (!cell.isEmpty() && cell.getItem() == targetLetter.getValue()) applyTargetLetter();
            });
            return cell;
        });
        targetLetter.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (targetLetter.isShowing() && event.getCode() == KeyCode.ENTER) applyTargetLetter();
        });

        HBox percentage = new HBox(6, targetField, label("%", "muted"));
        percentage.setAlignment(Pos.CENTER_LEFT);
        HBox fields = new HBox(10, label("Target minimum", "field-label"), percentage, targetLetter, spacer(),
                button("Atur ulang", this::resetSimulation));
        fields.setAlignment(Pos.CENTER_LEFT);
        VBox region = new VBox(6, fields, targetError);
        region.getStyleClass().add("target-panel");
        return region;
    }

    private void applyTargetLetter() {
        if (syncingTarget || targetLetter.getValue() == null) return;
        simulation.chooseGrade(targetLetter.getValue());
        syncingTarget = true;
        targetField.setText(simulation.targetInput());
        syncingTarget = false;
        refreshResults(selectedRowId());
    }

    private HBox summary() {
        metricTitle = label("", "eyebrow");
        metricValue = label("", "metric");
        metricValue.setMaxWidth(190);
        Tooltip fullMetric = new Tooltip();
        fullMetric.textProperty().bind(metricValue.textProperty());
        metricValue.setTooltip(fullMetric);
        metricGrade = label("", "metric-grade");
        HBox measure = new HBox(12, metricValue, metricGrade);
        measure.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(4, metricTitle, measure);
        left.setMinWidth(Region.USE_PREF_SIZE);
        resultStatus = label("", "result-badge");
        resultStatus.setWrapText(true);
        resultStatus.setMinHeight(Region.USE_PREF_SIZE);
        resultDetail = label("", "muted");
        resultDetail.setWrapText(true);
        resultDetail.setMinHeight(Region.USE_PREF_SIZE);
        VBox right = new VBox(6, resultStatus, resultDetail);
        right.setAlignment(Pos.CENTER_LEFT);
        right.setMaxWidth(570);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox panel = new HBox(22, left, spacer(), right);
        panel.getStyleClass().add("summary");
        panel.setAlignment(Pos.CENTER_LEFT);
        return panel;
    }

    private VBox assessmentArea() {
        table = new TableView<>();
        table.getStyleClass().add("assessments");
        table.setFixedCellSize(simulation == null ? 38 : 32);
        if (simulation != null) {
            table.getStyleClass().add("simulation-assessments");
            table.setMinHeight(Region.USE_PREF_SIZE);
            table.setMaxHeight(Region.USE_PREF_SIZE);
            table.setSkin(new CompactAssessmentSkin(table));
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<ViewRow, String> name = column("Penilaian", 115, 234, ViewRow::name, "row-name");
        TableColumn<ViewRow, String> category = column("Kategori", 76, 140, ViewRow::category, "");
        TableColumn<ViewRow, String> max = column("Maks.", simulation == null ? 72 : 57, 78, ViewRow::maximum, "numeric");
        TableColumn<ViewRow, String> entered = column(simulation == null ? "Diperoleh" : "Nilai tetap",
                simulation == null ? 100 : 84, 112, ViewRow::entered, "numeric");
        if (simulation == null) {
            table.getColumns().setAll(List.of(name, category, max, entered,
                    column("Status", 118, 165, ViewRow::status, "row-status")));
        } else {
            table.getColumns().setAll(List.of(name, category, max, entered,
                    column("Diperlukan", 171, 185, ViewRow::required, "numeric required"),
                    column("Status", 99, 173, ViewRow::status, "row-status")));
        }

        VBox placeholder = new VBox(4, label("Belum ada penilaian", "field-label"),
                label(simulation == null ? "Gunakan tombol Tambah penilaian." : "Tambahkan penilaian untuk mencoba proyeksi.", "muted"));
        placeholder.setAlignment(Pos.CENTER);
        table.setPlaceholder(placeholder);
        table.setRowFactory(unused -> {
            TableRow<ViewRow> row = new TableRow<>() {
                @Override protected void updateItem(ViewRow item, boolean empty) {
                    super.updateItem(item, empty);
                    pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("excluded"),
                            !empty && item.excluded());
                }
            };
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) editAssessment(row.getItem().id());
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && selectedRowId() != null) {
                editAssessment(selectedRowId());
                event.consume();
            }
        });

        Button add = button(simulation == null ? "+ Tambah penilaian" : "+ Penilaian hipotetis",
                () -> editAssessment(null));
        add.getStyleClass().add("primary");
        tableCount = label("", "muted");
        VBox title = new VBox(2, heading("Penilaian", "section-title"), tableCount);
        HBox top = new HBox(10, title, spacer(), add);
        top.setAlignment(Pos.CENTER_LEFT);

        Button edit = button("Ubah", () -> editAssessment(selectedRowId()));
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        HBox actions = new HBox(8, edit);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (simulation == null) {
            Button delete = button("Hapus penilaian", this::deleteAssessment);
            delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
            actions.getChildren().addAll(delete, spacer(), label("Pilih baris untuk mengubahnya.", "muted"));
        } else {
            Button exclude = button("Keluarkan", this::toggleExcluded);
            Button restore = button("Pulihkan aktual", this::restoreOriginal);
            exclude.setDisable(true);
            restore.setDisable(true);
            table.getSelectionModel().selectedItemProperty().addListener((unused, before, row) -> {
                exclude.setDisable(row == null);
                exclude.setText(row != null && row.excluded() ? "Sertakan kembali" : "Keluarkan");
                restore.setDisable(row == null || !row.restorable());
            });
            actions.getChildren().addAll(exclude, restore);
        }

        if (simulation != null) {
            return new VBox(7, top, actions, table);
        }
        VBox area = new VBox(9, top, table, actions);
        VBox.setVgrow(area, Priority.ALWAYS);
        VBox.setVgrow(table, Priority.ALWAYS);
        return area;
    }

    private static final class CompactAssessmentSkin extends TableViewSkin<ViewRow> {
        private static final int VISIBLE_ROWS = 8;

        CompactAssessmentSkin(TableView<ViewRow> table) {
            super(table);
            table.getItems().addListener((javafx.beans.InvalidationListener) change -> table.requestLayout());
        }

        @Override protected double computePrefHeight(double width, double top, double right,
                                                    double bottom, double left) {
            TableView<ViewRow> table = getSkinnable();
            int rows = Math.min(VISIBLE_ROWS, table.getItems().size());
            double content = rows == 0 ? 76 : rows * table.getFixedCellSize();
            return top + getTableHeaderRow().prefHeight(-1) + content + bottom + 1;
        }
    }

    private static TableColumn<ViewRow, String> column(String title, double min, double pref,
                                                       Function<ViewRow, String> value, String style) {
        TableColumn<ViewRow, String> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setReorderable(false);
        column.setMinWidth(min);
        column.setPrefWidth(pref);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(value.apply(row.getValue())));
        column.setCellFactory(unused -> new TableCell<>() {
            private final Tooltip fullText = new Tooltip();

            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                fullText.setText(text);
                setTooltip(empty ? null : fullText);
            }
        });
        if (!style.isBlank()) column.getStyleClass().addAll(style.split(" "));
        return column;
    }

    private void refreshResults(UUID selectRow) {
        if (course == null || table == null) return;
        if (simulation == null) {
            Calculation.Actual result = Calculation.actual(course);
            List<ViewRow> rows = course.assessments().stream().map(this::actualRow).toList();
            fillTable(rows, selectRow, result.known() + " dari " + result.total() + " memiliki nilai");
            if (invalidDraft) {
                showResult("Nilai aktual", null, "Hasil tidak tersedia", "Perbaiki isian pada formulir.", "warning");
            } else if (result.percent() == null) {
                showResult("Nilai aktual", null, "Belum ada nilai", "Hasil muncul setelah Anda menyimpan nilai pertama.", "");
            } else if (result.partial()) {
                String category = result.details().size() == 1 ? " " + result.details().getFirst().name() : "";
                showResult("Nilai sementara", result.percent(), "Sebagian dinilai",
                        "Kontribusi" + category + " ke nilai akhir: " + ResultText.value(result.contribution())
                                + " poin persentase.\nBobot kategori yang sudah terwakili: "
                                + ResultText.percent(result.represented()) + ".", "");
                if (result.details().size() > 1) {
                    resultDetail.setTooltip(new Tooltip(String.join("\n", result.details().stream().map(detail ->
                            detail.name() + ": " + ResultText.value(detail.points()) + " poin persentase"
                                    + " · bobot " + ResultText.percent(detail.weight())).toList())));
                }
            } else {
                showResult("Nilai aktual", result.percent(), "Lengkap", "Semua penilaian sudah memiliki nilai.", "positive");
            }
            return;
        }

        String lineage = simulation.baseline().sameGradeInputs(course) ? "" : " · Data dasar lama";
        if (simulation.baseline().legacyWeights()) lineage += " · Bobot relatif lama";
        scenarioStatus.setText((scenarioModified() ? "Belum disimpan"
                : openedScenario == null ? "Draf baru" : "Tersimpan") + lineage);
        Calculation.TargetReport report = null;
        String calculationError = "";
        try {
            var target = simulation.target();
            syncLetter(Grade.classify(Exact.of(target)));
            setTargetError("");
            if (!invalidDraft) report = Calculation.targetReport(simulation);
        } catch (IllegalArgumentException invalid) {
            syncLetter(null);
            calculationError = invalid.getMessage();
            setTargetError(calculationError);
        }
        targetSummary.render(simulation, openedScenario, course, report, calculationError);
        Calculation.Projection projection = report == null ? null : report.selected();
        List<ViewRow> rows = simulation.rows().stream().map(row -> simulationRow(row, projection)).toList();
        long included = rows.stream().filter(row -> !row.excluded()).count();
        fillTable(rows, selectRow, included + " dari " + rows.size() + " disertakan");
        if (projection == null) {
            showResult("Nilai proyeksi", null, "Hasil tidak tersedia", "Perbaiki isian yang ditandai.", "warning");
        } else if (projection.status() == Calculation.Status.EMPTY) {
            showResult("Nilai proyeksi", null, "Belum ada hasil", "Sertakan minimal satu penilaian untuk menghitung proyeksi.", "");
        } else {
            showProjection(projection);
        }
    }

    private void showProjection(Calculation.Projection projection) {
        boolean unreachable = projection.status() == Calculation.Status.UNREACHABLE;
        boolean conditional = !projection.aboveMaximumAssumptions().isEmpty();
        String detail = "Target " + Numbers.display(simulation.target()) + "% · Maksimum "
                + ResultText.percent(projection.maximum());
        if (unreachable) {
            detail += "\nKekurangan: " + ResultText.value(projection.shortfall()) + " poin persentase. "
                    + (projection.suggestions().isEmpty() ? "Semua penilaian yang disertakan memiliki nilai tetap."
                    : "Penilaian tersisa tidak cukup dalam batas skor normal.");
        }
        if (conditional) detail += "\nBersyarat: asumsi " + String.join(", ", projection.aboveMaximumAssumptions())
                + " melewati maksimum.";
        if (projection.represented().compareTo(Exact.HUNDRED) < 0) {
            detail += "\nBobot disertakan: " + ResultText.percent(projection.represented())
                    + (simulation.baseline().legacyWeights() ? " (aturan relatif lama)." : ". Bobot kategori kosong tetap tidak terwakili.");
        }
        showResult(unreachable ? "Maksimum dalam batas normal" : "Nilai proyeksi",
                unreachable ? projection.maximum() : projection.percent(),
                (conditional && !unreachable ? "Bersyarat · " : "") + ResultText.status(projection), detail,
                unreachable || conditional ? "warning" : "positive");
    }

    private void syncLetter(Grade grade) {
        syncingTarget = true;
        targetLetter.setValue(grade);
        syncingTarget = false;
    }

    private void setTargetError(String message) {
        targetError.setText(message);
        targetError.setVisible(!message.isEmpty());
        targetField.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), !message.isEmpty());
    }

    private ViewRow actualRow(Assessment row) {
        return new ViewRow(row.id(), row.name(), course.categoryName(row.categoryId()), Numbers.display(row.maximum()),
                score(row.earned()), NONE, row.earned() == null ? "Belum dinilai" : "Aktual", false, false);
    }

    private ViewRow simulationRow(Simulation.Row row, Calculation.Projection projection) {
        Assessment value = row.value();
        String status = row.excluded() ? "Dikeluarkan"
                : row.assumption() ? "Asumsi tetap"
                : value.earned() == null ? "Bebas" : "Aktual";
        if (row.original() == null) status += " · Hipotetis";
        else if (row.modified()) status += " · Diubah";
        var recommendation = ResultText.scoreRecommendation(projection, value);
        String required = recommendation == null ? NONE : recommendation.display();
        return new ViewRow(value.id(), value.name(), simulation.baseline().categoryName(value.categoryId()), Numbers.display(value.maximum()),
                score(value.earned()), required, status, row.excluded(),
                row.original() != null && (row.modified() || row.excluded()));
    }

    private void fillTable(List<ViewRow> rows, UUID selected, String count) {
        if (!table.getItems().equals(rows)) table.getItems().setAll(rows);
        if (selected != null) {
            rows.stream().filter(row -> row.id().equals(selected)).findFirst()
                    .ifPresent(row -> table.getSelectionModel().select(row));
        }
        tableCount.setText(count);
    }

    private void showResult(String title, Exact percent, String status, String detail, String style) {
        metricTitle.setText(title);
        metricValue.setText(percent == null ? NONE : Numbers.display(percent.decimal(2, RoundingMode.DOWN)) + "%");
        Grade grade = percent == null ? null : Grade.classify(percent);
        metricGrade.setText(grade == null ? "" : grade + " · poin " + grade.point.toPlainString().replace('.', ','));
        metricGrade.setVisible(grade != null);
        metricGrade.setManaged(grade != null);
        resultStatus.setText(status);
        resultStatus.getStyleClass().setAll("label", "result-badge");
        if (!style.isEmpty()) resultStatus.getStyleClass().add(style);
        resultDetail.setTooltip(null);
        resultDetail.setText(detail);
    }

    private void editCourse(Course previous) {
        if (previous == null && !confirmTransition("Tambah mata kuliah")) return;
        Editor.course(stage, previous, this::invalidDraft, (draft, success, failure) ->
                save(previous, draft, null, success, failure));
    }

    private void manageCategories() {
        Course previous = course;
        UUID selected = selectedRowId();
        CategoryEditor.show(stage, previous, this::invalidDraft, (draft, success, failure) ->
                save(previous, draft, selected, success, failure));
    }

    private void editAssessment(UUID id) {
        if (simulation == null) {
            Course expected = course;
            Assessment previous = expected.assessments().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
            Editor.assessment(stage, expected, previous, null, expected.assessments(), false, this::invalidDraft, (draft, success, failure) -> {
                Assessment value = draft.assessment();
                Course next = expected.withAssessments(replace(expected.assessments(), row -> row.id().equals(id), value));
                save(expected, next, value.id(), success, failure);
            });
        } else {
            Simulation.Row row = simulation.rows().stream().filter(item -> item.value().id().equals(id)).findFirst().orElse(null);
            List<Assessment> included = simulation.rows().stream().filter(item -> !item.excluded())
                    .map(Simulation.Row::value).toList();
            Editor.assessment(stage, simulation.baseline(), row == null ? null : row.value(), row,
                    included, true, this::invalidDraft,
                    (draft, success, failure) -> {
                        simulation.put(draft.assessment(), draft.assumption());
                        refreshResults(draft.assessment().id());
                        success.run();
                    });
        }
    }

    private void deleteAssessment() {
        UUID selected = selectedRowId();
        Course expected = course;
        Assessment row = expected.assessments().stream().filter(item -> item.id().equals(selected)).findFirst().orElseThrow();
        if (!confirm(stage, "Hapus penilaian", "Hapus penilaian “" + row.name() + "” dari nilai aktual?", "Hapus")) return;
        Course next = expected.withAssessments(expected.assessments().stream().filter(item -> !item.id().equals(selected)).toList());
        save(expected, next, null, () -> {}, message -> errorAlert(stage, message));
    }

    private void deleteSelectedCourse() {
        Course selected = course;
        if (!confirm(stage, "Hapus mata kuliah",
                "Hapus “" + selected.name() + "” beserta kategori, penilaian, dan skenario tersimpan?", "Hapus mata kuliah")) return;
        deleteCourse(selected);
    }

    private void deleteCourse(Course selected) {
        busy = true;
        root.setDisable(true);
        runIo(() -> { store.delete(selected); return null; }, unused -> {
            root.setDisable(false);
            busy = false;
            int index = courses.indexOf(selected);
            selectingCourse = true;
            courses.remove(selected);
            scenarios = scenarios.stream().filter(item -> !item.courseId().equals(selected.id())).toList();
            selectingCourse = false;
            updateCount();
            course = courses.isEmpty() ? null : courses.get(Math.min(index, courses.size() - 1));
            selectInSidebar(course);
            showCourse(null);
        }, message -> {
            root.setDisable(false);
            busy = false;
            errorAlert(stage, message);
        });
    }

    private void save(Course expected, Course draft, UUID row, Runnable success, Consumer<String> failure) {
        busy = true;
        root.setDisable(true);
        runIo(() -> { store.save(expected, draft); return draft; }, saved -> {
            busy = false;
            root.setDisable(false);
            selectingCourse = true;
            if (expected == null) courses.add(saved);
            else courses.set(courses.indexOf(expected), saved);
            selectingCourse = false;
            course = saved;
            simulation = null;
            openedScenario = null;
            updateCount();
            selectInSidebar(saved);
            showCourse(row);
            success.run();
        }, message -> {
            busy = false;
            root.setDisable(false);
            failure.accept(message);
        });
    }

    private <T> void runIo(Callable<T> job, Consumer<T> success, Consumer<String> failure) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return job.call(); }
        };
        task.setOnSucceeded(event -> success.accept(task.getValue()));
        task.setOnFailed(event -> {
            Throwable cause = task.getException();
            System.err.println("What If SQLite: " + cause);
            String detail = cause.getMessage();
            boolean readable = detail != null && (detail.startsWith("Data telah berubah")
                    || detail.startsWith("Versi basis data") || detail.startsWith("Skema basis data")
                    || detail.startsWith("Basis data rusak") || detail.startsWith("Data skenario")
                    || detail.startsWith("Data mata kuliah"));
            boolean corrupt = cause instanceof org.sqlite.SQLiteException sqlite
                    && (sqlite.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CORRUPT
                        || sqlite.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_NOTADB);
            failure.accept(readable ? detail : corrupt ? "Basis data rusak. Pulihkan dari cadangan sebelum mencoba lagi."
                    : "Penyimpanan lokal belum bisa diakses. Coba lagi.");
        });
        io.execute(task);
    }

    private void invalidDraft(boolean invalid) {
        if (invalidDraft == invalid) return;
        invalidDraft = invalid;
        refreshResults(selectedRowId());
    }

    private void toggleExcluded() {
        UUID id = selectedRowId();
        Simulation.Row row = simulation.rows().stream().filter(item -> item.value().id().equals(id)).findFirst().orElseThrow();
        simulation.setExcluded(id, !row.excluded());
        refreshResults(id);
    }

    private void restoreOriginal() {
        UUID id = selectedRowId();
        simulation.restore(id);
        refreshResults(id);
    }

    private void resetSimulation() {
        String description = openedScenario == null
                ? "Kembalikan draf ke data dasarnya dan target awal 85%?"
                : "Kembalikan draf ke isi dan data dasar yang terakhir disimpan?";
        if (scenarioModified() && !confirm(stage, "Atur ulang draf", description, "Atur ulang")) return;
        if (openedScenario == null) simulation.reset();
        else simulation = Simulation.from(openedScenario.state());
        showCourse(selectedRowId());
    }

    private boolean confirmTransition(String title) {
        if (simulation == null || !scenarioModified()) return true;
        ButtonType save = new ButtonType("Simpan Simulasi", ButtonData.YES);
        ButtonType discard = new ButtonType("Buang Perubahan", ButtonData.NO);
        ButtonType cancel = new ButtonType("Batal", ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Ada perubahan What If sejak terakhir disimpan.", cancel, discard, save);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        style(alert.getDialogPane());
        Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        alert.setOnShown(event -> cancelButton.requestFocus());
        ButtonType result = alert.showAndWait().orElse(cancel);
        return result == discard || (result == save && saveScenario(false, false));
    }

    private static boolean confirm(Window owner, String title, String message, String action) {
        ButtonType proceed = new ButtonType(action, ButtonData.YES);
        ButtonType cancel = new ButtonType("Batal", ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, cancel, proceed);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        style(alert.getDialogPane());
        Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        alert.setOnShown(event -> cancelButton.requestFocus());
        return alert.showAndWait().filter(proceed::equals).isPresent();
    }

    private static void errorAlert(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, new ButtonType("Tutup", ButtonData.CANCEL_CLOSE));
        alert.initOwner(owner);
        alert.setTitle("Penyimpanan lokal");
        alert.setHeaderText("Perubahan belum tersimpan");
        style(alert.getDialogPane());
        alert.showAndWait();
    }

    private void showEmpty(String title, String description, Button action) {
        Label caption = Editor.note(description, "muted");
        caption.setMaxWidth(420);
        caption.setAlignment(Pos.CENTER);
        VBox empty = new VBox(13, heading(title, "page-title"), caption);
        empty.getStyleClass().add("empty-page");
        empty.setAlignment(Pos.CENTER);
        if (action != null) {
            action.getStyleClass().add("primary");
            empty.getChildren().add(action);
        }
        root.setCenter(empty);
        if (action != null) Platform.runLater(action::requestFocus);
    }

    private void updateCount() { courseCount.setText(Integer.toString(courses.size())); }

    private UUID selectedRowId() {
        if (table == null) return null;
        ViewRow row = table.getSelectionModel().getSelectedItem();
        return row == null ? null : row.id();
    }

    private static String score(BigDecimal value) { return value == null ? NONE : Numbers.display(value); }


    private static Region spacer() {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return gap;
    }

    private static Label label(String text, String css) {
        Label label = new Label(text);
        label.getStyleClass().add(css);
        return label;
    }

    static Label heading(String text, String css) {
        Label label = label(text, css);
        label.setStyle("-fx-font-family: '" + headingFamily + "'; -fx-font-weight: bold;");
        return label;
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static <T> List<T> replace(List<T> items, Predicate<T> same, T value) {
        List<T> result = new ArrayList<>(items);
        for (int index = 0; index < result.size(); index++) {
            if (same.test(result.get(index))) {
                result.set(index, value);
                return result;
            }
        }
        result.add(value);
        return result;
    }

    private record Workspace(List<Course> courses, List<Scenario> scenarios) {}

    private record ViewRow(UUID id, String name, String category, String maximum, String entered, String required,
                           String status, boolean excluded, boolean restorable) {}
}
