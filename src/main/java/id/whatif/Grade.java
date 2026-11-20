package id.whatif;

import java.math.BigDecimal;
import java.util.Arrays;

enum Grade {
    A("A", 85, "4.00"), A_MINUS("A-", 80, "3.70"), B_PLUS("B+", 75, "3.30"),
    B("B", 70, "3.00"), C_PLUS("C+", 65, "2.70"), C("C", 60, "2.00"),
    D("D", 55, "1.00"), E("E", 0, "0.00");

    private final String label;
    final BigDecimal boundary;
    final BigDecimal point;

    Grade(String label, int boundary, String point) {
        this.label = label;
        this.boundary = BigDecimal.valueOf(boundary);
        this.point = new BigDecimal(point);
    }

    static Grade classify(Exact percent) {
        return Arrays.stream(values())
                .filter(grade -> percent.compareTo(Exact.of(grade.boundary)) >= 0)
                .findFirst().orElseThrow();
    }

    @Override public String toString() { return label; }
}
