import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ModDependencyLinkTest {
    @Test
    void installsOnlyRuntimeDependencies() {
        for (String relation : List.of("", "required", "dependency", "REQUIRED")) {
            assertTrue(dependency(relation).requiredForInstall(), relation);
        }
        for (String relation : List.of("optional", "embedded", "include", "incompatible", "tool")) {
            assertFalse(dependency(relation).requiredForInstall(), relation);
        }
    }

    @Test
    void keepsPinnedVersionAndFallsBackToAUsefulName() {
        ModDependencyLink pinned = new ModDependencyLink("", "version-42", "", "required", "");

        assertEquals("version-42", pinned.versionId());
        assertEquals("version-42", pinned.name());
    }

    @Test
    void releaseExposesAnImmutableRequiredSubset() {
        ModDependencyLink required = dependency("required");
        ModDependencyLink optional = dependency("optional");
        ModFileRelease release = new ModFileRelease("id", "name", "1.0", "mod.jar",
                "release", "", List.of("1.21.1"), List.of("fabric"),
                "https://example.invalid/mod.jar", "", 10, List.of(required, optional));

        assertEquals(List.of(required), release.requiredDependencies());
        assertThrows(UnsupportedOperationException.class,
                () -> release.requiredDependencies().add(optional));
    }

    private static ModDependencyLink dependency(String relation) {
        return new ModDependencyLink("project", "Dependency", relation, "");
    }
}
