package id.whatif;

import java.math.BigDecimal;
import java.util.Locale;

record Grade(String letter, BigDecimal boundary, BigDecimal point) {
    static final Grade A = initial("A", 85, "4.00");
    static final Grade A_MINUS = initial("A-", 80, "3.70");
    static final Grade B_PLUS = initial("B+", 75, "3.30");
    static final Grade B = initial("B", 70, "3.00");
    static final Grade C_PLUS = initial("C+", 65, "2.70");
    static final Grade C = initial("C", 60, "2.00");
    static final Grade D = initial("D", 55, "1.00");
    static final Grade E = initial("E", 0, "0.00");

    Grade {
        letter = normalizeLetter(letter);
        if (letter.isEmpty()) throw new IllegalArgumentException("Huruf nilai wajib diisi.");
        boundary = Numbers.validate(boundary, "Persentase minimum", false);
        point = normalizePoints(point);
    }

    static String normalizeLetter(String letter) {
        return letter.strip().replace('−', '-').toUpperCase(Locale.ROOT);
    }

    static BigDecimal normalizePoints(BigDecimal point) {
        BigDecimal value = Numbers.validate(point, "Angka mutu", false);
        return value.setScale(Math.max(2, value.scale()));
    }

    private static Grade initial(String letter, int minimum, String points) {
        return new Grade(letter, BigDecimal.valueOf(minimum), new BigDecimal(points));
    }

    static Grade[] values() { return new Grade[] { A, A_MINUS, B_PLUS, B, C_PLUS, C, D, E }; }

    static Grade classify(Exact percent) { return GradeScale.DEFAULT.classify(percent); }

    @Override public String toString() { return letter; }
}
