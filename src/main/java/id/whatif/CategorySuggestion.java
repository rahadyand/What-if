package id.whatif;

import id.whatif.Course.Category;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class CategorySuggestion {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final List<String> PROJECT_WORDS = List.of("projek", "project", "proyek");

    private CategorySuggestion() {}

    static List<Category> matches(String assessmentName, List<Category> activeCategories) {
        String name = phrase(assessmentName);
        return activeCategories.stream().filter(category -> {
            String categoryPhrase = phrase(category.name());
            if (categoryPhrase.isEmpty()) return false;
            if (PROJECT_WORDS.contains(categoryPhrase)) {
                return PROJECT_WORDS.stream().anyMatch(word -> containsPhrase(name, word));
            }
            return containsPhrase(name, categoryPhrase);
        }).toList();
    }

    private static boolean containsPhrase(String name, String phrase) {
        return (" " + name + " ").contains(" " + phrase + " ");
    }

    private static String phrase(String value) {
        return WORD.matcher(value.toLowerCase(Locale.ROOT)).results()
                .map(match -> match.group()).collect(Collectors.joining(" "));
    }
}
