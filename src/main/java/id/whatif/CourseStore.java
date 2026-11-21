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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.UUID;

final class CourseStore {
    private static final int SCHEMA_VERSION = 4;
    private static final String COURSE_COLUMNS = "id, name, method, legacy_weights, semester_id, sks, present, meetings";
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
                if (version < 0 || version > SCHEMA_VERSION) {
                    throw new SQLException("Versi basis data tidak didukung: " + version + ".");
                }
                if (version == 0) migrateWeights(connection);
                if (version < 2) migrateAcademic(connection);
                if (version < 3) migrateGrading(connection);
                if (version < 4) migrateProjection(connection);
                validateSchema(connection, SCHEMA_VERSION);
                if (version < SCHEMA_VERSION) {
                    readCourses(connection);
                    readSemesters(connection);
                    readGrading(connection);
                    statement.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
                }
            }
        });
    }

    private static void migrateWeights(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
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
            validateSchema(connection, 0);
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
        }
    }

    private static void migrateAcademic(Connection connection) throws SQLException {
        validateSchema(connection, 1);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE semesters (
                        id TEXT PRIMARY KEY, label TEXT NOT NULL, academic_year TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE academic_settings (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        active_semester_id TEXT REFERENCES semesters(id)
                    )
                    """);
            statement.executeUpdate("INSERT INTO academic_settings(id) VALUES (1)");
            statement.executeUpdate("ALTER TABLE courses ADD COLUMN semester_id TEXT REFERENCES semesters(id)");
            statement.executeUpdate("ALTER TABLE courses ADD COLUMN sks INTEGER CHECK (sks IS NULL OR (typeof(sks) = 'integer' AND sks > 0))");
            statement.executeUpdate("ALTER TABLE courses ADD COLUMN present INTEGER");
            statement.executeUpdate("""
                    ALTER TABLE courses ADD COLUMN meetings INTEGER CHECK (
                        (present IS NULL AND meetings IS NULL) OR
                        (typeof(present) = 'integer' AND typeof(meetings) = 'integer' AND present BETWEEN 0 AND meetings)
                    )
                    """);
        } catch (SQLException failure) {
            throw new SQLException("Skema basis data tidak dapat dimigrasikan. Berkas asli dipertahankan.", failure);
        }
    }

    private static void migrateGrading(Connection connection) throws SQLException {
        validateSchema(connection, 2);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE grade_scale_settings (id INTEGER PRIMARY KEY CHECK (id=1), maximum TEXT NOT NULL)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE grade_scale_rows (letter TEXT PRIMARY KEY, boundary TEXT NOT NULL UNIQUE, points TEXT NOT NULL)
                    """);
            statement.executeUpdate("INSERT INTO grade_scale_settings VALUES (1, '4')");
            writeScale(connection, GradeScale.DEFAULT);
            statement.executeUpdate("""
                    CREATE TABLE confirmed_grades (
                        course_id TEXT PRIMARY KEY REFERENCES courses ON DELETE CASCADE,
                        letter TEXT NOT NULL, points TEXT NOT NULL, source TEXT NOT NULL, kept_candidate TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE ipk_repeat_reviews (
                        course_id TEXT PRIMARY KEY REFERENCES courses ON DELETE CASCADE,
                        group_key TEXT NOT NULL, included INTEGER NOT NULL CHECK (included IN (0,1))
                    )
                    """);
        }
    }

    private static void migrateProjection(Connection connection) throws SQLException {
        validateSchema(connection, 3);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE dashboard_scenarios (
                        course_id TEXT PRIMARY KEY REFERENCES courses ON DELETE CASCADE, scenario_id TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE projected_repeat_reviews (
                        course_id TEXT NOT NULL REFERENCES courses ON DELETE CASCADE,
                        group_key TEXT NOT NULL, included INTEGER NOT NULL CHECK (included IN (0,1)),
                        PRIMARY KEY(course_id, group_key)
                    )
                    """);
        }
    }

    private static void validateSchema(Connection connection, int version) throws SQLException {
        try (var statement = connection.createStatement()) {
            String courseColumns = switch (version) {
                case 0 -> "id, name, method";
                case 1 -> "id, name, method, legacy_weights";
                default -> COURSE_COLUMNS;
            };
            statement.executeQuery("SELECT " + courseColumns + " FROM courses LIMIT 0").close();
            statement.executeQuery("SELECT id, course_id, name, weight, position FROM categories LIMIT 0").close();
            statement.executeQuery("SELECT id, course_id, category_id, name, maximum, earned, position FROM assessments LIMIT 0").close();
            if (version >= 1) {
                statement.executeQuery("SELECT id, course_id, name, snapshot FROM scenarios LIMIT 0").close();
                statement.executeQuery("SELECT course_id, category_id, name, weight FROM legacy_weight_history LIMIT 0").close();
            }
            if (version >= 2) {
                statement.executeQuery("SELECT id, label, academic_year FROM semesters LIMIT 0").close();
                statement.executeQuery("SELECT active_semester_id FROM academic_settings ORDER BY id LIMIT 0").close();
            }
            if (version >= 3) {
                statement.executeQuery("SELECT id, maximum FROM grade_scale_settings LIMIT 0").close();
                statement.executeQuery("SELECT letter, boundary, points FROM grade_scale_rows LIMIT 0").close();
                statement.executeQuery("SELECT course_id, letter, points, source, kept_candidate FROM confirmed_grades LIMIT 0").close();
                statement.executeQuery("SELECT course_id, group_key, included FROM ipk_repeat_reviews LIMIT 0").close();
            }
            if (version >= 4) {
                statement.executeQuery("SELECT course_id, scenario_id FROM dashboard_scenarios LIMIT 0").close();
                statement.executeQuery("SELECT course_id, group_key, included FROM projected_repeat_reviews LIMIT 0").close();
            }
            try (var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (violations.next()) throw new SQLException("Relasi basis data tidak sah. Berkas asli dipertahankan.");
            }
        } catch (SQLException failure) {
            throw new SQLException("Skema basis data tidak sah. Berkas asli dipertahankan.", failure);
        }
    }

    GradingData loadGrading() throws SQLException { return transact(CourseStore::readGrading); }

    private static GradingData readGrading(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            var finals = new HashMap<UUID, FinalGrade>();
            try (var rows = statement.executeQuery("SELECT course_id, letter, points, source, kept_candidate FROM confirmed_grades")) {
                while (rows.next()) finals.put(readId(rows.getString("course_id")), readFinal(rows));
            }
            var repeats = new HashMap<UUID, AcademicAverage.RepeatDecision>();
            try (var rows = statement.executeQuery("SELECT course_id, group_key, included FROM ipk_repeat_reviews")) {
                while (rows.next()) repeats.put(readId(rows.getString(1)), new AcademicAverage.RepeatDecision(rows.getString(2), rows.getBoolean(3)));
            }
            return new GradingData(readScale(connection), Map.copyOf(finals), Map.copyOf(repeats));
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Data nilai final atau skala tidak sah. Berkas asli dipertahankan.", failure);
        }
    }

    private static GradeScale readScale(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            BigDecimal maximum;
            try (var row = statement.executeQuery("SELECT maximum FROM grade_scale_settings WHERE id=1")) {
                if (!row.next()) throw new SQLException("Skema basis data tidak memiliki skala nilai.");
                maximum = new BigDecimal(row.getString(1));
            }
            var grades = new ArrayList<Grade>();
            try (var rows = statement.executeQuery("SELECT letter, boundary, points FROM grade_scale_rows")) {
                while (rows.next()) grades.add(new Grade(rows.getString(1), new BigDecimal(rows.getString(2)), new BigDecimal(rows.getString(3))));
            }
            return new GradeScale(maximum, grades);
        }
    }

    void saveScale(GradeScale expected, GradeScale draft) throws SQLException {
        transaction(connection -> {
            if (!readScale(connection).equals(expected)) throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat skala.");
            writeScale(connection, draft);
        });
    }

    private static void writeScale(Connection connection, GradeScale scale) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM grade_scale_rows");
        }
        try (var write = connection.prepareStatement("UPDATE grade_scale_settings SET maximum=? WHERE id=1");
             var row = connection.prepareStatement("INSERT INTO grade_scale_rows VALUES (?,?,?)")) {
            write.setString(1, scale.maximum().toPlainString());
            write.executeUpdate();
            for (Grade grade : scale.grades()) {
                row.setString(1, grade.letter());
                row.setString(2, grade.boundary().toPlainString());
                row.setString(3, grade.point().toPlainString());
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    private static FinalGrade readFinal(ResultSet row) throws SQLException {
        return new FinalGrade(row.getString("letter"), new BigDecimal(row.getString("points")),
                FinalGrade.Source.valueOf(row.getString("source")), row.getString("kept_candidate"));
    }

    void saveFinal(Course course, GradeScale scale, FinalGrade expected, FinalGrade draft) throws SQLException {
        transaction(connection -> {
            verifyCurrent(connection, course.id(), course);
            if (!readScale(connection).equals(scale)) throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat skala.");
            try (var lookup = connection.prepareStatement("SELECT letter, points, source, kept_candidate FROM confirmed_grades WHERE course_id=?")) {
                lookup.setString(1, course.id().toString());
                try (var row = lookup.executeQuery()) {
                    if (!Objects.equals(row.next() ? readFinal(row) : null, expected)) {
                        throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat nilai final.");
                    }
                }
            }
            try (var write = connection.prepareStatement("""
                    INSERT INTO confirmed_grades VALUES (?,?,?,?,?)
                    ON CONFLICT(course_id) DO UPDATE SET letter=excluded.letter, points=excluded.points,
                        source=excluded.source, kept_candidate=excluded.kept_candidate
                    """)) {
                write.setString(1, course.id().toString());
                write.setString(2, draft.letter());
                write.setString(3, draft.points().toPlainString());
                write.setString(4, draft.source().name());
                write.setString(5, draft.keptCandidate());
                write.executeUpdate();
            }
        });
    }

    record GradingData(GradeScale scale, Map<UUID, FinalGrade> finals, Map<UUID, AcademicAverage.RepeatDecision> repeats) {}

    Map<UUID, AcademicAverage.RepeatDecision> saveRepeatReview(AcademicAverage.RepeatGroup group,
            Map<UUID, AcademicAverage.RepeatDecision> expected, Set<UUID> included) throws SQLException {
        return transact(connection -> {
            GradingData data = readGrading(connection);
            var current = AcademicAverage.ipk(readCourses(connection), data.finals(), data.repeats()).repeats().stream()
                    .filter(item -> item.key().equals(group.key())).findFirst().orElse(null);
            if (!Objects.equals(group, current) || group.courses().stream().map(entry -> entry.course().id())
                    .anyMatch(id -> !Objects.equals(expected.get(id), data.repeats().get(id)))) {
                throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk meninjau mata kuliah.");
            }
            var updated = new HashMap<>(data.repeats());
            try (var write = connection.prepareStatement("""
                    INSERT INTO ipk_repeat_reviews VALUES (?,?,?)
                    ON CONFLICT(course_id) DO UPDATE SET group_key=excluded.group_key, included=excluded.included
                    """)) {
                for (var entry : group.courses()) {
                    UUID id = entry.course().id();
                    var decision = new AcademicAverage.RepeatDecision(group.key(), included.contains(id));
                    write.setString(1, id.toString());
                    write.setString(2, decision.groupKey());
                    write.setBoolean(3, decision.included());
                    write.addBatch();
                    updated.put(id, decision);
                }
                write.executeBatch();
            }
            return Map.copyOf(updated);
        });
    }

    DashboardProjection.Settings loadProjectionSettings() throws SQLException {
        return transact(CourseStore::readProjectionSettings);
    }

    private static DashboardProjection.Settings readProjectionSettings(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            var selections = new HashMap<UUID, UUID>();
            try (var rows = statement.executeQuery("SELECT course_id, scenario_id FROM dashboard_scenarios")) {
                while (rows.next()) selections.put(readId(rows.getString(1)), readId(rows.getString(2)));
            }
            var reviews = new HashMap<AcademicAverage.ProjectedRepeatKey, Boolean>();
            try (var rows = statement.executeQuery("SELECT course_id, group_key, included FROM projected_repeat_reviews")) {
                while (rows.next()) reviews.put(new AcademicAverage.ProjectedRepeatKey(rows.getString(2), readId(rows.getString(1))),
                        rows.getBoolean(3));
            }
            return new DashboardProjection.Settings(Map.copyOf(selections), Map.copyOf(reviews));
        }
    }

    void saveProjectionChoice(UUID course, UUID expected, UUID selected) throws SQLException {
        transaction(connection -> {
            var current = readProjectionSettings(connection).selections().get(course);
            if (!Objects.equals(current, expected)) {
                throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat pilihan skenario.");
            }
            if (selected == null) {
                deleteById(connection, "DELETE FROM dashboard_scenarios WHERE course_id=?", course);
                return;
            }
            try (var statement = connection.prepareStatement("""
                    SELECT 1 FROM scenarios
                    WHERE id=? AND course_id=? AND NOT EXISTS (SELECT 1 FROM confirmed_grades WHERE course_id=?)
                    """)) {
                statement.setString(1, selected.toString());
                statement.setString(2, course.toString());
                statement.setString(3, course.toString());
                try (var row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("Data skenario tidak tersedia untuk mata kuliah yang belum final.");
                }
            }
            try (var write = connection.prepareStatement("""
                    INSERT INTO dashboard_scenarios(course_id, scenario_id) VALUES (?,?)
                    ON CONFLICT(course_id) DO UPDATE SET scenario_id=excluded.scenario_id
                    """)) {
                write.setString(1, course.toString());
                write.setString(2, selected.toString());
                write.executeUpdate();
            }
        });
    }

    Map<AcademicAverage.ProjectedRepeatKey, Boolean> saveProjectedRepeatReview(UUID semester, AcademicAverage.RepeatGroup group,
            Map<AcademicAverage.ProjectedRepeatKey, Boolean> expected, Set<UUID> included) throws SQLException {
        return transact(connection -> {
            var data = readGrading(connection);
            var settings = readProjectionSettings(connection);
            var report = DashboardProjection.evaluate(semester, readCourses(connection), data.finals(), data.scale(),
                    readScenarioCatalog(connection), settings, data.repeats());
            var current = report.cumulative().repeats().stream().filter(item -> item.key().equals(group.key()) && item.projected())
                    .findFirst().orElse(null);
            if (!Objects.equals(group, current) || group.courses().stream()
                    .map(entry -> new AcademicAverage.ProjectedRepeatKey(group.key(), entry.course().id()))
                    .anyMatch(key -> !Objects.equals(expected.get(key), settings.reviews().get(key)))) {
                throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk meninjau proyeksi.");
            }
            var updated = new HashMap<>(settings.reviews());
            try (var write = connection.prepareStatement("""
                    INSERT INTO projected_repeat_reviews VALUES (?,?,?)
                    ON CONFLICT(course_id, group_key) DO UPDATE SET included=excluded.included
                    """)) {
                for (var entry : group.courses()) {
                    UUID id = entry.course().id();
                    boolean use = included.contains(id);
                    write.setString(1, id.toString());
                    write.setString(2, group.key());
                    write.setBoolean(3, use);
                    write.addBatch();
                    updated.put(new AcademicAverage.ProjectedRepeatKey(group.key(), id), use);
                }
                write.executeBatch();
            }
            return Map.copyOf(updated);
        });
    }

    List<Course> load() throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            List<Course> courses = readCourses(connection);
            connection.commit();
            return courses;
        }
    }

    SemesterSelection loadSemesters() throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            SemesterSelection selection = readSemesters(connection);
            connection.commit();
            return selection;
        }
    }

    private static SemesterSelection readSemesters(Connection connection) throws SQLException {
        List<Semester> semesters = new ArrayList<>();
        try (var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT id, label, academic_year FROM semesters ORDER BY rowid DESC")) {
                while (rows.next()) semesters.add(readSemester(rows));
            }
            semesters.sort(Semester.NEWEST_FIRST);
            try (var row = statement.executeQuery("SELECT active_semester_id FROM academic_settings WHERE id=1")) {
                if (!row.next()) throw new SQLException("Skema basis data tidak memiliki pengaturan semester.");
                return new SemesterSelection(List.copyOf(semesters), readId(row.getString(1)));
            }
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Data semester tidak sah. Berkas asli dipertahankan.", failure);
        }
    }

    private static Semester readSemester(ResultSet row) throws SQLException {
        return new Semester(readId(row.getString("id")), row.getString("label"), row.getString("academic_year"));
    }

    SemesterSelection saveSemester(Semester expected, Semester draft) throws SQLException {
        if (expected != null && !expected.id().equals(draft.id())) {
            throw new IllegalArgumentException("Identitas semester berubah.");
        }
        return transact(connection -> {
            try (var lookup = connection.prepareStatement("SELECT id, label, academic_year FROM semesters WHERE id=?")) {
                lookup.setString(1, draft.id().toString());
                try (var row = lookup.executeQuery()) {
                    if (!Objects.equals(row.next() ? readSemester(row) : null, expected)) {
                        throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat semester terbaru.");
                    }
                }
            }
            try (var write = connection.prepareStatement("""
                    INSERT INTO semesters(id, label, academic_year) VALUES (?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET label=excluded.label, academic_year=excluded.academic_year
                    """)) {
                write.setString(1, draft.id().toString());
                write.setString(2, draft.label());
                write.setString(3, draft.academicYear());
                write.executeUpdate();
            }
            return readSemesters(connection);
        });
    }

    void setActiveSemester(UUID id) throws SQLException {
        transaction(connection -> {
            try (var write = connection.prepareStatement("UPDATE academic_settings SET active_semester_id=? WHERE id=1")) {
                write.setString(1, Objects.requireNonNull(id).toString());
                if (write.executeUpdate() != 1) throw new SQLException("Skema basis data tidak memiliki pengaturan semester.");
            }
        });
    }

    private static List<Course> readCourses(Connection connection) throws SQLException {
        List<Course> courses = new ArrayList<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT " + COURSE_COLUMNS + " FROM courses ORDER BY rowid")) {
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
            validateSks(connection, expected, course.academic());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO courses(id, name, method, legacy_weights, semester_id, sks, present, meetings)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET name=excluded.name, method=excluded.method,
                        legacy_weights=excluded.legacy_weights, semester_id=excluded.semester_id,
                        sks=excluded.sks, present=excluded.present, meetings=excluded.meetings
                    """)) {
                statement.setString(1, course.id().toString());
                statement.setString(2, course.name());
                statement.setString(3, course.method().name());
                statement.setBoolean(4, course.legacyWeights());
                AcademicDetails academic = course.academic();
                statement.setString(5, stringId(academic.semesterId()));
                statement.setObject(6, academic.sks(), Types.INTEGER);
                statement.setObject(7, academic.present(), Types.INTEGER);
                statement.setObject(8, academic.meetings(), Types.INTEGER);
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

    private static void validateSks(Connection connection, Course before, AcademicDetails proposed) throws SQLException {
        if (proposed.semesterId() == null || proposed.sks() == null) return;
        AcademicDetails previous = before == null ? null : before.academic();
        if (previous != null && Objects.equals(previous.semesterId(), proposed.semesterId())
                && Objects.equals(previous.sks(), proposed.sks())) return;
        try (var query = connection.prepareStatement("""
                SELECT id, label, academic_year,
                    (SELECT COALESCE(SUM(sks),0) FROM courses WHERE semester_id=semesters.id) AS recorded
                FROM semesters WHERE id=?
                """)) {
            query.setString(1, proposed.semesterId().toString());
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Data semester tujuan tidak ditemukan.");
                SemesterSks.validate(readSemester(result).name(), result.getLong("recorded"), previous, proposed);
            }
        } catch (IllegalArgumentException issue) {
            throw new SQLException("Batas SKS. " + issue.getMessage(), issue);
        }
    }

    Scenario.Catalog loadScenarioCatalog() throws SQLException { return transact(CourseStore::readScenarioCatalog); }

    private static Scenario.Catalog readScenarioCatalog(Connection connection) throws SQLException {
        List<Scenario> scenarios = new ArrayList<>();
        var invalid = new HashMap<UUID, String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, course_id, name, snapshot FROM scenarios ORDER BY rowid")) {
            while (rows.next()) {
                try { scenarios.add(readScenario(rows)); }
                catch (InvalidScenario failure) {
                    try { invalid.put(readId(rows.getString("id")), rows.getString("name")); }
                    catch (IllegalArgumentException unusableId) { /* No valid selection can reference this ID. */ }
                }
            }
        }
        return new Scenario.Catalog(List.copyOf(scenarios), Map.copyOf(invalid));
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

    void deleteScenario(Scenario expected) throws SQLException {
        transaction(connection -> {
            try (var lookup = connection.prepareStatement("SELECT id, course_id, name, snapshot FROM scenarios WHERE id=?")) {
                lookup.setString(1, expected.id().toString());
                try (var row = lookup.executeQuery()) {
                    if (!row.next() || !expected.equals(readScenario(row))) {
                        throw new SQLException("Data telah berubah di jendela lain. Buka ulang untuk memuat skenario.");
                    }
                }
            }
            // The dashboard retains the selected ID so the user can see which selection needs replacement.
            deleteById(connection, "DELETE FROM scenarios WHERE id=?", expected.id());
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
            throw new InvalidScenario(failure);
        }
    }

    private static final class InvalidScenario extends SQLException {
        InvalidScenario(Throwable cause) {
            super("Data skenario rusak atau versinya tidak didukung. Berkas asli dipertahankan.", cause);
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
        try (var statement = connection.prepareStatement("SELECT " + COURSE_COLUMNS + " FROM courses WHERE id=?")) {
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
        AcademicDetails academic = new AcademicDetails(readId(result.getString("semester_id")),
                nullableInt(result, "sks"), nullableInt(result, "present"), nullableInt(result, "meetings"));
        return new Course(id, result.getString("name"), Aggregation.valueOf(result.getString("method")),
                categories, assessments, result.getBoolean("legacy_weights"), academic);
    }

    private static Integer nullableInt(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        if (result.wasNull()) return null;
        if (value < 0 || value > Integer.MAX_VALUE) throw new SQLException("Data mata kuliah di luar rentang bilangan bulat.");
        return (int) value;
    }

    private static String stringId(UUID id) { return id == null ? null : id.toString(); }

    private static UUID readId(String id) { return id == null ? null : UUID.fromString(id); }

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
        transact(connection -> {
            work.run(connection);
            return null;
        });
    }

    private <T> T transact(SqlQuery<T> work) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    record SemesterSelection(List<Semester> semesters, UUID activeId) {}

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlQuery<T> {
        T run(Connection connection) throws SQLException;
    }
}
