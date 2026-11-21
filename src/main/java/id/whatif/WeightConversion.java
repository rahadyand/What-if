package id.whatif;

import id.whatif.Course.Category;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WeightConversion {
    private WeightConversion() {}

    static Review propose(Course course) {
        Exact total = Exact.of(Course.totalWeight(course.categories()));
        List<Exact> exact = course.categories().stream()
                .map(category -> Exact.of(category.weight()).multiply(Exact.HUNDRED).divide(total)).toList();

        // Keep terminating decimal proportions exactly when possible.
        try {
            List<BigDecimal> values = exact.stream().map(value ->
                    new BigDecimal(value.numerator()).divide(new BigDecimal(value.denominator()))).toList();
            return new Review(apply(course, values), true);
        } catch (ArithmeticException repeating) {
            // Distribute the missing units to the largest remainders. Keep every weight positive.
            int scale = 2;
            while (true) {
                List<BigDecimal> values = new ArrayList<>();
                List<Integer> order = new ArrayList<>();
                BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
                BigDecimal sum = BigDecimal.ZERO;
                for (int index = 0; index < exact.size(); index++) {
                    BigDecimal floor = exact.get(index).decimal(scale, RoundingMode.DOWN);
                    BigDecimal value = floor.max(unit);
                    values.add(value);
                    order.add(index);
                    sum = sum.add(value);
                }
                if (sum.compareTo(Course.FULL_WEIGHT) > 0) {
                    scale++;
                    continue;
                }
                order.sort(Comparator.<Integer, Exact>comparing(index ->
                        exact.get(index).subtract(Exact.of(values.get(index)))).reversed());
                int remaining = Course.FULL_WEIGHT.subtract(sum).movePointRight(scale).intValueExact();
                for (int index : order.subList(0, remaining)) values.set(index, values.get(index).add(unit));
                return new Review(apply(course, values), false);
            }
        }
    }

    private static List<Category> apply(Course course, List<BigDecimal> weights) {
        List<Category> converted = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            Category category = course.categories().get(i);
            converted.add(new Category(category.id(), category.name(), weights.get(i)));
        }
        return List.copyOf(converted);
    }

    record Review(List<Category> categories, boolean exact) {}
}
