package id.whatif;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

record Semester(UUID id, String label, String academicYear) {
    static final Comparator<Semester> NEWEST_FIRST = Comparator
            .comparing(Semester::academicYear, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Semester::number, Comparator.nullsLast(Comparator.reverseOrder()));

    Semester {
        Objects.requireNonNull(id);
        label = Objects.requireNonNull(label).strip();
        if (label.isEmpty()) throw new IllegalArgumentException("Nomor atau label semester wajib diisi.");
        if (academicYear != null) {
            academicYear = academicYear.strip();
            if (academicYear.isEmpty()) academicYear = null;
        }
    }

    String name() { return label.matches("[0-9]+") ? "Semester " + label : label; }

    private BigInteger number() {
        String text = label.replaceFirst("(?i)^semester\\s+", "");
        return text.matches("[0-9]+") ? new BigInteger(text) : null;
    }

    @Override public String toString() { return name() + (academicYear == null ? "" : " · " + academicYear); }
}
