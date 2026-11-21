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
        for (Grade grade : Grade.values()) {
            if (grade.boundary.compareTo(target) == 0) return "Target " + grade;
        }
        return "Target " + Numbers.display(target) + "%";
    }

    UUID courseId() { return state.baseline().id(); }

    boolean stale(Course actual) { return !state.baseline().sameGradeInputs(actual); }

    @Override public String toString() { return name; }
}
