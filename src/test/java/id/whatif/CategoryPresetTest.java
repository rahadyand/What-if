package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CategoryPresetTest {
    @TempDir Path directory;

    @Test void countsOnlyActiveWeightsAndRequiresPositiveExactTotal() {
        assertEquals(List.of("UTS", "UAS", "Tugas", "Projek", "Praktikum"), CategoryConfiguration.PRESETS);
        var entries = exampleDraft();
        var configuration = CategoryConfiguration.review(entries);
        assertEquals(0, BigDecimal.valueOf(100).compareTo(configuration.total()));
        assertEquals(List.of("UTS", "UAS", "Tugas", "Kuis"),
                configuration.validatedCategories().stream().map(Category::name).toList());
        assertEquals(entries.getFirst().id(), configuration.validatedCategories().getFirst().id());

        var custom = entries.getLast();
        for (String invalid : List.of("14,99", "15.01", "0", " ", "-5")) {
            var adjusted = new ArrayList<>(entries);
            adjusted.set(adjusted.size() - 1, new CategoryConfiguration.Entry(custom.id(), custom.name(), invalid, true));
            var draft = CategoryConfiguration.review(adjusted);
            assertThrows(IllegalArgumentException.class, draft::validatedCategories, invalid);
        }
        assertThrows(IllegalArgumentException.class,
                CategoryConfiguration.review(List.of(entry("Praktikum", "100", false)))::validatedCategories);

        var shortfall = CategoryConfiguration.review(List.of(entry("UTS", "99,99", true)));
        assertEquals(new BigDecimal("0.01"), shortfall.remaining());
        var excess = CategoryConfiguration.review(List.of(entry("UTS", "100,01", true)));
        assertEquals(new BigDecimal("-0.01"), excess.remaining());
    }

    @Test void findsWholeWordsAliasesAndCustomPhrasesAndExposesAmbiguity() {
        var uts = category("UTS");
        var uas = category("UAS");
        var tugas = category("Tugas");
        var projek = category("Projek");
        var praktikum = category("Praktikum");
        var quiz = category("Kuis Praktik");
        List<Category> active = List.of(uts, uas, tugas, projek, praktikum, quiz);
        assertEquals(List.of(uts), CategorySuggestion.matches("Nilai (uts)", active));
        assertEquals(List.of(uas), CategorySuggestion.matches("UAS I", active));
        assertEquals(List.of(tugas), CategorySuggestion.matches("Tugas 2", active));
        assertEquals(List.of(projek), CategorySuggestion.matches("Project Akhir", active));
        assertEquals(List.of(projek), CategorySuggestion.matches("Projek Akhir", active));
        assertEquals(List.of(praktikum), CategorySuggestion.matches("Praktikum 1", active));
        assertEquals(List.of(quiz), CategorySuggestion.matches("KUIS–PRAKTIK 1", active));
        assertEquals(List.of(uts, uas), CategorySuggestion.matches("UTS / UAS", active));
        assertEquals(List.of(), CategorySuggestion.matches("UAS 1", List.of(uts, tugas, projek)));
        assertEquals(List.of(), CategorySuggestion.matches("UTSaan Projektor Petugas", active));
        assertEquals(List.of(), CategorySuggestion.matches("Kuis acak Praktik", active));
    }

    @Test void chosenIdDeterminesActualAndProjectionAndSurvivesCategoryEditsAndRestart() throws Exception {
        var db = directory.resolve("presets.sqlite3");
        var store = new CourseStore(db);
        store.initialize();
        var existing = Course.create("Mata kuliah lama", Aggregation.TOTAL_POINTS);
        store.save(null, existing);
        var categories = CategoryConfiguration.review(exampleDraft()).validatedCategories();
        var uas = categories.get(1);
        var assessment = new Assessment(UUID.randomUUID(), "UTS", uas.id(), BigDecimal.valueOf(100), BigDecimal.valueOf(80));
        var course = Course.create("Algoritma", Aggregation.EQUAL_PERCENTAGES, categories).withAssessments(List.of(assessment));
        store.save(null, course);

        assertEquals(Exact.of(24), Calculation.actual(course).contribution());
        assertEquals(Exact.of(24), Calculation.project(new Simulation(course)).maximum());
        var scenario = new Scenario(UUID.randomUUID(), "Sebelum edit", new Simulation(course).state());
        store.saveScenario(null, scenario);

        assertThrows(IllegalArgumentException.class, () -> course.withCategories(List.of(
                new Category(categories.getFirst().id(), "UTS", BigDecimal.valueOf(100)))));
        var changedCategories = new ArrayList<>(categories);
        changedCategories.set(0, new Category(categories.getFirst().id(), "UTS", BigDecimal.valueOf(25)));
        changedCategories.set(1, new Category(uas.id(), "UAS", BigDecimal.valueOf(25)));
        var changed = course.withCategories(changedCategories);
        store.save(course, changed);

        var reopened = new CourseStore(db);
        reopened.initialize();
        assertEquals(List.of(existing, changed), reopened.load());
        assertEquals(List.of(assessment), reopened.load().getLast().assessments());
        assertEquals(Exact.of(20), Calculation.actual(changed).contribution());
        var saved = reopened.loadScenarios().getFirst();
        assertTrue(saved.stale(changed));
        assertEquals(Exact.of(24), Calculation.project(Simulation.from(saved.state())).maximum());
    }

    private static List<CategoryConfiguration.Entry> exampleDraft() {
        return List.of(entry("UTS", "20", true), entry("UAS", "30", true), entry("Tugas", "35", true),
                entry("Projek", "", false), entry("Praktikum", "999", false), entry("Kuis", "15,00", true));
    }

    private static CategoryConfiguration.Entry entry(String name, String weight, boolean active) {
        return new CategoryConfiguration.Entry(UUID.randomUUID(), name, weight, active);
    }

    private static Category category(String name) {
        return new Category(UUID.randomUUID(), name, BigDecimal.ONE);
    }
}
