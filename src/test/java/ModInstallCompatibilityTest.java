import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModInstallCompatibilityTest {
    @Test
    void combinesReleasesPerVersionAndSortsVersionsLatestFirst() {
        ModInstallCompatibility compatibility = ModInstallCompatibility.builder()
                .addRelease(List.of("1.20.1", "1.21"), List.of("Fabric"))
                .addRelease(List.of("1.21"), List.of("Neo Forge"))
                .addRelease(List.of("1.20.1"), List.of("forge"))
                .addRelease(List.of("1.19.4"), List.of("quilt"))
                .build();

        assertEquals(List.of("1.21", "1.20.1", "1.19.4"), compatibility.gameVersions());
        assertEquals(Set.of("fabric", "neoforge"), compatibility.supportedLoaders("1.21"));
        assertEquals(Set.of("fabric", "forge"), compatibility.supportedLoaders("1.20.1"));
        assertEquals(Set.of("quilt"), compatibility.supportedLoaders("1.19.4"));
    }

    @Test
    void normalizesCaseAndWhitespaceAndIgnoresUnknownLoaders() {
        ModInstallCompatibility compatibility = ModInstallCompatibility.builder()
                .addRelease(List.of(" 1.21.1 ", "24w14A"),
                        List.of(" FABRIC ", "Neo Forge", "Rift", " "))
                .build();

        assertEquals(List.of("24w14A", "1.21.1"), compatibility.gameVersions());
        assertTrue(compatibility.supports(" 1.21.1 ", " fabric "));
        assertTrue(compatibility.supports("24W14a", "NEO  FORGE"));
        assertFalse(compatibility.supports("1.21.1", "rift"));
        assertFalse(compatibility.supports("1.20.1", "fabric"));
    }

    @Test
    void loaderChoicesAlwaysContainEveryKnownLoaderInStableOrder() {
        ModInstallCompatibility compatibility = ModInstallCompatibility.builder()
                .addRelease(List.of("1.21.1"), List.of("quilt", "fabric"))
                .build();

        assertEquals(List.of(
                        new ModInstallCompatibility.LoaderChoice("fabric", true),
                        new ModInstallCompatibility.LoaderChoice("forge", false),
                        new ModInstallCompatibility.LoaderChoice("neoforge", false),
                        new ModInstallCompatibility.LoaderChoice("quilt", true)),
                compatibility.loaderChoices("1.21.1"));
        assertEquals(List.of(
                        new ModInstallCompatibility.LoaderChoice("fabric", false),
                        new ModInstallCompatibility.LoaderChoice("forge", false),
                        new ModInstallCompatibility.LoaderChoice("neoforge", false),
                        new ModInstallCompatibility.LoaderChoice("quilt", false)),
                compatibility.loaderChoices("missing"));
    }

    @Test
    void builtCompatibilityIsAnImmutableSnapshot() {
        ModInstallCompatibility.Builder builder = ModInstallCompatibility.builder()
                .addRelease(List.of("1.20.1"), List.of("fabric"));
        ModInstallCompatibility compatibility = builder.build();
        builder.addRelease(List.of("1.21.1"), List.of("forge"));

        assertEquals(List.of("1.20.1"), compatibility.gameVersions());
        assertThrows(UnsupportedOperationException.class,
                () -> compatibility.gameVersions().add("1.22"));
        assertThrows(UnsupportedOperationException.class,
                () -> compatibility.supportedLoaders("1.20.1").add("forge"));
        assertThrows(UnsupportedOperationException.class,
                () -> compatibility.loaderChoices("1.20.1")
                        .add(new ModInstallCompatibility.LoaderChoice("forge", true)));
    }

    @Test
    void blankAndNullMetadataDoesNotCreatePhantomCompatibility() {
        ModInstallCompatibility compatibility = ModInstallCompatibility.builder()
                .addRelease(null, List.of("fabric"))
                .addRelease(java.util.Arrays.asList(null, " "), null)
                .build();

        assertTrue(compatibility.gameVersions().isEmpty());
        assertTrue(compatibility.supportedLoaders(null).isEmpty());
        assertFalse(compatibility.supports(null, null));
        assertEquals(ModInstallCompatibility.knownLoaders(),
                compatibility.loaderChoices(null).stream().map(ModInstallCompatibility.LoaderChoice::id).toList());
    }
}
