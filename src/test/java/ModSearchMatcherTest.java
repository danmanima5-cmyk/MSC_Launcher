import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ModSearchMatcherTest {
    @Test
    void normalizesUnicodeDiacriticsPunctuationAndWhitespaceForMatching() {
        assertEquals("creme brulee мод 12",
                ModSearchMatcher.normalizeForMatch("  Crème—Brûlée__МОД!!!  １２  "));
        assertEquals("cafe sodium",
                ModSearchMatcher.normalizeForMatch("Cafe\u0301\t\nSódium"));
    }

    @Test
    void removesOnlyWholeFillerWordsFromApiQueryVariant() {
        String input = "  Mínecraft — MODS: Sodium+++ для Fábric, мод!  ";

        assertEquals("minecraft mods sodium для fabric мод",
                ModSearchMatcher.normalizeForMatch(input));
        assertEquals("sodium fabric", ModSearchMatcher.cleanForSearch(input));
        assertEquals("", ModSearchMatcher.cleanForSearch("Minecraft mods мод моды для"));
        assertEquals("modular minecraftia модыx",
                ModSearchMatcher.cleanForSearch("modular minecraftia модыx mod"));
    }

    @Test
    void damerauLevenshteinHandlesEverySingleLetterTypo() {
        assertEquals(1, ModSearchMatcher.damerauLevenshteinDistance("sodum", "sodium"));
        assertEquals(1, ModSearchMatcher.damerauLevenshteinDistance("sodiumm", "sodium"));
        assertEquals(1, ModSearchMatcher.damerauLevenshteinDistance("sodxum", "sodium"));
        assertEquals(1, ModSearchMatcher.damerauLevenshteinDistance("sodimu", "sodium"));
        assertEquals(2, ModSearchMatcher.damerauLevenshteinDistance("CA", "ABC"));
    }

    @Test
    void distanceUsesTheSameUnicodeNormalizationAsMatching() {
        assertEquals(0, ModSearchMatcher.damerauLevenshteinDistance("Sódium!", "sodium"));
        assertEquals(1, ModSearchMatcher.damerauLevenshteinDistance("моды", "мода"));
    }

    @Test
    void ranksExactThenPrefixThenFuzzyAndIgnoresUnrelatedProjects() {
        ModrinthProject unrelated = project("iris", "Iris Shaders", "iris-shaders", 50_000_000);
        ModrinthProject fuzzy = project("sodimu", "Sodimu Fork", "sodimu-fork", 30_000_000);
        ModrinthProject prefix = project("extra", "Sodium Extra", "sodium-extra", 20_000_000);
        ModrinthProject exact = project("sodium", "Sodium", "sodium", 1_000);

        List<ModrinthProject> ranked = ModSearchMatcher.rank(
                "sodium", List.of(unrelated, fuzzy, prefix, exact), 10);

        assertEquals(List.of(exact, prefix, fuzzy), ranked);
    }

    @Test
    void suggestsTheIntendedProjectForInsertionDeletionReplacementAndTransposition() {
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 10_000_000);
        ModrinthProject sodiumExtra = project("extra", "Sodium Extra", "sodium-extra", 20_000_000);
        ModrinthProject iris = project("iris", "Iris", "iris", 30_000_000);
        List<ModrinthProject> candidates = List.of(iris, sodiumExtra, sodium);

        for (String typo : List.of("sodum", "sodiumm", "sodxum", "sodimu")) {
            assertEquals(sodium,
                    ModSearchMatcher.suggestProjects(typo, candidates, 3).get(0), typo);
        }
    }

    @Test
    void exactAndPrefixQualityBeatDownloadCount() {
        ModrinthProject exact = project("exact", "Sodium", "sodium", 1);
        ModrinthProject prefix = project("prefix", "Sodium Extra", "sodium-extra", 100_000_000);

        assertEquals(List.of(exact, prefix),
                ModSearchMatcher.suggestProjects("sodium", List.of(prefix, exact), 2));
    }

    @Test
    void suggestsProjectsFromTheFirstLetter() {
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 1_000);
        ModrinthProject iris = project("iris", "Iris", "iris", 2_000);

        assertEquals(List.of(sodium),
                ModSearchMatcher.suggestProjects("s", List.of(iris, sodium), 5));
    }

    @Test
    void returnsEveryAvailableTwoLetterPrefixInsteadOfOnlyPopularProjects() {
        ModrinthProject create = project("create", "Create", "create", 10);
        ModrinthProject craftTweaker = project("craft-tweaker", "CraftTweaker", "craft-tweaker", 20);
        ModrinthProject croptopia = project("croptopia", "Croptopia", "croptopia", 30);
        ModrinthProject unrelatedPopular = project("sodium", "Sodium", "sodium", 100_000_000);

        assertEquals(List.of(create, croptopia, craftTweaker),
                ModSearchMatcher.suggestProjectsWithFallback("Cr",
                        List.of(unrelatedPopular, create, craftTweaker, croptopia), 80));
    }

    @Test
    void downloadsBreakTiesAfterMatchQuality() {
        ModrinthProject lessPopular = project("one", "Sodium", "sodium-one", 100);
        ModrinthProject morePopular = project("two", "Sodium", "sodium-two", 1_000);

        assertEquals(List.of(morePopular, lessPopular),
                ModSearchMatcher.rank("sodium", List.of(lessPopular, morePopular), 2));
    }

    @Test
    void respectsLimitAndDeduplicatesRepeatedCatalogEntries() {
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 1_000);
        ModrinthProject duplicate = project("sodium", "Sodium duplicate", "sodium", 900);
        ModrinthProject extra = project("extra", "Sodium Extra", "sodium-extra", 800);
        ModrinthProject options = project("options", "Sodium Options", "sodium-options", 700);

        assertEquals(List.of(sodium, extra), ModSearchMatcher.suggestProjects(
                "sodium", List.of(duplicate, options, extra, sodium), 2));
        assertEquals(List.of("Sodium", "Sodium Extra"), ModSearchMatcher.suggestTitles(
                "sodium", List.of(duplicate, options, extra, sodium), 2));
    }

    @Test
    void conservativeThresholdRejectsShortAndDistantFalseMatches() {
        ModrinthProject car = project("car", "Car", "car", 1_000_000);
        ModrinthProject stadium = project("stadium", "Stadium", "stadium", 1_000_000);
        ModrinthProject unrelated = project("unrelated", "Completely Different", "different", 1_000_000);

        assertTrue(ModSearchMatcher.suggestProjects("cat", List.of(car), 5).isEmpty());
        assertTrue(ModSearchMatcher.suggestProjects("sodium", List.of(stadium, unrelated), 5).isEmpty());
        assertFalse(ModSearchMatcher.hasConfidentMatch("cat", List.of(car)));
    }

    @Test
    void autocompleteFallsBackToNearestProjectsForBadlyMisspelledInput() {
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 100);
        ModrinthProject iris = project("iris", "Iris Shaders", "iris-shaders", 200);

        assertEquals(List.of(sodium), ModSearchMatcher.suggestProjectsWithFallback(
                "sodxxm", List.of(iris, sodium), 2));
    }

    @Test
    void transposedCreateBeatsSimilarAndPopularUnrelatedProjects() {
        ModrinthProject create = project("create", "Create", "create", 1_000);
        ModrinthProject crate = project("crate", "Crate", "crate", 2_000_000);
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 100_000_000);

        assertEquals(List.of(create, crate), ModSearchMatcher.suggestProjectsWithFallback(
                "cerate", List.of(sodium, crate, create), 5));
        assertTrue(ModSearchMatcher.isLikelyCorrection("cerate", create));
        assertTrue(ModSearchMatcher.isLikelyCorrection("sodimu",
                project("sodium", "Sodium", "sodium", 1_000)));
        assertFalse(ModSearchMatcher.isLikelyCorrection("create", create));
        assertFalse(ModSearchMatcher.isLikelyCorrection("sodium",
                project("extra", "Sodium Extra", "sodium-extra", 1_000)));
    }

    @Test
    void autocompleteDoesNotOfferUnrelatedNearestProjects() {
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 100_000_000);
        ModrinthProject iris = project("iris", "Iris Shaders", "iris-shaders", 200_000_000);

        assertTrue(ModSearchMatcher.suggestProjectsWithFallback(
                "cerate", List.of(sodium, iris), 5).isEmpty());
    }

    @Test
    void confidenceAcceptsGoodFuzzyMatchesButNotTwoCharacterPrefixes() {
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 1_000);

        assertTrue(ModSearchMatcher.hasConfidentMatch("sodimu", List.of(sodium)));
        assertTrue(ModSearchMatcher.hasConfidentMatch("sodium", List.of(sodium)));
        assertFalse(ModSearchMatcher.hasConfidentMatch("so", List.of(sodium)));
    }

    @Test
    void emptyInputAndNonPositiveLimitsReturnNoSuggestions() {
        ModrinthProject sodium = project("sodium", "Sodium", "sodium", 1_000);

        assertTrue(ModSearchMatcher.rank("  !!! ", List.of(sodium), 5).isEmpty());
        assertTrue(ModSearchMatcher.suggestProjects("sodium", List.of(sodium), 0).isEmpty());
        assertTrue(ModSearchMatcher.suggestProjects("sodium", null, 5).isEmpty());
    }

    private static ModrinthProject project(String id, String title, String slug, long downloads) {
        return new ModrinthProject(id, slug, title, "", "mod", "author", downloads, "", List.of(), "");
    }
}
