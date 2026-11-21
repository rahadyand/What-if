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
        long recorded = members.stream().filter(course -> course.academic().sks() != null)
                .mapToLong(course -> course.academic().sks()).sum();
        return new SemesterResult(summarize(available), recorded, missing.sks(), missing.finalGrade(),
                !members.isEmpty() && available.size() == members.size());
    }

    static Cumulative ipk(List<Course> courses, Map<UUID, FinalGrade> finals, Map<UUID, RepeatDecision> decisions) {
        var candidates = eligible(courses, finals);
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
            boolean reviewed = group.stream().map(entry -> decisions.get(entry.course().id()))
                    .allMatch(decision -> decision != null && decision.groupKey().equals(key));
            if (reviewed) group.stream().filter(entry -> decisions.get(entry.course().id()).included()).forEach(included::add);
            repeats.add(new RepeatGroup(key, List.copyOf(group), reviewed));
        }
        return new Cumulative(summarize(included), missing(courses, finals), List.copyOf(repeats));
    }

    private static List<Contribution> eligible(List<Course> courses, Map<UUID, FinalGrade> finals) {
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

    private static Result summarize(List<Contribution> courses) {
        long sks = courses.stream().mapToLong(Contribution::sks).sum();
        BigDecimal weighted = courses.stream().map(Contribution::weighted).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(List.copyOf(courses), sks, weighted);
    }

    record Contribution(Course course, FinalGrade grade) {
        int sks() { return course.academic().sks(); }
        BigDecimal weighted() { return grade.points().multiply(BigDecimal.valueOf(sks())); }
    }

    record Result(List<Contribution> courses, long sks, BigDecimal weighted) {
        String display() {
            return sks == 0 ? "Belum tersedia" : weighted.divide(BigDecimal.valueOf(sks), 2, RoundingMode.HALF_UP)
                    .toPlainString().replace('.', ',');
        }
    }

    record SemesterResult(Result result, long recordedSks, long missingSks, long missingFinal, boolean complete) {}
    record Missing(long semester, long sks, long finalGrade) {}
    record Cumulative(Result result, Missing missing, List<RepeatGroup> repeats) {}
    record RepeatDecision(String groupKey, boolean included) {}
    record RepeatGroup(String key, List<Contribution> courses, boolean reviewed) {}
}
