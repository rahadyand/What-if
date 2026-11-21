package id.whatif;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

final class AcademicAverage {
    private AcademicAverage() {}

    static SemesterResult semester(UUID id, List<Course> courses, Map<UUID, FinalGrade> finals) {
        var members = id == null ? List.<Course>of() : courses.stream()
                .filter(course -> id.equals(course.academic().semesterId())).toList();
        var available = eligible(members, finals);
        var missing = missing(members, finals);
        long recorded = id == null ? 0 : SemesterSks.recorded(id, members);
        return new SemesterResult(summarize(available), recorded, missing.sks(), missing.finalGrade(),
                !members.isEmpty() && available.size() == members.size());
    }

    static Cumulative ipk(List<Course> courses, Map<UUID, FinalGrade> finals, Map<UUID, RepeatDecision> decisions) {
        var selection = reviewRepeats(eligible(courses, finals), decisions, Map.of());
        return new Cumulative(selection.result(), missing(courses, finals), selection.repeats());
    }

    static Selection reviewRepeats(List<Contribution> candidates, Map<UUID, RepeatDecision> actual,
                                   Map<ProjectedRepeatKey, Boolean> projected) {
        return reviewRepeats(candidates, actual, projected, Map.of());
    }

    static Selection reviewRepeats(List<Contribution> candidates, Map<UUID, RepeatDecision> actual,
                                   Map<ProjectedRepeatKey, Boolean> projected, Map<ProjectedRepeatKey, Boolean> session) {
        var groups = new LinkedHashMap<String, List<Contribution>>();
        for (var entry : candidates) {
            String name = entry.course().name().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            groups.computeIfAbsent(name, unused -> new ArrayList<>()).add(entry);
        }
        var included = new ArrayList<Contribution>();
        var repeats = new ArrayList<RepeatGroup>();
        for (var group : groups.values()) {
            if (group.size() == 1) {
                included.add(group.getFirst());
                continue;
            }
            String key = group.stream().map(entry -> entry.course().id().toString()).sorted().collect(Collectors.joining(","));
            boolean hasProjection = group.stream().anyMatch(Contribution::projected);
            Map<UUID, Boolean> choices = new LinkedHashMap<>();
            for (var entry : group) {
                UUID id = entry.course().id();
                RepeatDecision decision = actual.get(id);
                var member = new ProjectedRepeatKey(key, id);
                Boolean include = hasProjection ? projected.get(member)
                        : decision != null && decision.groupKey().equals(key) ? Boolean.valueOf(decision.included()) : null;
                if (session.containsKey(member)) include = session.get(member);
                if (include != null) choices.put(id, include);
            }
            boolean reviewed = group.size() == choices.size();
            if (reviewed) group.stream().filter(entry -> choices.get(entry.course().id())).forEach(included::add);
            repeats.add(new RepeatGroup(key, List.copyOf(group), reviewed));
        }
        return new Selection(summarize(included), List.copyOf(repeats));
    }

    static List<Contribution> eligible(List<Course> courses, Map<UUID, FinalGrade> finals) {
        return courses.stream().filter(course -> course.academic().semesterId() != null && course.academic().sks() != null)
                .map(course -> {
                    FinalGrade grade = finals.get(course.id());
                    return grade == null ? null : new Contribution(course, grade);
                }).filter(Objects::nonNull).toList();
    }

    private static Missing missing(List<Course> courses, Map<UUID, FinalGrade> finals) {
        return new Missing(courses.stream().filter(course -> course.academic().semesterId() == null).count(),
                courses.stream().filter(course -> course.academic().sks() == null).count(),
                courses.stream().filter(course -> !finals.containsKey(course.id())).count());
    }

    static Result summarize(List<Contribution> courses) {
        long sks = courses.stream().mapToLong(Contribution::sks).sum();
        BigDecimal weighted = courses.stream().map(Contribution::weighted).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(List.copyOf(courses), sks, weighted);
    }

    record Contribution(Course course, String letter, BigDecimal points, String source, boolean projected) {
        Contribution(Course course, FinalGrade grade) {
            this(course, grade.letter(), grade.points(), "Nilai final · " + grade.source(), false);
        }

        int sks() { return course.academic().sks(); }
        BigDecimal weighted() { return points.multiply(BigDecimal.valueOf(sks())); }
        String pointText() { return points.toPlainString().replace('.', ','); }
    }

    record Result(List<Contribution> courses, long sks, BigDecimal weighted) {
        Exact exact() {
            return sks == 0 ? null : Exact.of(weighted).divide(Exact.of(BigDecimal.valueOf(sks)));
        }

        String display() {
            return sks == 0 ? "Belum tersedia" : weighted.divide(BigDecimal.valueOf(sks), 2, RoundingMode.HALF_UP)
                    .toPlainString().replace('.', ',');
        }
    }

    record SemesterResult(Result result, long recordedSks, long missingSks, long missingFinal, boolean complete) {}
    record Missing(long semester, long sks, long finalGrade) {}
    record Cumulative(Result result, Missing missing, List<RepeatGroup> repeats) {}
    record RepeatDecision(String groupKey, boolean included) {}
    record Selection(Result result, List<RepeatGroup> repeats) {}
    record ProjectedRepeatKey(String groupKey, UUID courseId) {}
    record RepeatGroup(String key, List<Contribution> courses, boolean reviewed) {
        boolean projected() { return courses.stream().anyMatch(Contribution::projected); }
    }
}
