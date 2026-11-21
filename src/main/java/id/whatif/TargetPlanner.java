package id.whatif;

import id.whatif.AcademicAverage.Contribution;
import id.whatif.AcademicAverage.ProjectedRepeatKey;
import id.whatif.AcademicAverage.RepeatGroup;
import id.whatif.AcademicAverage.RepeatDecision;
import id.whatif.AcademicAverage.Result;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

final class TargetPlanner {
    private static final Comparator<Outcome> OUTCOME_ORDER = Comparator.comparing((Outcome outcome) -> outcome.grade().point())
            .thenComparing(Outcome::percent).thenComparing(Outcome::target);

    private TargetPlanner() {}

    static BigDecimal readTarget(String input, GradeScale scale) {
        BigDecimal target = Numbers.read(input, "Target", false, false);
        if (target.compareTo(scale.maximum()) > 0) {
            throw new IllegalArgumentException("Gunakan target antara 0 dan " + Numbers.display(scale.maximum()) + ".");
        }
        return target;
    }

    static Report plan(Inputs inputs, Mode mode, BigDecimal target, Set<UUID> useActual, Map<ProjectedRepeatKey, Boolean> sessionReviews) {
        var courses = new ArrayList<CoursePlan>();
        for (Course course : inputs.courses()) {
            FinalGrade grade = inputs.finals().get(course.id());
            boolean viewed = inputs.semester().equals(course.academic().semesterId());
            if (viewed) courses.add(resolve(course, grade, inputs, useActual));
            else if (mode == Mode.CUMULATIVE && grade != null && course.academic().sks() != null
                    && course.academic().semesterId() != null) courses.add(confirmed(course, grade));
        }
        var ready = courses.stream().filter(CoursePlan::ready).map(CoursePlan::highest).toList();
        List<Contribution> included = ready;
        List<RepeatGroup> unresolved = List.of();
        if (mode == Mode.CUMULATIVE) {
            var reviewed = AcademicAverage.reviewRepeats(ready, inputs.actualReviews(), inputs.projectionReviews(), sessionReviews);
            included = reviewed.result().courses();
            unresolved = reviewed.repeats().stream().filter(group -> !group.reviewed()).toList();
        }
        var ids = included.stream().map(item -> item.course().id()).collect(Collectors.toUnmodifiableSet());
        var outside = mode == Mode.SEMESTER ? List.<Course>of() : inputs.outside();
        if (included.isEmpty() || !unresolved.isEmpty() || courses.stream().anyMatch(course -> !course.ready())) {
            return new Report(Status.INCOMPLETE, courses, ids, unresolved, outside, null, null, null, Map.of(), null);
        }

        Result ceiling = AcademicAverage.summarize(included);
        var considered = courses.stream().filter(row -> ids.contains(row.course().id())).toList();
        Result starting = considered.stream().allMatch(row -> row.confirmed() != null || row.starting() != null)
                ? AcademicAverage.summarize(considered.stream().map(CoursePlan::initialContribution).toList()) : null;
        if (target == null) return new Report(Status.INCOMPLETE, courses, ids, unresolved, outside, starting, ceiling, null, Map.of(), null);
        Exact goal = Exact.of(target);
        if (ceiling.exact().compareTo(goal) < 0) {
            return new Report(Status.UNREACHABLE, courses, ids, unresolved, outside, starting, ceiling, null,
                    Map.of(), goal.subtract(ceiling.exact()));
        }
        if (starting != null && starting.exact().compareTo(goal) >= 0) {
            var outcomes = considered.stream().filter(row -> row.confirmed() == null)
                    .collect(Collectors.toUnmodifiableMap(row -> row.course().id(), CoursePlan::starting));
            return new Report(Status.MET, courses, ids, unresolved, outside, starting, ceiling, starting, outcomes, null);
        }

        BigDecimal needed = target.multiply(BigDecimal.valueOf(ceiling.sks()));
        BigDecimal weighted = ceiling.weighted();
        var recommendations = new LinkedHashMap<UUID, Outcome>();
        for (var row : considered) {
            if (row.confirmed() != null) continue;
            weighted = weighted.subtract(row.highest().weighted());
            for (var option : row.outcomes()) {
                BigDecimal withOption = weighted.add(row.contribution(option).weighted());
                if (withOption.compareTo(needed) < 0) continue;
                recommendations.put(row.course().id(), option);
                weighted = withOption;
                break;
            }
        }
        Result suggested = AcademicAverage.summarize(considered.stream().map(row -> row.confirmed() != null
                ? row.highest() : row.contribution(recommendations.get(row.course().id()))).toList());
        return new Report(Status.REACHABLE, courses, ids, unresolved, outside, starting, ceiling, suggested,
                Map.copyOf(recommendations), null);
    }

