package id.whatif;

import id.whatif.Course.Assessment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A private working copy of a single immutable course with its own target and rows. */
final class Simulation {
    private final Course baseline;
    private final GradeScale scale;
    private final LinkedHashMap<UUID, Row> rows = new LinkedHashMap<>();
    private String targetInput;

    Simulation(Course baseline) {
        this(baseline, GradeScale.DEFAULT);
    }

    Simulation(Course baseline, GradeScale scale) {
        this.baseline = Objects.requireNonNull(baseline);
        this.scale = Objects.requireNonNull(scale);
        reset();
    }

    static Simulation from(State state) {
        Simulation simulation = new Simulation(state.baseline(), state.scale());
        simulation.targetInput(state.target());
        for (Row row : state.rows()) {
            simulation.put(row.value(), row.assumption());
            simulation.setExcluded(row.value().id(), row.excluded());
        }
        return simulation;
    }

    State state() { return new State(baseline, targetInput, rows(), scale); }

    Review reviewBaseline(Course current) {
        return reviewBaseline(current, GradeScale.DEFAULT);
    }

    Review reviewBaseline(Course current, GradeScale currentScale) {
        if (!baseline.id().equals(current.id())) throw new IllegalArgumentException("Mata kuliah berbeda.");
        Simulation refreshed = new Simulation(current, currentScale);
        refreshed.targetInput(targetInput);
        List<String> notes = new ArrayList<>();
        var currentRows = new HashMap<UUID, Assessment>();
        for (Assessment row : current.assessments()) currentRows.put(row.id(), row);
        for (Row change : rows.values()) {
            if (!change.modified() && !change.excluded()) continue;
            Assessment previous = change.original();
            Assessment edit = change.value();
            Assessment latest = currentRows.get(edit.id());
            if (previous != null && latest == null) {
                notes.add(edit.name() + " — penilaian aktual dihapus. Edit dan pengecualiannya dilepas.");
                continue;
            }
            boolean categoryEdited = previous == null || !edit.categoryId().equals(previous.categoryId());
            if (categoryEdited && current.categories().stream().noneMatch(c -> c.id().equals(edit.categoryId()))) {
                if (previous != null) refreshed.setExcluded(edit.id(), change.excluded());
                notes.add(edit.name() + " — kategori simulasi dihapus. Edit ini dilepas.");
                continue;
            }
            if (previous == null) {
                refreshed.put(edit, change.assumption());
            } else {
                boolean scoreEdited = change.assumption() || !Objects.equals(edit.earned(), previous.earned());
                boolean maximumEdited = !edit.maximum().equals(previous.maximum());
                if ((scoreEdited || maximumEdited) && !latest.maximum().equals(previous.maximum())) {
                    notes.add(edit.name() + " — maksimum aktual berubah. Nilai tetap/bebas dan maksimum simulasi dikembalikan ke aktual.");
                    scoreEdited = false;
                    maximumEdited = false;
                }
                refreshed.put(new Assessment(edit.id(),
                        edit.name().equals(previous.name()) ? latest.name() : edit.name(),
                        categoryEdited ? edit.categoryId() : latest.categoryId(),
                        maximumEdited ? edit.maximum() : latest.maximum(),
                        scoreEdited ? edit.earned() : latest.earned()), scoreEdited && change.assumption());
            }
            refreshed.setExcluded(edit.id(), change.excluded());
        }
        return new Review(refreshed, List.copyOf(notes));
    }

    Course baseline() { return baseline; }
    GradeScale scale() { return scale; }

    boolean matchesBaseline(Course current, GradeScale currentScale) {
        return baseline.sameGradeInputs(current) && scale.equals(currentScale);
    }
    List<Row> rows() { return List.copyOf(rows.values()); }
    String targetInput() { return targetInput; }
    BigDecimal target() { return Numbers.read(targetInput, "Target persentase", false, false); }

    void targetInput(String text) { targetInput = Objects.requireNonNull(text); }
    void chooseGrade(Grade grade) { targetInput = Numbers.display(grade.boundary()); }

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
        targetInput = Numbers.display(scale.defaultTarget());
    }

    boolean modified() {
        try {
            if (target().compareTo(scale.defaultTarget()) != 0) return true;
        } catch (IllegalArgumentException invalid) {
            return true;
        }
        return rows.values().stream().anyMatch(row -> row.excluded() || row.modified());
    }

    private static Row original(Assessment assessment) {
        return new Row(assessment, assessment, false, false);
    }

    record State(Course baseline, String target, List<Row> rows, GradeScale scale) {
        State {
            baseline = Objects.requireNonNull(baseline).withAcademic(AcademicDetails.EMPTY);
            Objects.requireNonNull(target);
            rows = List.copyOf(rows);
            Objects.requireNonNull(scale);
        }
    }

    record Review(Simulation draft, List<String> notes) {}

    record Row(Assessment value, Assessment original, boolean excluded, boolean assumption) {
        boolean modified() {
            return original == null || assumption || !value.equals(original);
        }
    }
}
