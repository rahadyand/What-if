package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;

import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class Editor {
    private Editor() {}

    static void course(Window owner, Course previous, List<Semester> semesters, UUID initialSemester,
                       Consumer<Boolean> invalid, Save<Course> save) {
        TextField name = text(previous == null ? "" : previous.name(), "Contoh: Pemrograman Berorientasi Objek");
        AcademicDetails current = previous == null ? AcademicDetails.EMPTY : previous.academic();
        ComboBox<Semester> semester = new ComboBox<>();
        semester.getItems().setAll(semesters);
        semester.setPromptText(previous == null ? "Pilih semester" : AcademicDetails.UNGROUPED);
        UUID semesterId = previous == null ? initialSemester : current.semesterId();
        semesters.stream().filter(item -> item.id().equals(semesterId)).findFirst().ifPresent(semester::setValue);
        semester.setMinWidth(0);
        TextField sks = wholeField(current.sks());
        TextField present = wholeField(current.present());
        TextField meetings = wholeField(current.meetings());
        VBox semesterField = field("Semester", semester);
        VBox sksField = field("SKS (opsional)", sks);
        HBox placement = fieldPair(semesterField, sksField);
        semesterField.setPrefWidth(245);
        sksField.setPrefWidth(125);
        ComboBox<Aggregation> method = new ComboBox<>();
        method.getItems().setAll(Aggregation.values());
        method.setTooltip(new Tooltip(Aggregation.EXAMPLE));
        method.setValue(previous == null ? Aggregation.TOTAL_POINTS : previous.method());
        VBox content = new VBox(9, field("Nama mata kuliah", name), placement,
                fieldPair(field("Jumlah hadir", present), field("Pertemuan terlaksana", meetings)),
                note("Kehadiran opsional. Isi kedua angka atau kosongkan keduanya.", "muted"),
                field("Metode per kategori", method));
        Supplier<AcademicDetails> readAcademic = () -> {
            Semester selected = semester.getValue();
            if (previous == null && selected == null) throw new IllegalArgumentException("Pilih semester untuk mata kuliah baru.");
            return AcademicDetails.read(selected == null ? null : selected.id(), sks.getText(), present.getText(), meetings.getText());
        };
        if (previous != null) {
            content.getChildren().add(note(Aggregation.EXAMPLE, "muted"));
            show(owner, "Pengaturan mata kuliah", "Simpan", content,
                    () -> previous.withSettings(name.getText(), method.getValue()).withAcademic(readAcademic.get()), save, invalid, name,
                    name.textProperty(), method.valueProperty(), semester.valueProperty(), sks.textProperty(),
                    present.textProperty(), meetings.textProperty());
            return;
        }

        var categories = new CategoryEditor(null);
        Label heading = note("Kategori dan bobot", "field-label");
        content.getChildren().addAll(heading, categories.content());
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(390);
        scroll.getStyleClass().add("course-form-scroll");
        show(owner, "Tambah mata kuliah", "Simpan", new VBox(scroll), () -> {
            var academic = readAcademic.get();
            var selected = categories.readCategories();
            return Course.create(name.getText(), method.getValue(), selected).withAcademic(academic);
        }, save, invalid, name, name.textProperty(), method.valueProperty(), semester.valueProperty(), sks.textProperty(),
                present.textProperty(), meetings.textProperty(), categories.changes());
    }

    static void semester(Window owner, Semester previous, Save<Semester> save) {
        UUID id = previous == null ? UUID.randomUUID() : previous.id();
        TextField label = text(previous == null ? "" : previous.label(), "Contoh: 3 atau Semester Antara");
        TextField year = text(previous == null ? "" : previous.academicYear(), "Contoh: 2025/2026");
        VBox content = new VBox(10, field("Nomor atau label semester", label), field("Tahun ajaran (opsional)", year));
        show(owner, previous == null ? "Tambah semester" : "Edit semester", "Simpan", content,
                () -> new Semester(id, label.getText(), year.getText()), save, ignored -> {}, label,
                label.textProperty(), year.textProperty());
    }

    private static TextField wholeField(Integer value) { return text(value == null ? "" : value.toString(), "Opsional"); }

    private static HBox fieldPair(VBox first, VBox second) {
        HBox pair = new HBox(10, first, second);
        for (VBox field : List.of(first, second)) {
            field.setMinWidth(0);
            field.setPrefWidth(185);
            HBox.setHgrow(field, Priority.ALWAYS);
        }
        return pair;
    }

    static void assessment(Window owner, Course course, Assessment previous, Simulation.Row row,
                           List<Assessment> includedAssessments, boolean simulation,
                           Consumer<Boolean> invalid, Save<AssessmentInput> save) {
        boolean hasActual = row != null && row.original() != null && row.original().earned() != null;
        TextField name = text(previous == null ? "" : previous.name(), "Contoh: Kuis 1");
        UUID id = previous == null ? UUID.randomUUID() : previous.id();
        CategoryChoice category = new CategoryChoice(course, previous, includedAssessments, id, name);
        TextField maximum = text(previous == null ? "100" : Numbers.display(previous.maximum()), "Contoh: 100");
        TextField earned = text(previous == null ? "" : Numbers.display(previous.earned()), "Contoh: 80,2");
        VBox content = new VBox(12, field("Nama penilaian", name), category.fields(),
                field("Nilai maksimum", maximum));

        ComboBox<ScoreType> type = new ComboBox<>();
        VBox earnedField = field(simulation ? "Nilai asumsi" : "Nilai diperoleh (opsional)", earned);
        if (simulation) {
            if (hasActual) type.getItems().add(ScoreType.ACTUAL);
            type.getItems().addAll(ScoreType.FREE, ScoreType.FIXED);
            type.setValue(row != null && row.assumption() ? ScoreType.FIXED
                    : hasActual && previous.earned() != null ? ScoreType.ACTUAL : ScoreType.FREE);
            earnedField.visibleProperty().bind(type.valueProperty().isEqualTo(ScoreType.FIXED));
            earnedField.managedProperty().bind(earnedField.visibleProperty());
            content.getChildren().addAll(field("Perlakuan nilai di What If", type), earnedField,
                    note("Nilai bebas mengikuti proyeksi. Asumsi tetap memakai nilai yang Anda isi.", "muted"));
        } else {
            content.getChildren().addAll(earnedField,
                    note("Kosong berarti belum dinilai. Isi 0 jika nilainya memang nol.", "muted"));
        }

        show(owner, previous == null ? (simulation ? "Tambah asumsi penilaian" : "Tambah penilaian")
                        : (simulation ? "Ubah simulasi penilaian" : "Ubah penilaian"),
                simulation ? "Terapkan" : "Simpan", content, () -> {
                    ScoreType selected = type.getValue();
                    BigDecimal points = !simulation ? Numbers.read(earned.getText(), "Nilai diperoleh", true, false)
                            : switch (selected) {
                                case ACTUAL -> row.original().earned();
                                case FREE -> null;
                                case FIXED -> Numbers.read(earned.getText(), "Nilai asumsi", false, false);
                            };
                    Assessment assessment = new Assessment(id, name.getText(), category.selected().id(),
                            Numbers.read(maximum.getText(), "Nilai maksimum", false, true), points);
                    return new AssessmentInput(assessment, selected == ScoreType.FIXED);
                }, save, invalid, name, name.textProperty(), category.selector.valueProperty(), maximum.textProperty(),
                earned.textProperty(), type.valueProperty());
    }

    private static final class CategoryChoice {
        private final ComboBox<Category> selector = new ComboBox<>();
        private final Label guidance = note("", "muted");
        private final Course course;
        private final List<Assessment> included;
        private final UUID assessmentId;
        private final TextField name;
        private boolean manual;
        private boolean applyingSuggestion;

        CategoryChoice(Course course, Assessment previous, List<Assessment> included, UUID assessmentId, TextField name) {
            this.course = course;
            this.included = included;
            this.assessmentId = assessmentId;
            this.name = name;
            manual = previous != null;
            selector.getItems().setAll(course.categories());
            selector.setPromptText("Pilih kategori aktif");
            if (previous != null) {
                selector.setValue(course.categories().stream().filter(c -> c.id().equals(previous.categoryId()))
                        .findFirst().orElseThrow());
            }
            selector.valueProperty().addListener((unused, oldCategory, newCategory) -> {
                if (!applyingSuggestion && newCategory != null) manual = true;
                showGuidance();
            });
            // Selecting the already-suggested item also counts as an explicit choice.
            selector.setCellFactory(unused -> {
                ListCell<Category> cell = new ListCell<>() {
                    @Override protected void updateItem(Category item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.name());
                    }
                };
                cell.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (!cell.isEmpty()) manual = true;
                });
                return cell;
            });
            selector.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (selector.isShowing() && event.getCode() == KeyCode.ENTER && selector.getValue() != null) manual = true;
            });
            name.textProperty().addListener(unused -> nameChanged());
            showGuidance();
        }

        VBox fields() {
            VBox fields = field("Kategori", selector);
            fields.getChildren().add(guidance);
            return fields;
        }

        Category selected() {
            Category selected = selector.getValue();
            if (selected == null) throw new IllegalArgumentException("Pilih kategori aktif untuk penilaian ini.");
            return selected;
        }

        private void nameChanged() {
            if (!manual) {
                List<Category> matches = matches();
                applyingSuggestion = true;
                selector.setValue(matches.size() == 1 ? matches.getFirst() : null);
                applyingSuggestion = false;
            }
            showGuidance();
        }

        private List<Category> matches() { return CategorySuggestion.matches(name.getText(), course.categories()); }

        private void showGuidance() {
            List<Category> matches = matches();
            Category current = selector.getValue();
            if (current == null) {
                guidance.setText(matches.size() > 1
                        ? "Nama cocok dengan lebih dari satu kategori aktif. Pilih kategorinya."
                        : "Pilih kategori aktif atau ketik nama yang cocok.");
                return;
            }
            String weight = course.legacyWeights() ? "bobot relatif lama " + Numbers.display(current.weight())
                    : "bobot kategori " + Numbers.display(current.weight()) + "%";
            String selected = "Kategori " + current.name() + " (" + weight + ").";
            if (matches.size() == 1 && !matches.getFirst().id().equals(current.id())) {
                selected = "Nama penilaian cocok dengan " + matches.getFirst().name()
                        + ". Jika disimpan, nilai ini dihitung dalam kategori " + current.name() + " (" + weight + ").";
            }
            if (included.stream().anyMatch(row -> !row.id().equals(assessmentId) && row.categoryId().equals(current.id()))) {
                selected += "\nJika beberapa penilaian dihitung, kategori ini memakai " + course.method() + ".";
            }
            guidance.setText(selected);
        }
    }

    static <T> void show(Window owner, String title, String action, VBox fields, Supplier<T> read,
                         Save<T> save, Consumer<Boolean> invalid, Node first, Observable... inputs) {
        show(owner, title, action, fields, read, save, invalid, first, () -> {}, inputs);
    }

    static <T> void show(Window owner, String title, String action, VBox fields, Supplier<T> read,
                         Save<T> save, Consumer<Boolean> invalid, Node first, Runnable onShown, Observable... inputs) {
        Dialog<Void> dialog = dialog(owner, title);
        ButtonType applyType = new ButtonType(action, ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Batal", ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(cancelType, applyType);
        Button apply = (Button) dialog.getDialogPane().lookupButton(applyType);
        Button cancel = (Button) dialog.getDialogPane().lookupButton(cancelType);
        apply.getStyleClass().add("primary");
        Label message = note("", "validation");
        message.setVisible(false);
        message.managedProperty().bind(message.visibleProperty());
        dialog.getDialogPane().setContent(new VBox(14, fields, message));
        Runnable validate = () -> {
            try {
                read.get();
                message.setVisible(false);
                apply.setDisable(false);
                invalid.accept(false);
            } catch (IllegalArgumentException failure) {
                message.setText(failure.getMessage());
                message.setVisible(true);
                apply.setDisable(true);
                invalid.accept(true);
            }
        };
        for (Observable input : inputs) input.addListener(unused -> validate.run());
        dialog.setOnShown(event -> {
            validate.run();
            first.requestFocus();
            onShown.run();
        });
        dialog.setOnHidden(event -> invalid.accept(false));
        dialog.setOnCloseRequest(event -> {
            if (fields.isDisabled()) event.consume();
        });
        apply.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            T draft = read.get();
            fields.setDisable(true);
            apply.setDisable(true);
            cancel.setDisable(true);
            save.write(draft, () -> {
                fields.setDisable(false);
                dialog.close();
            }, failure -> {
                fields.setDisable(false);
                apply.setDisable(false);
                cancel.setDisable(false);
                message.setText(failure);
                message.setVisible(true);
            });
        });
        dialog.showAndWait();
    }

    static Dialog<Void> dialog(Window owner, String title) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        WhatIfApp.style(dialog.getDialogPane());
        dialog.getDialogPane().setPrefWidth(440);
        return dialog;
    }

    static VBox field(String title, Node input) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        label.setLabelFor(input);
        if (input instanceof ComboBox<?> combo) combo.setMaxWidth(Double.MAX_VALUE);
        return new VBox(5, label, input);
    }

    static Label note(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static TextField text(String value, String prompt) {
        TextField field = new TextField(value);
        field.setPromptText(prompt);
        return field;
    }

    @FunctionalInterface
    interface Save<T> {
        void write(T draft, Runnable success, Consumer<String> failure);
    }

    record AssessmentInput(Assessment assessment, boolean assumption) {}

    private enum ScoreType {
        ACTUAL("Gunakan nilai aktual"), FREE("Nilai bebas"), FIXED("Asumsi tetap");

        private final String label;

        ScoreType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}
