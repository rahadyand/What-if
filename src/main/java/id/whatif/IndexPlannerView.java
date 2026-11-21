package id.whatif;

import id.whatif.AcademicAverage.ProjectedRepeatKey;
import id.whatif.AcademicAverage.RepeatGroup;
import id.whatif.AcademicAverage.Result;
import id.whatif.TargetPlanner.CoursePlan;
import id.whatif.TargetPlanner.Mode;
import id.whatif.TargetPlanner.Outcome;
import id.whatif.TargetPlanner.Report;
import id.whatif.TargetPlanner.Status;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

final class IndexPlannerView {
    private final TargetPlanner.Inputs inputs;
    private final Function<UUID, String> semesterName;
    private final Consumer<CoursePlan> fix;
    private final BiConsumer<CoursePlan, Outcome> openDraft;
    private final Set<UUID> useActual = new HashSet<>();
    private final Map<ProjectedRepeatKey, Boolean> sessionReviews = new HashMap<>();
    private final Dialog<Void> dialog;
    private final ComboBox<Mode> mode = new ComboBox<>();
    private final TextField target = new TextField();
    private final VBox summary = new VBox(8);
    private final VBox content = new VBox(9);
    private final Label error = Editor.note("", "validation");
    private Runnable afterClose;

    IndexPlannerView(Window owner, TargetPlanner.Inputs inputs, Function<UUID, String> semesterName,
                     Consumer<CoursePlan> fix, BiConsumer<CoursePlan, Outcome> openDraft) {
        this.inputs = inputs;
        this.semesterName = semesterName;
        this.fix = fix;
        this.openDraft = openDraft;
        dialog = Editor.dialog(owner, "Rencanakan Target IP/IPK");
        dialog.getDialogPane().setPrefWidth(756);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Tutup", ButtonBar.ButtonData.CANCEL_CLOSE));
        mode.getItems().setAll(Mode.values());
        mode.setValue(Mode.SEMESTER);
        target.setText(Numbers.display(inputs.scale().maximum().min(new BigDecimal("3.50"))));
        target.setPrefColumnCount(8);
        var scope = WhatIfApp.heading(semesterName.apply(inputs.semester()), "section-title");
        scope.setWrapText(true);
        error.managedProperty().bind(error.visibleProperty());
        var scroll = WhatIfApp.verticalScroll(content, "index-planner-scroll");
        scroll.setPrefViewportHeight(owner.getHeight() < 700 ? 168 : 299);
        var form = new HBox(12, Editor.field("Jenis target", mode), Editor.field("Target angka mutu", target));
        VBox fields = new VBox(8, scope, form, error, summary, scroll);
        fields.setId("index-planner");
        dialog.getDialogPane().setContent(fields);
        mode.valueProperty().addListener(unused -> refresh());
        target.textProperty().addListener(unused -> refresh());
        refresh();
    }

    void show() {
        dialog.showAndWait();
        if (afterClose != null) afterClose.run();
    }

    private void leave(Runnable action) {
        afterClose = action;
        dialog.close();
    }

