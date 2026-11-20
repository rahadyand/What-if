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
import java.util.Objects;
import java.util.UUID;

final class CourseStore {
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
        try (Connection connection = open(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS courses (
                        id TEXT PRIMARY KEY, name TEXT NOT NULL, method TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS categories (
                        id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                        name TEXT NOT NULL, weight TEXT NOT NULL, position INTEGER NOT NULL,
                        UNIQUE(id, course_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS assessments (
                        id TEXT PRIMARY KEY, course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                        category_id TEXT NOT NULL, name TEXT NOT NULL, maximum TEXT NOT NULL, earned TEXT,
                        position INTEGER NOT NULL,
                        FOREIGN KEY(category_id, course_id) REFERENCES categories(id, course_id)
                    )
                    """);
        }
    }

    List<Course> load() throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            List<Course> courses = new ArrayList<>();
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT id, name, method FROM courses ORDER BY rowid")) {
                while (result.next()) courses.add(readCourse(connection, result));
            }
            connection.commit();
            return List.copyOf(courses);
        }
    }

    void save(Course expected, Course course) throws SQLException {
        if (expected != null && !expected.id().equals(course.id())) {
            throw new IllegalArgumentException("Identitas mata kuliah berubah.");
        }
        transaction(connection -> {
            verifyCurrent(connection, course.id(), expected);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO courses(id, name, method) VALUES (?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET name=excluded.name, method=excluded.method
                    """)) {
                statement.setString(1, course.id().toString());
                statement.setString(2, course.name());
                statement.setString(3, course.method().name());
                statement.executeUpdate();
            }
            deleteById(connection, "DELETE FROM assessments WHERE course_id=?", course.id());
            deleteById(connection, "DELETE FROM categories WHERE course_id=?", course.id());
            writeCategories(connection, course);
            writeAssessments(connection, course);
        });
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
        try (var statement = connection.prepareStatement("SELECT id, name, method FROM courses WHERE id=?")) {
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
                categories, assessments);
    }

    private static void writeCategories(Connection connection, Course course) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO categories(id, course_id, name, weight, position) VALUES (?, ?, ?, ?, ?)
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
            statement.executeBatch();
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
