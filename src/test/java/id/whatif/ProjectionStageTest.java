package id.whatif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionStageTest {
    @TempDir Path directory;

    @Test void projectionsUseEffectiveGradesAndNeverChangeActualCoverage() {
        UUID old = UUID.randomUUID(), viewed = UUID.randomUUID();
        var a = course("Algoritma", old, 3);
        var b = course("Logika", old, 2);
        var aMinus = course("Grafika", old, 3);
        var viewA = course("Basis Data", viewed, 3);
        var viewB = course("Jaringan", viewed, 3);
        var project = course("Proyek", viewed, 3);
        var research = course("Riset", viewed, 3);
        var outOfScope = course("Arsip", old, 4);
        var courses = List.of(a, b, aMinus, viewA, viewB, project, research, outOfScope);
        var finals = Map.of(a.id(), grade(Grade.A), b.id(), grade(Grade.B), aMinus.id(), grade(Grade.A_MINUS),
                viewA.id(), grade(Grade.A), viewB.id(), grade(Grade.B));
        var scenario = scenario(project, "75");
        var researchA = new Simulation(research);
        researchA.targetInput("75");
        var row = research.assessments().getFirst();
        researchA.put(new Course.Assessment(row.id(), row.name(), row.categoryId(), row.maximum(), new BigDecimal("86")), true);
        var resultA = new Scenario(UUID.randomUUID(), "Nilai lebih tinggi dari target", researchA.state());
        var actual = AcademicAverage.semester(viewed, courses, finals);
        var actualIpk = AcademicAverage.ipk(courses, finals, Map.of()).result();
        assertEquals("3,50", actual.result().display());
        assertEquals(6, actual.result().sks());
        assertEquals(12, actual.recordedSks());
        assertEquals(new BigDecimal("50.10"), actualIpk.weighted());
        assertEquals("3,58", actualIpk.display());
        var catalog = new Scenario.Catalog(List.of(scenario, resultA), Map.of());
        var empty = DashboardProjection.evaluate(viewed, courses, finals, GradeScale.DEFAULT, catalog,
                new DashboardProjection.Settings(Map.of(), Map.of()), Map.of());
        assertFalse(empty.complete());
        assertEquals(List.of(project, research), empty.missing());
        var selected = new DashboardProjection.Settings(Map.of(project.id(), scenario.id(), research.id(), resultA.id()), Map.of());
        var projected = DashboardProjection.evaluate(viewed, courses, finals, GradeScale.DEFAULT, catalog, selected, Map.of());
        assertTrue(projected.complete());
        assertEquals("A", projected.rows().get(research.id()).grade().letter());
        assertEquals(new BigDecimal("42.90"), projected.semester().weighted());
        assertEquals(12, projected.semester().sks());
        assertEquals("3,58", projected.semester().display());
        assertEquals(new BigDecimal("72.00"), projected.cumulative().result().weighted());
        assertEquals("3,60", projected.cumulative().result().display());
        assertEquals(20, projected.cumulative().result().sks());
        assertEquals(7, projected.cumulative().result().courses().stream().map(entry -> entry.course().id()).distinct().count());
        assertEquals(List.of(outOfScope), projected.outside());

        var alternate = scenario(project, "80");
        var changed = DashboardProjection.evaluate(viewed, courses, finals, GradeScale.DEFAULT,
                new Scenario.Catalog(List.of(alternate, resultA), Map.of()),
                new DashboardProjection.Settings(Map.of(project.id(), alternate.id(), research.id(), resultA.id()), Map.of()), Map.of());
        assertEquals("3,68", changed.semester().display());
        assertEquals("3,66", changed.cumulative().result().display());
        assertEquals(actual, AcademicAverage.semester(viewed, courses, finals));
        assertEquals(actualIpk, AcademicAverage.ipk(courses, finals, Map.of()).result());
        var completed = new java.util.HashMap<>(finals);
        completed.put(project.id(), grade(Grade.E));
        completed.put(research.id(), grade(Grade.B));
        var finalOnly = DashboardProjection.evaluate(viewed, courses, completed, GradeScale.DEFAULT, catalog, selected, Map.of());
        assertFalse(finalOnly.hasUnfinished());
        assertEquals(grade(Grade.E), finalOnly.rows().get(project.id()).confirmed());
        assertEquals(new BigDecimal("30.00"), finalOnly.semester().weighted());
    }

    @Test void selectionsPersistWithOwnershipChecksAndKeepMissingInvalidAndStaleDiagnostics() throws Exception {
        Path db = directory.resolve("scenarios.sqlite3");
        var store = new CourseStore(db);
        store.initialize();
        Semester term = term(store, "1");
        var course = course("Praktikum", term.id(), 3);
        var other = course("Studio", term.id(), 3);
        store.save(null, course);
        store.save(null, other);
        var good = scenario(course, "75");
        var unreachable = scenario(course, "110");
        var excluded = new Simulation(course);
        excluded.setExcluded(course.assessments().getFirst().id(), true);
        var empty = new Scenario(UUID.randomUUID(), "Tanpa penilaian", excluded.state());
        for (var item : List.of(good, unreachable, empty)) store.saveScenario(null, item);
        assertThrows(SQLException.class, () -> store.saveProjectionChoice(other.id(), null, good.id()));
        store.saveProjectionChoice(course.id(), null, unreachable.id());
        assertTrue(report(store, term).rows().get(course.id()).issue().contains("batas normal"));
        store.saveProjectionChoice(course.id(), unreachable.id(), empty.id());
        assertTrue(report(store, term).rows().get(course.id()).issue().contains("belum lengkap"));
        store.saveProjectionChoice(course.id(), empty.id(), good.id());

        var reopened = new CourseStore(db);
        reopened.initialize();
        assertEquals(good.id(), reopened.loadProjectionSettings().selections().get(course.id()));
        var changed = course.withSettings(course.name(), Aggregation.EQUAL_PERCENTAGES);
        reopened.save(course, changed);
        assertTrue(report(reopened, term).rows().get(course.id()).issue().contains("Data dasar lama"));
        sql(db, "UPDATE scenarios SET snapshot=x'00' WHERE id='" + good.id() + "'");
        assertTrue(report(reopened, term).rows().get(course.id()).issue().contains("Snapshot skenario tidak valid"));
        assertEquals(2, reopened.loadScenarioCatalog().scenarios().size());
        reopened.deleteScenario(unreachable);
        reopened.saveProjectionChoice(course.id(), good.id(), empty.id());
        reopened.deleteScenario(empty);
        assertTrue(report(reopened, term).rows().get(course.id()).issue().contains("dihapus"));
        reopened.saveProjectionChoice(course.id(), empty.id(), null);
        assertTrue(report(reopened, term).rows().get(course.id()).issue().startsWith("Pilih"));
        reopened.saveFinal(changed, GradeScale.DEFAULT, null, grade(Grade.B));
        assertThrows(SQLException.class, () -> reopened.saveProjectionChoice(course.id(), null, good.id()));
    }

    @Test void projectionRepeatReviewDoesNotAlterActualDecisionsOrDisappearWhenSelectionChanges() throws Exception {
        Path db = directory.resolve("repeats.sqlite3");
        var store = new CourseStore(db);
        store.initialize();
        Semester older = term(store, "1");
        Semester viewed = term(store, "2");
        var first = course("Aljabar", older.id(), 3);
        var second = course("ALJABAR", older.id(), 2);
        var pending = course("Aljabar", viewed.id(), 3);
        for (var entry : List.of(first, second, pending)) store.save(null, entry);
        store.saveFinal(first, GradeScale.DEFAULT, null, grade(Grade.B));
        store.saveFinal(second, GradeScale.DEFAULT, null, grade(Grade.A));
        var actualGroup = AcademicAverage.ipk(store.load(), store.loadGrading().finals(), Map.of()).repeats().getFirst();
        var actualDecisions = store.saveRepeatReview(actualGroup, Map.of(), Set.of(first.id()));
        var scenario = scenario(pending, "85");
        store.saveScenario(null, scenario);
        store.saveProjectionChoice(pending.id(), null, scenario.id());
        var waiting = report(store, viewed);
        assertTrue(waiting.complete());
        assertTrue(waiting.needsRepeatReview());
        assertEquals(0, waiting.cumulative().result().sks());
        var group = waiting.cumulative().repeats().getFirst();
        assertTrue(group.projected());
        var decisions = store.saveProjectedRepeatReview(viewed.id(), group, Map.of(), Set.of(first.id(), pending.id()));
        assertEquals("3,50", report(store, viewed).cumulative().result().display());
        store.saveProjectionChoice(pending.id(), scenario.id(), null);
        assertEquals(decisions, store.loadProjectionSettings().reviews());
        store.saveProjectionChoice(pending.id(), null, scenario.id());
        store.initialize();
        assertFalse(report(store, viewed).needsRepeatReview());
        assertEquals(actualDecisions, store.loadGrading().repeats());
        assertEquals("3,00", AcademicAverage.ipk(store.load(), store.loadGrading().finals(), actualDecisions).result().display());
    }

    @Test void validatesTwentyFourSksForCreationEditingAndMoves() throws Exception {
        var store = new CourseStore(directory.resolve("limits.sqlite3"));
        store.initialize();
        Semester destination = term(store, "3");
        Semester origin = term(store, "2");
        var base = course("Dasar", destination.id(), 22);
        var traveler = course("Pindah", origin.id(), 3);
        store.save(null, base);
        store.save(null, traveler);
        var invalid = course("Tambahan", destination.id(), 3);
        var falseTotal = assertThrows(SQLException.class, () -> store.save(null, invalid));
        assertTrue(falseTotal.getMessage().contains("Semester 3 sudah memiliki 22 SKS. Menambahkan 3 SKS akan menjadi 25"));
        assertThrows(SQLException.class, () -> store.save(traveler, place(traveler, destination, 3)));
        var add = course("Tepat", destination.id(), 2);
        store.save(null, add);
        assertEquals(24, SemesterSks.recorded(destination.id(), store.load()));
        assertThrows(SQLException.class, () -> store.save(add, place(add, destination, 3)));
        var lower = place(add, destination, 1);
        store.save(add, lower);
        store.save(lower, add);
        assertThrows(IllegalArgumentException.class, () -> SemesterSks.validate(destination, store.load(), add,
                place(add, destination, 3).academic()));
        store.delete(add);
        store.save(traveler, place(traveler, destination, 2));
        assertEquals(24, SemesterSks.recorded(destination.id(), store.load()));
        assertEquals(0, SemesterSks.recorded(origin.id(), store.load()));
    }

    @Test void migrationFromVersionThreeRetainsOverLimitTermsAndHistoricalRecords() throws Exception {
        Path db = directory.resolve("previous.sqlite3");
        var store = new CourseStore(db);
        store.initialize();
        var term = term(store, "1");
        var course = course("Lama", term.id(), 24).withAcademic(new AcademicDetails(term.id(), 24, 5, 6));
        store.save(null, course);
        var scenario = scenario(course, "85");
        store.saveScenario(null, scenario);
        store.saveFinal(course, GradeScale.DEFAULT, null, grade(Grade.A));
        byte[] original = snapshot(db);
        sql(db, "DROP TABLE dashboard_scenarios", "DROP TABLE projected_repeat_reviews",
                "UPDATE courses SET sks=27", "PRAGMA user_version=3");
        store.initialize();
        var legacy = store.load().getFirst();
        assertEquals(27, legacy.academic().sks());
        assertEquals("5/6 (83,33%)", legacy.academic().attendanceText());
        assertEquals(course.assessments(), legacy.assessments());
        assertEquals(grade(Grade.A), store.loadGrading().finals().get(course.id()));
        assertEquals(scenario, store.loadScenarios().getFirst());
        assertArrayEquals(original, snapshot(db));
        assertTrue(store.loadProjectionSettings().selections().isEmpty());
        var renamed = legacy.withSettings("Diperbarui", legacy.method());
        store.save(legacy, renamed);
        var smaller = renamed.withAcademic(new AcademicDetails(term.id(), 26, 5, 6));
        store.save(renamed, smaller);
        assertThrows(SQLException.class, () -> store.save(smaller, renamed));
        assertThrows(SQLException.class, () -> store.save(null, course("Tambah", term.id(), 1)));
        store.save(smaller, smaller.withAcademic(new AcademicDetails(term.id(), 24, 5, 6)));
        assertEquals(24, SemesterSks.recorded(term.id(), store.load()));
    }

    private static DashboardProjection.Report report(CourseStore store, Semester term) throws SQLException {
        var grading = store.loadGrading();
        return DashboardProjection.evaluate(term.id(), store.load(), grading.finals(), grading.scale(),
                store.loadScenarioCatalog(), store.loadProjectionSettings(), grading.repeats());
    }

    private static Course course(String name, UUID term, Integer sks) {
        var course = Course.create(name, Aggregation.TOTAL_POINTS);
        var row = new Course.Assessment(UUID.randomUUID(), "Ujian", course.categories().getFirst().id(), Course.FULL_WEIGHT, null);
        return course.withAssessments(List.of(row)).withAcademic(new AcademicDetails(term, sks, null, null));
    }

    private static Course place(Course course, Semester term, int sks) {
        return course.withAcademic(new AcademicDetails(term.id(), sks, null, null));
    }

    private static Semester term(CourseStore store, String name) throws SQLException {
        var term = new Semester(UUID.randomUUID(), name, null);
        store.saveSemester(null, term);
        return term;
    }

    private static Scenario scenario(Course course, String target) {
        var simulation = new Simulation(course);
        simulation.targetInput(target);
        return new Scenario(UUID.randomUUID(), "Target " + target, simulation.state());
    }

    private static FinalGrade grade(Grade grade) { return FinalGrade.of(grade, FinalGrade.Source.OFFICIAL); }

    private static void sql(Path db, String... statements) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            for (String sql : statements) statement.executeUpdate(sql);
        }
    }

    private static byte[] snapshot(Path db) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT snapshot FROM scenarios")) {
            assertTrue(row.next());
            return row.getBytes(1);
        }
    }
}
