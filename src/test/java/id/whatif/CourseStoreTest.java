package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CourseStoreTest {
    @TempDir Path directory;

    @Test void actualDataSurvivesRestartAndDeletionCascades() throws Exception {
        Path database = Files.createDirectory(directory.resolve("Data demo")).resolve("restart.sqlite3");
        CourseStore first = new CourseStore(database);
        first.initialize();
        Course course = example();
        first.save(null, course);
        Course changed = course.withSettings("Struktur Data 2", Aggregation.EQUAL_PERCENTAGES);
        first.save(course, changed);

        CourseStore restarted = new CourseStore(database);
        restarted.initialize();
        assertEquals(List.of(changed), restarted.load());
        assertEquals(BigDecimal.ZERO, restarted.load().getFirst().assessments().get(1).earned());
        assertNull(restarted.load().getFirst().assessments().get(2).earned());
        restarted.delete(changed);
        assertTrue(first.load().isEmpty());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT (SELECT count(*) FROM categories) + (SELECT count(*) FROM assessments)")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test void failedSaveRollsBackAndSameDraftCanBeRetried() throws Exception {
        Path database = directory.resolve("failure.sqlite3");
        CourseStore store = new CourseStore(database);
        store.initialize();
        Course original = example();
        store.save(null, original);
        Course draft = original.withSettings("Perubahan tetap di formulir", Aggregation.EQUAL_PERCENTAGES);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_insert BEFORE INSERT ON assessments
                    BEGIN SELECT RAISE(ABORT, 'Uji kegagalan simpan'); END
                    """);
            assertThrows(SQLException.class, () -> store.save(original, draft));
            assertEquals(List.of(original), store.load());
            assertEquals("Perubahan tetap di formulir", draft.name());
            statement.executeUpdate("DROP TRIGGER reject_insert");
        }
        store.save(original, draft);
        assertEquals(List.of(draft), store.load());
        assertThrows(SQLException.class, () -> store.save(original, original));
    }

    @Test void simulationEditsRestoreResetAndExitLeaveStoredActualUnchanged() throws Exception {
        CourseStore store = new CourseStore(directory.resolve("simulation.sqlite3"));
        store.initialize();
        Course baseline = example();
        store.save(null, baseline);
        Assessment actual = baseline.assessments().getFirst();
        Assessment free = baseline.assessments().getLast();
        Simulation simulation = new Simulation(store.load().getFirst());
        assertFalse(simulation.modified());

        simulation.put(new Assessment(actual.id(), "Ujian diubah", actual.categoryId(),
                actual.maximum(), BigDecimal.TEN), true);
        simulation.put(new Assessment(free.id(), "Proyek diubah", free.categoryId(), free.maximum(), null), false);
        assertNull(simulation.rows().getLast().value().earned());
        assertTrue(Calculation.project(simulation).suggestions().containsKey(free.id()));

        Assessment extra = new Assessment(UUID.randomUUID(), "Tambahan", free.categoryId(), BigDecimal.TEN, null);
        simulation.put(extra, false);
        assertTrue(Calculation.project(simulation).suggestions().containsKey(extra.id()));
        simulation.put(new Assessment(extra.id(), "Tambahan tetap", extra.categoryId(), extra.maximum(), BigDecimal.ONE), true);
        assertFalse(Calculation.project(simulation).suggestions().containsKey(extra.id()));
        simulation.setExcluded(extra.id(), true);
        simulation.setExcluded(extra.id(), false);
        simulation.setExcluded(actual.id(), true);
        assertTrue(simulation.rows().getFirst().excluded());
        simulation.restore(actual.id());
        assertEquals(actual, simulation.rows().getFirst().value());
        assertFalse(simulation.rows().getFirst().excluded());
        assertTrue(simulation.modified());
        assertEquals(List.of(baseline), store.load());

        simulation.reset();
        assertFalse(simulation.modified());
        assertEquals(baseline.assessments(), simulation.rows().stream().map(Simulation.Row::value).toList());
        simulation.targetInput("93");
        simulation.setExcluded(actual.id(), true);
        assertTrue(simulation.modified());
        Simulation reopened = new Simulation(store.load().getFirst());
        assertFalse(reopened.modified());
        assertEquals(List.of(baseline), store.load());
    }

    @Test void validatesNamesCategoryMembershipWeightsAndScoreLimits() {
        assertThrows(IllegalArgumentException.class, () -> Course.create(" ", Aggregation.TOTAL_POINTS));
        assertThrows(IllegalArgumentException.class, () -> new Category(UUID.randomUUID(), "Kosong", BigDecimal.ZERO));
        Course original = example();
        assertThrows(IllegalArgumentException.class, () -> original.withCategories(List.of()));
        Category unused = new Category(UUID.randomUUID(), "Lain", BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, () -> original.withCategories(List.of(unused)));
        assertThrows(IllegalArgumentException.class, () -> new Assessment(UUID.randomUUID(), "Kuis", unused.id(),
                BigDecimal.ZERO, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> new Assessment(UUID.randomUUID(), "Kuis", unused.id(),
                BigDecimal.TEN, BigDecimal.valueOf(-1)));
    }

    private static Course example() {
        Course course = Course.create("Struktur Data", Aggregation.TOTAL_POINTS);
        Category first = course.categories().getFirst();
        Category second = new Category(UUID.randomUUID(), "Proyek", new BigDecimal("2.5"));
        return course.withCategories(List.of(first, second)).withAssessments(List.of(
                new Assessment(UUID.randomUUID(), "Kuis", first.id(), new BigDecimal("50"), new BigDecimal("49.5")),
                new Assessment(UUID.randomUUID(), "Latihan", first.id(), BigDecimal.TEN, BigDecimal.ZERO),
                new Assessment(UUID.randomUUID(), "Proyek", second.id(), new BigDecimal("100"), null)));
    }
}
