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
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class Editor {
    private Editor() {}

    static void course(Window owner, Course previous, Consumer<Boolean> invalid, Save<Course> save) {
        TextField name = text(previous == null ? "" : previous.name(), "Contoh: Pemrograman Berorientasi Objek");
        ComboBox<Aggregation> method = new ComboBox<>();
        method.getItems().setAll(Aggregation.values());
        method.setValue(previous == null ? Aggregation.TOTAL_POINTS : previous.method());
        VBox content = new VBox(12, field("Nama mata kuliah", name), field("Metode per kategori", method),
                note(Aggregation.EXAMPLE, "muted"));
        show(owner, previous == null ? "Tambah mata kuliah" : "Pengaturan mata kuliah", "Simpan", content,
                () -> previous == null ? Course.create(name.getText(), method.getValue())
                        : previous.withSettings(name.getText(), method.getValue()),
                save, invalid, name, name.textProperty(), method.valueProperty());
    }

    static void category(Window owner, Category previous, Consumer<Boolean> invalid, Save<Category> save) {
        TextField name = text(previous == null ? "" : previous.name(), "Contoh: Ujian");
        TextField weight = text(previous == null ? "1" : Numbers.display(previous.weight()), "Contoh: 1,5");
        UUID id = previous == null ? UUID.randomUUID() : previous.id();
        VBox content = new VBox(12, field("Nama kategori", name), field("Bobot relatif", weight),
                note("Bobot 100 dan 50 memberi proporsi yang sama dengan bobot 2 dan 1.", "muted"));
        show(owner, previous == null ? "Tambah kategori" : "Ubah kategori", "Simpan", content,
                () -> new Category(id, name.getText(), Numbers.read(weight.getText(), "Bobot", false, true)),
                save, invalid, name, name.textProperty(), weight.textProperty());
    }

    static void assessment(Window owner, Course course, Assessment previous, Simulation.Row row,
                           boolean simulation, Consumer<Boolean> invalid, Save<AssessmentInput> save) {
        boolean hasActual = row != null && row.original() != null && row.original().earned() != null;
        TextField name = text(previous == null ? "" : previous.name(), "Contoh: Kuis 1");
        ComboBox<Category> category = new ComboBox<>();
        category.getItems().setAll(course.categories());
        category.setValue(previous == null ? course.categories().getFirst()
                : course.categories().stream().filter(c -> c.id().equals(previous.categoryId())).findFirst().orElseThrow());
        TextField maximum = text(previous == null ? "100" : Numbers.display(previous.maximum()), "Contoh: 100");
        TextField earned = text(previous == null ? "" : Numbers.display(previous.earned()), "Contoh: 80,2");
        VBox content = new VBox(12, field("Nama penilaian", name), field("Kategori", category),
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

        UUID id = previous == null ? UUID.randomUUID() : previous.id();
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
                    Assessment assessment = new Assessment(id, name.getText(), category.getValue().id(),
                            Numbers.read(maximum.getText(), "Nilai maksimum", false, true), points);
                    return new AssessmentInput(assessment, selected == ScoreType.FIXED);
                }, save, invalid, name, name.textProperty(), category.valueProperty(), maximum.textProperty(),
                earned.textProperty(), type.valueProperty());
    }

    static <T> void show(Window owner, String title, String action, VBox fields, Supplier<T> read,
                         Save<T> save, Consumer<Boolean> invalid, Node first, Observable... inputs) {
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
