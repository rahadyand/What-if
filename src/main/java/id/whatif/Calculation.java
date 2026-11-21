package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;

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
        List<Group> groups = groups(course, known);
        Exact total = Exact.of(Course.totalWeight(course.categories()));
        Exact represented = Exact.ZERO;
        Exact contribution = Exact.ZERO;
        List<CategoryContribution> details = new ArrayList<>();
        for (Group group : groups) {
            Exact weight = Exact.of(group.category().weight()).divide(total).multiply(Exact.HUNDRED);
            Exact points = evaluate(group.terms(), Map.of()).multiply(weight);
            represented = represented.add(weight);
            contribution = contribution.add(points);
            details.add(new CategoryContribution(group.category().name(), points, weight));
        }
        Exact percent = groups.isEmpty() ? null : contribution.divide(represented).multiply(Exact.HUNDRED);
        return new Actual(percent, known.size(), course.assessments().size(), contribution, represented, List.copyOf(details));
    }

    static Projection project(Simulation simulation) {
        Exact target = Exact.of(simulation.target()).divide(Exact.HUNDRED);
        Course course = simulation.baseline();
        List<Simulation.Row> included = simulation.rows().stream().filter(row -> !row.excluded()).toList();
        List<Group> groups = groups(course, included.stream().map(Simulation.Row::value).toList());
        if (groups.isEmpty()) {
            return new Projection(null, null, null, null, null, Map.of(), Map.of(), List.of(), Exact.ZERO, Status.EMPTY);
        }

        Exact includedWeight = groups.stream().map(group -> Exact.of(group.category().weight()))
                .reduce(Exact.ZERO, Exact::add);
        Exact totalWeight = Exact.of(Course.totalWeight(course.categories()));
        Exact denominator = course.legacyWeights() ? includedWeight : totalWeight;
        List<Term> terms = new ArrayList<>();
        for (Group group : groups) {
            Exact weight = Exact.of(group.category().weight()).divide(denominator);
            for (Term term : group.terms()) terms.add(new Term(term.row(), term.share().multiply(weight)));
        }

        Exact fixed = evaluate(terms, Map.of());
        List<Term> free = terms.stream().filter(term -> term.row().earned() == null).toList();
        Exact freeShare = free.stream().map(Term::share).reduce(Exact.ZERO, Exact::add);
        Exact maximum = fixed.add(freeShare);
        boolean unreachable = target.compareTo(maximum) > 0;
        boolean secured = target.compareTo(fixed) <= 0;
        Exact gap = unreachable ? target.subtract(maximum).multiply(Exact.HUNDRED) : Exact.ZERO;
        Exact common = free.isEmpty() ? null : secured ? Exact.ZERO : target.subtract(fixed).divide(freeShare);
        Map<UUID, BigDecimal> suggestions = new LinkedHashMap<>();
        Map<UUID, BigDecimal> excess = new LinkedHashMap<>();
        for (Term term : free) {
            Assessment row = term.row();
            BigDecimal earned = Exact.of(row.maximum()).multiply(common).decimal(2, RoundingMode.CEILING);
            // A fractional normal maximum can have more than two decimal places.
            if (!unreachable && earned.compareTo(row.maximum()) > 0) earned = row.maximum();
            suggestions.put(row.id(), earned);
            if (earned.compareTo(row.maximum()) > 0) excess.put(row.id(), earned.subtract(row.maximum()));
        }
        List<String> aboveMaximumAssumptions = included.stream().filter(Simulation.Row::assumption)
                .map(Simulation.Row::value).filter(row -> row.earned().compareTo(row.maximum()) > 0)
                .map(Assessment::name).toList();
        Status status = unreachable ? Status.UNREACHABLE : secured ? Status.SECURED : Status.WITH_SUGGESTIONS;
        return new Projection(evaluate(terms, suggestions).multiply(Exact.HUNDRED), maximum.multiply(Exact.HUNDRED),
                gap, fixed.multiply(Exact.HUNDRED), common == null ? null : common.multiply(Exact.HUNDRED), Map.copyOf(suggestions), Map.copyOf(excess),
                aboveMaximumAssumptions, includedWeight.divide(totalWeight).multiply(Exact.HUNDRED), status);
    }

    private static List<Group> groups(Course course, List<Assessment> participating) {
        var byCategory = new LinkedHashMap<UUID, List<Assessment>>();
        for (Assessment row : participating) {
            byCategory.computeIfAbsent(row.categoryId(), unused -> new ArrayList<>()).add(row);
        }
        List<Group> result = new ArrayList<>();
        for (Category category : course.categories()) {
            List<Assessment> rows = byCategory.get(category.id());
            if (rows == null) continue;
            List<Exact> shares = course.method().shares(rows);
            List<Term> terms = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) terms.add(new Term(rows.get(index), shares.get(index)));
            result.add(new Group(category, terms));
        }
        return result;
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

    private record Group(Category category, List<Term> terms) {}
    private record Term(Assessment row, Exact share) {}

    enum Status { EMPTY, SECURED, UNREACHABLE, WITH_SUGGESTIONS }

    record CategoryContribution(String name, Exact points, Exact weight) {}

    record Actual(Exact percent, int known, int total, Exact contribution, Exact represented,
                  List<CategoryContribution> details) {
        boolean partial() { return known > 0 && (known < total || represented.compareTo(Exact.HUNDRED) < 0); }
    }

    record Projection(Exact percent, Exact maximum, Exact shortfall, Exact fixedContribution, Exact commonPercent,
                      Map<UUID, BigDecimal> suggestions, Map<UUID, BigDecimal> excess,
                      List<String> aboveMaximumAssumptions, Exact represented, Status status) {}
}
