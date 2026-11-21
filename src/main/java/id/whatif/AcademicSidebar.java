package id.whatif;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class AcademicSidebar extends VBox {
    private final VBox groups = new VBox(8);
    private final Button dashboard;
    private final Set<UUID> expanded = new HashSet<>();
    private final Consumer<UUID> viewSemester;
    private final Consumer<Course> viewCourse;
    private final Consumer<UUID> addCourse;

    AcademicSidebar(Runnable showDashboard, Runnable addSemester, Consumer<UUID> viewSemester,
                    Consumer<Course> viewCourse, Consumer<UUID> addCourse) {
        super(13);
        this.viewSemester = viewSemester;
        this.viewCourse = viewCourse;
        this.addCourse = addCourse;
        getStyleClass().add("sidebar");
        setPrefWidth(212);

        dashboard = navButton("Dashboard", showDashboard);
        dashboard.getStyleClass().add("dashboard-nav");
        ScrollPane scroll = WhatIfApp.verticalScroll(groups, "semester-navigation");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Button add = navButton("+ Semester", addSemester);
        add.getStyleClass().add("sidebar-add");
        getChildren().addAll(WhatIfApp.wordmark(176), dashboard, Editor.note("SEMESTER", "eyebrow"), scroll, add);
    }

    void expand(UUID semester) { expanded.add(semester); }

    void render(List<Semester> semesters, List<Course> courses, UUID active, UUID viewed, Course selected) {
        Node focused = getScene() == null ? null : getScene().getFocusOwner();
        String focusId = focused == null ? null : focused.getId();
        dashboard.getStyleClass().remove("nav-current");
        if (selected == null) dashboard.getStyleClass().add("nav-current");
        groups.getChildren().clear();
        for (Semester semester : semesters) {
            groups.getChildren().add(group(semester, courses, active, viewed, selected));
        }
        if (courses.stream().anyMatch(item -> item.academic().semesterId() == null)) {
            groups.getChildren().add(group(null, courses, active, viewed, selected));
        }
        if (groups.getChildren().isEmpty()) groups.getChildren().add(Editor.note("Belum ada semester.", "muted"));
        if (focusId != null) {
            Node replacement = groups.lookup("#" + focusId);
            if (replacement != null) Platform.runLater(replacement::requestFocus);
        }
    }

    private VBox group(Semester semester, List<Course> courses, UUID active, UUID viewed, Course selected) {
        UUID id = semester == null ? null : semester.id();
        String groupId = id == null ? "ungrouped" : id.toString();
        String title = semester == null ? AcademicDetails.UNGROUPED : semester.name();
        boolean isActive = id != null && id.equals(active);
        Button open = navButton(title, () -> viewSemester.accept(id));
        open.getStyleClass().add("semester-nav-label");
        open.setId("semester-" + groupId);
        open.setTooltip(new Tooltip(semester == null ? title : semester.toString()));
        open.setAccessibleText(title + (isActive ? ", aktif" : ""));
        if (selected == null && Objects.equals(viewed, id)) open.getStyleClass().add("nav-current");
        HBox.setHgrow(open, Priority.ALWAYS);

        VBox entries = new VBox(3);
        entries.getStyleClass().add("semester-entries");
        for (Course item : courses) {
            if (!Objects.equals(item.academic().semesterId(), id)) continue;
            Button course = navButton(item.name(), () -> viewCourse.accept(item));
            course.setId("course-" + item.id());
            course.setTooltip(new Tooltip(item.name()));
            if (selected != null && selected.id().equals(item.id())) course.getStyleClass().add("nav-current");
            entries.getChildren().add(course);
        }
        if (semester != null) {
            Button add = navButton("+ Mata kuliah", () -> addCourse.accept(id));
            add.setId("add-course-" + groupId);
            add.setAccessibleText("Tambah mata kuliah di " + title);
            add.getStyleClass().add("semester-add-course");
            entries.getChildren().add(add);
        }
        ToggleButton disclosure = new ToggleButton();
        disclosure.setId("toggle-" + groupId);
        disclosure.getStyleClass().add("semester-disclosure");
        disclosure.setSelected(expanded.contains(id));
        entries.visibleProperty().bind(disclosure.selectedProperty());
        entries.managedProperty().bind(entries.visibleProperty());
        disclosure.textProperty().bind(javafx.beans.binding.Bindings.when(disclosure.selectedProperty()).then("▾").otherwise("▸"));
        disclosure.accessibleTextProperty().bind(javafx.beans.binding.Bindings.when(disclosure.selectedProperty())
                .then("Tutup daftar " + title).otherwise("Buka daftar " + title));
        disclosure.selectedProperty().addListener((unused, before, isOpen) -> {
            if (isOpen) expanded.add(id);
            else expanded.remove(id);
        });
        HBox header = new HBox(3, disclosure, open);
        header.setAlignment(Pos.CENTER_LEFT);
        if (isActive) {
            Label activeBadge = Editor.note("Aktif", "sidebar-count");
            activeBadge.setMinWidth(USE_PREF_SIZE);
            header.getChildren().add(activeBadge);
        }
        return new VBox(3, header, entries);
    }

    private static Button navButton(String text, Runnable action) {
        Button button = WhatIfApp.button(text, action);
        button.getStyleClass().add("academic-nav");
        button.setAlignment(Pos.CENTER_LEFT);
        button.setMinWidth(0);
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }
}
