package id.whatif;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ScenarioComparison {
    private ScenarioComparison() {}

    static void show(Window owner, Course current, GradeScale currentScale, List<Scenario> scenarios) {
        Dialog<Void> dialog = Editor.dialog(owner, "Bandingkan skenario");
        dialog.getDialogPane().setPrefWidth(720);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Tutup", ButtonData.CANCEL_CLOSE));
        ScrollPane scroll = new ScrollPane(panel(current, currentScale, scenarios, false));
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(415);
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
    }

    static GridPane panel(Course current, GradeScale currentScale, List<Scenario> scenarios, boolean compact) {
        ComboBox<Scenario> first = new ComboBox<>(FXCollections.observableArrayList(scenarios));
        ComboBox<Scenario> second = new ComboBox<>(FXCollections.observableArrayList(scenarios));
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(6));
        grid.getColumnConstraints().addAll(new ColumnConstraints(138), flexibleColumn(), flexibleColumn());
        grid.add(Editor.field("Skenario pertama", first), 1, 0);
        grid.add(Editor.field("Skenario kedua", second), 2, 0);
        Runnable update = () -> render(grid, current, currentScale, first, second, compact);
        first.valueProperty().addListener(unused -> update.run());
        second.valueProperty().addListener(unused -> update.run());
        first.getSelectionModel().select(0);
        second.getSelectionModel().select(1);
        update.run();
        return grid;
    }

    private static ColumnConstraints flexibleColumn() {
        var column = new ColumnConstraints(155, 230, Double.MAX_VALUE);
        column.setHgrow(Priority.ALWAYS);
        return column;
    }

    private static void render(GridPane grid, Course current, GradeScale currentScale, ComboBox<Scenario> first, ComboBox<Scenario> second,
                               boolean compact) {
        Scenario a = first.getValue();
        Scenario b = second.getValue();
        if (a == null || b == null) return;
        grid.getChildren().removeIf(node -> GridPane.getRowIndex(node) != 0);
        Simulation left = Simulation.from(a.state());
        Simulation right = Simulation.from(b.state());
        Calculation.Projection p = Calculation.project(left);
        Calculation.Projection q = Calculation.project(right);
        row(grid, "Target", Numbers.display(left.target()) + "%", Numbers.display(right.target()) + "%", 1);
        row(grid, "Proyeksi dan huruf", projected(p, left.scale()), projected(q, right.scale()), 2);
        row(grid, "Maksimum normal", ResultText.percent(p.maximum()), ResultText.percent(q.maximum()), 3);
        row(grid, "Kekurangan target", gap(p), gap(q), 4);
        row(grid, "Kelayakan", status(p), status(q), 5);
        row(grid, "Data dasar", configuration(a, current, currentScale, compact), configuration(b, current, currentScale, compact), 6);
        String warning = a.equals(b) ? "Pilih dua skenario berbeda untuk membandingkan."
                : !a.stale(b.state().baseline(), b.state().scale()) ? "Data dasar kedua skenario sama."
                : "Data dasar kedua skenario berbeda. Nilai, struktur, atau aturan penilaian dapat memengaruhi selisih.";
        grid.add(Editor.note(warning, "field-label"), 0, 7, 3, 1);
        if (compact) return;
        Map<UUID, Simulation.Row> leftRows = rows(left);
        Map<UUID, Simulation.Row> rightRows = rows(right);
        var ids = new LinkedHashSet<>(leftRows.keySet());
        ids.addAll(rightRows.keySet());
        int index = 8;
        for (UUID id : ids) {
            Simulation.Row x = leftRows.get(id);
            Simulation.Row y = rightRows.get(id);
            if (java.util.Objects.equals(x == null ? null : x.value(), y == null ? null : y.value())
                    && java.util.Objects.equals(change(x), change(y))) continue;
            if (x != null && !x.modified() && !x.excluded() && y != null && !y.modified() && !y.excluded()) continue;
            row(grid, x == null ? y.value().name() : x.value().name(), entry(x, left), entry(y, right), index++);
        }
        if (index == 8) grid.add(Editor.note("Tidak ada perbedaan asumsi atau pengecualian.", "muted"), 0, 8, 3, 1);
    }

    private static Map<UUID, Simulation.Row> rows(Simulation simulation) {
        Map<UUID, Simulation.Row> rows = new LinkedHashMap<>();
        for (var row : simulation.rows()) rows.put(row.value().id(), row);
        return rows;
    }

    private static String change(Simulation.Row row) {
        if (row == null) return "";
        return (row.excluded() ? "Dikeluarkan · " : "") + (row.original() == null ? "Hipotetis · " : "")
                + (row.assumption() ? "Asumsi tetap" : row.value().earned() == null ? "Bebas" : "Aktual");
    }

    private static String entry(Simulation.Row row, Simulation simulation) {
        if (row == null) return "—";
        var value = row.value();
        String earned = value.earned() == null ? "—" : Numbers.display(value.earned());
        return change(row) + "\n" + value.name() + " · " + simulation.baseline().categoryName(value.categoryId())
                + "\n" + earned + " / " + Numbers.display(value.maximum());
    }

    private static String projected(Calculation.Projection projection, GradeScale scale) {
        if (projection.percent() == null) return "—";
        return ResultText.percent(projection.percent()) + " · " + scale.classify(projection.percent())
                + (!projection.excess().isEmpty() ? " (matematis)" : "");
    }

    private static String gap(Calculation.Projection projection) {
        return projection.shortfall() == null ? "—" : ResultText.value(projection.shortfall()) + " poin persentase";
    }

    private static String status(Calculation.Projection projection) {
        return ResultText.status(projection) + (projection.aboveMaximumAssumptions().isEmpty() ? ""
                : "\nBersyarat: " + String.join(", ", projection.aboveMaximumAssumptions()) + " melewati maksimum.");
    }

    private static String configuration(Scenario scenario, Course current, GradeScale currentScale, boolean compact) {
        Course baseline = scenario.state().baseline();
        StringBuilder text = new StringBuilder(scenario.stale(current, currentScale) ? "Berdasarkan data lama" : "Sesuai data aktual");
        if (compact) return text.toString();
        text.append("\n").append(baseline.method());
        if (baseline.legacyWeights()) text.append(" · bobot relatif lama");
        for (var category : baseline.categories()) {
            text.append("\n").append(category.name()).append(" ").append(Numbers.display(category.weight()));
            if (!baseline.legacyWeights()) text.append("%");
        }
        return text.toString();
    }

    private static void row(GridPane grid, String name, String first, String second, int index) {
        grid.add(Editor.note(name, "field-label"), 0, index);
        grid.add(Editor.note(first, "muted"), 1, index);
        grid.add(Editor.note(second, "muted"), 2, index);
    }
}
