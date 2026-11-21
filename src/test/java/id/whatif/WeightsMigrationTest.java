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

class WeightsMigrationTest {
    @TempDir Path directory;

    @Test void percentageEditsAreExactAtomicAndPreserveAssessmentsAndOtherCourses() throws Exception {
        Path db = directory.resolve("weights.sqlite3");
        CourseStore store = new CourseStore(db);
        store.initialize();
        Course a = Course.create("A", Aggregation.TOTAL_POINTS);
        Course b = Course.create("B", Aggregation.EQUAL_PERCENTAGES);
        a = a.withAssessments(List.of(new Assessment(UUID.randomUUID(), "Kuis", a.categories().getFirst().id(),
                decimal("50"), decimal("49.5"))));
        store.save(null, a);
        store.save(null, b);
        Course original = a;
        assertThrows(IllegalArgumentException.class, () -> draft(original, "70", "29.99"));
        assertThrows(IllegalArgumentException.class, () -> draft(original, "70", "30.01"));
        Course changed = draft(original, "70", "30");
        execute(db, """
                CREATE TRIGGER fail_category BEFORE INSERT ON categories WHEN NEW.name='Praktikum'
                BEGIN SELECT RAISE(ABORT, 'Uji transaksi'); END
                """);
        assertThrows(SQLException.class, () -> store.save(original, changed));
        assertEquals(List.of(original, b), store.load());
        execute(db, "DROP TRIGGER fail_category");
        execute(db, """
                CREATE TRIGGER keep_scores BEFORE DELETE ON assessments
                BEGIN SELECT RAISE(ABORT, 'Nilai harus tetap'); END
                """);
        store.save(original, changed);
        assertEquals(List.of(changed, b), store.load());
        assertEquals(original.assessments(), changed.assessments());
        assertEquals(Exact.of(decimal("69.3")), Calculation.actual(changed).contribution());
        assertEquals(Exact.of(99), Calculation.actual(changed).percent());
    }

    @Test void legacyMigrationPreservesExactOldValuesAndArchivesApprovedConversion() throws Exception {
        Path db = directory.resolve("legacy.sqlite3");
        legacySchema(db);
        UUID id = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID firstRow = UUID.randomUUID();
        execute(db, "INSERT INTO courses VALUES ('" + id + "','Lama','TOTAL_POINTS')");
        execute(db, "INSERT INTO categories VALUES ('" + a + "','" + id + "','Tugas','2',0), ('"
                + b + "','" + id + "','Ujian','1',1)");
        execute(db, "INSERT INTO assessments VALUES ('" + firstRow + "','" + id + "','" + a
                + "','Tugas','100','100',0), ('" + UUID.randomUUID() + "','" + id + "','" + b
                + "','Ujian','100','0',1)");

        CourseStore store = new CourseStore(db);
        store.initialize();
        store.initialize();
        assertEquals("4", scalar(db, "PRAGMA user_version"));
        Course original = store.load().getFirst();
        assertTrue(original.legacyWeights());
        assertEquals(decimal("2"), original.categories().getFirst().weight());
        assertEquals(firstRow, original.assessments().getFirst().id());
        assertEquals(Exact.of(200).divide(Exact.of(3)), Calculation.actual(original).percent());

        var proposal = WeightConversion.propose(original);
        assertFalse(proposal.exact());
        assertEquals(List.of(decimal("66.67"), decimal("33.33")), proposal.categories().stream().map(Category::weight).toList());
        Course converted = original.withCategories(proposal.categories());
        assertNotEquals(Calculation.actual(original).percent(), Calculation.actual(converted).percent());
        assertEquals(original, store.load().getFirst());
        store.save(original, converted);
        assertFalse(store.load().getFirst().legacyWeights());
        assertEquals(original.assessments(), store.load().getFirst().assessments());
        assertEquals("2", scalar(db, "SELECT weight FROM legacy_weight_history WHERE category_id='" + a + "'"));
    }

    @Test void conversionKeepsTerminatingProportionsExactlyAndAllWeightsPositive() {
        Course course = Course.create("Contoh", Aggregation.TOTAL_POINTS);
        var first = new Category(UUID.randomUUID(), "Tugas", decimal("3"));
        var second = new Category(UUID.randomUUID(), "Ujian", decimal("1"));
        Course legacy = new Course(course.id(), course.name(), course.method(), List.of(first, second), List.of(), true);
        var exact = WeightConversion.propose(legacy);
        assertTrue(exact.exact());
        assertEquals(List.of(decimal("75"), decimal("25")), exact.categories().stream().map(Category::weight).toList());
        var tiny = new Category(UUID.randomUUID(), "Praktikum", decimal("0.0000001"));
        var rounded = WeightConversion.propose(new Course(course.id(), course.name(), course.method(),
                List.of(first, second, tiny), List.of(), true));
        assertTrue(rounded.categories().stream().allMatch(category -> category.weight().signum() > 0));
        assertEquals(0, Course.FULL_WEIGHT.compareTo(Course.totalWeight(rounded.categories())));
    }

    @Test void unsupportedVersionsAndInvalidLegacyDataDoNotRewriteTheDatabase() throws Exception {
        Path future = directory.resolve("future.sqlite3");
        execute(future, "PRAGMA user_version = 99");
        assertThrows(SQLException.class, () -> new CourseStore(future).initialize());
        assertEquals("99", scalar(future, "PRAGMA user_version"));

        Path bad = directory.resolve("bad.sqlite3");
        legacySchema(bad);
        execute(bad, "INSERT INTO courses VALUES ('" + UUID.randomUUID() + "','Lama','TOTAL_POINTS')");
        assertThrows(SQLException.class, () -> new CourseStore(bad).initialize());
        assertEquals("0", scalar(bad, "PRAGMA user_version"));
        assertEquals("0", scalar(bad, "SELECT count(*) FROM sqlite_master WHERE name='scenarios'"));
        assertEquals("1", scalar(bad, "SELECT count(*) FROM courses"));
    }

    private static void legacySchema(Path db) throws Exception {
        execute(db, "CREATE TABLE courses (id TEXT PRIMARY KEY, name TEXT NOT NULL, method TEXT NOT NULL)");
        execute(db, """
                CREATE TABLE categories (
                    id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                    name TEXT NOT NULL, weight TEXT NOT NULL, position INTEGER NOT NULL, UNIQUE(id, course_id)
                )
                """);
        execute(db, """
                CREATE TABLE assessments (
                    id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                    category_id TEXT NOT NULL, name TEXT NOT NULL, maximum TEXT NOT NULL, earned TEXT,
                    position INTEGER NOT NULL, FOREIGN KEY(category_id, course_id) REFERENCES categories(id, course_id)
                )
                """);
    }

    private static Course draft(Course original, String first, String second) {
        return original.withCategories(List.of(new Category(original.categories().getFirst().id(), "Umum", decimal(first)),
                new Category(UUID.randomUUID(), "Praktikum", decimal(second))));
    }

    private static void execute(Path db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Path db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
