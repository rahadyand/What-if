package id.whatif;

import id.whatif.Course.Assessment;

import java.util.List;

/** Each method supplies assessment coefficients. Calculation uses them for both fixed and free scores. */
enum Aggregation {
    TOTAL_POINTS("Total poin") {
        @Override List<Exact> shares(List<Assessment> rows) {
            Exact totalMaximum = rows.stream().map(row -> Exact.of(row.maximum()))
                    .reduce(Exact.ZERO, Exact::add);
            return rows.stream().map(row -> Exact.of(row.maximum()).divide(totalMaximum)).toList();
        }
    },
    EQUAL_PERCENTAGES("Rata-rata persentase") {
        @Override List<Exact> shares(List<Assessment> rows) {
            Exact share = Exact.ONE.divide(Exact.of(rows.size()));
            return rows.stream().map(row -> share).toList();
        }
    };

    static final String EXAMPLE = "Untuk 8/10 dan 60/100 dalam satu kategori, total poin adalah 68/110 "
            + "(sekitar 61,82%). Rata-rata persentase memberi setiap penilaian porsi yang sama, "
            + "sehingga (80% + 60%) / 2 = 70%.";

    private final String label;

    Aggregation(String label) {
        this.label = label;
    }

    abstract List<Exact> shares(List<Assessment> rows);

    @Override public String toString() { return label; }
}
