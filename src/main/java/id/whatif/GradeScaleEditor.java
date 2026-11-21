package id.whatif;

import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

final class GradeScaleEditor {
    private final VBox rows = new VBox(4);
    private final List<Row> drafts = new ArrayList<>();
    private final SimpleIntegerProperty changes = new SimpleIntegerProperty();

    static void show(Window owner, GradeScale scale, Editor.Save<GradeScale> save) {
        new GradeScaleEditor().edit(owner, scale, save);
    }

    private void edit(Window owner, GradeScale scale, Editor.Save<GradeScale> save) {
        TextField maximum = new TextField(Numbers.display(scale.maximum()));
        scale.grades().forEach(grade -> add(grade.letter(), Numbers.display(grade.boundary()), Numbers.display(grade.point())));
        Button add = WhatIfApp.button("+ Huruf nilai", () -> add("", "", ""));
        var list = WhatIfApp.verticalScroll(rows, "course-form-scroll");
        list.setPrefViewportHeight(235);
        HBox headers = new HBox(6);
        headers.setPadding(new Insets(0, 17, 0, 1));
        for (String title : List.of("Huruf", "Minimum (%)", "Angka mutu")) {
            Label header = Editor.note(title, "field-label");
            header.setPrefWidth(83);
            header.setMinWidth(0);
            header.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(header, Priority.ALWAYS);
            headers.getChildren().add(header);
        }
        Region removeSpace = new Region();
        removeSpace.setPrefWidth(76);
        headers.getChildren().add(removeSpace);
        VBox fields = new VBox(9,
                Editor.note("Skala ini berlaku untuk nilai aktual terhitung, What If baru, dan usulan finalisasi. "
                        + "Nilai final tersimpan dan skenario lama tetap memakai data yang disimpan.", "muted"),
                Editor.field("Angka mutu maksimum", maximum),
                Editor.note("Gunakan batas 0% agar semua nilai tercakup.", "muted"),
                new VBox(4, headers, list), add);
        Editor.show(owner, "Pengaturan Skala Nilai", "Terapkan Skala", fields,
                () -> new GradeScale(Numbers.read(maximum.getText(), "Angka mutu maksimum", false, true),
                        drafts.stream().map(Row::grade).toList()), save, ignored -> {}, maximum,
                maximum.textProperty(), changes);
    }

    private void add(String letter, String threshold, String points) {
        Row row = new Row(new TextField(letter), new TextField(threshold), new TextField(points));
        row.letter().setPromptText("Huruf");
        row.threshold().setPromptText("Min. %");
        row.points().setPromptText("Mutu");
        row.letter().setAccessibleText("Huruf");
        row.threshold().setAccessibleText("Persentase minimum");
        row.points().setAccessibleText("Angka mutu");
        Button remove = new Button("Hapus");
        remove.setAccessibleText("Hapus baris nilai");
        HBox fields = new HBox(6, row.letter(), row.threshold(), row.points(), remove);
        for (TextField input : List.of(row.letter(), row.threshold(), row.points())) {
            input.setPrefColumnCount(4);
            input.setMinWidth(0);
            HBox.setHgrow(input, Priority.ALWAYS);
            input.textProperty().addListener(unused -> changed());
        }
        remove.setOnAction(unused -> {
            drafts.remove(row);
            rows.getChildren().remove(fields);
            changed();
        });
        drafts.add(row);
        rows.getChildren().add(fields);
        changed();
    }

    private void changed() { changes.set(changes.get() + 1); }

    private record Row(TextField letter, TextField threshold, TextField points) {
        Grade grade() {
            return new Grade(letter.getText(), Numbers.read(threshold.getText(), "Persentase minimum", false, false),
                    Numbers.read(points.getText(), "Angka mutu", false, false));
        }
    }
}
