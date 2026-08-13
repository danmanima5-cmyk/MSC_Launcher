import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable loader compatibility matrix built from provider release metadata. */
final class ModInstallCompatibility {
    private static final List<String> KNOWN_LOADERS = List.of(
            "fabric", "forge", "neoforge", "quilt");

    private final List<String> gameVersions;
    private final Map<String, Set<String>> loadersByVersion;

    private ModInstallCompatibility(Map<String, VersionCompatibility> releases) {
        ArrayList<VersionCompatibility> versions = new ArrayList<>(releases.values());
        versions.sort((left, right) -> VersionSort.latestFirst()
                .compare(left.displayVersion(), right.displayVersion()));

        ArrayList<String> sortedVersions = new ArrayList<>(versions.size());
        LinkedHashMap<String, Set<String>> immutableLoaders = new LinkedHashMap<>();
        for (VersionCompatibility version : versions) {
            sortedVersions.add(version.displayVersion());
            immutableLoaders.put(version.normalizedVersion(), orderedLoaders(version.loaders()));
        }
        gameVersions = List.copyOf(sortedVersions);
        loadersByVersion = Collections.unmodifiableMap(immutableLoaders);
    }

    static Builder builder() {
        return new Builder();
    }

    static List<String> knownLoaders() {
        return KNOWN_LOADERS;
    }

    List<String> gameVersions() {
        return gameVersions;
    }

    Set<String> supportedLoaders(String gameVersion) {
        Set<String> supported = loadersByVersion.get(normalizeGameVersion(gameVersion));
        return supported == null ? Set.of() : supported;
    }

    boolean supports(String gameVersion, String loader) {
        String normalizedLoader = normalizeLoader(loader);
        return !normalizedLoader.isBlank()
                && supportedLoaders(gameVersion).contains(normalizedLoader);
    }

    List<LoaderChoice> loaderChoices(String gameVersion) {
        Set<String> supported = supportedLoaders(gameVersion);
        ArrayList<LoaderChoice> choices = new ArrayList<>(KNOWN_LOADERS.size());
        for (String loader : KNOWN_LOADERS) {
            choices.add(new LoaderChoice(loader, supported.contains(loader)));
        }
        return List.copyOf(choices);
    }

    private static Set<String> orderedLoaders(Set<String> input) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String loader : KNOWN_LOADERS) {
            if (input.contains(loader)) {
                ordered.add(loader);
            }
        }
        return Collections.unmodifiableSet(ordered);
    }

    private static String normalizeGameVersion(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeLoader(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    record LoaderChoice(String id, boolean supported) { }

    static final class Builder {
        private final Map<String, MutableVersionCompatibility> releases = new LinkedHashMap<>();

        Builder addRelease(List<String> gameVersions, List<String> loaders) {
            LinkedHashSet<String> normalizedLoaders = new LinkedHashSet<>();
            if (loaders != null) {
                for (String loader : loaders) {
                    String normalized = normalizeLoader(loader);
                    if (KNOWN_LOADERS.contains(normalized)) {
                        normalizedLoaders.add(normalized);
                    }
                }
            }

            if (gameVersions == null) {
                return this;
            }
            for (String gameVersion : gameVersions) {
                String displayVersion = gameVersion == null ? "" : gameVersion.trim();
                String normalizedVersion = normalizeGameVersion(displayVersion);
                if (normalizedVersion.isBlank()) {
                    continue;
                }
                MutableVersionCompatibility compatibility = releases.computeIfAbsent(normalizedVersion,
                        ignored -> new MutableVersionCompatibility(displayVersion));
                compatibility.loaders().addAll(normalizedLoaders);
            }
            return this;
        }

        ModInstallCompatibility build() {
            LinkedHashMap<String, VersionCompatibility> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, MutableVersionCompatibility> entry : releases.entrySet()) {
                MutableVersionCompatibility value = entry.getValue();
                snapshot.put(entry.getKey(), new VersionCompatibility(
                        entry.getKey(), value.displayVersion(), Set.copyOf(value.loaders())));
            }
            return new ModInstallCompatibility(snapshot);
        }
    }

    private record MutableVersionCompatibility(String displayVersion, Set<String> loaders) {
        private MutableVersionCompatibility(String displayVersion) {
            this(displayVersion, new LinkedHashSet<>());
        }
    }

    private record VersionCompatibility(String normalizedVersion, String displayVersion,
                                        Set<String> loaders) { }
}
