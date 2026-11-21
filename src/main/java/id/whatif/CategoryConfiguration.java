package id.whatif;

import id.whatif.Course.Category;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class CategoryConfiguration {
    static final List<String> PRESETS = List.of("UTS", "UAS", "Tugas", "Projek", "Praktikum");

    private CategoryConfiguration() {}

    static Review review(List<Entry> entries) {
        List<Category> categories = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean invalidWeights = false;
        String invalidName = null;
        for (Entry entry : entries) {
            if (!entry.active()) continue;
            BigDecimal weight;
            try {
                weight = Numbers.read(entry.weight(), "Persentase", false, true);
            } catch (IllegalArgumentException invalid) {
                invalidWeights = true;
                continue;
            }
            total = total.add(weight);
            try {
                categories.add(new Category(entry.id(), entry.name(), weight));
            } catch (IllegalArgumentException invalid) {
                invalidName = invalid.getMessage();
            }
        }
        BigDecimal remaining = Course.FULL_WEIGHT.subtract(total);
        String error = invalidName;
        if (invalidWeights) error = "Isi persentase kategori aktif dengan angka lebih dari nol.";
        else if (entries.stream().noneMatch(Entry::active)) error = "Aktifkan minimal satu kategori.";
        else if (remaining.signum() != 0) error = "Jumlah persentase kategori aktif wajib tepat 100%.";
        return new Review(List.copyOf(categories), total, remaining, invalidWeights, error);
    }

    record Entry(UUID id, String name, String weight, boolean active) {}

    record Review(List<Category> categories, BigDecimal total, BigDecimal remaining, boolean invalidWeights,
                  String error) {
        List<Category> validatedCategories() {
            if (error != null) throw new IllegalArgumentException(error);
            return categories;
        }
    }
}
