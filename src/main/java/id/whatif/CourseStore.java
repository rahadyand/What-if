package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.UUID;

final class CourseStore {
    private static final int SCHEMA_VERSION = 1;
    private final Path database;

    CourseStore(Path database) {
        this.database = database.toAbsolutePath();
    }

    static Path defaultDatabase() {
        String override = System.getProperty("whatif.dataDir", "");
        if (!override.isBlank()) return Path.of(override).resolve("whatif.sqlite3");
        String local = System.getenv("LOCALAPPDATA");
        Path directory = local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share", "WhatIfStandalone")
                : Path.of(local, "WhatIfStandalone");
        return directory.resolve("whatif.sqlite3");
    }

    void initialize() throws IOException, SQLException {
        Files.createDirectories(database.getParent());
        transaction(connection -> {
            try (var statement = connection.createStatement()) {
                try (var check = statement.executeQuery("PRAGMA quick_check")) {
                    if (!check.next() || !"ok".equals(check.getString(1))) {
                        throw new SQLException("Basis data rusak. Pulihkan dari cadangan sebelum mencoba lagi.");
                    }
                }
                int version;
                try (var result = statement.executeQuery("PRAGMA user_version")) {
                    result.next();
                    version = result.getInt(1);
                }
                if (version == SCHEMA_VERSION) {
                    validateSchema(connection, true);
                    return;
                }
                if (version != 0) throw new SQLException("Versi basis data tidak didukung: " + version + ".");

                var tables = new HashSet<String>();
                try (var result = statement.executeQuery("""
                        SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'
                        """)) {
                    while (result.next()) tables.add(result.getString(1));
                }
                if (tables.isEmpty()) {
                    statement.executeUpdate("""
                            CREATE TABLE courses (
                                id TEXT PRIMARY KEY, name TEXT NOT NULL, method TEXT NOT NULL
                            )
                            """);
                    statement.executeUpdate("""
                            CREATE TABLE categories (
                                id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                                name TEXT NOT NULL, weight TEXT NOT NULL, position INTEGER NOT NULL,
                                UNIQUE(id, course_id)
                            )
                            """);
                    statement.executeUpdate("""
                            CREATE TABLE assessments (
                                id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                                category_id TEXT NOT NULL, name TEXT NOT NULL, maximum TEXT NOT NULL, earned TEXT,
                                position INTEGER NOT NULL,
                                FOREIGN KEY(category_id, course_id) REFERENCES categories(id, course_id)
                            )
                            """);
                } else if (!tables.equals(Set.of("courses", "categories", "assessments"))) {
                    throw new SQLException("Skema basis data lama tidak dikenali. Berkas asli dipertahankan.");
                }
                validateSchema(connection, false);
                statement.executeUpdate("ALTER TABLE courses ADD COLUMN legacy_weights INTEGER NOT NULL DEFAULT 1");
                statement.executeUpdate("""
                        CREATE TABLE scenarios (
                            id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                            name TEXT NOT NULL, snapshot BLOB NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE legacy_weight_history (
                            course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                            category_id TEXT NOT NULL, name TEXT NOT NULL, weight TEXT NOT NULL,
                            PRIMARY KEY(course_id, category_id)
                        )
                        """);
                readCourses(connection);
                statement.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
            }
        });
    }

