package id.whatif;

import java.math.BigDecimal;
import java.util.Objects;

record FinalGrade(String letter, BigDecimal points, Source source, String keptCandidate) {
    FinalGrade {
        letter = Grade.normalizeLetter(letter);
        if (letter.isEmpty()) throw new IllegalArgumentException("Huruf nilai final wajib diisi.");
        points = Grade.normalizePoints(points);
        Objects.requireNonNull(source);
    }

    static FinalGrade of(Grade grade, Source source) {
        return new FinalGrade(grade.letter(), grade.point(), source, null);
    }

    static FinalGrade calculated(Calculation.Actual actual, GradeScale scale) {
        return actual.percent() == null || actual.partial() ? null : of(scale.classify(actual.percent()), Source.CALCULATED);
    }

    Review review(FinalGrade calculated, GradeScale scale) {
        FinalGrade candidate = calculated;
        if (source == Source.OFFICIAL) {
            Grade mapped = scale.find(letter);
            if (mapped == null) {
                String key = "OFFICIAL_UNMAPPED";
                return new Review(null, "Huruf resmi ini tidak ada dalam skala aktif.", key, !key.equals(keptCandidate));
            }
            candidate = of(mapped, Source.OFFICIAL);
        }
        if (candidate == null) return new Review(null, "Perhitungan aktual belum lengkap.", null, false);
        if (letter.equals(candidate.letter) && points.compareTo(candidate.points) == 0) {
            return new Review(candidate, "Huruf dan angka mutu setara dengan hasil tersimpan.", null, false);
        }
        String key = candidate.source.name() + ":" + candidate.letter + ":" + candidate.points.toPlainString();
        return new Review(candidate, "Huruf atau angka mutu berbeda dari hasil tersimpan.", key, !key.equals(keptCandidate));
    }

    FinalGrade keep(Review review) { return new FinalGrade(letter, points, source, review.candidateKey()); }

    String display() { return letter + " · " + pointText(); }

    String pointText() { return points.toPlainString().replace('.', ','); }

    enum Source {
        CALCULATED("Hasil perhitungan"),
        OFFICIAL("Resmi dari kampus · dimasukkan manual");

        private final String label;
        Source(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    record Review(FinalGrade candidate, String explanation, String candidateKey, boolean needed) {}
}
