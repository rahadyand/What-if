package id.whatif;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Supplier;

final class FinalGradeEditor {
    private FinalGradeEditor() {}

    static void show(Window owner, FinalGrade stored, FinalGrade calculated, GradeScale scale, Editor.Save<FinalGrade> save) {
        FinalGrade.Review review = stored == null ? null : stored.review(calculated, scale);
        ComboBox<Choice> choice = new ComboBox<>();
        if (calculated != null) choice.getItems().add(Choice.CALCULATED);
        choice.getItems().add(Choice.OFFICIAL);
        if (review != null && review.needed()) choice.getItems().add(Choice.KEEP);
        choice.setPromptText("Pilih tindakan");

        ComboBox<Grade> official = new ComboBox<>();
        official.getItems().setAll(scale.grades());
        official.setPromptText("Pilih huruf resmi");
        if (stored != null && stored.source() == FinalGrade.Source.OFFICIAL) official.setValue(scale.find(stored.letter()));
        VBox officialField = Editor.field("Huruf nilai resmi", official);
        officialField.visibleProperty().bind(choice.valueProperty().isEqualTo(Choice.OFFICIAL));
        officialField.managedProperty().bind(officialField.visibleProperty());

        Label preview = Editor.note("", "muted");
        preview.setId("final-grade-preview");
        Supplier<FinalGrade> read = () -> switch (choice.getValue()) {
            case null -> throw new IllegalArgumentException("Pilih tindakan untuk melanjutkan.");
            case CALCULATED -> calculated;
            case OFFICIAL -> {
                if (official.getValue() == null) throw new IllegalArgumentException("Pilih huruf nilai resmi.");
                yield FinalGrade.of(official.getValue(), FinalGrade.Source.OFFICIAL);
            }
            case KEEP -> stored.keep(review);
        };
        Runnable update = () -> {
            FinalGrade value = review == null ? calculated : review.candidate();
            if (choice.getValue() != null) {
                try { value = read.get(); }
                catch (IllegalArgumentException incomplete) { value = null; }
            }
            preview.setText(value == null ? "Belum ada usulan untuk pilihan ini." : describe(value));
        };
        choice.valueProperty().addListener(unused -> update.run());
        official.valueProperty().addListener(unused -> update.run());
        if (stored == null && calculated != null) choice.setValue(Choice.CALCULATED);
        if (stored == null && calculated == null) choice.setValue(Choice.OFFICIAL);
        update.run();

        HBox cards = new HBox(9);
        if (stored != null) cards.getChildren().add(card("Nilai final tersimpan", Editor.note(describe(stored), "muted")));
        cards.getChildren().add(card("Pratinjau nilai final", preview));
        VBox fields = new VBox(9, cards);
        if (review != null) fields.getChildren().add(Editor.note(review.explanation(), "muted"));
        if (calculated == null) fields.getChildren().add(Editor.note(
                "Perhitungan aktual belum lengkap. Nilai resmi dapat dimasukkan jika sudah diumumkan.", "muted"));
        fields.getChildren().addAll(Editor.field("Tindakan", choice), officialField);
        String title = stored == null ? "Tetapkan Nilai Final"
                : review.needed() ? "Tinjau Ulang Nilai Final" : "Kelola Nilai Final";
        Editor.show(owner, title, "Konfirmasi Pilihan", fields, read, save, ignored -> {}, choice,
                choice.valueProperty(), official.valueProperty());
    }

    private static VBox card(String title, Label body) {
        VBox panel = new VBox(4, Editor.note(title, "field-label"), body);
        panel.setMinWidth(0);
        panel.setPrefWidth(190);
        panel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(panel, Priority.ALWAYS);
        panel.getStyleClass().add("summary");
        return panel;
    }

    private static String describe(FinalGrade result) { return result.display() + " angka mutu\n" + result.source(); }

    private enum Choice {
        CALCULATED("Gunakan hasil perhitungan"), OFFICIAL("Masukkan nilai resmi"), KEEP("Pertahankan nilai tersimpan");
        private final String label;
        Choice(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}
