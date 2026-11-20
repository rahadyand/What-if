package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static id.whatif.Calculation.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class CalculationTest {
    @Test void totalPointsAndEqualPercentagesGiveDifferentResults() {
        Category category = category("Umum", "1");
        List<Assessment> rows = List.of(row(category, "10", "8"), row(category, "100", "60"));
        assertEquals(Exact.of(6800).divide(Exact.of(110)), actual(totalCourse(category, rows)));
        assertEquals(Exact.of(70), actual(course(Aggregation.EQUAL_PERCENTAGES, List.of(category), rows)));
    }

    @Test void relativeWeightsAndScaledWeightsAreEquivalent() {
        Category a = category("Tugas", "100");
        Category b = category("Ujian", "50");
        Category empty = category("Kosong", "1000");
        List<Assessment> rows = List.of(row(a, "100", "90"), row(b, "100", "60"),
                row(empty, "30", null));
        Course weighted = course(Aggregation.TOTAL_POINTS, List.of(a, b, empty), rows);
        Course scaled = weighted.withCategories(List.of(
                new Category(a.id(), a.name(), num("2")), new Category(b.id(), b.name(), num("1")), empty));
        assertEquals(Exact.of(80), actual(weighted));
        assertEquals(actual(weighted), actual(scaled));
        assertTrue(Calculation.actual(weighted).partial());
    }

    @Test void emptyAndAllUnknownHaveNoActualResultButFreeOnlyCategoryParticipates() {
        Category a = category("Tugas", "1");
        Category b = category("Ujian", "1");
        Category empty = category("Kosong", "20");
        Assessment free = row(b, "100", null);
        Course unknown = course(Aggregation.TOTAL_POINTS, List.of(a, b, empty), List.of(free));
        assertNull(actual(unknown.withAssessments(List.of())));
        assertNull(actual(unknown));
        assertEquals(num("85.00"), Calculation.project(new Simulation(unknown)).suggestions().get(free.id()));

        Course mixed = unknown.withAssessments(List.of(row(a, "100", "100"), free));
        assertEquals(Exact.HUNDRED, actual(mixed));
        assertEquals(num("70.00"), Calculation.project(new Simulation(mixed)).suggestions().get(free.id()));
    }

    @Test void allExcludedIsEmpty() {
        Category category = category("Umum", "1");
        Assessment known = row(category, "100", "80");
        Assessment free = row(category, "100", null);
        Simulation simulation = new Simulation(totalCourse(category, List.of(known, free)));
        simulation.setExcluded(known.id(), true);
        simulation.setExcluded(free.id(), true);
        var projected = Calculation.project(simulation);
        assertEquals(EMPTY, projected.status());
        assertNull(projected.percent());
        assertTrue(projected.suggestions().isEmpty());
    }

    @Test void zeroIsKnownAndBonusKeepsA() {
        Category category = category("Umum", "1");
        Assessment zero = row(category, "100", "0");
        assertEquals(Exact.ZERO, actual(totalCourse(category, List.of(zero))));
        assertEquals(Grade.E, Grade.classify(actual(totalCourse(category, List.of(zero)))));
        Assessment bonus = row(category, "100", "110");
        Exact result = actual(totalCourse(category, List.of(bonus)));
        assertEquals(Exact.of(110), result);
        assertEquals(Grade.A, Grade.classify(result));
        assertEquals(num("4.00"), Grade.classify(result).point);
    }

    @Test void gradeThresholdsUseExactUnroundedPercent() {
        Category category = category("Umum", "1");
        assertEquals(Grade.A_MINUS, Grade.classify(actual(totalCourse(category, List.of(row(category, "100", "84.5"))))));
        assertEquals(Grade.A_MINUS, Grade.classify(Exact.of(num("84.9999999999999999999"))));
        for (Grade grade : Grade.values()) {
            assertEquals(grade, Grade.classify(Exact.of(grade.boundary)));
        }
    }

    @Test void targetOf821Requires7365UnderTotalPoints() {
        Category category = category("Umum", "1");
        Assessment free = row(category, "100", null);
        Simulation simulation = new Simulation(totalCourse(category, List.of(row(category, "50", "49.5"), free)));
        simulation.targetInput("82,1");
        var result = Calculation.project(simulation);
        assertEquals(num("73.65"), result.suggestions().get(free.id()));
        assertEquals(Exact.of(num("82.1")), result.percent());
    }

    @Test void suggestionsShareAnExactPercentageBeforeCeilingAndProjectionUsesDisplayedScores() {
        Category small = category("Kuis", "2");
        Category large = category("Ujian", "1");
        Assessment fixed = row(small, "7", "3");
        Assessment freeSmall = row(small, "3", null);
        Assessment freeLarge = row(large, "9", null);
        Course course = course(Aggregation.EQUAL_PERCENTAGES, List.of(small, large), List.of(fixed, freeSmall, freeLarge));
        Simulation simulation = new Simulation(course);
        simulation.targetInput("71.23");

        // Weight 2:1 and equal assessment shares give all three rows a course share of 1/3.
        var result = Calculation.project(simulation);
        Exact common = Exact.of(num("71.23")).multiply(Exact.of(3))
                .subtract(Exact.of(300).divide(Exact.of(7))).divide(Exact.of(2));
        assertEquals(common, result.commonPercent());
        assertEquals(num("2.57"), result.suggestions().get(freeSmall.id()));
        assertEquals(num("7.69"), result.suggestions().get(freeLarge.id()));
        Exact independent = Exact.of(3).divide(Exact.of(7))
                .add(Exact.of(num("2.57")).divide(Exact.of(3)))
                .add(Exact.of(num("7.69")).divide(Exact.of(9)))
                .multiply(Exact.HUNDRED).divide(Exact.of(3));
        assertEquals(independent, result.percent());
        assertTrue(result.percent().compareTo(Exact.of(num("71.23"))) >= 0);
    }

    @Test void securedNoFreeAndAboveMaximumHaveDistinctOutcomes() {
        Category category = category("Umum", "1");
        Assessment fixed = row(category, "100", "100");
        Assessment free = row(category, "100", null);
        Simulation simulation = new Simulation(totalCourse(category, List.of(fixed, free)));
        simulation.targetInput("40");
        assertEquals(SECURED, Calculation.project(simulation).status());
        assertEquals(num("0.00"), Calculation.project(simulation).suggestions().get(free.id()));
        simulation.targetInput("105.125");
        var high = Calculation.project(simulation);
        assertEquals(num("110.25"), high.suggestions().get(free.id()));
        assertTrue(high.bonus());
        simulation.setExcluded(free.id(), true);
        assertEquals(FIXED_BELOW, Calculation.project(simulation).status());
        assertEquals(Exact.HUNDRED, Calculation.project(simulation).percent());
        simulation.targetInput("99");
        assertEquals(SECURED, Calculation.project(simulation).status());
        assertTrue(Calculation.project(simulation).suggestions().isEmpty());
    }

    @Test void roundingPastSmallFractionalMaximumIsFlagged() {
        Category category = category("Umum", "1");
        Assessment free = row(category, "0.001", null);
        var result = Calculation.project(new Simulation(totalCourse(category, List.of(free))));
        assertEquals(num("0.01"), result.suggestions().get(free.id()));
        assertTrue(result.bonus());
    }

    @Test void bothMethodsMaintainConsistencyAfterEditsAndTargetChanges() {
        Category category = category("Umum", "1");
        Assessment fixed = row(category, "50", "49.5");
        Assessment free = row(category, "100", null);
        for (Aggregation method : Aggregation.values()) {
            Simulation simulation = new Simulation(course(method, List.of(category), List.of(fixed, free)));
            simulation.chooseGrade(Grade.B_PLUS);
            assertEquals(num("75"), simulation.target());
            simulation.targetInput("84,5");
            assertEquals(num("84.5"), simulation.target());
            assertEquals(Grade.A_MINUS, Grade.classify(Exact.of(simulation.target())));
            var baseline = Calculation.project(simulation);
            simulation.put(new Assessment(fixed.id(), fixed.name(), fixed.categoryId(), fixed.maximum(), num("20")), true);
            assertTrue(Calculation.project(simulation).suggestions().get(free.id())
                    .compareTo(baseline.suggestions().get(free.id())) > 0);
            simulation.setExcluded(fixed.id(), true);
            assertEquals(num("84.50"), Calculation.project(simulation).suggestions().get(free.id()));
            simulation.restore(fixed.id());
            assertEquals(baseline, Calculation.project(simulation));
            simulation.targetInput("8,4.5");
            assertThrows(IllegalArgumentException.class, () -> Calculation.project(simulation));
            simulation.targetInput("84.5");
            assertEquals(baseline, Calculation.project(simulation));
        }
    }

    @Test void numericInputPreservesDecimalMeaningAndRejectsAmbiguity() {
        assertEquals(num("80.2"), Numbers.read("80,2", "Skor", false, false));
        assertEquals(num("80.2"), Numbers.read(" 80.2 ", "Skor", false, false));
        assertEquals(num("1.234"), Numbers.read("1,234", "Bobot", false, true));
        assertNull(Numbers.read(" ", "Skor", true, false));
        assertEquals(BigDecimal.ZERO, Numbers.read("0", "Skor", true, false));
        for (String bad : List.of("80,2.0", "1.000.000", "-1", "30%", "1e3", "80,", "1 000", "x")) {
            assertThrows(IllegalArgumentException.class, () -> Numbers.read(bad, "Skor", false, false), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Numbers.read("0", "Bobot", false, true));
        assertThrows(IllegalArgumentException.class, () -> Numbers.read("", "Skor", false, false));
    }

    private static Exact actual(Course course) { return Calculation.actual(course).percent(); }

    private static Course totalCourse(Category category, List<Assessment> rows) {
        return course(Aggregation.TOTAL_POINTS, List.of(category), rows);
    }

    private static Course course(Aggregation method, List<Category> categories, List<Assessment> rows) {
        return new Course(UUID.randomUUID(), "Kuliah Uji", method, categories, rows);
    }

    private static Category category(String name, String weight) {
        return new Category(UUID.randomUUID(), name, num(weight));
    }

    private static Assessment row(Category category, String maximum, String earned) {
        return new Assessment(UUID.randomUUID(), "Penilaian Uji", category.id(), num(maximum),
                earned == null ? null : num(earned));
    }

    private static BigDecimal num(String value) { return new BigDecimal(value); }
}
