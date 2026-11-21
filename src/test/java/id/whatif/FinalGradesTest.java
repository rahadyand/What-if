package id.whatif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static id.whatif.FinalGrade.Source.CALCULATED;
import static id.whatif.FinalGrade.Source.OFFICIAL;
import static org.junit.jupiter.api.Assertions.*;

class FinalGradesTest {
    @TempDir Path directory;

    @Test void semesterAndCumulativeUseExactContributionsAndIgnoreUnfinalizedCredits() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        Course a = course("Algoritma", first, 3, "86");
        Course b = course("Logika", first, 2, "72");
        Course aMinus = course("Grafika", first, 3, "83");
        var stored = Map.of(a.id(), FinalGrade.of(Grade.A, CALCULATED), b.id(), FinalGrade.of(Grade.B, CALCULATED),
                aMinus.id(), FinalGrade.of(Grade.A_MINUS, CALCULATED));
        var complete = AcademicAverage.semester(first, List.of(a, b, aMinus), stored);
        assertEquals(8, complete.result().sks());
        assertEquals(new BigDecimal("29.10"), complete.result().weighted());
        assertEquals("3,64", complete.result().display());
        assertTrue(complete.complete());

        Course unfinishedB = course("Jaringan", second, 3, "72");
        Course unfinishedA = course("Basis Data", second, 3, "86");
        Course pending = course("Proyek", second, 6, null);
        var all = List.of(a, b, aMinus, unfinishedA, unfinishedB, pending);
        var finals = new java.util.HashMap<>(stored);
        finals.put(unfinishedA.id(), FinalGrade.of(Grade.A, CALCULATED));
        finals.put(unfinishedB.id(), FinalGrade.of(Grade.B, CALCULATED));
        var partial = AcademicAverage.semester(second, all, finals);
        assertEquals("3,50", partial.result().display());
        assertEquals(6, partial.result().sks());
        assertEquals(12, partial.recordedSks());
        assertFalse(partial.complete());
        var cumulative = AcademicAverage.ipk(all, finals, Map.of()).result();
        assertEquals(14, cumulative.sks());
        assertEquals(new BigDecimal("50.10"), cumulative.weighted());
        assertEquals("3,58", cumulative.display());
        assertEquals("Belum tersedia", AcademicAverage.semester(UUID.randomUUID(), all, finals).result().display());
    }

    @Test void gradeReviewKeepsStoredPointsUntilAnUpdateIsConfirmed() throws Exception {
        var store = store("review");
        var semester = term(store, "1");
        Course original = course("Nilai akhir", semester.id(), 3, "86");
        store.save(null, original);
        var base = GradeScale.DEFAULT;
        var stored = FinalGrade.calculated(Calculation.actual(original), base);
        store.saveFinal(original, base, null, stored);
        var scale = new GradeScale(new BigDecimal("4"), List.of(grade("A", "90", "4"), grade("B", "70", "2.75"), Grade.E));
        store.saveScale(base, scale);
        var newResult = FinalGrade.calculated(Calculation.actual(original), scale);
        assertEquals("B", newResult.letter());
        assertTrue(stored.review(newResult, scale).needed());
        assertEquals("4,00", AcademicAverage.semester(semester.id(), List.of(original), store.loadGrading().finals()).result().display());

        FinalGrade kept = stored.keep(stored.review(newResult, scale));
        store.saveFinal(original, scale, stored, kept);
        assertFalse(store.loadGrading().finals().get(original.id()).review(newResult, scale).needed());
        assertThrows(SQLException.class, () -> store.saveFinal(original, scale, stored, newResult));
        assertEquals(kept, store.loadGrading().finals().get(original.id()));
        store.saveFinal(original, scale, kept, newResult);
        assertEquals("2,75", AcademicAverage.ipk(List.of(original), store.loadGrading().finals(), Map.of()).result().display());
        assertEquals(new BigDecimal("86"), store.load().getFirst().assessments().getFirst().earned());

        var changedPoints = new FinalGrade("B", new BigDecimal("2.50"), CALCULATED, null);
        assertTrue(newResult.review(changedPoints, scale).needed());
        assertFalse(newResult.review(newResult, scale).needed());
        assertFalse(newResult.review(null, scale).needed());
    }

    @Test void officialGradesAndExplicitRepeatReviewPersistAndZeroCounts() throws Exception {
        var store = store("official");
        var one = term(store, "1");
        var two = term(store, "2");
        Course first = course("Aljabar", one.id(), 2, null);
        Course second = course("ALJABAR", two.id(), 3, null);
        Course missing = course("Tanpa SKS", two.id(), null, null);
        Course ungrouped = course("Tanpa Semester", null, 4, null);
        for (Course course : List.of(first, second, missing, ungrouped)) store.save(null, course);
        var e = FinalGrade.of(Grade.E, OFFICIAL);
        var b = FinalGrade.of(Grade.B, OFFICIAL);
        var base = GradeScale.DEFAULT;
        store.saveFinal(first, base, null, e);
        store.saveFinal(second, base, null, b);
        store.saveFinal(missing, base, null, b);
        store.saveFinal(ungrouped, base, null, b);
        var term = AcademicAverage.semester(one.id(), store.load(), store.loadGrading().finals());
        assertEquals("0,00", term.result().display());
        assertEquals(2, term.result().sks());

        var pending = AcademicAverage.ipk(store.load(), store.loadGrading().finals(), Map.of());
        assertEquals(0, pending.result().sks());
        assertEquals(1, pending.missing().semester());
        assertEquals(1, pending.missing().sks());
        var review = pending.repeats().getFirst();
        assertFalse(review.reviewed());
        store.saveRepeatReview(review, Map.of(), Set.of(first.id(), second.id()));

        var scale = new GradeScale(new BigDecimal("4"), List.of(grade("A", "90", "4"), grade("B", "76", "2.75"), Grade.E));
        store.saveScale(base, scale);
        var reopened = new CourseStore(directory.resolve("official.sqlite3"));
        reopened.initialize();
        var data = reopened.loadGrading();
        assertEquals(OFFICIAL, data.finals().get(second.id()).source());
        assertEquals(b, data.finals().get(second.id()));
        assertTrue(b.review(null, data.scale()).needed());
        assertFalse(e.review(null, data.scale()).needed());
        assertEquals("1,80", AcademicAverage.ipk(reopened.load(), data.finals(), data.repeats()).result().display());
        Course moved = first.withAcademic(new AcademicDetails(two.id(), 2, null, null));
        reopened.save(first, moved);
        assertEquals("Belum tersedia", AcademicAverage.semester(one.id(), reopened.load(), data.finals()).result().display());
        assertEquals(5, AcademicAverage.semester(two.id(), reopened.load(), data.finals()).result().sks());
    }

    @Test void migrationFromStageOnePreservesLegacySnapshotAndDefaultsWithoutFinalizing() throws Exception {
        var store = store("previous");
        var term = term(store, "3");
        var course = course("Arsip", term.id(), 8, "86").withAcademic(new AcademicDetails(term.id(), 8, 2, 3));
        store.save(null, course);
        store.setActiveSemester(term.id());
        UUID scenario = UUID.randomUUID();
        byte[] bytes = oldSnapshot(course);
        Path db = directory.resolve("previous.sqlite3");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = c.createStatement()) {
            for (String table : List.of("projected_repeat_reviews", "dashboard_scenarios", "ipk_repeat_reviews", "confirmed_grades", "grade_scale_rows", "grade_scale_settings")) {
                sql.execute("DROP TABLE " + table);
            }
            sql.execute("PRAGMA user_version=2");
            try (var insert = c.prepareStatement("INSERT INTO scenarios VALUES (?, ?, ?, ?)")) {
                insert.setString(1, scenario.toString());
                insert.setString(2, course.id().toString());
                insert.setString(3, "Sebelum skala");
                insert.setBytes(4, bytes);
                insert.executeUpdate();
            }
        }
        store.initialize();
        assertEquals(List.of(course), store.load());
        assertEquals(term.id(), store.loadSemesters().activeId());
        assertEquals(GradeScale.DEFAULT, store.loadGrading().scale());
        assertTrue(store.loadGrading().finals().isEmpty());
        Scenario saved = store.loadScenarios().getFirst();
        assertEquals(scenario, saved.id());
        assertEquals(GradeScale.DEFAULT, saved.state().scale());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = c.createStatement();
             var rows = sql.executeQuery("SELECT snapshot FROM scenarios")) {
            assertTrue(rows.next());
            assertArrayEquals(bytes, rows.getBytes(1));
        }
        var custom = new GradeScale(new BigDecimal("4"), List.of(grade("A", "90", "4"), Grade.E));
        store.saveScale(GradeScale.DEFAULT, custom);
        assertTrue(saved.stale(course, custom));
        assertEquals(Grade.A, saved.state().scale().classify(Exact.of(86)));
        assertEquals(Grade.E, custom.classify(Exact.of(86)));
        assertEquals("90", new Simulation(course, custom).targetInput());
        var refreshed = Simulation.from(saved.state()).reviewBaseline(course, custom).draft();
        assertEquals(custom, refreshed.scale());
        assertEquals("85", refreshed.targetInput());
        var encoded = ScenarioSnapshot.write(refreshed.state());
        assertEquals(refreshed.state(), ScenarioSnapshot.read(encoded));
    }

    @Test void scaleValidatesUniqueBoundariesAndUsesUnroundedPercentages() {
        Grade a = grade("A", "89.5", "4");
        Grade e = Grade.E;
        BigDecimal max = new BigDecimal("4");
        var scale = new GradeScale(max, List.of(a, e));
        assertEquals(a, scale.classify(Exact.of(new BigDecimal("89.5"))));
        assertEquals(e, scale.classify(Exact.of(new BigDecimal("89.499999999"))));
        assertThrows(IllegalArgumentException.class, () -> new GradeScale(max, List.of(a)));
        assertThrows(IllegalArgumentException.class, () -> new GradeScale(max, List.of(a, e, grade("a", "90", "3"))));
        assertThrows(IllegalArgumentException.class, () -> new GradeScale(max, List.of(a, e, grade("B", "89.50", "3"))));
        assertThrows(IllegalArgumentException.class, () -> new GradeScale(max, List.of(e, grade("B", "101", "3"))));
        assertThrows(IllegalArgumentException.class, () -> new GradeScale(max, List.of(e, grade("B", "70", "4.01"))));
    }

    private CourseStore store(String name) throws Exception {
        var store = new CourseStore(directory.resolve(name + ".sqlite3"));
        store.initialize();
        return store;
    }

    private static Semester term(CourseStore store, String label) throws SQLException {
        var term = new Semester(UUID.randomUUID(), label, "2025/2026");
        store.saveSemester(null, term);
        return term;
    }

    private static Course course(String name, UUID semester, Integer sks, String earned) {
        var course = Course.create(name, Aggregation.TOTAL_POINTS);
        var assessment = new Course.Assessment(UUID.randomUUID(), "Tes", course.categories().getFirst().id(),
                new BigDecimal("100"), earned == null ? null : new BigDecimal(earned));
        return course.withAssessments(List.of(assessment)).withAcademic(new AcademicDetails(semester, sks, null, null));
    }

    private static Grade grade(String letter, String threshold, String point) {
        return new Grade(letter, new BigDecimal(threshold), new BigDecimal(point));
    }

    private static byte[] oldSnapshot(Course course) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var write = new DataOutputStream(bytes)) {
            write.writeInt(1);
            text(write, "85", course.id().toString(), course.name(), course.method().name());
            write.writeBoolean(false);
            var category = course.categories().getFirst();
            write.writeInt(1);
            text(write, category.id().toString(), category.name(), "100");
            var row = course.assessments().getFirst();
            for (int i = 0; i < 2; i++) {
                write.writeInt(1);
                text(write, row.id().toString(), row.name(), category.id().toString(), "100", "86");
            }
            write.writeBoolean(false);
            write.writeBoolean(false);
        }
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream write, String... words) throws Exception {
        for (String word : words) {
            byte[] encoded = word.getBytes(StandardCharsets.UTF_8);
            write.writeInt(encoded.length);
            write.write(encoded);
        }
    }
}
