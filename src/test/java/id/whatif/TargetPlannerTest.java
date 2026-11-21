package id.whatif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static id.whatif.TargetPlanner.Mode.*;
import static id.whatif.TargetPlanner.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class TargetPlannerTest {
    @TempDir Path directory;

    @Test void combinesFeasibleCourseGradesAndUsesTheUnroundedCumulativeLimit() {
        Fixture fixture = fixture();
        var inputs = fixture.inputs();
        var semester = plan(inputs, SEMESTER, "3,50");
        assertEquals(REACHABLE, semester.status());
        assertNull(semester.starting());
        assertEquals(12, semester.suggested().sks());
        assertEquals("3,50", semester.suggested().display());
        assertEquals(new BigDecimal("45.00"), semester.ceiling().weighted());
        assertEquals("3,75", semester.ceiling().display());
        assertEquals(semester.recommendations(), plan(inputs, SEMESTER, "3.50").recommendations());
        for (var row : semester.courses()) {
            var outcome = semester.recommendations().get(row.course().id());
            if (outcome == null) continue;
            var draft = row.draft(outcome);
            var projected = Calculation.project(draft);
            assertEquals(outcome.grade(), draft.scale().classify(projected.percent()));
            assertTrue(projected.excess().isEmpty());
        }

        var impossible = plan(inputs, SEMESTER, "3.76");
        assertEquals(UNREACHABLE, impossible.status());
        assertEquals(Exact.of(new BigDecimal("0.01")), impossible.shortfall());
        var cumulative = plan(inputs, CUMULATIVE, "3,71");
        assertEquals(UNREACHABLE, cumulative.status());
        assertEquals(20, cumulative.ceiling().sks());
        assertEquals(7, cumulative.included().size());
        assertEquals(List.of(fixture.outside()), cumulative.outside());
        assertEquals(new BigDecimal("74.10"), cumulative.ceiling().weighted());
        assertEquals(Exact.of(new BigDecimal("3.705")), cumulative.ceiling().exact());
        assertEquals("3,71", cumulative.ceiling().display());
        assertEquals(Exact.of(new BigDecimal("0.005")), cumulative.shortfall());
        assertEquals(REACHABLE, plan(inputs, CUMULATIVE, "3.705").status());
    }

    @Test void honorsAssessmentCapsAndChoosesRankByPointsUnderACustomScale() {
        Fixture fixture = fixture();
        Course project = fixture.project();
        var row = project.assessments().getFirst();
        Course capped = project.withAssessments(List.of(row, new Course.Assessment(UUID.randomUUID(), "Nilai tetap",
                row.categoryId(), new BigDecimal("100"), new BigDecimal("56"))));
        var updated = fixture.inputs().courses().stream().map(course -> course.id().equals(capped.id()) ? capped : course).toList();
        var inputs = inputs(fixture.viewed(), updated, fixture.inputs().finals(), GradeScale.DEFAULT, List.of(), Map.of(), List.of());
        var unreachable = plan(inputs, SEMESTER, "3.60");
        assertEquals(UNREACHABLE, unreachable.status());
        assertEquals(new BigDecimal("42.90"), unreachable.ceiling().weighted());
        var coursePlan = unreachable.courses().stream().filter(course -> course.course().id().equals(project.id())).findFirst().orElseThrow();
        assertEquals(Grade.B_PLUS, coursePlan.outcomes().getLast().grade());
        assertEquals(Exact.of(78), coursePlan.outcomes().getLast().percent());

        var custom = new GradeScale(new BigDecimal("5"), List.of(
                new Grade("Istimewa", new BigDecimal("90"), new BigDecimal("3")),
                new Grade("Kompeten", new BigDecimal("70"), new BigDecimal("5")), Grade.E));
        var customInputs = inputs(fixture.viewed(), List.of(project), Map.of(), custom, List.of(), Map.of(), List.of());
        var result = plan(customInputs, SEMESTER, "4,99");
        assertEquals("5,00", result.ceiling().display());
        assertEquals("KOMPETEN", result.recommendations().get(project.id()).grade().letter());
        assertEquals("70%", ResultText.percent(result.recommendations().get(project.id()).percent()));
        assertTrue(result.courses().getFirst().outcomes().stream().anyMatch(option -> option.percent().equals(Exact.HUNDRED)
                && option.grade().letter().equals("ISTIMEWA")));
        assertThrows(IllegalArgumentException.class, () -> TargetPlanner.readTarget("5,01", custom));
        assertThrows(IllegalArgumentException.class, () -> TargetPlanner.readTarget("3,7.1", custom));
        assertThrows(IllegalArgumentException.class, () -> TargetPlanner.readTarget("-1", custom));
    }

    @Test void showsAlreadyMetOnlyWithACompleteStartingResult() {
        Fixture fixture = fixture();
        Scenario a = scenario(fixture.project(), GradeScale.DEFAULT, "85");
        Scenario b = scenario(fixture.research(), GradeScale.DEFAULT, "70");
        var selected = Map.of(fixture.project().id(), a.id(), fixture.research().id(), b.id());
        var inputs = inputs(fixture.viewed(), fixture.inputs().courses(), fixture.inputs().finals(), GradeScale.DEFAULT,
                List.of(a, b), selected, List.of(fixture.outside()));
        var report = plan(inputs, SEMESTER, "3.5");
        assertEquals(MET, report.status());
        assertEquals("3,50", report.starting().display());
        assertEquals(report.starting(), report.suggested());
        var partial = inputs(fixture.viewed(), fixture.inputs().courses(), fixture.inputs().finals(), GradeScale.DEFAULT,
                List.of(a), Map.of(fixture.project().id(), a.id()), List.of(fixture.outside()));
        assertEquals(REACHABLE, plan(partial, SEMESTER, "2").status());
        assertNull(plan(partial, SEMESTER, "2").starting());
    }

    @Test void identifiesMissingSksIncompleteCategoriesAndStaleSourcesAndAllowsSessionFallback() {
        UUID term = UUID.randomUUID();
        Course missing = course("Tanpa SKS", term, null);
        Course incomplete = course("Tanpa penilaian", term, 3).withAssessments(List.of());
        Course changed = course("Skenario lama", term, 3);
        Scenario old = scenario(changed, GradeScale.DEFAULT, "85");
        changed = changed.withSettings(changed.name(), Aggregation.EQUAL_PERCENTAGES);
        var inputs = inputs(term, List.of(missing, incomplete, changed), Map.of(missing.id(), finalGrade(Grade.A)),
                GradeScale.DEFAULT, List.of(old), Map.of(changed.id(), old.id()), List.of());
        var report = plan(inputs, SEMESTER, "3.5");
        assertEquals(INCOMPLETE, report.status());
        assertNull(report.ceiling());
        assertTrue(report.courses().get(0).issue().contains("Isi SKS"));
        assertTrue(report.courses().get(1).issue().contains("kategori"));
        assertTrue(report.courses().get(2).issue().contains("Data dasar lama"));
        var actual = inputs(term, List.of(changed), Map.of(), GradeScale.DEFAULT,
                List.of(old), Map.of(changed.id(), old.id()), List.of());
        var fallback = TargetPlanner.plan(actual, SEMESTER, new BigDecimal("3.50"), Set.of(changed.id()), Map.of());
        assertEquals(REACHABLE, fallback.status());
        assertEquals("Data aktual", fallback.courses().getFirst().source());
        assertEquals(old.id(), actual.dashboardRows().get(changed.id()).choice());
        assertEquals(INCOMPLETE, plan(actual, SEMESTER, "3.5").status());
    }

    @Test void cumulativeRepeatsNeedExplicitSessionDecisionsWithoutWritingToTheRepository() throws Exception {
        var store = new CourseStore(directory.resolve("planner.sqlite3"));
        store.initialize();
        Semester old = new Semester(UUID.randomUUID(), "1", null);
        Semester current = new Semester(UUID.randomUUID(), "2", null);
        store.saveSemester(null, old);
        store.saveSemester(null, current);
        Course past = course("Aljabar", old.id(), 3);
        Course pending = course("Aljabar", current.id(), 3);
        store.save(null, past);
        store.save(null, pending);
        var fixed = finalGrade(Grade.B);
        store.saveFinal(past, GradeScale.DEFAULT, null, fixed);
        var scenario = scenario(pending, GradeScale.DEFAULT, "85");
        store.saveScenario(null, scenario);
        store.saveProjectionChoice(pending.id(), null, scenario.id());
        var choices = store.loadProjectionSettings();
        var courses = store.load();
        var grading = store.loadGrading();
        var inputs = inputs(current.id(), courses, grading.finals(), grading.scale(), List.of(scenario), choices.selections(), List.of());
        var blocked = plan(inputs, CUMULATIVE, "3.5");
        assertEquals(INCOMPLETE, blocked.status());
        var group = blocked.unresolved().getFirst();
        assertNull(blocked.ceiling());
        assertEquals(2, group.courses().size());
        var picks = new HashMap<AcademicAverage.ProjectedRepeatKey, Boolean>();
        picks.put(new AcademicAverage.ProjectedRepeatKey(group.key(), past.id()), true);
        picks.put(new AcademicAverage.ProjectedRepeatKey(group.key(), pending.id()), true);
        var planned = TargetPlanner.plan(inputs, CUMULATIVE, new BigDecimal("3.5"), Set.of(), picks);
        assertEquals(MET, planned.status());
        assertEquals("3,50", planned.starting().display());
        assertEquals(6, planned.starting().sks());
        planned.courses().getLast().draft(planned.recommendations().get(pending.id())).targetInput("90");
        assertEquals(choices, store.loadProjectionSettings());
        assertEquals(grading, store.loadGrading());
        assertEquals(courses, store.load());
        assertEquals(List.of(scenario), store.loadScenarios());
    }

    private static TargetPlanner.Report plan(TargetPlanner.Inputs inputs, TargetPlanner.Mode mode, String value) {
        return TargetPlanner.plan(inputs, mode, TargetPlanner.readTarget(value, inputs.scale()), Set.of(), Map.of());
    }

    private static Fixture fixture() {
        UUID old = UUID.randomUUID();
        UUID viewed = UUID.randomUUID();
        Course priorA = course("Algoritma", old, 3);
        Course priorB = course("Logika", old, 2);
        Course priorA2 = course("Grafika", old, 3);
        Course viewA = course("Basis Data", viewed, 3);
        Course viewB = course("Jaringan", viewed, 3);
        Course project = course("Proyek", viewed, 3);
        Course research = course("Riset", viewed, 3);
        Course outside = course("Arsip", old, 2);
        var finals = Map.of(priorA.id(), finalGrade(Grade.A), priorB.id(), finalGrade(Grade.B), priorA2.id(), finalGrade(Grade.A_MINUS),
                viewA.id(), finalGrade(Grade.A), viewB.id(), finalGrade(Grade.B));
        var courses = List.of(priorA, priorB, priorA2, viewA, viewB, project, research, outside);
        return new Fixture(inputs(viewed, courses, finals, GradeScale.DEFAULT, List.of(), Map.of(), List.of(outside)), viewed, project, research, outside);
    }

    private static TargetPlanner.Inputs inputs(UUID viewed, List<Course> courses, Map<UUID, FinalGrade> finals, GradeScale scale,
                                               List<Scenario> scenarios, Map<UUID, UUID> selections, List<Course> outside) {
        var dashboard = DashboardProjection.evaluate(viewed, courses, finals, scale, new Scenario.Catalog(scenarios, Map.of()),
                new DashboardProjection.Settings(selections, Map.of()), Map.of());
        return new TargetPlanner.Inputs(viewed, courses, finals, scale, dashboard.rows(), outside, Map.of(), Map.of());
    }

    private record Fixture(TargetPlanner.Inputs inputs, UUID viewed, Course project, Course research, Course outside) {}

    private static Course course(String name, UUID term, Integer sks) {
        var course = Course.create(name, Aggregation.TOTAL_POINTS);
        var row = new Course.Assessment(UUID.randomUUID(), "Ujian", course.categories().getFirst().id(), Course.FULL_WEIGHT, null);
        return course.withAssessments(List.of(row)).withAcademic(new AcademicDetails(term, sks, null, null));
    }

    private static Scenario scenario(Course course, GradeScale scale, String target) {
        var simulation = new Simulation(course, scale);
        simulation.targetInput(target);
        return new Scenario(UUID.randomUUID(), "Target " + target, simulation.state());
    }

    private static FinalGrade finalGrade(Grade grade) { return FinalGrade.of(grade, FinalGrade.Source.OFFICIAL); }
}
