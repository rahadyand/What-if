package id.whatif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AcademicStoreTest {
    @TempDir Path directory;

    @Test void migrationKeepsUngroupedGradesAndScenarioBytesThroughACourseMove() throws Exception {
        Path db = directory.resolve("previous.sqlite3");
        Course original = Course.create("Algoritma", Aggregation.TOTAL_POINTS);
        var assessment = new Course.Assessment(UUID.randomUUID(), "Kuis", original.categories().getFirst().id(),
                BigDecimal.TEN, new BigDecimal("8.5"));
        original = original.withAssessments(List.of(assessment));
        var draft = new Simulation(original);
        draft.targetInput("78");
        var scenario = new Scenario(UUID.randomUUID(), "Target lama", draft.state());
        byte[] snapshot = ScenarioSnapshot.write(scenario.state());
        previousSchema(db, original, scenario, snapshot);

        var store = new CourseStore(db);
        store.initialize();
        assertEquals("4", scalar(db, "PRAGMA user_version"));
        assertEquals(List.of(original), store.load());
        assertEquals(new CourseStore.SemesterSelection(List.of(), null), store.loadSemesters());
        assertEquals(List.of(scenario), store.loadScenarios());

        var first = new Semester(UUID.randomUUID(), "1", "2025/2026");
        var second = new Semester(UUID.randomUUID(), "2", "2025/2026");
        store.saveSemester(null, second);
        store.saveSemester(null, first);
        store.setActiveSemester(first.id());
        assertEquals(List.of(second, first), store.loadSemesters().semesters());

        Course supplied = original.withAcademic(new AcademicDetails(first.id(), 8, 0, 0));
        store.save(original, supplied);
        Course moved = supplied.withAcademic(new AcademicDetails(second.id(), 8, 5, 6));
        store.save(supplied, moved);
        Course sameName = Course.create(original.name(), Aggregation.TOTAL_POINTS)
                .withAcademic(new AcademicDetails(first.id(), null, null, null));
        store.save(null, sameName);

        var semesterEdit = new Semester(second.id(), "Semester Antara", "2026/2027");
        store.saveSemester(second, semesterEdit);
        var restarted = new CourseStore(db);
        restarted.initialize();
        assertEquals(List.of(moved, sameName), restarted.load());
        assertEquals(first.id(), restarted.loadSemesters().activeId());
        assertEquals(List.of(semesterEdit, first), restarted.loadSemesters().semesters());
        assertEquals(List.of(scenario), restarted.loadScenarios());
        assertFalse(restarted.loadScenarios().getFirst().stale(moved));
        assertEquals(new BigDecimal("8.5"), moved.assessments().getFirst().earned());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement(); var result = statement.executeQuery("SELECT snapshot FROM scenarios")) {
            assertTrue(result.next());
            assertArrayEquals(snapshot, result.getBytes(1));
        }

        var current = new Simulation(moved);
        var newScenario = new Scenario(UUID.randomUUID(), "Setelah pindah", current.state());
        restarted.saveScenario(null, newScenario);
        current.targetInput("75");
        var updatedScenario = new Scenario(newScenario.id(), newScenario.name(), current.state());
        restarted.saveScenario(newScenario, updatedScenario);
        assertEquals(updatedScenario, restarted.loadScenarios().getLast());
    }

    @Test void failedMigrationRollsBackSchemaAndRows() throws Exception {
        Path db = directory.resolve("invalid-previous.sqlite3");
        Course course = Course.create("Basis Data", Aggregation.TOTAL_POINTS);
        var scenario = new Scenario(UUID.randomUUID(), "Skenario asli", new Simulation(course).state());
        previousSchema(db, course, scenario, ScenarioSnapshot.write(scenario.state()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE courses SET method='INVALID'");
        }
        assertThrows(SQLException.class, () -> new CourseStore(db).initialize());
        assertEquals("1", scalar(db, "PRAGMA user_version"));
        assertEquals("0", scalar(db, "SELECT count(*) FROM sqlite_master WHERE name='semesters'"));
        assertEquals("1", scalar(db, "SELECT count(*) FROM scenarios"));
    }

    @Test void academicNumbersAndAttendanceStates() {
        assertEquals("Belum dicatat", AcademicDetails.EMPTY.attendanceText());
        assertEquals("Belum ada pertemuan", AcademicDetails.read(null, "12", "0", "0").attendanceText());
        var recorded = AcademicDetails.read(UUID.randomUUID(), " 8 ", "5", "6");
        assertEquals(8, recorded.sks());
        assertEquals("5/6 (83,33%)", recorded.attendanceText());
        assertEquals("0/4 (0%)", AcademicDetails.read(null, "", "0", "4").attendanceText());
        assertThrows(IllegalArgumentException.class, () -> AcademicDetails.read(null, "0", "", ""));
        assertThrows(IllegalArgumentException.class, () -> AcademicDetails.read(null, "3,5", "", ""));
        assertThrows(IllegalArgumentException.class, () -> AcademicDetails.read(null, "", "3", ""));
        assertThrows(IllegalArgumentException.class, () -> AcademicDetails.read(null, "", "-1", "3"));
        assertThrows(IllegalArgumentException.class, () -> AcademicDetails.read(null, "", "4", "3"));
    }

    private static void previousSchema(Path db, Course course, Scenario scenario, byte[] data) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) {
            sql.execute("PRAGMA user_version=1");
            sql.execute("CREATE TABLE courses(id TEXT PRIMARY KEY, name TEXT NOT NULL, method TEXT NOT NULL, legacy_weights INTEGER NOT NULL DEFAULT 1)");
            sql.execute("""
                    CREATE TABLE categories(id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                    name TEXT NOT NULL, weight TEXT NOT NULL, position INTEGER NOT NULL, UNIQUE(id, course_id))
                    """);
            sql.execute("""
                    CREATE TABLE assessments(id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                    category_id TEXT NOT NULL, name TEXT NOT NULL, maximum TEXT NOT NULL, earned TEXT, position INTEGER NOT NULL,
                    FOREIGN KEY(category_id, course_id) REFERENCES categories(id, course_id))
                    """);
            sql.execute("""
                    CREATE TABLE scenarios(id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                    name TEXT NOT NULL, snapshot BLOB NOT NULL)
                    """);
            sql.execute("""
                    CREATE TABLE legacy_weight_history(course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                    category_id TEXT NOT NULL, name TEXT NOT NULL, weight TEXT NOT NULL, PRIMARY KEY(course_id, category_id))
                    """);
            try (var write = connection.prepareStatement("INSERT INTO courses VALUES (?, ?, ?, 0)")) {
                write.setString(1, course.id().toString());
                write.setString(2, course.name());
                write.setString(3, course.method().name());
                write.executeUpdate();
            }
            var category = course.categories().getFirst();
            sql.executeUpdate("INSERT INTO categories VALUES ('" + category.id() + "', '" + course.id() + "', 'Umum', '100', 0)");
            for (var assessment : course.assessments()) {
                sql.executeUpdate("INSERT INTO assessments VALUES ('" + assessment.id() + "', '" + course.id() + "', '"
                        + category.id() + "', 'Kuis', '10', '8.5', 0)");
            }
            try (var write = connection.prepareStatement("INSERT INTO scenarios VALUES (?, ?, ?, ?)")) {
                write.setString(1, scenario.id().toString());
                write.setString(2, course.id().toString());
                write.setString(3, scenario.name());
                write.setBytes(4, data);
                write.executeUpdate();
            }
        }
    }

    private static String scalar(Path db, String query) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
