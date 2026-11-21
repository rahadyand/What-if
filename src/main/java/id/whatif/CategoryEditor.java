package id.whatif;

import id.whatif.Course.Category;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.SimpleLongProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class CategoryEditor {
    private final Course course;
    private final List<Draft> drafts = new ArrayList<>();
    private final VBox rows = new VBox(4);
    private final Label total = Editor.note("", "field-label");
    private final Label preview = Editor.note("", "muted");
    private final Label notice = Editor.note("", "validation");
    private final SimpleLongProperty changed = new SimpleLongProperty();
    private final ScrollPane scroll = new ScrollPane(rows);
    private final VBox content;

    CategoryEditor(Course course) {
        this.course = course;
        boolean legacy = course != null && course.legacyWeights();
        Label rules = Editor.note(course == null ? "Centang kategori dan isi persentase sesuai RPS."
                : "Persentase kategori aktif menentukan kontribusi ke nilai akhir.", "muted");
        List<Category> proposed = course == null ? List.of() : course.categories();
        var originals = new HashMap<UUID, BigDecimal>();
        if (legacy) {
            var review = WeightConversion.propose(course);
            proposed = review.categories();
            for (Category category : course.categories()) originals.put(category.id(), category.weight());
            rules.setText(review.exact()
                    ? "Tinjau konversi bobot lama. Usulan mempertahankan proporsi secara eksak."
                    : "Tinjau konversi bobot lama. Usulan dibulatkan ke total 100% dan bisa mengubah hasil.");
        }
        for (Category category : proposed) {
            addRow(category.id(), category.name(), Numbers.display(category.weight()), true,
                    originals.get(category.id()), false);
        }
        for (String preset : CategoryConfiguration.PRESETS) {
            if (proposed.stream().noneMatch(category -> preset.equalsIgnoreCase(category.name()))) {
                addRow(UUID.randomUUID(), preset, "", false, null, true);
            }
        }
        Button add = new Button("+ Kategori kustom");
        add.setOnAction(event -> {
            Draft row = addRow(UUID.randomUUID(), "", "", true, null, false);
            row.name().requestFocus();
            Platform.runLater(() -> scroll.setVvalue(1));
            changed();
        });
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(218);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rows.setPadding(new Insets(5, 7, 5, 5));
        rows.getStyleClass().add("category-edit-rows");
        HBox actions = new HBox(9, add, total);
        actions.setAlignment(Pos.CENTER_LEFT);
        content = new VBox(7, rules, scroll, actions, preview, notice);
        notice.managedProperty().bind(notice.visibleProperty());
        notice.setVisible(false);
        preview.managedProperty().bind(preview.visibleProperty());
        preview.setVisible(legacy);
    }

    static void show(Window owner, Course course, Consumer<Boolean> invalid, Editor.Save<Course> save) {
        var editor = new CategoryEditor(course);
        Editor.show(owner, course.legacyWeights() ? "Tinjau konversi bobot" : "Persentase kategori",
                "Simpan persentase", editor.content(), editor::readCourse, save, invalid,
                editor.drafts.getFirst().active(), editor.changes());
    }

    VBox content() { return content; }

    Observable changes() { return changed; }

    List<Category> readCategories() {
        var review = CategoryConfiguration.review(drafts.stream().map(draft -> new CategoryConfiguration.Entry(
                draft.id(), draft.name().getText(), draft.weight().getText(), draft.active().isSelected())).toList());
        BigDecimal remaining = review.remaining();
        total.setText("Total " + Numbers.display(review.total()) + "% / 100%" + (remaining.signum() == 0 ? ""
                : " · " + (remaining.signum() > 0 ? "Kurang " : "Lebih ") + Numbers.display(remaining.abs()) + "%")
                + (review.invalidWeights() ? " · isian sah" : ""));
        return review.validatedCategories();
    }

    private Course readCourse() {
        preview.setText("");
        Course proposed = course.withCategories(readCategories());
        if (course.legacyWeights()) showDifference(proposed);
        return proposed;
    }

    private Draft addRow(UUID id, String name, String weight, boolean active, BigDecimal original, boolean preset) {
        CheckBox included = new CheckBox(preset ? name : "");
        included.setSelected(active);
        included.setAccessibleText(name.isBlank() ? "Aktifkan kategori kustom" : "Aktifkan " + name);
        included.getStyleClass().add("category-toggle");
        TextField categoryName = new TextField(name);
        categoryName.setPromptText("Nama kategori kustom");
        categoryName.setAccessibleText("Nama kategori");
        categoryName.disableProperty().bind(included.selectedProperty().not());
        TextField percentage = new TextField(weight);
        percentage.setPromptText("Bobot");
        percentage.setAccessibleText("Persentase " + (preset ? name : "kategori kustom"));
        percentage.setPrefColumnCount(5);
        percentage.setMaxWidth(89);
        percentage.setAlignment(Pos.CENTER_RIGHT);
        percentage.disableProperty().bind(included.selectedProperty().not());
        Button remove = new Button("Hapus");
        remove.setMinWidth(Region.USE_PREF_SIZE);
        HBox fields = new HBox(7, included);
        if (preset) {
            HBox.setHgrow(included, Priority.ALWAYS);
            included.setMaxWidth(Double.MAX_VALUE);
        } else {
            HBox.setHgrow(categoryName, Priority.ALWAYS);
            fields.getChildren().addAll(categoryName, remove);
        }
        fields.getChildren().addAll(percentage, Editor.note("%", "muted"));
        fields.setAlignment(Pos.CENTER_LEFT);
        VBox entry = new VBox(3, fields);
        if (original != null) entry.getChildren().add(Editor.note("Bobot lama: " + Numbers.display(original), "muted"));
        Draft draft = new Draft(id, categoryName, percentage, included);
        drafts.add(draft);
        rows.getChildren().add(entry);
        included.setOnAction(event -> {
            if (!included.isSelected() && !canDeactivate(id)) {
                included.setSelected(true);
                return;
            }
            changed();
        });
        remove.setOnAction(event -> {
            if (!canDeactivate(id)) return;
            drafts.remove(draft);
            rows.getChildren().remove(entry);
            changed();
        });
        categoryName.textProperty().addListener((unused, before, after) -> {
            included.setAccessibleText(after.isBlank() ? "Aktifkan kategori kustom" : "Aktifkan " + after);
            changed();
        });
        percentage.textProperty().addListener(unused -> changed());
        return draft;
    }

    private boolean canDeactivate(UUID id) {
        if (course == null || !course.categoryInUse(id)) return true;
        notice.setText("Kategori ini masih berisi penilaian. Di Nilai Aktual, buka tiap penilaian itu, "
                + "pilih kategori aktif lain, lalu simpan sebelum menonaktifkannya.");
        notice.setVisible(true);
        return false;
    }

    private void changed() {
        notice.setVisible(false);
        changed.set(changed.get() + 1);
    }

    private void showDifference(Course proposed) {
        var old = Calculation.actual(course);
        var next = Calculation.actual(proposed);
        var oldMaximum = Calculation.project(new Simulation(course)).maximum();
        var newMaximum = Calculation.project(new Simulation(proposed)).maximum();
        if (old.percent() == null && oldMaximum == null) {
            preview.setText("Belum ada penilaian untuk membandingkan hasil lama dan usulan.");
            return;
        }
        preview.setText(difference("Aktual/sementara", old.percent(), next.percent())
                + difference("Kontribusi saat ini", old.contribution(), next.contribution())
                + difference("Maksimum What If dari aktual", oldMaximum, newMaximum)
                + "Kategori kosong pada konfigurasi baru tidak mengalihkan persentasenya.");
    }

    private static String difference(String label, Exact before, Exact after) {
        if (before == null || after == null) return "";
        return label + " " + ResultText.percent(before) + " → " + ResultText.percent(after) + " ("
                + ResultText.delta(after.subtract(before)) + " poin persentase).\n";
    }

    private record Draft(UUID id, TextField name, TextField weight, CheckBox active) {}
}
