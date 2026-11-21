package id.whatif;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

record AcademicDetails(UUID semesterId, Integer sks, Integer present, Integer meetings) {
    static final AcademicDetails EMPTY = new AcademicDetails(null, null, null, null);
    static final String UNGROUPED = "Belum dikelompokkan";

    AcademicDetails {
        if (sks != null && sks <= 0) throw new IllegalArgumentException("SKS harus lebih dari nol.");
        if ((present == null) != (meetings == null)) {
            throw new IllegalArgumentException("Isi kedua angka kehadiran atau kosongkan keduanya.");
        }
        if (present != null && (present < 0 || meetings < 0 || present > meetings)) {
            throw new IllegalArgumentException("Jumlah hadir tidak boleh melebihi pertemuan terlaksana.");
        }
    }

    static AcademicDetails read(UUID semesterId, String sks, String present, String meetings) {
        return new AcademicDetails(semesterId, whole(sks, "SKS"), whole(present, "Jumlah hadir"),
                whole(meetings, "Pertemuan terlaksana"));
    }

    private static Integer whole(String input, String field) {
        String text = input.strip();
        if (text.isEmpty()) return null;
        if (!text.matches("[0-9]+")) throw new IllegalArgumentException(field + " harus berupa bilangan bulat nonnegatif.");
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException tooLarge) {
            throw new IllegalArgumentException(field + " terlalu besar.");
        }
    }

    String sksText() { return sks == null ? "SKS belum dicatat" : sks + " SKS"; }

    String attendanceText() {
        if (meetings == null) return "Belum dicatat";
        if (meetings == 0) return "Belum ada pertemuan";
        BigDecimal percentage = BigDecimal.valueOf(present * 100L).divide(BigDecimal.valueOf(meetings), 2, RoundingMode.HALF_UP);
        return present + "/" + meetings + " (" + Numbers.display(percentage) + "%)";
    }
}