    private static void validateSchema(Connection connection, boolean current) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeQuery("SELECT id, name, method" + (current ? ", legacy_weights" : "") + " FROM courses LIMIT 0").close();
            statement.executeQuery("SELECT id, course_id, name, weight, position FROM categories LIMIT 0").close();
            statement.executeQuery("SELECT id, course_id, category_id, name, maximum, earned, position FROM assessments LIMIT 0").close();
            if (current) {
                statement.executeQuery("SELECT id, course_id, name, snapshot FROM scenarios LIMIT 0").close();
                statement.executeQuery("SELECT course_id, category_id, name, weight FROM legacy_weight_history LIMIT 0").close();
            }
            try (var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (violations.next()) throw new SQLException("Relasi basis data tidak sah. Berkas asli dipertahankan.");
            }
        } catch (SQLException failure) {
            throw new SQLException("Skema basis data tidak sah. Berkas asli dipertahankan.", failure);
        }
    }

    List<Course> load() throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            List<Course> courses = readCourses(connection);
            connection.commit();
            return courses;
        }
    }

    private static List<Course> readCourses(Connection connection) throws SQLException {
        List<Course> courses = new ArrayList<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id, name, method, legacy_weights FROM courses ORDER BY rowid")) {
            while (result.next()) courses.add(readCourse(connection, result));
        } catch (IllegalArgumentException corrupt) {
            throw new SQLException("Data mata kuliah tidak sah. Berkas asli dipertahankan.", corrupt);
        }
        return List.copyOf(courses);
    }

    void save(Course expected, Course course) throws SQLException {
        if (expected != null && !expected.id().equals(course.id())) {
            throw new IllegalArgumentException("Identitas mata kuliah berubah.");
        }
        transaction(connection -> {
            verifyCurrent(connection, course.id(), expected);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO courses(id, name, method, legacy_weights) VALUES (?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET name=excluded.name, method=excluded.method,
                        legacy_weights=excluded.legacy_weights
                    """)) {
                statement.setString(1, course.id().toString());
                statement.setString(2, course.name());
                statement.setString(3, course.method().name());
                statement.setBoolean(4, course.legacyWeights());
                statement.executeUpdate();
            }
            if (expected != null && expected.legacyWeights() && !course.legacyWeights()) {
                try (var archive = connection.prepareStatement("""
                        INSERT INTO legacy_weight_history(course_id, category_id, name, weight)
                        SELECT course_id, id, name, weight FROM categories WHERE course_id=?
                        """)) {
                    archive.setString(1, course.id().toString());
                    archive.executeUpdate();
                }
            }
            if (expected == null || !course.categories().equals(expected.categories())) writeCategories(connection, course);
            if (expected == null || !course.assessments().equals(expected.assessments())) {
                deleteById(connection, "DELETE FROM assessments WHERE course_id=?", course.id());
                writeAssessments(connection, course);
            }
            if (expected != null) {
                var ids = course.categories().stream().map(Category::id).collect(Collectors.toSet());
                for (Category category : expected.categories()) {
                    if (!ids.contains(category.id())) deleteById(connection, "DELETE FROM categories WHERE id=?", category.id());
                }
            }
        });
    }

    List<Scenario> loadScenarios() throws SQLException {
        try (Connection connection = open(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id, course_id, name, snapshot FROM scenarios ORDER BY rowid")) {
            List<Scenario> scenarios = new ArrayList<>();
            while (result.next()) scenarios.add(readScenario(result));
            return List.copyOf(scenarios);
        }
    }

    void saveScenario(Scenario expected, Scenario scenario) throws SQLException, IOException {
        if (expected != null && (!expected.id().equals(scenario.id()) || !expected.courseId().equals(scenario.courseId()))) {
            throw new IllegalArgumentException("Identitas skenario berubah.");
        }
        byte[] snapshot = ScenarioSnapshot.write(scenario.state());
        transaction(connection -> {
            try (var current = connection.prepareStatement("SELECT id, course_id, name, snapshot FROM scenarios WHERE id=?")) {
                current.setString(1, scenario.id().toString());
                try (var result = current.executeQuery()) {
                    Scenario stored = result.next() ? readScenario(result) : null;
                    if (!Objects.equals(stored, expected)) {
                        throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat skenario terbaru.");
                    }
                }
            }
            try (var write = connection.prepareStatement("""
                    INSERT INTO scenarios(id, course_id, name, snapshot) VALUES (?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET name=excluded.name, snapshot=excluded.snapshot
                    """)) {
                write.setString(1, scenario.id().toString());
                write.setString(2, scenario.courseId().toString());
                write.setString(3, scenario.name());
                write.setBytes(4, snapshot);
                write.executeUpdate();
            }
        });
    }

    private static Scenario readScenario(ResultSet result) throws SQLException {
        try {
            var data = ScenarioSnapshot.read(result.getBytes("snapshot"));
            var scenario = new Scenario(UUID.fromString(result.getString("id")), result.getString("name"), data);
            if (!scenario.courseId().toString().equals(result.getString("course_id"))) {
                throw new IOException("Mata kuliah snapshot berbeda.");
            }
            return scenario;
        } catch (IOException | IllegalArgumentException failure) {
            throw new SQLException("Data skenario rusak atau versinya tidak didukung. Berkas asli dipertahankan.", failure);
        }
    }

    void delete(Course expected) throws SQLException {
        transaction(connection -> {
            verifyCurrent(connection, expected.id(), expected);
            deleteById(connection, "DELETE FROM courses WHERE id=?", expected.id());
        });
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 1500");
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    private static void verifyCurrent(Connection connection, UUID id, Course expected) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id, name, method, legacy_weights FROM courses WHERE id=?")) {
            statement.setString(1, id.toString());
            try (var result = statement.executeQuery()) {
                Course current = result.next() ? readCourse(connection, result) : null;
                if (!Objects.equals(current, expected)) {
                    throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat data terbaru.");
                }
            }
        }
    }

    private static Course readCourse(Connection connection, ResultSet result) throws SQLException {
        UUID id = UUID.fromString(result.getString("id"));
        List<Category> categories = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT id, name, weight FROM categories WHERE course_id=? ORDER BY position
                """)) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    categories.add(new Category(UUID.fromString(rows.getString("id")), rows.getString("name"),
                            new BigDecimal(rows.getString("weight"))));
                }
            }
        }
        List<Assessment> assessments = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT id, name, category_id, maximum, earned FROM assessments WHERE course_id=? ORDER BY position
                """)) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String earned = rows.getString("earned");
                    assessments.add(new Assessment(UUID.fromString(rows.getString("id")), rows.getString("name"),
                            UUID.fromString(rows.getString("category_id")), new BigDecimal(rows.getString("maximum")),
                            earned == null ? null : new BigDecimal(earned)));
                }
            }
        }
        return new Course(id, result.getString("name"), Aggregation.valueOf(result.getString("method")),
                categories, assessments, result.getBoolean("legacy_weights"));
    }

    private static void writeCategories(Connection connection, Course course) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO categories(id, course_id, name, weight, position) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name, weight=excluded.weight, position=excluded.position
                WHERE categories.course_id=excluded.course_id
                """)) {
            int position = 0;
            for (Category category : course.categories()) {
                statement.setString(1, category.id().toString());
                statement.setString(2, course.id().toString());
                statement.setString(3, category.name());
                statement.setString(4, category.weight().toPlainString());
                statement.setInt(5, position++);
                statement.addBatch();
            }
            for (int written : statement.executeBatch()) {
                if (written == 0) throw new SQLException("Identitas kategori tidak dapat digunakan pada mata kuliah ini.");
            }
        }
    }

    private static void writeAssessments(Connection connection, Course course) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO assessments(id, course_id, category_id, name, maximum, earned, position)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            int position = 0;
            for (Assessment row : course.assessments()) {
                statement.setString(1, row.id().toString());
                statement.setString(2, course.id().toString());
                statement.setString(3, row.categoryId().toString());
                statement.setString(4, row.name());
                statement.setString(5, row.maximum().toPlainString());
                if (row.earned() == null) statement.setNull(6, Types.VARCHAR);
                else statement.setString(6, row.earned().toPlainString());
                statement.setInt(7, position++);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void deleteById(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.executeUpdate();
        }
    }

    private void transaction(SqlWork work) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
