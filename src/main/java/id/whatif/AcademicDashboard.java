package id.whatif;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.function.BiConsumer;

final class AcademicDashboard extends VBox {
    AcademicDashboard(Semester semester, boolean active, List<Course> courses, HBox indexCards, Runnable configureScale, Runnable addSemester,
                      Runnable editSemester, Runnable makeActive, Runnable addCourse, BiConsumer<Course, Boolean> openCourse) {
        super(17);
        setId("academic-dashboard");
        getStyleClass().addAll("page", "academic-dashboard");

        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        HBox welcome = new HBox(12, WhatIfApp.heading("Welcome to", "page-title"), WhatIfApp.wordmark(166),
                fill, WhatIfApp.button("Pengaturan Skala Nilai", configureScale));
        welcome.setAlignment(Pos.CENTER_LEFT);
        welcome.getStyleClass().add("dashboard-welcome");
        getChildren().add(welcome);

        String title = semester != null ? semester.toString()
                : courses.isEmpty() ? "Semester" : AcademicDetails.UNGROUPED;
        Label heading = WhatIfApp.heading(title, "section-title");
        heading.setWrapText(true);
        heading.setTooltip(new Tooltip(title));
        VBox semesterTitle = new VBox(4, heading);
        HBox.setHgrow(semesterTitle, Priority.ALWAYS);
        HBox actions = new HBox(8, semesterTitle);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (semester != null) {
            if (active) semesterTitle.getChildren().add(Editor.note("Semester aktif", "result-badge"));
            actions.getChildren().add(WhatIfApp.button("Edit Semester", editSemester));
            if (!active) actions.getChildren().add(WhatIfApp.button("Jadikan Aktif", makeActive));
        } else if (!courses.isEmpty()) {
            actions.getChildren().add(WhatIfApp.button("+ Tambah semester", addSemester));
        }
        getChildren().add(actions);

        long totalSks = courses.stream().map(Course::academic).filter(details -> details.sks() != null)
                .mapToLong(details -> details.sks()).sum();
        long missingSks = courses.stream().filter(course -> course.academic().sks() == null).count();
        HBox totals = new HBox(12, metric("Mata kuliah", courses.size(), ""),
                metric("Total SKS tercatat", totalSks, missingSks == 0 ? "" : missingSks + " mata kuliah belum mencatat SKS"));
        getChildren().addAll(totals, indexCards);

        if (courses.isEmpty()) {
            String state = semester == null ? "Belum ada semester" : "Belum ada mata kuliah";
            String detail = semester == null ? "Tambahkan semester untuk mengelompokkan mata kuliah."
                    : "Tambahkan mata kuliah untuk mencatat SKS, kehadiran, dan nilai.";
            Button next = primary(semester == null ? "+ Tambah semester" : "+ Tambah mata kuliah",
                    semester == null ? addSemester : addCourse);
            VBox empty = new VBox(8, WhatIfApp.heading(state, "section-title"), Editor.note(detail, "muted"), next);
            empty.getStyleClass().add("dashboard-empty");
            getChildren().add(empty);
            return;
        }

        HBox listTitle = new HBox(12, WhatIfApp.heading("Mata Kuliah", "section-title"));
        listTitle.setAlignment(Pos.CENTER_LEFT);
        if (semester != null) {
            HBox.setHgrow(listTitle.getChildren().getFirst(), Priority.ALWAYS);
            listTitle.getChildren().add(primary("+ Tambah mata kuliah", addCourse));
        }
        VBox list = new VBox(5, listTitle);
        if (semester == null) {
            list.getChildren().add(Editor.note("Pilih Nilai Aktual lalu Pengaturan untuk melengkapi semester, SKS, dan kehadiran.", "muted"));
        }
        for (Course course : courses) list.getChildren().add(courseRow(course, openCourse));
        getChildren().add(list);
    }

    private static VBox metric(String title, long value, String supplementary) {
        VBox card = new VBox(3, Editor.note(title, "field-label"), Editor.note(Long.toString(value), "metric"));
        if (!supplementary.isEmpty()) card.getChildren().add(Editor.note(supplementary, "muted"));
        card.setPrefWidth(200);
        card.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(card, Priority.ALWAYS);
        card.getStyleClass().add("summary");
        return card;
    }

    private static HBox courseRow(Course course, BiConsumer<Course, Boolean> openCourse) {
        Label title = Editor.note(course.name(), "field-label");
        VBox info = new VBox(3, title, Editor.note(course.academic().sksText(), "muted"));
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);
        Button actual = WhatIfApp.button("Nilai Aktual", () -> openCourse.accept(course, false));
        actual.setAccessibleText("Nilai Aktual untuk " + course.name());
        Button whatIf = WhatIfApp.button("What If", () -> openCourse.accept(course, true));
        whatIf.setAccessibleText("What If untuk " + course.name());
        HBox row = new HBox(9, info, actual, whatIf);
        row.setId("dashboard-course-" + course.id());
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("dashboard-course");
        return row;
    }

    private static Button primary(String text, Runnable action) {
        Button button = WhatIfApp.button(text, action);
        button.getStyleClass().add("primary");
        return button;
    }
}