    private void refresh() {
        BigDecimal parsed = null;
        String message = "";
        try {
            parsed = TargetPlanner.readTarget(target.getText(), inputs.scale());
        } catch (IllegalArgumentException invalid) {
            message = invalid.getMessage();
        }
        error.setText(message);
        error.setVisible(!message.isEmpty());
        target.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), !message.isEmpty());

        Mode selectedMode = mode.getValue();
        var report = TargetPlanner.plan(inputs, selectedMode, parsed, useActual, sessionReviews);
        Label status = Editor.note(report.status().toString(), "result-badge");
        status.setId("planner-status");
        if (report.status() == Status.MET || report.status() == Status.REACHABLE) status.getStyleClass().add("positive");
        if (report.status() == Status.UNREACHABLE) status.getStyleClass().add("warning");
        String coverage = report.knownSks() + " SKS " + (report.ceiling() == null ? "siap" : "dalam cakupan");
        var statusLine = new HBox(9, status, Editor.note(coverage, "muted"));
        statusLine.setAlignment(Pos.CENTER_LEFT);
        summary.getChildren().setAll(statusLine, new HBox(8,
                metric("Proyeksi saat ini", report.starting(), "planner-starting"),
                metric("Maksimum yang bisa dicapai", report.ceiling(), "planner-ceiling")));
        content.getChildren().clear();
        if (report.shortfall() != null) {
            Label gap = Editor.note("Masih kurang " + ResultText.value(report.shortfall()) + " poin " + selectedMode.unit() + ".", "validation");
            gap.setId("planner-shortfall");
            summary.getChildren().add(gap);
        } else if (report.ceiling() == null) {
            summary.getChildren().add(Editor.note(incomplete(report), "muted"));
        }
        if (selectedMode == Mode.CUMULATIVE) {
            String outside = report.outside().isEmpty() ? ""
                    : " (" + report.outside().size() + " mata kuliah)";
            summary.getChildren().add(Editor.note("Mata kuliah belum final di semester lain berada di luar cakupan" + outside + ".", "muted"));
        }
        for (RepeatGroup group : report.unresolved()) {
            Button review = WhatIfApp.button("Tinjau pengulangan " + group.courses().getFirst().course().name(), () -> review(group));
            review.setWrapText(true);
            content.getChildren().add(review);
        }
        if (report.suggested() != null) {
            content.getChildren().add(WhatIfApp.heading("Salah satu kombinasi yang bisa dicapai", "section-title"));
            Label result = Editor.note(selectedMode.unit() + " hasil kombinasi " + report.suggested().display()
                    + " · " + report.suggested().sks() + " SKS", "field-label");
            result.setId("planner-combination");
            content.getChildren().add(result);
        } else if (!report.courses().isEmpty()) {
            content.getChildren().add(WhatIfApp.heading("Mata kuliah dalam rencana", "section-title"));
        }
        for (CoursePlan row : report.courses()) content.getChildren().add(courseRow(report, row));
    }

    private static String incomplete(Report report) {
        if (!report.unresolved().isEmpty()) return "Tentukan mata kuliah berulang yang ikut dihitung.";
        if (report.courses().isEmpty()) return "Tambahkan mata kuliah melalui Dashboard.";
        if (report.courses().stream().anyMatch(row -> !row.ready())) return "Lengkapi data mata kuliah yang ditandai.";
        return "Pilih setidaknya satu kontribusi untuk menghitung indeks.";
    }

    private static VBox metric(String title, Result result, String id) {
        VBox card = new VBox(3, Editor.note(title, "field-label"),
                Editor.note(result == null ? "Belum tersedia" : result.display(), result == null ? "index-unavailable" : "index-metric"));
        if (result != null && !Exact.of(result.exact().decimal(2, RoundingMode.HALF_UP)).equals(result.exact())) {
            card.getChildren().add(Editor.note("Sebelum pembulatan " + ResultText.value(result.exact())
                    + " (" + Numbers.display(result.weighted()) + " ÷ " + result.sks() + ")", "muted"));
        }
        card.setId(id);
        card.setMinWidth(0);
        card.setPrefWidth(200);
        HBox.setHgrow(card, Priority.ALWAYS);
        card.getStyleClass().addAll("summary", "index-card");
        return card;
    }

    private javafx.scene.Node courseRow(Report report, CoursePlan row) {
        Course course = row.course();
        String title = course.name() + " · " + course.academic().sksText();
        VBox card = new VBox(3, Editor.note(title, "field-label"));
        card.setId("plan-course-" + course.id());
        card.getStyleClass().add("planner-course");
        if (!row.ready()) {
            String scenario = row.choice() == null || row.choice().name() == null ? "" : row.choice().name() + " · ";
            card.getChildren().add(Editor.note(scenario + row.issue(), "validation"));
            Button fixButton = WhatIfApp.button(fixLabel(row), () -> leave(() -> fix.accept(row)));
            FlowPane actions = new FlowPane(6, 5, fixButton);
            if (canSubstitute(row)) {
                actions.getChildren().add(WhatIfApp.button("Pakai data aktual untuk sesi ini", () -> {
                    useActual.add(course.id());
                    refresh();
                }));
            }
            card.getChildren().add(actions);
            return card;
        }
        if (!report.included().contains(course.id())) {
            card.getChildren().add(Editor.note(row.source() + " · "
                    + (report.unresolved().stream().anyMatch(group -> group.courses().stream().anyMatch(entry -> entry.course().id().equals(course.id())))
                        ? "Menunggu tinjauan pengulangan" : "Di luar cakupan sesuai tinjauan"), "muted"));
            return card;
        }
        card.getChildren().add(Editor.note(row.source(), "muted"));
        if (row.confirmed() != null) {
            card.getChildren().add(Editor.note(row.confirmed().display() + " angka mutu · tetap", "field-label"));
        } else {
            Outcome suggested = report.recommendations().get(course.id());
            if (suggested != null) {
                card.getChildren().add(Editor.note(suggested.grade() + " · " + ResultText.percent(suggested.percent())
                        + " · " + Numbers.display(suggested.grade().point()) + " angka mutu", "field-label"));
                var detail = WhatIfApp.button("Rincian skor di What If", () -> leave(() -> openDraft.accept(row, suggested)));
                if (!useActual.contains(course.id())) return courseWithDetail(card, detail);
                card.getChildren().add(detail);
            } else if (report.status() == Status.UNREACHABLE) {
                Outcome highest = row.outcomes().getLast();
                card.getChildren().add(Editor.note("Batas mata kuliah " + highest.grade() + " · " + ResultText.percent(highest.percent()), "muted"));
            } else if (row.starting() != null) {
                card.getChildren().add(Editor.note("Proyeksi awal " + row.starting().grade() + " · "
                        + ResultText.percent(row.starting().percent()), "muted"));
            }
            if (useActual.contains(course.id())) {
                card.getChildren().add(WhatIfApp.button("Kembali ke skenario pilihan", () -> {
                    useActual.remove(course.id());
                    refresh();
                }));
            }
        }
        return card;
    }

    private static HBox courseWithDetail(VBox card, Button detail) {
        var row = new HBox(9, card, detail);
        row.setId(card.getId());
        card.setId(null);
        card.getStyleClass().remove("planner-course");
        card.setMinWidth(0);
        HBox.setHgrow(card, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("planner-course");
        return row;
    }

    private boolean canSubstitute(CoursePlan row) {
        return row.course().academic().sks() != null && row.choice() != null && row.choice().choice() != null
                && !row.choice().usable() && !useActual.contains(row.course().id());
    }

    private String fixLabel(CoursePlan row) {
        if (row.course().academic().sks() == null) return "Isi SKS";
        if (canSubstitute(row)) return row.choice().snapshot() == null ? "Pilih skenario di Dashboard" : "Tinjau skenario di What If";
        return "Buka Nilai Aktual";
    }

    private void review(RepeatGroup group) {
        Window owner = dialog.getDialogPane().getScene().getWindow();
        AcademicIndexView.review(owner, group, id -> sessionReviews.get(new ProjectedRepeatKey(group.key(), id)),
                semesterName, true, (selected, success, failure) -> {
                    for (var entry : group.courses()) {
                        UUID id = entry.course().id();
                        sessionReviews.put(new ProjectedRepeatKey(group.key(), id), selected.contains(id));
                    }
                    refresh();
                    success.run();
                });
    }
}
