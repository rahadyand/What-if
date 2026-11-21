package id.whatif;

import id.whatif.Course.Assessment;

import java.math.BigDecimal;
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

    static ScoreRecommendation scoreRecommendation(Calculation.Projection projection, Assessment assessment) {
        BigDecimal required = projection == null ? null : projection.suggestions().get(assessment.id());
        if (required == null) return null;
        BigDecimal maximum = assessment.maximum();
        boolean capped = projection.status() == Calculation.Status.UNREACHABLE;
        return new ScoreRecommendation(Numbers.display(capped ? maximum : required) + "/" + Numbers.display(maximum),
                capped ? "Maksimum" : "Minimum");
    }

    record ScoreRecommendation(String fraction, String label) {
        String display() { return fraction + " · " + label; }
    }

    static BigDecimal wholeScoreWithinMaximum(Exact commonPercent, BigDecimal maximum) {
        BigDecimal score = Exact.of(maximum).multiply(commonPercent).divide(Exact.HUNDRED).decimal(0, RoundingMode.CEILING);
        return score.compareTo(maximum) <= 0 ? score : null;
    }

    static String status(Calculation.Projection projection) {
        return switch (projection.status()) {
            case EMPTY -> "Belum ada hasil";
            case SECURED -> "Target sudah aman";
            case UNREACHABLE -> "Tidak dapat dicapai dalam batas normal";
            case WITH_SUGGESTIONS -> "Target dapat dicapai";
        };
    }
}
