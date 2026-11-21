package id.whatif;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

record GradeScale(BigDecimal maximum, List<Grade> grades) {
    static final GradeScale DEFAULT = new GradeScale(BigDecimal.valueOf(4), List.of(Grade.values()));

    GradeScale {
        maximum = Numbers.validate(maximum, "Angka mutu maksimum", true);
        if (grades.isEmpty()) throw new IllegalArgumentException("Tambahkan minimal satu huruf nilai.");
        grades = grades.stream().sorted(Comparator.comparing(Grade::boundary).reversed()).toList();
        var letters = new HashSet<String>();
        var thresholds = new HashSet<BigDecimal>();
        for (Grade grade : grades) {
            if (!letters.add(grade.letter())) throw new IllegalArgumentException("Huruf nilai harus unik.");
            if (!thresholds.add(grade.boundary())) throw new IllegalArgumentException("Persentase minimum harus unik.");
            if (grade.boundary().compareTo(Course.FULL_WEIGHT) > 0) {
                throw new IllegalArgumentException("Persentase minimum harus antara 0 dan 100.");
            }
            if (grade.point().compareTo(maximum) > 0) throw new IllegalArgumentException("Angka mutu melampaui maksimum skala.");
        }
        if (grades.getLast().boundary().signum() != 0) {
            throw new IllegalArgumentException("Cantumkan huruf dengan batas 0% agar semua nilai tercakup.");
        }
    }

    Grade classify(Exact percent) {
        return grades.stream().filter(grade -> percent.compareTo(Exact.of(grade.boundary())) >= 0)
                .findFirst().orElseThrow();
    }

    Grade find(String letter) {
        return grades.stream().filter(grade -> grade.letter().equals(letter)).findFirst().orElse(null);
    }

    BigDecimal defaultTarget() { return grades.getFirst().boundary(); }
}
