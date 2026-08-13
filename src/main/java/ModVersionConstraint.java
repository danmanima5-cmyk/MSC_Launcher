import java.util.ArrayList;
import java.util.List;

/** Evaluates the version predicates used by Fabric/Quilt manifests. */
final class ModVersionConstraint {
    private ModVersionConstraint() {
    }

    static boolean matches(String version, String predicate) {
        if (predicate == null || predicate.isBlank() || "*".equals(predicate.trim())) {
            return true;
        }
        for (String alternative : predicate.split("\\|\\|")) {
            if (matchesAll(version, alternative.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAll(String version, String alternative) {
        if (alternative.isBlank() || "*".equals(alternative)) {
            return true;
        }
        String normalized = alternative.trim();
        if (normalized.startsWith("[") || normalized.startsWith("(")) {
            return matchesMavenRange(version, normalized);
        }
        normalized = normalized.replace(",", " ");
        for (String term : normalized.split("\\s+")) {
            if (!matchesTerm(version, term)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesMavenRange(String version, String range) {
        if (range.length() < 2 || !(range.endsWith("]") || range.endsWith(")"))) {
            return false;
        }
        String[] bounds = range.substring(1, range.length() - 1).split(",", -1);
        if (bounds.length != 2) {
            return false;
        }
        int lower = bounds[0].isBlank() ? 1 : compare(version, bounds[0].trim());
        int upper = bounds[1].isBlank() ? -1 : compare(version, bounds[1].trim());
        return (range.startsWith("[") ? lower >= 0 : lower > 0)
                && (range.endsWith("]") ? upper <= 0 : upper < 0);
    }

    private static boolean matchesTerm(String version, String rawTerm) {
        if (rawTerm.isBlank() || "*".equals(rawTerm)) {
            return true;
        }
        String operator = "";
        String expected = rawTerm;
        for (String candidate : List.of(">=", "<=", ">", "<", "=", "~", "^")) {
            if (rawTerm.startsWith(candidate)) {
                operator = candidate;
                expected = rawTerm.substring(candidate.length());
                break;
            }
        }
        if (expected.isBlank()) {
            return false;
        }
        if (expected.contains("*") || expected.toLowerCase(java.util.Locale.ROOT).contains("x")) {
            String prefix = expected.replaceAll("(?i)[*x].*$", "").replaceAll("\\.$", "");
            return normalized(version).startsWith(prefix.isBlank() ? "" : prefix + ".");
        }
        int comparison = compare(version, expected);
        return switch (operator) {
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            case "~" -> comparison >= 0 && samePrefix(version, expected, 1);
            case "^" -> comparison >= 0 && samePrefix(version, expected, caretPrefixLength(expected));
            default -> comparison == 0;
        };
    }

    private static int caretPrefixLength(String version) {
        List<Integer> parts = numericParts(version);
        for (int index = 0; index < parts.size(); index++) {
            if (parts.get(index) != 0) {
                return index;
            }
        }
        return Math.max(0, parts.size() - 1);
    }

    private static boolean samePrefix(String left, String right, int lastIndex) {
        List<Integer> a = numericParts(left);
        List<Integer> b = numericParts(right);
        for (int index = 0; index <= lastIndex; index++) {
            if (part(a, index) != part(b, index)) {
                return false;
            }
        }
        return true;
    }

    private static int compare(String left, String right) {
        List<Integer> a = numericParts(left);
        List<Integer> b = numericParts(right);
        int count = Math.max(a.size(), b.size());
        for (int index = 0; index < count; index++) {
            int difference = Integer.compare(part(a, index), part(b, index));
            if (difference != 0) {
                return difference;
            }
        }
        // Build metadata (for example Fabric API's "+1.21.11") does not make
        // a dependency on the same base API version incompatible.
        return 0;
    }

    private static int part(List<Integer> values, int index) {
        return index < values.size() ? values.get(index) : 0;
    }

    private static List<Integer> numericParts(String version) {
        String base = normalized(version).split("[+\\-]", 2)[0];
        ArrayList<Integer> values = new ArrayList<>();
        for (String token : base.split("\\.")) {
            String digits = token.replaceFirst("^([^0-9]*)([0-9]+).*$", "$2");
            try {
                values.add(Integer.parseInt(digits));
            } catch (NumberFormatException ex) {
                values.add(0);
            }
        }
        return values;
    }

    private static String normalized(String version) {
        return version == null ? "" : version.trim().replaceFirst("^[vV]", "");
    }
}
