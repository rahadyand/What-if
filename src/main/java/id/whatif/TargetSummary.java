package id.whatif;

import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

final class TargetSummary extends VBox {
    private final Label context = Editor.note("", "muted");
    private final Label details = Editor.note("", "target-detail");
    private final Label gradeIntro = Editor.note("", "muted");
    private final GridPane grades = new GridPane();
    private final VBox recommendations;

    TargetSummary(Course course, List<Scenario> alternatives) {
        super(9);
        setId("target-summary");
        getStyleClass().add("target-summary");
        grades.setHgap(12);
        grades.setVgap(7);
        ColumnConstraints letter = new ColumnConstraints(47);
        ColumnConstraints minimum = new ColumnConstraints(72);
        ColumnConstraints scores = new ColumnConstraints(160, 290, Double.MAX_VALUE);
        scores.setHgrow(Priority.ALWAYS);
        ColumnConstraints status = new ColumnConstraints(140, 172, 210);
        grades.getColumnConstraints().addAll(letter, minimum, scores, status);

        recommendations = new VBox(7, WhatIfApp.heading("Target per huruf", "section-title"), gradeIntro, grades);
        recommendations.managedProperty().bind(recommendations.visibleProperty());
        getChildren().addAll(WhatIfApp.heading("Ringkasan Target Nilai", "section-title"), context, details);
        if (alternatives.size() >= 2) {
            TitledPane comparison = new TitledPane("Bandingkan dua skenario tersimpan",
                    ScenarioComparison.panel(course, alternatives, true));
            comparison.setExpanded(false);
            comparison.setAnimated(false);
            comparison.setId("target-summary-comparison");
            getChildren().add(comparison);
        }
        getChildren().add(recommendations);
    }

    void render(Simulation simulation, Scenario opened, Course current, Calculation.TargetReport report, String error) {
        String source = opened == null ? "Simulasi saat ini" : "Skenario: " + opened.name();
        if (!simulation.baseline().sameGradeInputs(current)) source += " · Data dasar lama";
        if (simulation.baseline().legacyWeights()) source += " · Bobot relatif lama";
        context.setText(source);
        if (report == null || report.selected().status() == Calculation.Status.EMPTY) {
            details.setText(report == null ? (error.isBlank() ? "Periksa target dan isian penilaian yang ditandai." : error)
                    : "Sertakan minimal satu penilaian untuk menghitung target.");
            recommendations.setVisible(false);
            return;
        }

        Calculation.Projection projection = report.selected();
        List<Simulation.Row> free = simulation.rows().stream()
                .filter(row -> !row.excluded() && row.value().earned() == null).toList();
        details.setText(selectedDetails(simulation, projection, free));
        String intro = "Ambang huruf memakai nilai minimum. Nilai tertinggi dalam batas normal: "
                + result(projection.maximum()) + ".";
        if (free.size() > 1) {
            intro += " Setiap baris mengusulkan satu kombinasi dengan persentase yang sama untuk semua "
                    + "penilaian bebas sebelum pembulatan. Penuhi seluruh skor dalam kombinasi tersebut.";
        }
        gradeIntro.setText(intro);
        recommendations.setVisible(true);
        grades.getChildren().clear();
        grades.addRow(0, Editor.note("Huruf", "field-label"), Editor.note("Minimum", "field-label"),
                Editor.note("Skor yang diperlukan", "field-label"), Editor.note("Kelayakan", "field-label"));
        int index = 1;
        for (var grade : report.grades()) {
            Calculation.Projection candidate = grade.projection();
            boolean reachable = candidate.status() != Calculation.Status.UNREACHABLE;
            String status = candidate.status() == Calculation.Status.SECURED ? "Sudah aman"
                    : reachable ? "Dapat dicapai" : "Di luar batas normal";
            if (reachable && !candidate.aboveMaximumAssumptions().isEmpty()) status += " · bersyarat";
            grades.addRow(index++, Editor.note(grade.grade().toString(), "field-label"),
                    Editor.note(Numbers.display(grade.grade().boundary) + "%", "muted"),
                    Editor.note(gradeRequirements(grade.grade(), candidate, free), "target-detail"),
                    Editor.note(status, reachable ? "muted" : "validation"));
        }
    }

    private static String selectedDetails(Simulation simulation, Calculation.Projection projection, List<Simulation.Row> free) {
        StringBuilder text = new StringBuilder("Target ").append(Numbers.display(simulation.target())).append("% · ")
                .append(ResultText.status(projection)).append(".\n");
        if (free.isEmpty()) {
            text.append("Semua penilaian yang disertakan sudah memiliki skor tetap. Hasilnya ")
                    .append(result(projection.percent())).append(".\n");
        } else {
            if (free.size() > 1) text.append("Berikut satu kombinasi dengan persentase yang sama sebelum pembulatan. Penuhi semua skornya.\n");
            for (Simulation.Row row : free) {
                UUID id = row.value().id();
                BigDecimal extra = projection.excess().get(id);
                String required = Numbers.display(projection.suggestions().get(id));
                String maximum = Numbers.display(row.value().maximum());
                text.append(row.value().name()).append(": perlu ").append(required).append('/').append(maximum);
                if (extra != null) {
                    text.append(" (").append(maximum).append(" maksimum normal + ")
                            .append(Numbers.display(extra)).append(" poin tambahan)");
                }
                text.append(".\n");
            }
            if (!projection.excess().isEmpty()) {
                text.append("Proyeksi ").append(result(projection.percent()))
                        .append(" hanya berlaku jika dosen mengizinkan bonus tersebut dan semua skor diperoleh.\n");
            } else {
                text.append("Jika semua skor tersebut diperoleh, hasil proyeksi ")
                        .append(result(projection.percent())).append(".\n");
            }
        }
        text.append("Maksimum dengan semua skor bebas di batas normal: ").append(result(projection.maximum())).append('.');
        if (projection.status() == Calculation.Status.UNREACHABLE) {
            text.append(" Masih kurang ").append(ResultText.value(projection.shortfall())).append(" poin persentase.");
        }
        if (!projection.aboveMaximumAssumptions().isEmpty()) {
            text.append("\nHasil bersyarat: asumsi ").append(String.join(", ", projection.aboveMaximumAssumptions()))
                    .append(" melampaui maksimum. Bonus bergantung pada izin dosen.");
        }
        return text.toString();
    }

    private static String gradeRequirements(Grade grade, Calculation.Projection projection, List<Simulation.Row> free) {
        if (free.isEmpty()) return "Hasil tetap " + result(projection.percent()) + ".";
        if (free.size() == 1) {
            var assessment = free.getFirst().value();
            return "Untuk mencapai minimal " + grade + ", kamu perlu "
                    + Numbers.display(projection.suggestions().get(assessment.id())) + " dari "
                    + Numbers.display(assessment.maximum()) + " pada " + assessment.name() + ".";
        }
        return String.join(" · ", free.stream().map(row -> {
            var value = row.value();
            return value.name() + " " + Numbers.display(projection.suggestions().get(value.id()))
                    + "/" + Numbers.display(value.maximum());
        }).toList());
    }

    private static String result(Exact percentage) {
        return ResultText.percent(percentage) + " (" + Grade.classify(percentage) + ")";
    }
}
