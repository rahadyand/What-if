package id.whatif;

import id.whatif.Course.Assessment;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class TargetSummary extends VBox {
    private static final Set<Grade> COMMON_TARGETS = EnumSet.of(Grade.A, Grade.A_MINUS, Grade.B_PLUS, Grade.B);
    private final VBox selected = new VBox(7);
    private final GridPane grades = new GridPane();
    private final Button toggle = new Button();
    private final VBox recommendations;
    private boolean showAll;

    TargetSummary(Course course, List<Scenario> alternatives) {
        super(11);
        setId("target-summary");
        getStyleClass().add("target-summary");
        selected.setId("selected-target-card");
        selected.getStyleClass().add("target-highlight");

        ColumnConstraints letter = new ColumnConstraints(65);
        ColumnConstraints scores = new ColumnConstraints(115, 280, Double.MAX_VALUE);
        scores.setHgrow(Priority.ALWAYS);
        ColumnConstraints status = new ColumnConstraints(178, 188, 198);
        grades.getColumnConstraints().addAll(letter, scores, status);
        grades.getStyleClass().add("target-grade-table");
        grades.setId("letter-targets");

        toggle.getStyleClass().add("target-grade-toggle");
        HBox gradeHeading = spread(WhatIfApp.heading("Target per Huruf", "section-title"), toggle);
        recommendations = new VBox(7, gradeHeading, grades);
        recommendations.managedProperty().bind(recommendations.visibleProperty());
        getChildren().addAll(WhatIfApp.heading("Ringkasan Target Nilai", "section-title"), selected, recommendations);
        if (alternatives.size() >= 2) {
            TitledPane comparison = new TitledPane("Bandingkan dua skenario tersimpan",
                    ScenarioComparison.panel(course, alternatives, true));
            comparison.setExpanded(false);
            comparison.setAnimated(false);
            comparison.setId("target-summary-comparison");
            getChildren().add(comparison);
        }
    }

    void render(Simulation simulation, Scenario opened, Course current, Calculation.TargetReport report, String error) {
        String context = opened == null ? "" : "Skenario " + opened.name();
        if (!simulation.baseline().sameGradeInputs(current)) context = addContext(context, "Data dasar lama");
        if (simulation.baseline().legacyWeights()) context = addContext(context, "Bobot relatif lama");

        BigDecimal minimum = error.isBlank() ? simulation.target() : null;
        String title = minimum == null ? "Target belum valid" : targetTitle(minimum);
        Label target = Editor.note(title, "target-highlight-heading");
        selected.getChildren().setAll(target);
        if (report == null || report.selected().status() == Calculation.Status.EMPTY) {
            if (!context.isEmpty()) selected.getChildren().add(Editor.note(context, "muted"));
            String action = report != null ? "Belum ada penilaian yang disertakan. Tambah atau sertakan penilaian untuk menghitung target."
                    : error.isBlank() ? "Isian penilaian belum valid. Perbaiki isian yang ditandai pada formulir."
                    : error + " Perbaiki angka pada kolom Target minimum.";
            selected.getChildren().add(Editor.note(action, report == null ? "validation" : "muted"));
            recommendations.setVisible(false);
            return;
        }

        List<Assessment> free = simulation.rows().stream().filter(row -> !row.excluded() && row.value().earned() == null)
                .map(Simulation.Row::value).toList();
        Calculation.Projection projection = report.selected();
        selected.getChildren().setAll(spread(target, statusBadge(projection.status())));
        if (!context.isEmpty()) selected.getChildren().add(Editor.note(context, "muted"));
        if (free.isEmpty()) {
            selected.getChildren().add(new VBox(1, Editor.note(result(projection.percent()), "metric"),
                    Editor.note("Hasil dari skor tetap", "muted")));
        } else {
            selected.getChildren().add(scoreOverview(projection, free));
        }

        if (projection.status() == Calculation.Status.UNREACHABLE) {
            selected.getChildren().add(Editor.note(shortfall(minimum, projection, free), "validation"));
        } else if (!free.isEmpty() && projection.status() == Calculation.Status.WITH_SUGGESTIONS) {
            String rounding = wholePointNote(projection, free);
            if (!rounding.isEmpty()) selected.getChildren().add(Editor.note(rounding, "muted"));
        }
        if (!projection.aboveMaximumAssumptions().isEmpty()) {
            selected.getChildren().add(Editor.note("Hasil bergantung pada izin dosen untuk asumsi di atas maksimum pada "
                    + String.join(", ", projection.aboveMaximumAssumptions()) + ".", "validation"));
        }

        recommendations.setVisible(true);
        renderGrades(report.grades(), free);
        toggle.setOnAction(event -> {
            showAll = !showAll;
            renderGrades(report.grades(), free);
        });
    }

    private static HBox scoreOverview(Calculation.Projection projection, List<Assessment> free) {
        VBox required = new VBox(2);
        boolean unreachable = projection.status() == Calculation.Status.UNREACHABLE;
        if (free.size() == 1) {
            Assessment assessment = free.getFirst();
            var score = ResultText.scoreRecommendation(projection, assessment);
            required.getChildren().addAll(Editor.note(score.label() + " " + assessment.name(), "field-label"),
                    Editor.note(score.fraction(), "metric"));
        } else {
            if (!unreachable) {
                required.getChildren().add(Editor.note("Saran ini berlaku jika semua skor berikut tercapai.", "muted"));
            }
            for (Assessment assessment : free) {
                var score = ResultText.scoreRecommendation(projection, assessment);
                required.getChildren().add(Editor.note(assessment.name() + " · " + score.display(), "target-score-item"));
            }
        }
        if (projection.status() == Calculation.Status.WITH_SUGGESTIONS) {
            required.getChildren().add(Editor.note("Proyeksi dengan skor saran " + result(projection.percent()), "muted"));
        }

        VBox maximum = new VBox(2, Editor.note("Nilai akhir tertinggi", "field-label"),
                Editor.note(result(projection.maximum()), "target-normal-value"));
        if (!unreachable) {
            maximum.getChildren().add(Editor.note("Jika semua penilaian tersisa mendapat skor maksimum normal.", "muted"));
        }
        HBox comparison = new HBox(16, required, maximum);
        comparison.setAlignment(Pos.TOP_LEFT);
        for (VBox column : List.of(required, maximum)) {
            column.setMinWidth(0);
            column.setPrefWidth(220);
            HBox.setHgrow(column, Priority.ALWAYS);
        }
        return comparison;
    }

    private static String shortfall(BigDecimal target, Calculation.Projection projection, List<Assessment> free) {
        Grade grade = Grade.classify(Exact.of(target));
        String label = target.compareTo(grade.boundary) == 0 ? letter(grade) : Numbers.display(target) + "%";
        String condition = switch (free.size()) {
            case 0 -> "";
            case 1 -> " meskipun " + free.getFirst().name() + " mendapat "
                    + ResultText.scoreRecommendation(projection, free.getFirst()).fraction();
            default -> " meskipun semua penilaian tersisa mendapat skor maksimum normal";
        };
        return "Target " + label + " masih kurang " + ResultText.value(projection.shortfall())
                + " poin persentase" + condition + ".";
    }

    private void renderGrades(List<Calculation.GradeRequirement> entries, List<Assessment> free) {
        toggle.setText(showAll ? "Tampilkan Lebih Sedikit" : "Lihat Semua Target");
        grades.getChildren().clear();
        String scoreHeading = free.size() == 1 ? "Skor " + free.getFirst().name() : "Skor Penilaian";
        grades.addRow(0, gradeCell(Editor.note("Huruf", "field-label"), true),
                gradeCell(Editor.note(scoreHeading, "field-label"), true),
                gradeCell(Editor.note("Status", "field-label"), true));
        int index = 1;
        for (var entry : entries) {
            if (!showAll && !COMMON_TARGETS.contains(entry.grade())) continue;
            var projection = entry.projection();
            String required = switch (free.size()) {
                case 0 -> "—";
                case 1 -> ResultText.scoreRecommendation(projection, free.getFirst()).display();
                default -> String.join("\n", free.stream().map(row -> row.name() + " "
                        + ResultText.scoreRecommendation(projection, row).display()).toList());
            };
            grades.addRow(index++, gradeCell(Editor.note(letter(entry.grade()), "field-label"), false),
                    gradeCell(Editor.note(required, "target-grade-score"), false),
                    gradeCell(statusBadge(projection.status()), false));
        }
    }

    private static StackPane gradeCell(Node content, boolean header) {
        StackPane cell = new StackPane(content);
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.getStyleClass().add("target-grade-cell");
        if (header) cell.getStyleClass().add("target-grade-header");
        return cell;
    }

    private static Label statusBadge(Calculation.Status status) {
        String text = switch (status) {
            case SECURED -> "Sudah Memenuhi";
            case WITH_SUGGESTIONS -> "Bisa Dicapai";
            case UNREACHABLE -> "Tidak Dapat Dicapai";
            case EMPTY -> throw new IllegalArgumentException("Status target belum tersedia.");
        };
        String icon = switch (status) {
            case SECURED -> "✓";
            case UNREACHABLE -> "×";
            default -> "→";
        };
        Label badge = Editor.note(icon + "  " + text, "result-badge");
        badge.setAccessibleText(text);
        badge.getStyleClass().add(status == Calculation.Status.UNREACHABLE ? "warning" : "positive");
        return badge;
    }

    private static String wholePointNote(Calculation.Projection projection, List<Assessment> free) {
        List<String> scores = new ArrayList<>();
        boolean changesScore = false;
        boolean single = free.size() == 1;
        for (Assessment row : free) {
            BigDecimal whole = ResultText.wholeScoreWithinMaximum(projection.commonPercent(), row.maximum());
            if (whole == null) {
                return single ? "Jika " + row.name() + " hanya menerima bilangan bulat, target melampaui skor bulat dalam batas normal."
                        : "Jika semua penilaian hanya menerima bilangan bulat, kombinasi ini melampaui batas normal setelah dibulatkan.";
            }
            changesScore |= whole.compareTo(projection.suggestions().get(row.id())) != 0;
            scores.add((single ? "" : row.name() + " ") + Numbers.display(whole) + "/" + Numbers.display(row.maximum()));
        }
        if (!changesScore) return "";
        String assessment = single ? free.getFirst().name() : "semua penilaian";
        return "Jika " + assessment + " hanya menerima bilangan bulat, targetkan minimal " + String.join(" · ", scores) + ".";
    }

    private static HBox spread(Node left, Node right) {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox row = new HBox(5, left, space, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String addContext(String before, String item) { return before.isEmpty() ? item : before + " · " + item; }

    private static String targetTitle(BigDecimal minimum) {
        return "Target " + letter(Grade.classify(Exact.of(minimum))) + " · " + Numbers.display(minimum) + "%";
    }

    private static String letter(Grade grade) { return grade.toString().replace('-', '−'); }

    private static String result(Exact percentage) {
        return ResultText.percent(percentage) + " (" + letter(Grade.classify(percentage)) + ")";
    }
}
