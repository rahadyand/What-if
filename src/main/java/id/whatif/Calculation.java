package id.whatif;

import id.whatif.Course.Assessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Calculation {
    private Calculation() {}

    static Actual actual(Course course) {
        List<Assessment> known = course.assessments().stream().filter(row -> row.earned() != null).toList();
        Exact ratio = known.isEmpty() ? null : evaluate(terms(course, known), Map.of());
        return new Actual(ratio == null ? null : ratio.multiply(Exact.HUNDRED),
                known.size(), course.assessments().size());
    }

    static Projection project(Simulation simulation) {
        Exact target = Exact.of(simulation.target()).divide(Exact.HUNDRED);
        List<Assessment> included = simulation.rows().stream().filter(row -> !row.excluded())
                .map(Simulation.Row::value).toList();
        if (included.isEmpty()) {
            return new Projection(null, Map.of(), null, Status.EMPTY, false);
        }

        List<Term> terms = terms(simulation.baseline(), included);
        Exact fixed = evaluate(terms, Map.of());
        List<Term> free = terms.stream().filter(term -> term.row().earned() == null).toList();
        if (free.isEmpty()) {
            return new Projection(fixed.multiply(Exact.HUNDRED), Map.of(), null,
                    fixed.compareTo(target) >= 0 ? Status.SECURED : Status.FIXED_BELOW, false);
        }

        Exact freeShare = free.stream().map(Term::share).reduce(Exact.ZERO, Exact::add);
        boolean secured = fixed.compareTo(target) >= 0;
        Exact common = secured ? Exact.ZERO : target.subtract(fixed).divide(freeShare);
        Map<UUID, BigDecimal> suggestions = new LinkedHashMap<>();
        boolean bonus = false;
        for (Term term : free) {
            Assessment row = term.row();
            BigDecimal earned = Exact.of(row.maximum()).multiply(common).decimal(2, RoundingMode.CEILING);
            suggestions.put(row.id(), earned);
            if (earned.compareTo(row.maximum()) > 0) bonus = true;
        }
        Exact projected = evaluate(terms, suggestions).multiply(Exact.HUNDRED);
        return new Projection(projected, Map.copyOf(suggestions), common.multiply(Exact.HUNDRED),
                secured ? Status.SECURED : Status.WITH_SUGGESTIONS, bonus);
    }

    private static List<Term> terms(Course course, List<Assessment> participating) {
        var byCategory = new LinkedHashMap<UUID, List<Assessment>>();
        for (Assessment row : participating) {
            byCategory.computeIfAbsent(row.categoryId(), unused -> new ArrayList<>()).add(row);
        }
        Exact weightSum = course.categories().stream()
                .filter(category -> byCategory.containsKey(category.id()))
                .map(category -> Exact.of(category.weight())).reduce(Exact.ZERO, Exact::add);

        List<Term> terms = new ArrayList<>();
        for (Course.Category category : course.categories()) {
            List<Assessment> rows = byCategory.get(category.id());
            if (rows == null) continue;
            Exact categoryShare = Exact.of(category.weight()).divide(weightSum);
            List<Exact> shares = course.method().shares(rows);
            for (int index = 0; index < rows.size(); index++) {
                terms.add(new Term(rows.get(index), categoryShare.multiply(shares.get(index))));
            }
        }
        return terms;
    }

    private static Exact evaluate(List<Term> terms, Map<UUID, BigDecimal> suggested) {
        Exact result = Exact.ZERO;
        for (Term term : terms) {
            Assessment row = term.row();
            BigDecimal earned = row.earned() == null ? suggested.get(row.id()) : row.earned();
            if (earned != null) {
                Exact ratio = Exact.of(earned).divide(Exact.of(row.maximum()));
                result = result.add(term.share().multiply(ratio));
            }
        }
        return result;
    }

    private record Term(Assessment row, Exact share) {}

    enum Status { EMPTY, SECURED, FIXED_BELOW, WITH_SUGGESTIONS }

    record Actual(Exact percent, int known, int total) {
        boolean partial() { return known > 0 && known < total; }
    }

    record Projection(Exact percent, Map<UUID, BigDecimal> suggestions, Exact commonPercent,
                      Status status, boolean bonus) {}
}
