package id.whatif;

import id.whatif.Course.Assessment;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A private working copy of a single immutable course with its own target and rows. */
final class Simulation {
    private static final BigDecimal DEFAULT_TARGET = Grade.A.boundary;
    private final Course baseline;
    private final LinkedHashMap<UUID, Row> rows = new LinkedHashMap<>();
    private String targetInput;

    Simulation(Course baseline) {
        this.baseline = Objects.requireNonNull(baseline);
        reset();
    }

    Course baseline() { return baseline; }
    List<Row> rows() { return List.copyOf(rows.values()); }
    String targetInput() { return targetInput; }
    BigDecimal target() { return Numbers.read(targetInput, "Target persentase", false, false); }

    void targetInput(String text) { targetInput = Objects.requireNonNull(text); }
    void chooseGrade(Grade grade) { targetInput = Numbers.display(grade.boundary); }

    void put(Assessment value, boolean assumption) {
        baseline.categoryName(value.categoryId());
        if (assumption && value.earned() == null) {
            throw new IllegalArgumentException("Isi nilai untuk asumsi tetap.");
        }
        Row old = rows.get(value.id());
        if (old == null || old.original() == null) {
            if (assumption != (value.earned() != null)) {
                throw new IllegalArgumentException("Tentukan apakah nilai bebas atau asumsi tetap.");
            }
        } else if (!assumption && value.earned() != null && !Objects.equals(value.earned(), old.original().earned())) {
            throw new IllegalArgumentException("Perubahan nilai aktual memerlukan asumsi sementara.");
        }
        rows.put(value.id(), new Row(value, old == null ? null : old.original(),
                old != null && old.excluded(), assumption));
    }

    void setExcluded(UUID id, boolean excluded) {
        Row row = Objects.requireNonNull(rows.get(id));
        rows.put(id, new Row(row.value(), row.original(), excluded, row.assumption()));
    }

    void restore(UUID id) {
        Row row = Objects.requireNonNull(rows.get(id));
        if (row.original() != null) rows.put(id, original(row.original()));
    }

    void reset() {
        rows.clear();
        for (Assessment assessment : baseline.assessments()) rows.put(assessment.id(), original(assessment));
        targetInput = Numbers.display(DEFAULT_TARGET);
    }

    boolean modified() {
        try {
            if (target().compareTo(DEFAULT_TARGET) != 0) return true;
        } catch (IllegalArgumentException invalid) {
            return true;
        }
        return rows.values().stream().anyMatch(row -> row.excluded() || row.modified());
    }

    private static Row original(Assessment assessment) {
        return new Row(assessment, assessment, false, false);
    }

    record Row(Assessment value, Assessment original, boolean excluded, boolean assumption) {
        boolean modified() {
            return original == null || assumption || !value.equals(original);
        }
    }
}
