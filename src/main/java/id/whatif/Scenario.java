package id.whatif;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

record Scenario(UUID id, String name, Simulation.State state) {
    Scenario {
        Objects.requireNonNull(id);
        name = Objects.requireNonNull(name).strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Nama skenario wajib diisi.");
        Simulation.from(state).target();
    }

    static String suggestedName(BigDecimal target) {
        return suggestedName(target, GradeScale.DEFAULT);
    }

    static String suggestedName(BigDecimal target, GradeScale scale) {
        for (Grade grade : scale.grades()) {
            if (grade.boundary().compareTo(target) == 0) return "Target " + grade;
        }
        return "Target " + Numbers.display(target) + "%";
    }

    UUID courseId() { return state.baseline().id(); }

    boolean stale(Course actual) { return stale(actual, GradeScale.DEFAULT); }

    boolean stale(Course actual, GradeScale scale) {
        return !state.baseline().sameGradeInputs(actual) || !state.scale().equals(scale);
    }

    @Override public String toString() { return name; }
}
