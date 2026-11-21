package id.whatif;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record Course(UUID id, String name, Aggregation method, List<Category> categories, List<Assessment> assessments,
              boolean legacyWeights) {
    static final BigDecimal FULL_WEIGHT = BigDecimal.valueOf(100);

    Course(UUID id, String name, Aggregation method, List<Category> categories, List<Assessment> assessments) {
        this(id, name, method, categories, assessments, false);
    }

    static BigDecimal totalWeight(List<Category> categories) {
        return categories.stream().map(Category::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    Course {
        Objects.requireNonNull(id);
        name = requiredName(name, "Nama mata kuliah");
        Objects.requireNonNull(method);
        categories = List.copyOf(categories);
        assessments = List.copyOf(assessments);

        if (categories.isEmpty()) throw new IllegalArgumentException("Sisakan minimal satu kategori.");
        if (!legacyWeights && totalWeight(categories).compareTo(FULL_WEIGHT) != 0) {
            throw new IllegalArgumentException("Jumlah persentase kategori wajib tepat 100%.");
        }
        var categoryIds = new HashSet<UUID>();
        for (Category category : categories) {
            if (!categoryIds.add(category.id())) throw new IllegalArgumentException("Kategori duplikat.");
        }
        var assessmentIds = new HashSet<UUID>();
        for (Assessment assessment : assessments) {
            if (!categoryIds.contains(assessment.categoryId())) {
                throw new IllegalArgumentException("Pilih kategori yang tersedia.");
            }
            if (!assessmentIds.add(assessment.id())) throw new IllegalArgumentException("Penilaian duplikat.");
        }
    }

    static Course create(String name, Aggregation method) {
        return new Course(UUID.randomUUID(), name, method,
                List.of(new Category(UUID.randomUUID(), "Umum", FULL_WEIGHT)), List.of());
    }

    Course withSettings(String name, Aggregation method) {
        return new Course(id, name, method, categories, assessments, legacyWeights);
    }

    Course withCategories(List<Category> categories) {
        return new Course(id, name, method, categories, assessments);
    }

    Course withAssessments(List<Assessment> assessments) {
        return new Course(id, name, method, categories, assessments, legacyWeights);
    }

    String categoryName(UUID id) {
        return categories.stream().filter(category -> category.id().equals(id)).findFirst().orElseThrow().name();
    }

    private static String requiredName(String text, String field) {
        String name = Objects.requireNonNull(text).strip();
        if (name.isEmpty()) throw new IllegalArgumentException(field + " wajib diisi.");
        return name;
    }

    record Category(UUID id, String name, BigDecimal weight) {
        Category {
            Objects.requireNonNull(id);
            name = requiredName(name, "Nama kategori");
            weight = Numbers.validate(weight, "Bobot", true);
        }

        @Override public String toString() { return name; }
    }

    record Assessment(UUID id, String name, UUID categoryId, BigDecimal maximum, BigDecimal earned) {
        Assessment {
            Objects.requireNonNull(id);
            Objects.requireNonNull(categoryId);
            name = requiredName(name, "Nama penilaian");
            maximum = Numbers.validate(maximum, "Nilai maksimum", true);
            if (earned != null) earned = Numbers.validate(earned, "Nilai diperoleh", false);
        }
    }
}
