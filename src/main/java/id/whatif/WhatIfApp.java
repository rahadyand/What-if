package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBar.ButtonData;
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
import java.util.Map;
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
            if (busy || !confirmDiscard("Tutup What If")) event.consume();
        });
        stage.show();
        initialize();
        root.applyCss();
        Label heading = (Label) root.lookup(".brand-title");
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
        Label mark = label("?", "brand-mark");
        Label brand = heading("What If", "brand-title");
        HBox identity = new HBox(11, mark, brand);
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
            return store.load();
        }, loaded -> {
            busy = false;
            addCourse.setDisable(false);
            courses.setAll(loaded);
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
        if (!confirmDiscard("Ganti mata kuliah")) {
            selectInSidebar(course);
            return;
        }
        course = next;
        simulation = null;
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

        Label method = label("Metode · " + course.method(), "muted");
        method.setTooltip(new Tooltip(Aggregation.EXAMPLE));
        Button categories = button("Kelola kategori", this::manageCategories);
        categories.setVisible(simulation == null);
        categories.setManaged(categories.isVisible());
        HBox navigation = new HBox(12, tabs, spacer(), method, categories);
        navigation.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(14, top, navigation);
        page.getStyleClass().add("page");
        if (simulation != null) {
            page.getChildren().add(targetControls());
        }
        page.getChildren().addAll(summary(), assessmentArea());
        root.setCenter(page);
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
            if (!enter && !confirmDiscard("Keluar dari What If")) {
                group.getToggles().getLast().setSelected(true);
                return;
            }
            simulation = enter ? new Simulation(course) : null;
            showCourse(null);
            if (enter) targetField.requestFocus();
            else table.requestFocus();
        });
        return tab;
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
        Label message = label("Eksperimen lokal. Perubahan What If dibuang saat sesi berakhir.", "muted");
        message.setWrapText(true);
        VBox region = new VBox(7, fields, targetError, message);
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
        table.setFixedCellSize(38);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<ViewRow, String> name = column("Penilaian", 115, 234, ViewRow::name, "row-name");
        TableColumn<ViewRow, String> category = column("Kategori", 76, 140, ViewRow::category, "");
        TableColumn<ViewRow, String> max = column("Maks.", 72, 78, ViewRow::maximum, "numeric");
        TableColumn<ViewRow, String> entered = column(simulation == null ? "Diperoleh" : "Nilai tetap",
                100, 112, ViewRow::entered, "numeric");
        if (simulation == null) {
            table.getColumns().setAll(List.of(name, category, max, entered,
                    column("Status", 118, 165, ViewRow::status, "row-status")));
        } else {
            table.getColumns().setAll(List.of(name, category, max, entered,
                    column("Diperlukan", 110, 119, ViewRow::required, "numeric required"),
                    column("Status", 141, 200, ViewRow::status, "row-status")));
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

        VBox area = new VBox(9, top, table, actions);
        VBox.setVgrow(area, Priority.ALWAYS);
        VBox.setVgrow(table, Priority.ALWAYS);
        return area;
    }

    private static TableColumn<ViewRow, String> column(String title, double min, double pref,
                                                       Function<ViewRow, String> value, String style) {
        TableColumn<ViewRow, String> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setReorderable(false);
        column.setMinWidth(min);
        column.setPrefWidth(pref);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(value.apply(row.getValue())));
        if (!style.contains("numeric")) {
            column.setCellFactory(unused -> new TableCell<>() {
                private final Tooltip fullText = new Tooltip();

                @Override protected void updateItem(String text, boolean empty) {
                    super.updateItem(text, empty);
                    setText(empty ? null : text);
                    fullText.setText(text);
                    setTooltip(empty ? null : fullText);
                }
            });
        }
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
                showResult("Nilai sementara", result.percent(), "Sebagian dinilai",
                        "Perhitungan memakai penilaian yang sudah memiliki nilai.", "");
            } else {
                showResult("Nilai aktual", result.percent(), "Lengkap", "Semua penilaian sudah memiliki nilai.", "positive");
            }
            return;
        }

        Calculation.Projection projection = null;
        try {
            var target = simulation.target();
            syncLetter(Grade.classify(Exact.of(target)));
            setTargetError("");
            if (!invalidDraft) projection = Calculation.project(simulation);
        } catch (IllegalArgumentException invalid) {
            syncLetter(null);
            setTargetError(invalid.getMessage());
        }
        Map<UUID, BigDecimal> suggestions = projection == null ? Map.of() : projection.suggestions();
        List<ViewRow> rows = simulation.rows().stream().map(row -> simulationRow(row, suggestions)).toList();
        long included = rows.stream().filter(row -> !row.excluded()).count();
        fillTable(rows, selectRow, included + " dari " + rows.size() + " disertakan");
        if (projection == null) {
            showResult("Nilai proyeksi", null, "Hasil tidak tersedia", "Perbaiki isian yang ditandai.", "warning");
        } else if (projection.status() == Calculation.Status.EMPTY) {
            showResult("Nilai proyeksi", null, "Belum ada hasil", "Sertakan minimal satu penilaian untuk menghitung proyeksi.", "");
        } else if (projection.bonus()) {
            showResult("Nilai proyeksi", projection.percent(), "Perlu poin bonus",
                    "Saran melampaui maksimum pada setidaknya satu baris. Bonus belum tentu tersedia.", "warning");
        } else {
            switch (projection.status()) {
                case SECURED -> showResult("Nilai proyeksi", projection.percent(), "Target sudah aman",
                        projection.suggestions().isEmpty() ? "Semua penilaian yang disertakan memiliki nilai tetap."
                                : "Nilai tetap sudah cukup. Penilaian bebas memerlukan 0 poin.", "positive");
                case FIXED_BELOW -> showResult("Nilai proyeksi", projection.percent(), "Target belum tercapai",
                        "Semua penilaian yang disertakan memiliki nilai tetap.", "warning");
                case WITH_SUGGESTIONS -> showResult("Nilai proyeksi", projection.percent(), "Target tercapai dengan saran",
                        "Saran dengan persentase yang sama adalah salah satu cara mencapai target.", "positive");
                case EMPTY -> throw new IllegalStateException();
            }
        }
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

    private ViewRow simulationRow(Simulation.Row row, Map<UUID, BigDecimal> suggestions) {
        Assessment value = row.value();
        String status = row.excluded() ? "Dikeluarkan"
                : row.assumption() ? "Asumsi tetap"
                : value.earned() == null ? "Bebas" : "Aktual";
        if (row.original() == null) status += " · Hipotetis";
        else if (row.modified()) status += " · Diubah";
        return new ViewRow(value.id(), value.name(), course.categoryName(value.categoryId()), Numbers.display(value.maximum()),
                score(value.earned()), score(suggestions.get(value.id())), status, row.excluded(),
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
        resultDetail.setText(detail);
    }

    private void editCourse(Course previous) {
        if (previous == null && !confirmDiscard("Tambah mata kuliah")) return;
        Editor.course(stage, previous, this::invalidDraft, (draft, success, failure) ->
                save(previous, draft, null, success, failure));
    }

    private void manageCategories() {
        Dialog<Void> dialog = Editor.dialog(stage, "Kategori dan bobot");
        dialog.getDialogPane().getButtonTypes().setAll(new ButtonType("Selesai", ButtonData.CANCEL_CLOSE));
        ListView<Category> list = new ListView<>();
        list.setPrefHeight(180);
        list.getItems().setAll(course.categories());
        list.setCellFactory(unused -> new ListCell<>() {
            @Override protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item.name() + "   ·   bobot " + Numbers.display(item.weight()));
            }
        });
        Label error = Editor.note("", "validation");
        error.setVisible(false);
        error.managedProperty().bind(error.visibleProperty());
        Window owner = dialog.getDialogPane().getScene().getWindow();
        Button add = button("+ Kategori", () -> editCategory(owner, null, list));
        Button edit = button("Ubah", () -> editCategory(owner, list.getSelectionModel().getSelectedItem(), list));
        Button delete = button("Hapus", () -> {
            Category selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (course.assessments().stream().anyMatch(row -> row.categoryId().equals(selected.id()))) {
                error.setText("Pindahkan penilaiannya ke kategori lain terlebih dahulu.");
                error.setVisible(true);
                return;
            }
            if (course.categories().size() == 1) {
                error.setText("Sisakan minimal satu kategori pada mata kuliah.");
                error.setVisible(true);
                return;
            }
            if (!confirm(owner, "Hapus kategori", "Hapus kategori “" + selected.name() + "”?", "Hapus")) return;
            Course previous = course;
            Course next = previous.withCategories(previous.categories().stream()
                    .filter(item -> !item.id().equals(selected.id())).toList());
            dialog.getDialogPane().setDisable(true);
            save(previous, next, selectedRowId(), () -> {
                dialog.getDialogPane().setDisable(false);
                list.getItems().setAll(course.categories());
            }, message -> {
                dialog.getDialogPane().setDisable(false);
                error.setText(message);
                error.setVisible(true);
            });
        });
        edit.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(edit.disableProperty());
        list.getSelectionModel().selectedItemProperty().addListener(unused -> error.setVisible(false));
        dialog.setOnCloseRequest(event -> { if (busy) event.consume(); });
        dialog.getDialogPane().setContent(new VBox(12,
                Editor.note("Bobot menentukan proporsi antar kategori yang ikut dihitung.", "muted"),
                list, new HBox(8, add, edit, delete), error));
        dialog.showAndWait();
    }

    private void editCategory(Window owner, Category previous, ListView<Category> list) {
        Editor.category(owner, previous, this::invalidDraft, (draft, success, failure) -> {
            Course expected = course;
            Course next = expected.withCategories(replace(expected.categories(), c -> c.id().equals(draft.id()), draft));
            save(expected, next, selectedRowId(), () -> {
                list.getItems().setAll(course.categories());
                list.getSelectionModel().select(draft);
                success.run();
            }, failure);
        });
    }

    private void editAssessment(UUID id) {
        if (simulation == null) {
            Course expected = course;
            Assessment previous = expected.assessments().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
            Editor.assessment(stage, expected, previous, null, false, this::invalidDraft, (draft, success, failure) -> {
                Assessment value = draft.assessment();
                Course next = expected.withAssessments(replace(expected.assessments(), row -> row.id().equals(id), value));
                save(expected, next, value.id(), success, failure);
            });
        } else {
            Simulation.Row row = simulation.rows().stream().filter(item -> item.value().id().equals(id)).findFirst().orElse(null);
            Editor.assessment(stage, course, row == null ? null : row.value(), row, true, this::invalidDraft,
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
                "Hapus “" + selected.name() + "” beserta kategori dan penilaiannya?", "Hapus mata kuliah")) return;
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
            failure.accept(detail != null && detail.startsWith("Data telah berubah")
                    ? detail : "Penyimpanan lokal belum bisa diakses. Coba lagi.");
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
        if (!confirmDiscard("Atur ulang simulasi")) return;
        simulation.reset();
        syncingTarget = true;
        targetField.setText(simulation.targetInput());
        syncingTarget = false;
        refreshResults(selectedRowId());
    }

    private boolean confirmDiscard(String title) {
        return simulation == null || !simulation.modified()
                || confirm(stage, title, "Perubahan simulasi sementara akan dibuang.", "Buang simulasi");
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

    private static Label heading(String text, String css) {
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

    private record ViewRow(UUID id, String name, String category, String maximum, String entered, String required,
                           String status, boolean excluded, boolean restorable) {}
}
