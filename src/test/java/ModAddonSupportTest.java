import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ModAddonSupportTest {
    @Test
    void relationshipRequiresAnExplicitEdgeBackToRoot() {
        ModFileRelease required = release("addon-version", List.of(
                new ModDependencyLink("root", "Root", "required", "")));
        ModFileRelease optional = release("addon-version", List.of(
                new ModDependencyLink("root", "Root", "optional", "")));
        ModFileRelease unrelated = release("addon-version", List.of(
                new ModDependencyLink("other", "Other", "required", "")));

        assertEquals("required", ModAddonSupport.relationshipToRoot("root", required));
        assertEquals("optional", ModAddonSupport.relationshipToRoot("root", optional));
        assertEquals("", ModAddonSupport.relationshipToRoot("root", unrelated));
    }

    @Test
    void pinnedAddonMustTargetTheSelectedRootRelease() {
        ModFileRelease pinned = release("addon-version", List.of(
                new ModDependencyLink("root", "root-v1", "Root", "required", "")));

        assertEquals("required",
                ModAddonSupport.relationshipToRoot("root", "root-v1", pinned));
        assertEquals("",
                ModAddonSupport.relationshipToRoot("root", "root-v2", pinned));
    }

    @Test
    void choosesOlderAddonReleasePinnedToTheSelectedRoot() {
        ModFileRelease newest = release("addon-new", List.of(
                new ModDependencyLink("root", "root-v2", "Root", "required", "")));
        ModFileRelease older = release("addon-old", List.of(
                new ModDependencyLink("root", "root-v1", "Root", "optional", "")));

        AddonReleaseMatch match = ModAddonSupport.firstCompatibleRelease(
                "root", "root-v1", List.of(newest, older));

        assertEquals(older, match.release());
        assertEquals("optional", match.relation());
    }

    @Test
    void planSeedsEverySelectedProjectButTraversesRootOnlyWhenEnabled() {
        ModInstallEntry root = new ModInstallEntry(project("root"), release("root-v1", List.of()));
        ModInstallEntry addonA = new ModInstallEntry(project("addon-a"), release("addon-a-v1", List.of()));
        ModInstallEntry addonB = new ModInstallEntry(project("addon-b"), release("addon-b-v1", List.of()));

        ModInstallPlan withoutRootLibraries = new ModInstallPlan(
                List.of(root, addonA, addonB), false);
        ModInstallPlan withRootLibraries = new ModInstallPlan(
                List.of(root, addonA, addonB), true);

        assertEquals(Set.of("root", "addon-a", "addon-b"),
                withoutRootLibraries.selectedProjectIds());
        assertEquals(List.of(addonA, addonB), withoutRootLibraries.dependencyRoots());
        assertEquals(List.of(root, addonA, addonB), withRootLibraries.dependencyRoots());
    }

    @Test
    void pinnedRootConflictIsRejectedBeforeInstallation() {
        ModInstallEntry root = new ModInstallEntry(project("root"), release("root-v1", List.of()));
        ModDependencyLink wrongRoot = new ModDependencyLink(
                "root", "root-v2", "Root", "required", "");
        ModInstallEntry addon = new ModInstallEntry(project("addon"),
                release("addon-v1", List.of(wrongRoot)));
        ModInstallPlan plan = new ModInstallPlan(List.of(root, addon), false);

        assertThrows(LauncherException.class, plan::validatePinnedVersions);
    }

    @Test
    void duplicateTargetFilenameAcrossProjectsIsRejected() {
        ModFileRelease rootRelease = release("root-v1", List.of());
        ModFileRelease addonRelease = new ModFileRelease(
                "addon-v1", "addon-v1", "addon-v1", rootRelease.fileName(),
                "release", "", List.of("1.20.1"), List.of("forge"),
                "https://example.invalid/addon.jar", "different", 2, List.of());
        ModInstallPlan plan = new ModInstallPlan(List.of(
                new ModInstallEntry(project("root"), rootRelease),
                new ModInstallEntry(project("addon"), addonRelease)), false);

        assertThrows(LauncherException.class, plan::validatePinnedVersions);
    }

    @Test
    void providerDeclaredIncompatibleAddonIsRejected() {
        ModInstallEntry root = new ModInstallEntry(project("root"), release("root-v1", List.of()));
        ModDependencyLink conflict = new ModDependencyLink(
                "root", "", "Root", "incompatible", "");
        ModInstallEntry addon = new ModInstallEntry(project("dangerous-addon"),
                release("dangerous-v1", List.of(conflict)));

        ModInstallPlan plan = new ModInstallPlan(List.of(root, addon), false);

        assertThrows(LauncherException.class, plan::validatePinnedVersions);
    }

    @Test
    void bulkSelectionHelpersAreImmutableAndDefaultCanStayEmpty() {
        ModAddonCandidate addon = new ModAddonCandidate(project("addon"),
                release("addon-v1", List.of()), "required");

        List<ModAddonCandidate> all = ModAddonSupport.selectAll(List.of(addon));
        assertEquals(List.of(addon), all);
        assertEquals(List.of(), ModAddonSupport.clearAll());
        assertThrows(UnsupportedOperationException.class, () -> all.add(addon));
    }

    private ModrinthProject project(String id) {
        return new ModrinthProject(id, id, id, "", "mod", "", 0, "", List.of(), "");
    }

    private ModFileRelease release(String versionId, List<ModDependencyLink> dependencies) {
        return new ModFileRelease(versionId, versionId, versionId, versionId + ".jar",
                "release", "", List.of("1.20.1"), List.of("forge"),
                "https://example.invalid/" + versionId + ".jar", "", 1, dependencies);
    }
}
