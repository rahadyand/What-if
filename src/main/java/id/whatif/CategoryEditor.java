package id.whatif;

import javafx.beans.property.SimpleLongProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import id.whatif.Course.Category;

final class CategoryEditor {
    private final Course course;
    private final List<Draft> drafts = new ArrayList<>();
    private final VBox rows = new VBox(7);
    private final Label total = Editor.note("", "field-label");
    private final Label preview = Editor.note("", "muted");
    private final Label notice = Editor.note("", "validation");
    private final SimpleLongProperty changed = new SimpleLongProperty();

    private CategoryEditor(Course course) { this.course = course; }

    static void show(Window owner, Course course, Consumer<Boolean> invalid, Editor.Save<Course> save) {
        var editor = new CategoryEditor(course);
        editor.open(owner, invalid, save);
    }

    private void open(Window owner, Consumer<Boolean> invalid, Editor.Save<Course> save) {
        Label rules = Editor.note("Total Poin atau Rata-rata Persentase menghitung nilai dalam kategori. "
                + "Persentase menentukan kontribusi kategori ke nilai akhir.", "muted");
        List<Category> proposed = course.categories();
        var originals = new HashMap<UUID, BigDecimal>();
        if (course.legacyWeights()) {
            var review = WeightConversion.propose(course);
            proposed = review.categories();
            for (Category category : course.categories()) originals.put(category.id(), category.weight());
            rules.setText(review.exact()
                    ? "Tinjau konversi bobot lama. Usulan mempertahankan proporsi secara eksak."
                    : "Tinjau konversi bobot lama. Usulan dibulatkan ke total 100% dan bisa mengubah hasil.");
        }
        for (Category category : proposed) addRow(category.id(), category.name(),
                Numbers.display(category.weight()), originals.get(category.id()));
        Button add = new Button("+ Kategori");
        add.setOnAction(event -> {
            Draft row = addRow(UUID.randomUUID(), "", "", null);
            row.name().requestFocus();
            changed();
        });
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(160);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox content = new VBox(9, rules, scroll, add, total, preview, notice);
        notice.managedProperty().bind(notice.visibleProperty());
        notice.setVisible(false);
        preview.managedProperty().bind(preview.visibleProperty());
        preview.setVisible(course.legacyWeights());
        Editor.show(owner, course.legacyWeights() ? "Tinjau konversi bobot" : "Persentase kategori",
                "Simpan persentase", content, this::read, save, invalid, drafts.getFirst().name(), changed);
    }

    private Draft addRow(UUID id, String name, String weight, BigDecimal original) {
        TextField categoryName = new TextField(name);
        categoryName.setPromptText("Nama kategori");
        categoryName.setAccessibleText("Nama kategori");
        TextField percentage = new TextField(weight);
        percentage.setPromptText("%");
        percentage.setAccessibleText("Persentase kategori");
        percentage.setPrefColumnCount(5);
        Button remove = new Button("Hapus");
        HBox fields = new HBox(7, categoryName, percentage, remove);
        HBox.setHgrow(categoryName, Priority.ALWAYS);
        fields.setAlignment(Pos.CENTER_LEFT);
        VBox entry = new VBox(3, fields);
        if (original != null) entry.getChildren().add(Editor.note("Bobot lama: " + Numbers.display(original), "muted"));
        Draft draft = new Draft(id, categoryName, percentage);
        drafts.add(draft);
        rows.getChildren().add(entry);
        remove.setOnAction(event -> {
            if (course.assessments().stream().anyMatch(row -> row.categoryId().equals(id))) {
                notice.setText("Pindahkan penilaian ke kategori lain sebelum menghapus kategori ini.");
                notice.setVisible(true);
                return;
            }
            if (drafts.size() == 1) {
                notice.setText("Sisakan minimal satu kategori.");
                notice.setVisible(true);
                return;
            }
            drafts.remove(draft);
            rows.getChildren().remove(entry);
            changed();
        });
        categoryName.textProperty().addListener(unused -> changed());
        percentage.textProperty().addListener(unused -> changed());
        return draft;
    }

    private void changed() {
        notice.setVisible(false);
        changed.set(changed.get() + 1);
    }

    private Course read() {
        preview.setText("");
        BigDecimal sum = BigDecimal.ZERO;
        List<BigDecimal> values = new ArrayList<>();
        boolean invalid = false;
        for (Draft draft : drafts) {
            try {
                BigDecimal value = Numbers.read(draft.weight().getText(), "Persentase", false, true);
                values.add(value);
                sum = sum.add(value);
            } catch (IllegalArgumentException error) {
                invalid = true;
            }
        }
        BigDecimal delta = Course.FULL_WEIGHT.subtract(sum);
        total.setText("Total " + Numbers.display(sum) + "% / 100%" + (delta.signum() == 0 ? ""
                : " · " + (delta.signum() > 0 ? "Kurang " : "Lebih ") + Numbers.display(delta.abs()) + "%")
                + (invalid ? " · hanya isian yang sah" : ""));
        if (invalid) throw new IllegalArgumentException("Isi semua persentase dengan angka positif.");
        if (delta.signum() != 0) throw new IllegalArgumentException("Jumlah persentase kategori wajib tepat 100%.");
        List<Category> categories = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            categories.add(new Category(draft.id(), draft.name().getText(), values.get(i)));
        }
        Course proposed = course.withCategories(categories);
        if (course.legacyWeights()) showDifference(proposed);
        return proposed;
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

    private record Draft(UUID id, TextField name, TextField weight) {}
}
