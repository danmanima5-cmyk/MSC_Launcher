import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModDependencyMetadataTest {
    @Test
    void onlyRequiredAndUndefinedRelationsAreInstallable() {
        for (String relation : List.of("required", "", "dependency")) {
            assertTrue(link(relation).requiredForInstall(), relation);
        }
        for (String relation : List.of(
                "optional", "embedded", "include", "incompatible", "tool", "unknown", "future-relation")) {
            assertFalse(link(relation).requiredForInstall(), relation);
        }
    }

    @Test
    void legacyConstructorKeepsCompatibilityAndReleaseFiltersDependencies() {
        ModDependencyLink legacy = new ModDependencyLink("project", "Library", "required", "https://example.test");
        assertEquals("", legacy.versionId());

        ModFileRelease release = new ModFileRelease(
                "version", "Version", "1.0", "mod.jar", "release", "",
                List.of("1.20.1"), List.of("fabric"), "https://example.test/mod.jar", "", 1,
                List.of(legacy, link("optional"), link("embedded")));
        assertEquals(List.of(legacy), release.requiredDependencies());
    }

    @Test
    void addonRelationshipAcceptsOnlyUsableReverseDependencies() {
        for (String relation : List.of("required", "optional")) {
            assertTrue(ModAddonSupport.isAddonRelationship(relation), relation);
        }
        for (String relation : List.of(
                "", "dependency", "incompatible", "embedded", "tool", "include", "unknown")) {
            assertFalse(ModAddonSupport.isAddonRelationship(relation), relation);
        }
    }

    @Test
    void releaseChecksBothMinecraftVersionAndLoader() {
        ModFileRelease release = new ModFileRelease(
                "version", "Version", "1.0", "mod.jar", "release", "",
                List.of("1.20.1"), List.of("fabric"), "https://example.test/mod.jar", "", 1,
                List.of());

        assertTrue(release.supportsTarget("1.20.1", "fabric"));
        assertFalse(release.supportsTarget("1.21", "fabric"));
        assertFalse(release.supportsTarget("1.20.1", "forge"));
    }

    @Test
    void manifestDependencyPrefersLoaderProviderOverUnrelatedCreateAddon() {
        ModrinthProject steamAndRails = project(
                "ZzjhlDgM", "create-steam-n-rails", "Create: Steam 'n' Rails", 20_000_000);
        ModrinthProject createFabric = project(
                "Xbc0uyRg", "create-fabric", "Create Fabric", 10_000_000);

        assertEquals(createFabric, ModrinthService.selectDependencyProject(
                List.of(steamAndRails, createFabric), List.of("create"), "fabric"));
    }

    @Test
    void ambiguousSearchHitIsNotInstalledAsAManifestDependency() {
        assertNull(ModrinthService.selectDependencyProject(
                List.of(project("addon", "create-steam-n-rails", "Create: Steam 'n' Rails", 20_000_000)),
                List.of("create"), "fabric"));
    }

    private ModrinthProject project(String id, String slug, String title, long downloads) {
        return new ModrinthProject(id, slug, title, "", "mod", "", downloads,
                "", List.of(), "");
    }

    private ModDependencyLink link(String relation) {
        return new ModDependencyLink("project-" + relation, "version-" + relation,
                "Dependency " + relation, relation, "");
    }
}