    private static CoursePlan resolve(Course course, FinalGrade grade, Inputs inputs, Set<UUID> useActual) {
        DashboardProjection.Row choice = inputs.dashboardRows().get(course.id());
        if (course.academic().sks() == null) return CoursePlan.issue(course, choice, "Isi SKS di Pengaturan Mata Kuliah.");
        if (grade != null) return confirmed(course, grade);
        boolean chosen = choice != null && choice.choice() != null && !useActual.contains(course.id());
        if (chosen && !choice.usable()) return CoursePlan.issue(course, choice, choice.issue());
        var sim = chosen ? Simulation.from(choice.snapshot().state()) : new Simulation(course, inputs.scale());
        var plan = Calculation.targetReport(sim);
        var current = plan.selected();
        if (current.status() == Calculation.Status.EMPTY || current.represented().compareTo(Exact.HUNDRED) < 0) {
            return CoursePlan.issue(course, chosen ? choice : null, "Lengkapi penilaian pada setiap kategori berbobot.");
        }
        String source = chosen ? "Skenario " + choice.name() : "Data aktual";
        boolean fixed = sim.rows().stream().filter(row -> !row.excluded()).allMatch(row -> row.value().earned() != null);
        Outcome starting = chosen ? outcome(sim, current.percent(), sim.target())
                : fixed ? outcome(sim, current.maximum(), BigDecimal.ZERO) : null;
        var outcomes = new LinkedHashSet<Outcome>();
        for (var item : plan.grades()) {
            var result = item.projection();
            if (result.status() != Calculation.Status.UNREACHABLE && result.excess().isEmpty()) {
                outcomes.add(outcome(sim, result.percent(), item.grade().boundary()));
            }
        }
        Grade maximumLetter = sim.scale().classify(current.maximum());
        outcomes.add(new Outcome(maximumLetter, current.maximum(), maximumLetter.boundary()));
        if (starting != null) outcomes.add(starting);
        return new CoursePlan(course, source, "", choice, null, sim.state(), starting,
                outcomes.stream().sorted(OUTCOME_ORDER).toList());
    }

    private static CoursePlan confirmed(Course course, FinalGrade grade) {
        return new CoursePlan(course, "Nilai final · " + grade.source(), "", null, grade, null, null, List.of());
    }

    private static Outcome outcome(Simulation sim, Exact percent, BigDecimal target) {
        return new Outcome(sim.scale().classify(percent), percent, target);
    }

    enum Mode {
        SEMESTER("Target IP Semester", "IP"), CUMULATIVE("Target IPK", "IPK");

        private final String label;
        private final String unit;
        Mode(String label, String unit) { this.label = label; this.unit = unit; }
        String unit() { return unit; }
        @Override public String toString() { return label; }
    }

    enum Status {
        MET("Sudah Memenuhi"), REACHABLE("Bisa Dicapai"), UNREACHABLE("Tidak Dapat Dicapai"), INCOMPLETE("Belum Lengkap");

        private final String label;
        Status(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    record Inputs(UUID semester, List<Course> courses, Map<UUID, FinalGrade> finals, GradeScale scale,
                  Map<UUID, DashboardProjection.Row> dashboardRows, List<Course> outside,
                  Map<UUID, RepeatDecision> actualReviews, Map<ProjectedRepeatKey, Boolean> projectionReviews) {}

    record Outcome(Grade grade, Exact percent, BigDecimal target) {}

    record CoursePlan(Course course, String source, String issue, DashboardProjection.Row choice, FinalGrade confirmed,
                      Simulation.State baseline, Outcome starting, List<Outcome> outcomes) {
        static CoursePlan issue(Course course, DashboardProjection.Row choice, String reason) {
            return new CoursePlan(course, "", reason, choice, null, null, null, List.of());
        }

        boolean ready() { return issue.isEmpty(); }

        Contribution contribution(Outcome result) {
            return new Contribution(course, result.grade().letter(), result.grade().point(), source, true);
        }

        Contribution highest() { return confirmed == null ? contribution(outcomes.getLast()) : new Contribution(course, confirmed); }

        Contribution initialContribution() { return confirmed == null ? contribution(starting) : highest(); }

        Simulation draft(Outcome outcome) {
            var sim = Simulation.from(baseline);
            sim.targetInput(Numbers.display(outcome.target()));
            return sim;
        }
    }

    record Report(Status status, List<CoursePlan> courses, Set<UUID> included, List<RepeatGroup> unresolved,
                  List<Course> outside, Result starting, Result ceiling, Result suggested,
                  Map<UUID, Outcome> recommendations, Exact shortfall) {
        Report { courses = List.copyOf(courses); }

        long knownSks() {
            return courses.stream().filter(row -> included.contains(row.course().id()))
                    .map(CoursePlan::course).map(Course::academic).map(AcademicDetails::sks)
                    .mapToLong(Integer::longValue).sum();
        }
    }
}
