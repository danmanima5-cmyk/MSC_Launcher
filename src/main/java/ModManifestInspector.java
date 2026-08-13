import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads loader metadata directly from a mod JAR without loading any mod classes. */
final class ModManifestInspector {
    private static final Set<String> PLATFORM_IDS = Set.of(
            "minecraft", "forge", "neoforge", "java", "mixin",
            "fabricloader", "fabric_loader", "quilt_loader");
    private static final Pattern TOML_BLOCK = Pattern.compile(
            "(?ms)^\\s*\\[\\[\\s*([^]]+)]]\\s*(.*?)(?=^\\s*\\[\\[|\\z)");

    private ModManifestInspector() {
    }

    static ModManifestMetadata inspect(Path jar) throws IOException {
        LinkedHashSet<String> provided = new LinkedHashSet<>();
        LinkedHashMap<String, ModManifestDependency> required = new LinkedHashMap<>();
        LinkedHashSet<String> incompatible = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            inspectForgeToml(zip, "META-INF/mods.toml", provided, required, incompatible);
            inspectForgeToml(zip, "META-INF/neoforge.mods.toml", provided, required, incompatible);
            inspectFabricJson(zip, provided, required, incompatible);
            inspectQuiltJson(zip, provided, required, incompatible);
        }
        required.keySet().removeAll(provided);
        required.keySet().removeAll(PLATFORM_IDS);
        incompatible.removeAll(provided);
        incompatible.removeAll(PLATFORM_IDS);
        return new ModManifestMetadata(
                provided, new ArrayList<>(required.values()), incompatible);
    }

    static Set<String> providedModIds(Path jar) throws IOException {
        return inspect(jar).providedModIds();
    }

    static Set<String> ignoredPlatformIds() {
        return PLATFORM_IDS;
    }

    private static void inspectForgeToml(ZipFile zip, String entryName,
                                         Set<String> provided,
                                         Map<String, ModManifestDependency> required,
                                         Set<String> incompatible) throws IOException {
        String toml = readEntry(zip, entryName);
        if (toml.isBlank()) {
            return;
        }
        Matcher blocks = TOML_BLOCK.matcher(toml);
        while (blocks.find()) {
            String header = blocks.group(1).trim().toLowerCase(Locale.ROOT);
            String body = blocks.group(2);
            if (header.equals("mods")) {
                addId(provided, tomlValue(body, "modId"));
                continue;
            }
            if (!header.startsWith("dependencies.")) {
                continue;
            }
            String id = normalizeId(tomlValue(body, "modId"));
            if (id.isBlank() || id.contains("${")) {
                continue;
            }
            if (isIncompatibleForgeDependency(body)) {
                incompatible.add(id);
            } else if (isRequiredForgeDependency(body)) {
                required.putIfAbsent(id, new ModManifestDependency(id, tomlValue(body, "versionRange")));
            }
        }
    }

    private static boolean isIncompatibleForgeDependency(String body) {
        String type = tomlValue(body, "type").trim().toLowerCase(Locale.ROOT);
        return type.equals("incompatible") || type.equals("conflict") || type.equals("breaks");
    }

    private static boolean isRequiredForgeDependency(String body) {
        String type = tomlValue(body, "type").trim().toLowerCase(Locale.ROOT);
        if (!type.isBlank()) {
            return type.equals("required") || type.equals("dependency");
        }
        String mandatory = tomlValue(body, "mandatory").trim();
        return mandatory.isBlank() || Boolean.parseBoolean(mandatory);
    }

    private static void inspectFabricJson(ZipFile zip, Set<String> provided,
                                          Map<String, ModManifestDependency> required,
                                          Set<String> incompatible) throws IOException {
        String json = readEntry(zip, "fabric.mod.json");
        if (json.isBlank()) {
            return;
        }
        Map<String, Object> root = Json.object(Json.parse(json));
        addId(provided, Json.string(root, "id"));
        addProvidedValues(provided, root.get("provides"));
        addDependencyMap(required, Json.object(root, "depends"));
        addDependencyIds(incompatible, root.get("breaks"));
        addDependencyIds(incompatible, root.get("conflicts"));
    }

    private static void inspectQuiltJson(ZipFile zip, Set<String> provided,
                                         Map<String, ModManifestDependency> required,
                                         Set<String> incompatible) throws IOException {
        String json = readEntry(zip, "quilt.mod.json");
        if (json.isBlank()) {
            return;
        }
        Map<String, Object> root = Json.object(Json.parse(json));
        Map<String, Object> loader = Json.object(root, "quilt_loader");
        addId(provided, Json.string(loader, "id"));
        addProvidedValues(provided, loader.get("provides"));
        addDependencyIds(incompatible, loader.get("breaks"));
        addDependencyIds(incompatible, loader.get("conflicts"));

        Object depends = loader.get("depends");
        if (depends == null) {
            return;
        } else if (depends instanceof Map<?, ?>) {
            addDependencyMap(required, Json.object(depends));
        } else {
            for (Object item : Json.list(depends)) {
                if (item instanceof String text) {
                    addRequired(required, text, "");
                } else if (item instanceof Map<?, ?>) {
                    Map<String, Object> dependency = Json.object(item);
                    addRequired(required, Json.string(dependency, "id"),
                            jsonVersion(dependency.get("versions")));
                }
            }
        }
    }

    private static void addDependencyIds(Set<String> target, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?>) {
            for (Object key : Json.object(value).keySet()) {
                addId(target, String.valueOf(key));
            }
            return;
        }
        for (Object item : Json.list(value)) {
            if (item instanceof String text) {
                addId(target, text);
            } else if (item instanceof Map<?, ?>) {
                Map<String, Object> dependency = Json.object(item);
                addId(target, Json.string(dependency, "id"));
            }
        }
    }

    private static void addProvidedValues(Set<String> provided, Object value) {
        if (value == null) {
            return;
        }
        for (Object item : Json.list(value)) {
            if (item instanceof Map<?, ?>) {
                addId(provided, Json.string(Json.object(item), "id"));
            } else {
                addId(provided, Json.string(item));
            }
        }
    }

    private static void addDependencyMap(Map<String, ModManifestDependency> required,
                                         Map<String, Object> dependencies) {
        for (Map.Entry<String, Object> entry : dependencies.entrySet()) {
            addRequired(required, entry.getKey(), jsonVersion(entry.getValue()));
        }
    }

    private static String jsonVersion(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof List<?> list) {
            ArrayList<String> values = new ArrayList<>();
            for (Object item : list) {
                String text = Json.string(item);
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
            return String.join(" || ", values);
        }
        return Json.stringify(value);
    }

    private static void addRequired(Map<String, ModManifestDependency> required,
                                    String rawId, String version) {
        String id = normalizeId(rawId);
        if (!id.isBlank() && !id.contains("${")) {
            required.putIfAbsent(id, new ModManifestDependency(id, version));
        }
    }

    private static void addId(Set<String> ids, String value) {
        String id = normalizeId(value);
        if (!id.isBlank() && !id.contains("${")) {
            ids.add(id);
        }
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String tomlValue(String block, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
                + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s#]+))").matcher(block);
        if (!matcher.find()) {
            return "";
        }
        if (matcher.group(1) != null) {
            return matcher.group(1);
        }
        if (matcher.group(2) != null) {
            return matcher.group(2);
        }
        return matcher.group(3) == null ? "" : matcher.group(3);
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return "";
        }
        try (var input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

record ModManifestDependency(String modId, String versionConstraint) {
    ModManifestDependency {
        modId = modId == null ? "" : modId;
        versionConstraint = versionConstraint == null ? "" : versionConstraint;
    }
}

record ModManifestMetadata(Set<String> providedModIds,
                           List<ModManifestDependency> requiredDependencies,
                           Set<String> incompatibleModIds) {
    ModManifestMetadata {
        providedModIds = providedModIds == null ? Set.of() : Set.copyOf(providedModIds);
        requiredDependencies = requiredDependencies == null ? List.of() : List.copyOf(requiredDependencies);
        incompatibleModIds = incompatibleModIds == null ? Set.of() : Set.copyOf(incompatibleModIds);
    }
}
