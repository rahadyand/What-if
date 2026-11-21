package id.whatif;

import id.whatif.Course.Assessment;
import id.whatif.Course.Category;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Versioned snapshot of inputs only. SQLite owns scenario metadata and the atomic write. */
final class ScenarioSnapshot {
    private static final int VERSION = 2;

    private ScenarioSnapshot() {}

    static byte[] write(Simulation.State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            Course baseline = state.baseline();
            output.writeInt(VERSION);
            text(output, state.target());
            text(output, baseline.id().toString());
            text(output, baseline.name());
            text(output, baseline.method().name());
            output.writeBoolean(baseline.legacyWeights());
            text(output, state.scale().maximum().toPlainString());
            output.writeInt(state.scale().grades().size());
            for (Grade grade : state.scale().grades()) {
                text(output, grade.letter());
                text(output, grade.boundary().toPlainString());
                text(output, grade.point().toPlainString());
            }
            output.writeInt(baseline.categories().size());
            for (Category category : baseline.categories()) {
                text(output, category.id().toString());
                text(output, category.name());
                text(output, category.weight().toPlainString());
            }
            output.writeInt(baseline.assessments().size());
            for (Assessment row : baseline.assessments()) assessment(output, row);
            output.writeInt(state.rows().size());
            for (Simulation.Row row : state.rows()) {
                assessment(output, row.value());
                output.writeBoolean(row.assumption());
                output.writeBoolean(row.excluded());
            }
        }
        return bytes.toByteArray();
    }

    static Simulation.State read(byte[] bytes) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = input.readInt();
            if (version != 1 && version != VERSION) throw new IOException("Versi snapshot skenario tidak didukung.");
            String target = text(input);
            UUID id = UUID.fromString(text(input));
            String name = text(input);
            Aggregation method = Aggregation.valueOf(text(input));
            boolean legacy = input.readBoolean();
            GradeScale scale = version == 1 ? GradeScale.DEFAULT : scale(input);
            List<Category> categories = new ArrayList<>();
            int count = count(input);
            for (int index = 0; index < count; index++) {
                categories.add(new Category(UUID.fromString(text(input)), text(input), new BigDecimal(text(input))));
            }
            List<Assessment> actual = new ArrayList<>();
            var originals = new HashMap<UUID, Assessment>();
            count = count(input);
            for (int index = 0; index < count; index++) {
                Assessment assessment = assessment(input);
                actual.add(assessment);
                originals.put(assessment.id(), assessment);
            }
            Course baseline = new Course(id, name, method, categories, actual, legacy);
            List<Simulation.Row> rows = new ArrayList<>();
            var seen = new HashSet<UUID>();
            count = count(input);
            for (int index = 0; index < count; index++) {
                Assessment assessment = assessment(input);
                if (!seen.add(assessment.id())) throw new IOException("Identitas penilaian skenario duplikat.");
                boolean assumption = input.readBoolean();
                boolean excluded = input.readBoolean();
                rows.add(new Simulation.Row(assessment, originals.get(assessment.id()), excluded, assumption));
            }
            if (!seen.containsAll(originals.keySet()) || input.available() != 0) {
                throw new IOException("Snapshot skenario tidak lengkap.");
            }
            Simulation.State state = new Simulation.State(baseline, target, rows, scale);
            Simulation.from(state).target();
            return state;
        }
    }

    private static GradeScale scale(DataInputStream input) throws IOException {
        BigDecimal maximum = new BigDecimal(text(input));
        List<Grade> grades = new ArrayList<>();
        int count = count(input);
        for (int index = 0; index < count; index++) {
            grades.add(new Grade(text(input), new BigDecimal(text(input)), new BigDecimal(text(input))));
        }
        return new GradeScale(maximum, grades);
    }

    private static void assessment(DataOutputStream out, Assessment row) throws IOException {
        text(out, row.id().toString());
        text(out, row.name());
        text(out, row.categoryId().toString());
        text(out, row.maximum().toPlainString());
        text(out, row.earned() == null ? "" : row.earned().toPlainString());
    }

    private static Assessment assessment(DataInputStream in) throws IOException {
        UUID id = UUID.fromString(text(in));
        String name = text(in);
        UUID category = UUID.fromString(text(in));
        BigDecimal maximum = new BigDecimal(text(in));
        String earned = text(in);
        return new Assessment(id, name, category, maximum, earned.isEmpty() ? null : new BigDecimal(earned));
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in) throws IOException {
        return new String(in.readNBytes(count(in)), StandardCharsets.UTF_8);
    }

    private static int count(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > in.available()) throw new IOException("Ukuran snapshot tidak sah.");
        return size;
    }
}
