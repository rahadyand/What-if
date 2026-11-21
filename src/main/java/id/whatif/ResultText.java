package id.whatif;

import java.math.RoundingMode;

final class ResultText {
    private ResultText() {}

    static String value(Exact value) {
        if (value == null) return "—";
        var decimal = value.decimal(6, RoundingMode.DOWN);
        if (decimal.signum() == 0 && value.compareTo(Exact.ZERO) != 0) {
            return (value.compareTo(Exact.ZERO) < 0 ? "−" : "") + "<0,000001";
        }
        return (Exact.of(decimal).equals(value) ? "" : "≈ ") + Numbers.display(decimal);
    }

    static String delta(Exact value) {
        return (value.compareTo(Exact.ZERO) > 0 ? "+" : "") + value(value);
    }

    static String percent(Exact value) { return value == null ? "—" : value(value) + "%"; }

    static String status(Calculation.Projection projection) {
        return switch (projection.status()) {
            case EMPTY -> "Belum ada hasil";
            case SECURED -> "Target sudah aman";
            case UNREACHABLE -> "Target Tidak Dapat Dicapai";
            case WITH_SUGGESTIONS -> "Target dapat dicapai";
        };
    }
}
