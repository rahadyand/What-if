package id.whatif;

import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

final class AcademicIndexView {
    private AcademicIndexView() {}

    static HBox cards(Semester viewed, AcademicAverage.SemesterResult semester, AcademicAverage.Cumulative cumulative,
                      Runnable breakdown) {
        var result = semester.result();
        String title = semester.complete() ? "IP Semester Final"
                : result.sks() > 0 ? "IP dari nilai final sejauh ini" : "IP Semester";
        String coverage = semester.missingSks() > 0
                ? result.sks() + " SKS masuk · Total semester belum lengkap"
                : result.sks() + " dari " + semester.recordedSks() + " SKS";
        if (result.sks() == 0 && semester.recordedSks() == 0 && semester.missingSks() == 0) coverage = "";
        String next = viewed == null ? "Pilih atau isi semester mata kuliah."
                : semester.missingSks() > 0 ? "Lengkapi SKS di Pengaturan."
                : semester.missingFinal() > 0 ? "Tetapkan nilai final di Nilai Aktual."
                : result.sks() == 0 ? "Tambahkan mata kuliah semester ini." : "";
        VBox term = card(title, result, coverage, next);
        term.setId("semester-ip");

        var missing = cumulative.missing();
        boolean review = cumulative.repeats().stream().anyMatch(group -> !group.reviewed());
        String guidance = review ? "Tinjau mata kuliah yang mungkin diulang."
                : missing.semester() > 0 ? "Lengkapi semester mata kuliah."
                : missing.sks() > 0 ? "Lengkapi SKS di Pengaturan."
                : missing.finalGrade() > 0 ? "Tetapkan nilai final di Nilai Aktual."
                : cumulative.result().sks() == 0 ? (cumulative.repeats().isEmpty()
                    ? "Tambahkan semester dan mata kuliah untuk mulai." : "Buka rincian untuk menyesuaikan kontribusi.") : "";
        VBox global = card("IPK Aktual", cumulative.result(), cumulative.result().sks() + " SKS masuk", guidance);
        global.setId("actual-ipk");
        if (cumulative.result().sks() > 0 || !cumulative.repeats().isEmpty()) {
            global.getChildren().add(WhatIfApp.button(review ? "Rincian dan tinjau IPK" : "Lihat rincian IPK", breakdown));
        }
        return new HBox(12, term, global);
    }

    private static VBox card(String title, AcademicAverage.Result result, String coverage, String next) {
        VBox card = new VBox(3, Editor.note(title, "field-label"),
                Editor.note(result.display(), result.sks() == 0 ? "index-unavailable" : "metric"));
        if (!coverage.isEmpty()) card.getChildren().add(Editor.note(coverage, "muted"));
        if (!next.isEmpty()) card.getChildren().add(Editor.note(next, "muted"));
        card.getStyleClass().add("summary");
        card.setMinWidth(0);
        card.setPrefWidth(200);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    static void breakdown(Window owner, AcademicAverage.Cumulative cumulative, Function<UUID, String> semesterName,
                          Consumer<AcademicAverage.RepeatGroup> review) {
        Dialog<Void> dialog = Editor.dialog(owner, "Rincian IPK Aktual");
        dialog.getDialogPane().setPrefWidth(615);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Tutup", ButtonBar.ButtonData.CANCEL_CLOSE));
        var result = cumulative.result();
        var selected = new AtomicReference<AcademicAverage.RepeatGroup>();
        VBox content = new VBox(8, Editor.note("IPK " + result.display() + " · " + result.sks() + " SKS masuk", "field-label"));
        if (!result.courses().isEmpty()) {
            GridPane grid = new GridPane();
            grid.setHgap(9);
            grid.setVgap(7);
            ColumnConstraints name = new ColumnConstraints(160, 230, Double.MAX_VALUE);
            name.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(name, new ColumnConstraints(47), new ColumnConstraints(52),
                    new ColumnConstraints(38), new ColumnConstraints(75));
            grid.addRow(0, header("Mata kuliah"), header("Huruf"), header("Mutu"), header("SKS"), header("Kontribusi"));
            int index = 1;
            for (var entry : result.courses()) {
                grid.addRow(index++, Editor.note(entry.course().name() + "\n" + semesterName.apply(entry.course().academic().semesterId()), "muted"),
                        Editor.note(entry.grade().letter(), "muted"), Editor.note(entry.grade().pointText(), "muted"),
                        Editor.note(Integer.toString(entry.sks()), "muted"), Editor.note(Numbers.display(entry.weighted()), "muted"));
            }
            content.getChildren().add(grid);
        }
        if (!cumulative.repeats().isEmpty()) {
            content.getChildren().add(Editor.note(
                    "Periksa mata kuliah bernama sama. Pilih secara eksplisit mana yang masuk IPK.", "muted"));
            for (var group : cumulative.repeats()) {
                var action = WhatIfApp.button("Tinjau " + group.courses().getFirst().course().name(), () -> {
                    selected.set(group);
                    dialog.close();
                });
                action.setWrapText(true);
                HBox line = new HBox(9, action, Editor.note(group.reviewed() ? "Sudah ditinjau" : "Menunggu tinjauan", "muted"));
                line.setAlignment(Pos.CENTER_LEFT);
                content.getChildren().add(line);
            }
        }
        var scroll = WhatIfApp.verticalScroll(content, "course-form-scroll");
        scroll.setPrefViewportHeight(330);
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
        if (selected.get() != null) review.accept(selected.get());
    }

    private static javafx.scene.control.Label header(String text) { return Editor.note(text, "field-label"); }

    static void review(Window owner, AcademicAverage.RepeatGroup group, Map<UUID, AcademicAverage.RepeatDecision> decisions,
                       Function<UUID, String> semesterName, Editor.Save<Set<UUID>> save) {
        VBox fields = new VBox(8, Editor.note("Centang kontribusi yang akan dimasukkan ke IPK. "
                + "Gunakan daftar ini untuk meninjau kemungkinan mata kuliah berulang.", "muted"));
        var choices = new LinkedHashMap<UUID, CheckBox>();
        for (var entry : group.courses()) {
            Course course = entry.course();
            CheckBox box = new CheckBox(semesterName.apply(course.academic().semesterId()) + " · "
                    + entry.grade().letter() + " · " + entry.sks() + " SKS");
            box.setWrapText(true);
            box.setSelected(group.reviewed() && decisions.get(course.id()).included());
            choices.put(course.id(), box);
            fields.getChildren().add(box);
        }
        var scroll = WhatIfApp.verticalScroll(fields, "course-form-scroll");
        scroll.setPrefViewportHeight(Math.min(290, 80 + choices.size() * 33));
        Editor.show(owner, "Tinjau mata kuliah berulang", "Konfirmasi Kontribusi", new VBox(scroll),
                () -> choices.entrySet().stream().filter(row -> row.getValue().isSelected())
                        .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet()),
                save, ignored -> {}, choices.sequencedValues().getFirst());
    }
}
