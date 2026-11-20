package id.whatif;

import java.math.BigDecimal;

final class Numbers {
    private Numbers() {}

    static BigDecimal read(String text, String field, boolean optional, boolean positive) {
        String value = text.strip();
        if (value.isEmpty()) {
            if (optional) return null;
            throw new IllegalArgumentException(field + " wajib diisi.");
        }
        if (!value.matches("[0-9]+([.,][0-9]+)?")) {
            throw new IllegalArgumentException(field + " harus berupa angka nonnegatif, misalnya 80,2 atau 80.2.");
        }
        return validate(new BigDecimal(value.replace(',', '.')), field, positive);
    }

    static BigDecimal validate(BigDecimal value, String field, boolean positive) {
        if (value == null || value.signum() < 0 || (positive && value.signum() == 0)) {
            throw new IllegalArgumentException(field + (positive ? " harus lebih dari nol." : " harus nonnegatif."));
        }
        return value.stripTrailingZeros();
    }

    static String display(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
