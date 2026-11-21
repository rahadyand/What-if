package id.whatif;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ProjectionView {
    private ProjectionView() {}

    static HBox cards(DashboardProjection.Report report, String semester, Runnable details) {
        var ip = report.semester();
        var ipk = report.cumulative().result();
        boolean complete = report.complete();
        boolean repeat = report.needsRepeatReview();
        String missing = report.missing().stream().limit(2).map(Course::name).collect(Collectors.joining(", "));
        if (report.missing().size() > 2) missing += " dan " + (report.missing().size() - 2) + " lainnya";
        if (!missing.isEmpty()) missing = "Lengkapi " + missing + ".";
        VBox term = card("IP Semester Proyeksi", complete ? ip.display() : "Proyeksi belum lengkap",
                ip.sks() + (complete ? " SKS masuk" : " SKS siap"), missing);
        term.setId("projected-ip");
        String global = !complete || repeat ? "Proyeksi belum lengkap"
                : report.hasCumulativeProjection() ? ipk.display() : "Belum tersedia";
        String note = repeat ? "Tinjau calon mata kuliah berulang."
                : complete && !report.hasCumulativeProjection() ? "Pilihan tinjauan saat ini tidak memasukkan skenario ke IPK." : "";
        VBox cumulative = card("IPK Proyeksi", global, semester + " · " + ipk.sks()
                + (complete && !repeat ? " SKS masuk" : " SKS siap"), note);
        cumulative.setId("projected-ipk");
        cumulative.getChildren().add(WhatIfApp.button("Rincian Proyeksi", details));
        return new HBox(12, term, cumulative);
    }

    private static VBox card(String title, String value, String coverage, String note) {
        VBox node = new VBox(3, Editor.note(title, "field-label"),
                Editor.note(value, value.matches("[0-9]+,[0-9]{2}") ? "index-metric" : "index-unavailable"),
                Editor.note(coverage, "muted"));
        if (!note.isEmpty()) node.getChildren().add(Editor.note(note, "muted"));
        node.getStyleClass().addAll("summary", "index-card");
        node.setMinWidth(0);
        node.setPrefWidth(200);
        HBox.setHgrow(node, Priority.ALWAYS);
        return node;
    }

    static void choose(Window owner, Course course, DashboardProjection.Row row, List<Scenario> available,
                       Editor.Save<Choice> save) {
        ComboBox<Choice> chooser = new ComboBox<>();
        Choice clear = new Choice(null, "Tanpa skenario");
        chooser.getItems().add(clear);
        for (Scenario scenario : available) chooser.getItems().add(new Choice(scenario.id(), scenario.name()));
        Choice current = chooser.getItems().stream().filter(choice -> choice.id() != null && choice.id().equals(row.choice()))
                .findFirst().orElse(clear);
        chooser.setValue(current);
        VBox fields = new VBox(8, Editor.note(course.name(), "field-label"),
                Editor.note(available.isEmpty() ? "Simpan skenario di What If, lalu pilih di sini."
                        : "Dashboard memakai hasil dari skenario yang disimpan dan dipilih.", "muted"));
        if (row.choice() != null && !row.issue().isEmpty()) fields.getChildren().add(Editor.note(row.display(), "validation"));
        fields.getChildren().add(Editor.field("Skenario What If", chooser));
        Editor.show(owner, "Pilih Skenario Dashboard", "Simpan Pilihan", fields, chooser::getValue, save, ignored -> {},
                chooser, chooser.valueProperty());
    }

    record Choice(UUID id, String name) {
        @Override public String toString() { return name; }
    }

    static void details(Window owner, Semester viewed, DashboardProjection.Report report, Function<UUID, String> semesterName,
                        Consumer<AcademicAverage.RepeatGroup> review) {
        Dialog<Void> dialog = Editor.dialog(owner, "Rincian Proyeksi");
        dialog.getDialogPane().setPrefWidth(680);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Tutup", ButtonBar.ButtonData.CANCEL_CLOSE));
        var term = report.semester();
        var ipk = report.cumulative().result();
        VBox semester = new VBox(9, Editor.note(viewed + " · " + term.sks() + " SKS "
                + (report.complete() ? "masuk" : "siap"), "field-label"), contributions(term, semesterName));
        if (!report.missing().isEmpty()) {
            semester.getChildren().add(Editor.note("Mata kuliah yang perlu dilengkapi", "field-label"));
            for (Course course : report.missing()) {
                String issue = report.rows().get(course.id()).issue();
                String sks = course.academic().sks() == null ? "SKS belum dicatat. " : "";
                semester.getChildren().add(Editor.note(course.name() + " · " + (sks + issue).strip(), "muted"));
            }
        }

        boolean ready = report.complete() && !report.needsRepeatReview();
        VBox cumulative = new VBox(9, Editor.note("Semester proyeksi " + viewed + " · "
                + ipk.sks() + " SKS " + (ready ? "masuk" : "siap"), "field-label"), contributions(ipk, semesterName));
        var selected = new AtomicReference<AcademicAverage.RepeatGroup>();
        for (var group : report.cumulative().repeats()) {
            Button action = WhatIfApp.button("Tinjau " + group.courses().getFirst().course().name(), () -> {
                selected.set(group);
                dialog.close();
            });
            action.setWrapText(true);
            HBox line = new HBox(8, action, Editor.note(group.reviewed() ? "Sudah ditinjau" : "Menunggu tinjauan", "muted"));
            line.setAlignment(Pos.CENTER_LEFT);
            cumulative.getChildren().add(line);
        }
        if (!report.outside().isEmpty()) {
            cumulative.getChildren().add(Editor.note("Mata kuliah belum final di luar cakupan", "field-label"));
            for (Course course : report.outside()) {
                cumulative.getChildren().add(Editor.note(course.name() + " · " + semesterName.apply(course.academic().semesterId()), "muted"));
            }
        }
        Tab termTab = new Tab("IP Semester", scroller(semester));
        Tab globalTab = new Tab("IPK", scroller(cumulative));
        TabPane tabs = new TabPane(termTab, globalTab);
        tabs.getStyleClass().add("projection-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        if (report.needsRepeatReview()) tabs.getSelectionModel().select(globalTab);
        dialog.getDialogPane().setContent(tabs);
        dialog.showAndWait();
        if (selected.get() != null) review.accept(selected.get());
    }

    private static ScrollPane scroller(VBox content) {
        ScrollPane scroll = WhatIfApp.verticalScroll(content, "projection-details-scroll");
        scroll.setPrefViewportHeight(380);
        return scroll;
    }

    private static GridPane contributions(AcademicAverage.Result result, Function<UUID, String> semesterName) {
        GridPane grid = new GridPane();
        grid.setHgap(9);
        grid.setVgap(8);
        ColumnConstraints course = new ColumnConstraints(125, 200, Double.MAX_VALUE);
        ColumnConstraints source = new ColumnConstraints(132, 185, Double.MAX_VALUE);
        course.setHgrow(Priority.ALWAYS);
        source.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(course, source, new ColumnConstraints(39), new ColumnConstraints(61), new ColumnConstraints(75));
        grid.addRow(0, Editor.note("Mata kuliah", "field-label"), Editor.note("Sumber", "field-label"),
                Editor.note("SKS", "field-label"), Editor.note("Nilai", "field-label"), Editor.note("Kontribusi", "field-label"));
        int row = 1;
        for (var entry : result.courses()) {
            grid.addRow(row++, Editor.note(entry.course().name() + "\n" + semesterName.apply(entry.course().academic().semesterId()), "muted"),
                    Editor.note(entry.source(), "muted"), Editor.note(Integer.toString(entry.sks()), "muted"),
                    Editor.note(entry.letter() + " · " + entry.pointText(), "muted"),
                    Editor.note(Numbers.display(entry.weighted()), "muted"));
        }
        return grid;
    }
}
