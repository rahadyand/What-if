package id.whatif;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class SemesterSks {
    static final int LIMIT = 24;

    private SemesterSks() {}

    static long recorded(UUID semester, List<Course> courses) {
        return courses.stream().map(Course::academic).filter(details -> Objects.equals(semester, details.semesterId()))
                .mapToLong(details -> count(details.sks())).sum();
    }

    static void validate(Semester semester, List<Course> courses, Course previous, AcademicDetails proposed) {
        if (semester == null) return;
        validate(semester.name(), recorded(semester.id(), courses), previous == null ? null : previous.academic(), proposed);
    }

    static void validate(String semesterName, long current, AcademicDetails previous, AcademicDetails proposed) {
        long replaced = previous != null && Objects.equals(previous.semesterId(), proposed.semesterId()) ? count(previous.sks()) : 0;
        long added = count(proposed.sks());
        long resulting = current - replaced + added;
        if (resulting <= LIMIT || resulting <= current) return;
        String explanation = replaced == 0 ? "Menambahkan " + added + " SKS akan menjadi "
                : "Perubahan SKS mata kuliah ini akan menjadi ";
        throw new IllegalArgumentException(semesterName + " sudah memiliki " + current + " SKS. "
                + explanation + resulting + " dari maksimum " + LIMIT + " SKS.");
    }

    private static int count(Integer sks) { return sks == null ? 0 : sks; }
}
