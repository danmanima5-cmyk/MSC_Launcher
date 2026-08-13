import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModManifestInspectorTest {
    @TempDir
    Path tempDir;

    @Test
    void forgeSeparatesProvidedModFromRequiredLibraries() throws IOException {
        String modsToml = """
                modLoader="javafml"
                loaderVersion="[47,)"

                [[mods]]
                modId="verity"
                version="5.7.3"

                [[dependencies.verity]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"

                [[dependencies.verity]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.21)"

                [[dependencies.verity]]
                modId="yet_another_config_lib_v3"
                mandatory=true
                versionRange="[3.6,)"

                [[dependencies.verity]]
                modId="geckolib"
                mandatory=true
                versionRange="[4.4,)"

                [[dependencies.verity]]
                modId="optional_example"
                mandatory=false
                """;
        Path jar = writeJar("verity.jar", Map.of("META-INF/mods.toml", modsToml));

        ModManifestMetadata metadata = ModManifestInspector.inspect(jar);

        assertEquals(Set.of("verity"), metadata.providedModIds());
        assertEquals(Set.of("yet_another_config_lib_v3", "geckolib"), dependencyIds(metadata));
        assertFalse(metadata.providedModIds().contains("geckolib"));
    }

    @Test
    void neoForgeRecognizesRequiredTypeAndIgnoresOptionalType() throws IOException {
        String modsToml = """
                [[mods]]
                modId='sample_mod'

                [[dependencies.sample_mod]]
                modId='neoforge'
                type='required'

                [[dependencies.sample_mod]]
                modId='required_library'
                type='required'
                versionRange='[2,)'

                [[dependencies.sample_mod]]
                modId='optional_library'
                type='optional'
                """;
        Path jar = writeJar("neoforge.jar", Map.of("META-INF/neoforge.mods.toml", modsToml));

        ModManifestMetadata metadata = ModManifestInspector.inspect(jar);

        assertEquals(Set.of("sample_mod"), metadata.providedModIds());
        assertEquals(Set.of("required_library"), dependencyIds(metadata));
    }

    @Test
    void fabricUsesDependsButNotRecommendsOrSuggests() throws IOException {
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "betterf3",
                  "provides": ["better_f3_alias"],
                  "depends": {
                    "fabricloader": ">=0.15.0",
                    "minecraft": "1.21.1",
                    "cloth-config": ">=15.0.0"
                  },
                  "recommends": {"modmenu": "*"},
                  "suggests": {"optional_helper": "*"}
                }
                """;
        Path jar = writeJar("fabric.jar", Map.of("fabric.mod.json", json));

        ModManifestMetadata metadata = ModManifestInspector.inspect(jar);

        assertEquals(Set.of("betterf3", "better_f3_alias"), metadata.providedModIds());
        assertEquals(Set.of("cloth-config"), dependencyIds(metadata));
        assertEquals(">=15.0.0", metadata.requiredDependencies().get(0).versionConstraint());
    }

    @Test
    void quiltReadsListDependenciesAndFiltersItsLoader() throws IOException {
        String json = """
                {
                  "quilt_loader": {
                    "id": "quilt_sample",
                    "provides": ["quilt_alias"],
                    "depends": [
                      {"id": "quilt_loader", "versions": ">=0.20"},
                      {"id": "qsl", "versions": ">=7.0"}
                    ]
                  }
                }
                """;
        Path jar = writeJar("quilt.jar", Map.of("quilt.mod.json", json));

        ModManifestMetadata metadata = ModManifestInspector.inspect(jar);

        assertEquals(Set.of("quilt_sample", "quilt_alias"), metadata.providedModIds());
        assertEquals(Set.of("qsl"), dependencyIds(metadata));
    }

    @Test
    void fabricReadsBreaksAndConflictsAsUnsafeModIds() throws IOException {
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "safe_mod",
                  "breaks": {"old_renderer": "*"},
                  "conflicts": {"other_renderer": "*"}
                }
                """;

        ModManifestMetadata metadata = ModManifestInspector.inspect(
                writeJar("fabric-breaks.jar", Map.of("fabric.mod.json", json)));

        assertEquals(Set.of("old_renderer", "other_renderer"),
                metadata.incompatibleModIds());
    }

    @Test
    void forgeReadsIncompatibleDependencyType() throws IOException {
        String toml = """
                [[mods]]
                modId="safe_mod"
                [[dependencies.safe_mod]]
                modId="broken_companion"
                type="incompatible"
                versionRange="[1,)"
                """;

        ModManifestMetadata metadata = ModManifestInspector.inspect(
                writeJar("forge-incompatible.jar", Map.of("META-INF/mods.toml", toml)));

        assertEquals(Set.of("broken_companion"), metadata.incompatibleModIds());
        assertTrue(metadata.requiredDependencies().isEmpty());
    }

    private Set<String> dependencyIds(ModManifestMetadata metadata) {
        return metadata.requiredDependencies().stream()
                .map(ModManifestDependency::modId)
                .collect(Collectors.toSet());
    }

    private Path writeJar(String name, Map<String, String> entries) throws IOException {
        Path jar = tempDir.resolve(name);
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>(entries);
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }
}
