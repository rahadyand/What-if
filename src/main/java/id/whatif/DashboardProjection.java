package id.whatif;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

final class DashboardProjection {
    private DashboardProjection() {}

    static Report evaluate(UUID viewed, List<Course> courses, Map<UUID, FinalGrade> finals, GradeScale scale,
                           Scenario.Catalog catalog, Settings saved,
                           Map<UUID, AcademicAverage.RepeatDecision> actualReviews) {
        var available = catalog.scenarios().stream().collect(Collectors.toMap(Scenario::id, Function.identity()));
        var rows = new LinkedHashMap<UUID, Row>();
        var term = new ArrayList<AcademicAverage.Contribution>();
        var missing = new ArrayList<Course>();
        var outside = new ArrayList<Course>();
        var combined = new ArrayList<>(AcademicAverage.eligible(courses, finals));
        boolean pending = false;
        for (Course course : courses) {
            FinalGrade confirmed = finals.get(course.id());
            if (viewed == null || !viewed.equals(course.academic().semesterId())) {
                if (confirmed == null) outside.add(course);
                continue;
            }
            Row row = confirmed == null ? selected(course, scale, saved.selections().get(course.id()),
                    available, catalog.invalid()) : new Row(null, null, null, confirmed, "");
            rows.put(course.id(), row);
            pending |= confirmed == null;
            if (course.academic().sks() == null || !row.usable()) {
                missing.add(course);
                continue;
            }
            if (confirmed != null) {
                term.add(new AcademicAverage.Contribution(course, confirmed));
            } else {
                var contribution = new AcademicAverage.Contribution(course, row.grade().letter(), row.grade().point(),
                        "Skenario " + row.name(), true);
                term.add(contribution);
                combined.add(contribution);
            }
        }
        var cumulative = AcademicAverage.reviewRepeats(combined, actualReviews, saved.reviews());
        return new Report(Map.copyOf(rows), AcademicAverage.summarize(term), cumulative,
                List.copyOf(missing), List.copyOf(outside), pending);
    }

    private static Row selected(Course course, GradeScale scale, UUID id, Map<UUID, Scenario> available, Map<UUID, String> invalid) {
        if (id == null) return new Row(null, null, null, null, "Pilih skenario tersimpan.");
        Scenario snapshot = available.get(id);
        if (snapshot == null) {
            String name = invalid.get(id);
            return new Row(id, null, name, null, name == null ? "Skenario pilihan sudah dihapus atau tidak ditemukan."
                    : "Snapshot skenario tidak valid. Pilih skenario lain.");
        }
        String issue;
        if (!snapshot.courseId().equals(course.id())) issue = "Skenario berasal dari mata kuliah lain.";
        else if (snapshot.stale(course, scale)) issue = "Data dasar lama. Tinjau, perbarui, lalu simpan di What If.";
        else {
            Simulation simulation = Simulation.from(snapshot.state());
            var result = Calculation.project(simulation);
            if (result.status() == Calculation.Status.EMPTY || result.represented().compareTo(Exact.HUNDRED) < 0) {
                issue = "Kategori penilaian belum lengkap. Tinjau skenario di What If.";
            } else if (result.status() == Calculation.Status.UNREACHABLE || !result.aboveMaximumAssumptions().isEmpty()) {
                issue = "Proyeksi melampaui batas normal. Tinjau skenario di What If.";
            } else {
                return new Row(id, snapshot, snapshot.name(), null, "", simulation.scale().classify(result.percent()));
            }
        }
        return new Row(id, snapshot, snapshot.name(), null, issue);
    }

    record Settings(Map<UUID, UUID> selections, Map<AcademicAverage.ProjectedRepeatKey, Boolean> reviews) {}

    record Row(UUID choice, Scenario snapshot, String name, FinalGrade confirmed, String issue, Grade grade) {
        Row(UUID choice, Scenario snapshot, String name, FinalGrade confirmed, String issue) {
            this(choice, snapshot, name, confirmed, issue, null);
        }

        boolean usable() { return confirmed != null || grade != null; }

        String display() {
            if (confirmed != null) return "Nilai final · " + confirmed.letter();
            if (grade != null) return name + " · " + grade.letter();
            return (name == null ? "" : name + " · ") + issue;
        }
    }

    record Report(Map<UUID, Row> rows, AcademicAverage.Result semester, AcademicAverage.Selection cumulative,
                  List<Course> missing, List<Course> outside, boolean hasUnfinished) {
        boolean complete() { return hasUnfinished && missing.isEmpty(); }
        boolean needsRepeatReview() { return cumulative.repeats().stream().anyMatch(group -> !group.reviewed()); }
        boolean hasCumulativeProjection() { return cumulative.result().courses().stream().anyMatch(AcademicAverage.Contribution::projected); }
    }
}
