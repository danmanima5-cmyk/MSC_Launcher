import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure helpers for preparing mod-catalog queries and ranking typo-tolerant
 * suggestions. The class deliberately has no Swing or network dependencies.
 */
final class ModSearchMatcher {
    private static final Set<String> API_FILLER_WORDS = Set.of(
            "mod", "mods", "minecraft", "мод", "моды", "для");

    private static final int NO_MATCH = Integer.MIN_VALUE;
    private static final int MAX_FUZZY_CODE_POINTS = 128;

    private ModSearchMatcher() {
    }

    /**
     * Normalizes text for comparisons while preserving every meaningful word,
     * including words such as "mod" and "minecraft".
     */
    static String normalizeForMatch(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String decomposed = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        StringBuilder result = new StringBuilder(decomposed.length());
        boolean separatorPending = false;
        for (int offset = 0; offset < decomposed.length();) {
            int codePoint = decomposed.codePointAt(offset);
            offset += Character.charCount(codePoint);

            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }

            if (Character.isLetterOrDigit(codePoint)) {
                if (separatorPending && result.length() > 0) {
                    result.append(' ');
                }
                result.appendCodePoint(codePoint);
                separatorPending = false;
            } else if (result.length() > 0) {
                separatorPending = true;
            }
        }
        return result.toString();
    }

    /**
     * Produces the compact query sent to a catalog API. Unlike
     * {@link #normalizeForMatch(String)}, this removes whole filler tokens.
     */
    static String cleanForSearch(String value) {
        String normalized = normalizeForMatch(value);
        if (normalized.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder(normalized.length());
        for (String token : normalized.split(" ")) {
            if (token.isEmpty() || API_FILLER_WORDS.contains(token)) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(token);
        }
        return result.toString();
    }

    /**
     * Full Damerau-Levenshtein distance after match normalization. Insertions,
     * deletions, substitutions and adjacent transpositions each cost one.
     */
    static int damerauLevenshteinDistance(String first, String second) {
        int[] left = normalizeForMatch(first).codePoints().toArray();
        int[] right = normalizeForMatch(second).codePoints().toArray();
        if (left.length == 0) {
            return right.length;
        }
        if (right.length == 0) {
            return left.length;
        }

        int infinity = left.length + right.length;
        int[][] distance = new int[left.length + 2][right.length + 2];
        distance[0][0] = infinity;
        for (int i = 0; i <= left.length; i++) {
            distance[i + 1][0] = infinity;
            distance[i + 1][1] = i;
        }
        for (int j = 0; j <= right.length; j++) {
            distance[0][j + 1] = infinity;
            distance[1][j + 1] = j;
        }

        Map<Integer, Integer> lastRowByCodePoint = new HashMap<>();
        for (int i = 1; i <= left.length; i++) {
            int lastMatchingColumn = 0;
            for (int j = 1; j <= right.length; j++) {
                int matchingRow = lastRowByCodePoint.getOrDefault(right[j - 1], 0);
                int matchingColumn = lastMatchingColumn;
                int substitutionCost = 1;
                if (left[i - 1] == right[j - 1]) {
                    substitutionCost = 0;
                    lastMatchingColumn = j;
                }

                int substitution = distance[i][j] + substitutionCost;
                int insertion = distance[i + 1][j] + 1;
                int deletion = distance[i][j + 1] + 1;
                int transposition = distance[matchingRow][matchingColumn]
                        + (i - matchingRow - 1) + 1 + (j - matchingColumn - 1);
                distance[i + 1][j + 1] = Math.min(
                        Math.min(substitution, insertion),
                        Math.min(deletion, transposition));
            }
            lastRowByCodePoint.put(left[i - 1], i);
        }
        return distance[left.length + 1][right.length + 1];
    }

    /** Returns matching projects ordered by match quality and then downloads. */
    static List<ModrinthProject> rank(String query, List<ModrinthProject> candidates, int limit) {
        String normalizedQuery = normalizeForMatch(query);
        if (normalizedQuery.isEmpty() || candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }

        ArrayList<RankedProject> ranked = new ArrayList<>();
        int ordinal = 0;
        for (ModrinthProject project : candidates) {
            if (project != null) {
                int score = projectScore(normalizedQuery, project);
                if (score != NO_MATCH) {
                    ranked.add(new RankedProject(project, score, ordinal));
                }
            }
            ordinal++;
        }

        ranked.sort(Comparator
                .comparingInt(RankedProject::score).reversed()
                .thenComparing(Comparator.comparingLong(
                        (RankedProject value) -> value.project().downloads()).reversed())
                .thenComparing(value -> normalizeForMatch(value.project().title()))
                .thenComparing(value -> safe(value.project().id()))
                .thenComparingInt(RankedProject::ordinal));

        ArrayList<ModrinthProject> result = new ArrayList<>(Math.min(limit, ranked.size()));
        HashSet<String> seenProjects = new HashSet<>();
        for (RankedProject value : ranked) {
            String key = projectKey(value.project());
            if (!seenProjects.add(key)) {
                continue;
            }
            result.add(value.project());
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /** Project-valued suggestion API used by the catalog UI. */
    static List<ModrinthProject> suggestProjects(String query, List<ModrinthProject> candidates, int limit) {
        return rank(query, candidates, limit);
    }

    /**
     * Autocomplete variant which still offers the nearest known projects when
     * the input contains too many mistakes for the conservative match filter.
     */
    static List<ModrinthProject> suggestProjectsWithFallback(
            String query, List<ModrinthProject> candidates, int limit) {
        List<ModrinthProject> matches = rank(query, candidates, limit);
        String normalizedQuery = normalizeForMatch(query);
        if (!matches.isEmpty() || normalizedQuery.isEmpty()
                || candidates == null || candidates.isEmpty() || limit <= 0) {
            return matches;
        }

        ArrayList<ModrinthProject> nearest = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (ModrinthProject project : candidates) {
            if (project != null && seen.add(projectKey(project))
                    && isPlausibleFallback(normalizedQuery, project)) {
                nearest.add(project);
            }
        }
        nearest.sort(Comparator
                .comparingInt((ModrinthProject project) -> nearestDistance(normalizedQuery, project))
                .thenComparing(Comparator.comparingLong(ModrinthProject::downloads).reversed())
                .thenComparing(project -> normalizeForMatch(project.title())));
        return List.copyOf(nearest.subList(0, Math.min(limit, nearest.size())));
    }

    /**
     * The autocomplete fallback is intentionally a little more forgiving than
     * the normal matcher, but it must never turn into a list of arbitrary
     * popular projects. For example, a two-letter typo in a six-letter name is
     * useful; the nearest completely unrelated title is not.
     */
    private static boolean isPlausibleFallback(String query, ModrinthProject project) {
        int distance = nearestDistance(query, project);
        int queryLength = codePointLength(query);
        int allowedDistance = Math.min(3, maxFuzzyDistance(queryLength) + 1);
        return allowedDistance > 0 && distance <= allowedDistance
                && distance * 2 <= Math.max(1, queryLength);
    }

    /** Backward-compatible project-valued shorthand. */
    static List<ModrinthProject> suggest(String query, List<ModrinthProject> candidates, int limit) {
        return suggestProjects(query, candidates, limit);
    }

    /** Convenience wrapper for controls which need only unique display titles. */
    static List<String> suggestTitles(String query, List<ModrinthProject> candidates, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ModrinthProject> projects = suggestProjects(query, candidates,
                candidates == null ? 0 : candidates.size());
        ArrayList<String> titles = new ArrayList<>(Math.min(limit, projects.size()));
        HashSet<String> seenTitles = new HashSet<>();
        for (ModrinthProject project : projects) {
            String title = safe(project.title()).trim();
            String key = normalizeForMatch(title);
            if (title.isEmpty() || key.isEmpty() || !seenTitles.add(key)) {
                continue;
            }
            titles.add(title);
            if (titles.size() >= limit) {
                break;
            }
        }
        return List.copyOf(titles);
    }

    /**
     * Returns whether at least one candidate is a conservative exact, prefix,
     * token or fuzzy match. Two-character prefixes are suggestions but are not
     * considered confident enough to suppress a broader fallback search.
     */
    static boolean hasConfidentMatch(String query, List<ModrinthProject> candidates) {
        String normalizedQuery = normalizeForMatch(query);
        if (normalizedQuery.isEmpty() || candidates == null || candidates.isEmpty()) {
            return false;
        }

        int queryLength = codePointLength(normalizedQuery);
        for (ModrinthProject project : candidates) {
            if (project == null || projectScore(normalizedQuery, project) == NO_MATCH) {
                continue;
            }
            if (queryLength >= 3 || hasExactMatch(normalizedQuery, project)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true only for a close typo of the complete title or slug. */
    static boolean isLikelyCorrection(String query, ModrinthProject project) {
        if (project == null) {
            return false;
        }
        String normalizedQuery = normalizeForMatch(query);
        if (normalizedQuery.isEmpty()) {
            return false;
        }
        for (String value : List.of(safe(project.title()), safe(project.slug()))) {
            String candidate = normalizeForMatch(value);
            if (!candidate.isEmpty() && !candidate.equals(normalizedQuery)
                    && acceptedFuzzyDistance(normalizedQuery, candidate) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExactMatch(String query, ModrinthProject project) {
        for (String value : List.of(safe(project.title()), safe(project.slug()), safe(project.author()))) {
            String normalized = normalizeForMatch(value);
            if (query.equals(normalized)) {
                return true;
            }
            for (String token : tokens(normalized)) {
                if (query.equals(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int projectScore(String query, ModrinthProject project) {
        int titleScore = fieldScore(query, normalizeForMatch(project.title()), 0, true);
        int slugScore = fieldScore(query, normalizeForMatch(project.slug()), 15_000, true);
        int authorScore = fieldScore(query, normalizeForMatch(project.author()), 180_000, false);
        return Math.max(titleScore, Math.max(slugScore, authorScore));
    }

    private static int nearestDistance(String query, ModrinthProject project) {
        int best = Integer.MAX_VALUE;
        for (String value : List.of(safe(project.title()), safe(project.slug()), safe(project.author()))) {
            String normalized = normalizeForMatch(value);
            if (normalized.isEmpty()) {
                continue;
            }
            best = Math.min(best, damerauLevenshteinDistance(query, normalized));
            for (String token : tokens(normalized)) {
                best = Math.min(best, damerauLevenshteinDistance(query, token));
            }
        }
        return best;
    }

    private static int fieldScore(String query, String candidate, int fieldPenalty, boolean allowFuzzy) {
        if (candidate.isEmpty()) {
            return NO_MATCH;
        }
        int queryLength = codePointLength(query);
        int candidateLength = codePointLength(candidate);
        int lengthDelta = Math.abs(candidateLength - queryLength);
        int best = NO_MATCH;

        if (candidate.equals(query)) {
            best = Math.max(best, 1_000_000 - fieldPenalty);
        }
        if (candidate.startsWith(query)) {
            best = Math.max(best, 900_000 - fieldPenalty - Math.min(40_000, lengthDelta * 200));
        }
        if (containsWholePhrase(candidate, query)) {
            best = Math.max(best, 870_000 - fieldPenalty - Math.min(30_000, lengthDelta * 150));
        }

        String[] candidateTokens = tokens(candidate);
        if (query.indexOf(' ') < 0) {
            for (String token : candidateTokens) {
                if (token.equals(query)) {
                    best = Math.max(best, 840_000 - fieldPenalty);
                } else if (token.startsWith(query)) {
                    int delta = codePointLength(token) - queryLength;
                    best = Math.max(best, 810_000 - fieldPenalty - Math.min(30_000, delta * 200));
                }
            }
        }

        if (!allowFuzzy || queryLength > MAX_FUZZY_CODE_POINTS
                || candidateLength > MAX_FUZZY_CODE_POINTS) {
            return best;
        }

        int wholeDistance = acceptedFuzzyDistance(query, candidate);
        if (wholeDistance >= 0) {
            best = Math.max(best, 760_000 - fieldPenalty
                    - wholeDistance * 20_000 - Math.min(20_000, lengthDelta * 100));
        }

        String[] queryTokens = tokens(query);
        if (candidateTokens.length >= queryTokens.length) {
            for (int start = 0; start + queryTokens.length <= candidateTokens.length; start++) {
                String window = join(candidateTokens, start, queryTokens.length);
                int windowDistance = acceptedFuzzyDistance(query, window);
                if (windowDistance >= 0) {
                    int windowDelta = Math.abs(codePointLength(window) - queryLength);
                    best = Math.max(best, 700_000 - fieldPenalty
                            - windowDistance * 20_000 - Math.min(20_000, windowDelta * 100));
                }
            }
        }
        return best;
    }

    private static int acceptedFuzzyDistance(String query, String candidate) {
        int queryLength = codePointLength(query);
        int candidateLength = codePointLength(candidate);
        int maxDistance = maxFuzzyDistance(queryLength);
        if (maxDistance == 0 || Math.abs(queryLength - candidateLength) > maxDistance) {
            return -1;
        }
        int distance = damerauLevenshteinDistance(query, candidate);
        int longest = Math.max(queryLength, candidateLength);
        return distance <= maxDistance && distance * 4 <= longest ? distance : -1;
    }

    private static int maxFuzzyDistance(int queryLength) {
        if (queryLength <= 3) {
            return 0;
        }
        if (queryLength <= 7) {
            return 1;
        }
        if (queryLength <= 12) {
            return 2;
        }
        return 3;
    }

    private static boolean containsWholePhrase(String candidate, String query) {
        return (" " + candidate + " ").contains(" " + query + " ");
    }

    private static String[] tokens(String normalized) {
        return normalized.isEmpty() ? new String[0] : normalized.split(" ");
    }

    private static String join(String[] values, int start, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < start + count; i++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(values[i]);
        }
        return result.toString();
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String projectKey(ModrinthProject project) {
        String id = normalizeForMatch(project.id());
        if (!id.isEmpty()) {
            return "id:" + id;
        }
        String slug = normalizeForMatch(project.slug());
        if (!slug.isEmpty()) {
            return "slug:" + slug;
        }
        return "title:" + normalizeForMatch(project.title());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record RankedProject(ModrinthProject project, int score, int ordinal) {
    }
}
