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
        Category category = category("Umum", "100");
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
        Course weighted = new Course(UUID.randomUUID(), "Bobot lama", Aggregation.TOTAL_POINTS, List.of(a, b, empty), rows, true);
        Course scaled = new Course(weighted.id(), weighted.name(), weighted.method(), List.of(
                new Category(a.id(), a.name(), num("2")), new Category(b.id(), b.name(), num("1")), empty), rows, true);
        assertEquals(Exact.of(80), actual(weighted));
        assertEquals(actual(weighted), actual(scaled));
        assertTrue(Calculation.actual(weighted).partial());
    }

    @Test void emptyAndAllUnknownHaveNoActualResultButFreeOnlyCategoryParticipates() {
        Category a = category("Tugas", "45");
        Category b = category("Ujian", "45");
        Category empty = category("Kosong", "10");
        Assessment free = row(b, "100", null);
        Course unknown = course(Aggregation.TOTAL_POINTS, List.of(a, b, empty), List.of(free));
        assertNull(actual(unknown.withAssessments(List.of())));
        assertNull(actual(unknown));
        assertEquals(num("188.89"), Calculation.project(new Simulation(unknown)).suggestions().get(free.id()));

        Course mixed = unknown.withAssessments(List.of(row(a, "100", "100"), free));
        assertEquals(Exact.HUNDRED, actual(mixed));
        assertEquals(num("88.89"), Calculation.project(new Simulation(mixed)).suggestions().get(free.id()));
    }

    @Test void allExcludedIsEmpty() {
        Category category = category("Umum", "100");
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
        Category category = category("Umum", "100");
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
        Category category = category("Umum", "100");
        assertEquals(Grade.A_MINUS, Grade.classify(actual(totalCourse(category, List.of(row(category, "100", "84.5"))))));
        assertEquals(Grade.A_MINUS, Grade.classify(Exact.of(num("84.9999999999999999999"))));
        for (Grade grade : Grade.values()) {
            assertEquals(grade, Grade.classify(Exact.of(grade.boundary)));
        }
    }

    @Test void targetOf821Requires7365UnderTotalPoints() {
        Category category = category("Umum", "100");
        Assessment free = row(category, "100", null);
        Simulation simulation = new Simulation(totalCourse(category, List.of(row(category, "50", "49.5"), free)));
        simulation.targetInput("82,1");
        var result = Calculation.project(simulation);
        assertEquals(num("73.65"), result.suggestions().get(free.id()));
        assertEquals(Exact.of(num("82.1")), result.percent());
    }

    @Test void suggestionsShareAnExactPercentageBeforeCeilingAndProjectionUsesDisplayedScores() {
        Category small = category("Kuis", "60");
        Category large = category("Ujian", "40");
        Assessment fixed = row(small, "7", "3");
        Assessment freeSmall = row(small, "3", null);
        Assessment freeLarge = row(large, "9", null);
        Course course = course(Aggregation.EQUAL_PERCENTAGES, List.of(small, large), List.of(fixed, freeSmall, freeLarge));
        Simulation simulation = new Simulation(course);
        simulation.targetInput("71.23");

        // The first category gives each row 30% of the course; the second gives its only row 40%.
        var result = Calculation.project(simulation);
        Exact common = Exact.of(num("71.23"))
                .subtract(Exact.of(300).divide(Exact.of(7)).multiply(Exact.of(num("0.3"))))
                .divide(Exact.of(num("0.7")));
        assertEquals(common, result.commonPercent());
        assertEquals(num("2.51"), result.suggestions().get(freeSmall.id()));
        assertEquals(num("7.51"), result.suggestions().get(freeLarge.id()));
        Exact independent = Exact.of(3).divide(Exact.of(7)).multiply(Exact.of(30))
                .add(Exact.of(num("2.51")).divide(Exact.of(3)).multiply(Exact.of(30)))
                .add(Exact.of(num("7.51")).divide(Exact.of(9)).multiply(Exact.of(40)));
        assertEquals(independent, result.percent());
        assertTrue(result.percent().compareTo(Exact.of(num("71.23"))) >= 0);
    }

    @Test void securedNoFreeAndAboveMaximumHaveDistinctOutcomes() {
        Category category = category("Umum", "100");
        Assessment fixed = row(category, "100", "100");
        Assessment free = row(category, "100", null);
        Simulation simulation = new Simulation(totalCourse(category, List.of(fixed, free)));
        simulation.targetInput("40");
        assertEquals(SECURED, Calculation.project(simulation).status());
        assertEquals(num("0.00"), Calculation.project(simulation).suggestions().get(free.id()));
        simulation.targetInput("105.125");
        var high = Calculation.project(simulation);
        assertEquals(num("110.25"), high.suggestions().get(free.id()));
        assertEquals(UNREACHABLE, high.status());
        simulation.setExcluded(free.id(), true);
        assertEquals(UNREACHABLE, Calculation.project(simulation).status());
        assertEquals(Exact.HUNDRED, Calculation.project(simulation).percent());
        simulation.targetInput("99");
        assertEquals(SECURED, Calculation.project(simulation).status());
        assertTrue(Calculation.project(simulation).suggestions().isEmpty());
    }

    @Test void ceilingUsesExactFractionalMaximumWhenNecessary() {
        Category category = category("Umum", "100");
        Assessment free = row(category, "0.001", null);
        var result = Calculation.project(new Simulation(totalCourse(category, List.of(free))));
        assertEquals(num("0.001"), result.suggestions().get(free.id()));
        assertEquals(WITH_SUGGESTIONS, result.status());
        assertTrue(result.excess().isEmpty());
    }

    @Test void bothMethodsMaintainConsistencyAfterEditsAndTargetChanges() {
        Category category = category("Umum", "100");
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

    @Test void independentlyVerifiedMaximumShortfallAndRawExcess() {
        Category tasks = category("Tugas", "50");
        Category midterm = category("UTS", "25");
        Category finalExam = category("UAS", "25");
        Assessment free = row(finalExam, "100", null);
        for (Aggregation method : Aggregation.values()) {
            var simulation = new Simulation(course(method, List.of(tasks, midterm, finalExam),
                    List.of(row(tasks, "100", "80"), row(midterm, "100", "45"), free)));
            var result = Calculation.project(simulation);
            assertEquals(UNREACHABLE, result.status());
            assertEquals(Exact.of(num("51.25")), result.fixedContribution());
            assertEquals(Exact.of(num("76.25")), result.maximum());
            assertEquals(Exact.of(num("8.75")), result.shortfall());
            assertEquals(num("135.00"), result.suggestions().get(free.id()));
            assertEquals(0, num("35").compareTo(result.excess().get(free.id())));

            simulation.targetInput("76,25");
            var boundary = Calculation.project(simulation);
            assertEquals(WITH_SUGGESTIONS, boundary.status());
            assertEquals(boundary.maximum(), boundary.percent());
            assertTrue(boundary.excess().isEmpty());
            simulation.targetInput("76,2500000000000001");
            assertEquals(UNREACHABLE, Calculation.project(simulation).status());
            assertEquals("<0,000001", ResultText.value(Calculation.project(simulation).shortfall()));
        }
    }

    @Test void partialAverageContributionAndRepresentedWeightAreSeparate() {
        Category midterm = category("UTS", "30");
        Category finalExam = category("UAS", "70");
        Course course = course(Aggregation.TOTAL_POINTS, List.of(midterm, finalExam),
                List.of(row(midterm, "100", "80")));
        var actual = Calculation.actual(course);
        assertTrue(actual.partial());
        assertEquals(Exact.of(80), actual.percent());
        assertEquals(Exact.of(24), actual.contribution());
        assertEquals(Exact.of(30), actual.represented());
        assertEquals(new Calculation.CategoryContribution("UTS", Exact.of(24), Exact.of(30)), actual.details().getFirst());
        assertEquals(Exact.of(24), Calculation.project(new Simulation(course)).maximum());
    }

    @Test void recordedBonusAndHypotheticalAboveMaximumUseDistinctProvenance() {
        Category category = category("Umum", "100");
        Assessment actual = row(category, "100", "110");
        Assessment missing = row(category, "100", null);
        var simulation = new Simulation(totalCourse(category, List.of(actual, missing)));
        assertEquals(Exact.of(105), Calculation.project(simulation).maximum());
        assertTrue(Calculation.project(simulation).aboveMaximumAssumptions().isEmpty());
        var hypothesis = new Assessment(missing.id(), "UAS", category.id(), num("100"), num("115"));
        simulation.put(hypothesis, true);
        assertEquals(List.of("UAS"), Calculation.project(simulation).aboveMaximumAssumptions());
        assertEquals(SECURED, Calculation.project(simulation).status());
        simulation.setExcluded(hypothesis.id(), true);
        assertTrue(Calculation.project(simulation).aboveMaximumAssumptions().isEmpty());
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
