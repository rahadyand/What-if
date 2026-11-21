package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioStoreTest {
    @TempDir Path directory;

    @Test void scenariosRoundTripWithIndependentCopiesAndNoActualWrites() throws Exception {
        Path db = directory.resolve("snapshots.sqlite3");
        var store = new CourseStore(db);
        store.initialize();
        Course course = sample("Algoritma");
        Course other = sample("Basis Data");
        store.save(null, course);
        store.save(null, other);

        Simulation draft = new Simulation(course);
        assertFalse(draft.modified());
        draft.targetInput("79,5");
        Assessment first = course.assessments().getFirst();
        Assessment second = course.assessments().getLast();
        draft.put(new Assessment(first.id(), first.name(), first.categoryId(), first.maximum(), decimal("70")), true);
        draft.setExcluded(first.id(), true);
        Assessment extra = new Assessment(UUID.randomUUID(), "Proyek tambahan", first.categoryId(), decimal("30"), decimal("15"));
        draft.put(extra, true);

        Scenario saved = scenario("Rencana A", draft);
        Scenario copy = scenario("Rencana B", draft);
        store.saveScenario(null, saved);
        store.saveScenario(null, copy);
        Scenario independent = scenario("Mata kuliah lain", new Simulation(other));
        store.saveScenario(null, independent);

        draft.targetInput("95");
        var updated = new Scenario(copy.id(), copy.name(), draft.state());
        store.saveScenario(copy, updated);

        var restarted = new CourseStore(db);
        restarted.initialize();
        assertEquals(List.of(saved, updated, independent), restarted.loadScenarios());
        assertEquals(List.of(course, other), restarted.load());
        assertEquals(course.id(), saved.courseId());
        assertNotEquals(independent.courseId(), copy.courseId());

        Simulation reopened = Simulation.from(restarted.loadScenarios().getFirst().state());
        assertEquals("79,5", reopened.targetInput());
        assertTrue(reopened.rows().getFirst().excluded());
        assertTrue(reopened.rows().getFirst().assumption());
        assertEquals(extra, reopened.rows().getLast().value());
        assertNull(reopened.rows().get(1).value().earned());
        assertTrue(Calculation.project(reopened).suggestions().containsKey(second.id()));
        reopened.targetInput("0");
        assertNotEquals(Calculation.project(Simulation.from(saved.state())), Calculation.project(reopened));
        assertEquals("79,5", restarted.loadScenarios().getFirst().state().target());
        restarted.delete(other);
        assertEquals(List.of(saved, updated), restarted.loadScenarios());
    }

    @Test void renameKeepsAssessmentIdsAndSavedScenarioFreshAfterRestart() throws Exception {
        Path db = directory.resolve("rename.sqlite3");
        var store = new CourseStore(db);
        store.initialize();
        Course original = sample("Algoritma");
        store.save(null, original);
        Scenario saved = scenario("Target saya", new Simulation(original));
        store.saveScenario(null, saved);
        assertThrows(IllegalArgumentException.class, () -> original.withSettings("  ", original.method()));
        Course renamed = original.withSettings("  Algoritma Lanjut  ", original.method());
        store.save(original, renamed);

        var restarted = new CourseStore(db);
        restarted.initialize();
        Course loaded = restarted.load().getFirst();
        Scenario reopened = restarted.loadScenarios().getFirst();
        assertEquals("Algoritma Lanjut", loaded.name());
        assertEquals(original.id(), loaded.id());
        assertEquals(original.categories(), loaded.categories());
        assertEquals(original.assessments(), loaded.assessments());
        assertEquals(saved, reopened);
        assertFalse(reopened.stale(loaded));
        assertEquals(Calculation.project(new Simulation(original)), Calculation.project(new Simulation(loaded)));
        assertTrue(reopened.stale(loaded.withSettings(loaded.name(), Aggregation.EQUAL_PERCENTAGES)));
    }

    @Test void rebaseMergesByIdReportsInvalidationsAndOnlyPersistsOnSave() throws Exception {
        var store = new CourseStore(directory.resolve("rebase.sqlite3"));
        store.initialize();
        Course old = sample("Algoritma");
        store.save(null, old);
        Assessment midterm = old.assessments().getFirst();
        Assessment exam = old.assessments().getLast();
        Simulation draft = new Simulation(old);
        draft.put(new Assessment(midterm.id(), midterm.name(), midterm.categoryId(), midterm.maximum(), decimal("90")), true);
        draft.setExcluded(exam.id(), true);
        Assessment extra = new Assessment(UUID.randomUUID(), "Bonus demo", exam.categoryId(), decimal("10"), null);
        draft.put(extra, false);
        draft.targetInput("75");
        Scenario saved = scenario("Rencana", draft);
        store.saveScenario(null, saved);

        var category = new Category(old.categories().getFirst().id(), "Ujian tengah", decimal("100"));
        var refreshed = new Assessment(midterm.id(), "UTS diubah", category.id(), decimal("120"), decimal("83"));
        var newExam = new Assessment(UUID.randomUUID(), "UAS baru", category.id(), decimal("100"), null);
        Course current = old.withAssessments(List.of(refreshed)).withCategories(List.of(category))
                .withAssessments(List.of(newExam, refreshed)).withSettings(old.name(), Aggregation.EQUAL_PERCENTAGES);
        store.save(old, current);
        assertTrue(saved.stale(current));

        var review = Simulation.from(saved.state()).reviewBaseline(current);
        assertEquals(3, review.notes().size());
        assertTrue(review.notes().stream().anyMatch(text -> text.contains("maksimum aktual berubah")));
        assertTrue(review.notes().stream().anyMatch(text -> text.contains("penilaian aktual dihapus")));
        assertTrue(review.notes().stream().anyMatch(text -> text.contains("kategori simulasi dihapus")));
        assertEquals(List.of(newExam, refreshed), review.draft().rows().stream().map(Simulation.Row::value).toList());
        assertEquals("75", review.draft().targetInput());
        assertEquals(List.of(saved), store.loadScenarios());
        assertEquals(old, saved.state().baseline());
        var updated = new Scenario(saved.id(), saved.name(), review.draft().state());
        store.saveScenario(saved, updated);
        assertFalse(store.loadScenarios().getFirst().stale(current));
        assertEquals(List.of(current), store.load());
    }

    @Test void scenarioWriteCanRetryWithoutDuplicatesOrLostSavedState() throws Exception {
        Path db = directory.resolve("retry.sqlite3");
        var store = new CourseStore(db);
        store.initialize();
        Course actual = sample("Algoritma");
        store.save(null, actual);
        var draft = new Simulation(actual);
        Scenario scenario = scenario("Percobaan", draft);
        execute(db, """
                CREATE TRIGGER block_save BEFORE INSERT ON scenarios
                BEGIN SELECT RAISE(ABORT, 'Uji simpan'); END
                """);
        assertThrows(SQLException.class, () -> store.saveScenario(null, scenario));
        assertEquals(List.of(), store.loadScenarios());
        execute(db, "DROP TRIGGER block_save");
        store.saveScenario(null, scenario);
        assertThrows(SQLException.class, () -> store.saveScenario(null, scenario));
        assertEquals(List.of(scenario), store.loadScenarios());
        assertEquals(List.of(actual), store.load());
    }

    private static Scenario scenario(String name, Simulation simulation) {
        return new Scenario(UUID.randomUUID(), name, simulation.state());
    }

    private static Course sample(String name) {
        Course course = Course.create(name, Aggregation.TOTAL_POINTS);
        var first = new Category(course.categories().getFirst().id(), "UTS", decimal("30"));
        var second = new Category(UUID.randomUUID(), "UAS", decimal("70"));
        return course.withCategories(List.of(first, second)).withAssessments(List.of(
                new Assessment(UUID.randomUUID(), "UTS", first.id(), decimal("100"), decimal("80")),
                new Assessment(UUID.randomUUID(), "UAS", second.id(), decimal("100"), null)));
    }

    private static void execute(Path db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
