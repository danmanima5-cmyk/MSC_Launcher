import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ResolvedVersion {
    private final String id;
    private final String rootId;
    private final String type;
    private final String assets;
    private final String mainClass;
    private final String javaComponent;
    private final int javaMajorVersion;
    private final Map<String, Object> assetIndex;
    private final Map<String, Object> clientDownload;
    private final List<Object> libraries;
    private final List<Object> gameArguments;
    private final List<Object> jvmArguments;
    private final String legacyMinecraftArguments;

    ResolvedVersion(String id, String rootId, String type, String assets, String mainClass, Map<String, Object> assetIndex,
                    String javaComponent, int javaMajorVersion, Map<String, Object> clientDownload, List<Object> libraries, List<Object> gameArguments,
                    List<Object> jvmArguments, String legacyMinecraftArguments) {
        this.id = id;
        this.rootId = rootId;
        this.type = type;
        this.assets = assets;
        this.mainClass = mainClass;
        this.javaComponent = javaComponent;
        this.javaMajorVersion = javaMajorVersion;
        this.assetIndex = assetIndex;
        this.clientDownload = clientDownload;
        this.libraries = libraries;
        this.gameArguments = gameArguments;
        this.jvmArguments = jvmArguments;
        this.legacyMinecraftArguments = legacyMinecraftArguments;
    }

    static ResolvedVersion fromJson(String requestedId, Map<String, Object> json) {
        String id = Json.string(json, "id").isBlank() ? requestedId : Json.string(json, "id");
        String type = Json.string(json, "type");
        String assets = Json.string(json, "assets");
        String mainClass = Json.string(json, "mainClass");
        Map<String, Object> assetIndex = Json.object(json, "assetIndex");
        Map<String, Object> downloads = Json.object(json, "downloads");
        Map<String, Object> clientDownload = Json.object(downloads, "client");
        Map<String, Object> javaVersion = Json.object(json, "javaVersion");
        String javaComponent = Json.string(javaVersion, "component");
        int javaMajorVersion = (int) Json.longValue(javaVersion, "majorVersion", 0);
        List<Object> libraries = new ArrayList<>(Json.list(json, "libraries"));
        Map<String, Object> arguments = Json.object(json, "arguments");
        List<Object> gameArgs = new ArrayList<>(Json.list(arguments, "game"));
        List<Object> jvmArgs = new ArrayList<>(Json.list(arguments, "jvm"));
        String legacyArgs = Json.string(json, "minecraftArguments");
        return new ResolvedVersion(id, id, type, assets, mainClass, assetIndex, javaComponent, javaMajorVersion,
                clientDownload, libraries, gameArgs, jvmArgs, legacyArgs);
    }

    ResolvedVersion mergeChild(Map<String, Object> childJson, String requestedId) {
        String childId = Json.string(childJson, "id").isBlank() ? requestedId : Json.string(childJson, "id");
        String mergedType = Json.string(childJson, "type").isBlank() ? type : Json.string(childJson, "type");
        String childAssets = Json.string(childJson, "assets");
        String mergedAssets = childAssets.isBlank() ? assets : childAssets;
        String mergedMainClass = Json.string(childJson, "mainClass").isBlank() ? mainClass : Json.string(childJson, "mainClass");
        Map<String, Object> childJava = Json.object(childJson, "javaVersion");
        String childJavaComponent = Json.string(childJava, "component");
        int childJavaMajor = (int) Json.longValue(childJava, "majorVersion", 0);
        String mergedJavaComponent = childJavaComponent.isBlank() ? javaComponent : childJavaComponent;
        int mergedJavaMajor = childJavaMajor == 0 ? javaMajorVersion : childJavaMajor;

        Map<String, Object> childAssetIndex = Json.object(childJson, "assetIndex");
        Map<String, Object> mergedAssetIndex = childAssetIndex.isEmpty() ? assetIndex : childAssetIndex;
        Map<String, Object> childClient = Json.object(Json.object(childJson, "downloads"), "client");
        Map<String, Object> mergedClient = childClient.isEmpty() ? clientDownload : childClient;

        List<Object> mergedLibraries = new ArrayList<>(libraries);
        mergedLibraries.addAll(Json.list(childJson, "libraries"));

        Map<String, Object> childArguments = Json.object(childJson, "arguments");
        List<Object> mergedGameArgs = new ArrayList<>(gameArguments);
        mergedGameArgs.addAll(Json.list(childArguments, "game"));
        List<Object> mergedJvmArgs = new ArrayList<>(jvmArguments);
        mergedJvmArgs.addAll(Json.list(childArguments, "jvm"));

        String childLegacy = Json.string(childJson, "minecraftArguments");
        String mergedLegacy = childLegacy.isBlank() ? legacyMinecraftArguments : childLegacy;

        return new ResolvedVersion(childId, rootId, mergedType, mergedAssets, mergedMainClass, mergedAssetIndex,
                mergedJavaComponent, mergedJavaMajor, mergedClient, deduplicateLibraries(mergedLibraries),
                mergedGameArgs, mergedJvmArgs, mergedLegacy);
    }

    private List<Object> deduplicateLibraries(List<Object> input) {
        Map<String, Object> byModule = new LinkedHashMap<>();
        List<Object> withoutName = new ArrayList<>();
        for (Object item : input) {
            if (item instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> library = (Map<String, Object>) raw;
                String name = Json.string(library, "name");
                if (name.isBlank() || !Json.list(library, "rules").isEmpty()) {
                    withoutName.add(item);
                } else {
                    byModule.put(libraryModuleKey(name), item);
                }
            } else {
                withoutName.add(item);
            }
        }
        ArrayList<Object> output = new ArrayList<>(byModule.values());
        output.addAll(withoutName);
        return output;
    }

    private String libraryModuleKey(String name) {
        int extensionMarker = name.indexOf('@');
        if (extensionMarker >= 0) {
            name = name.substring(0, extensionMarker);
        }
        String[] parts = name.split(":");
        if (parts.length < 2) {
            return name;
        }
        String classifier = parts.length >= 4 ? ":" + parts[3] : "";
        return parts[0] + ":" + parts[1] + classifier;
    }

    String id() {
        return id;
    }

    String rootId() {
        return rootId;
    }

    String type() {
        return type;
    }

    String assets() {
        return assets;
    }

    String mainClass() {
        return mainClass;
    }

    String javaComponent() {
        return javaComponent;
    }

    int javaMajorVersion() {
        return javaMajorVersion;
    }

    Map<String, Object> assetIndex() {
        return assetIndex;
    }

    Map<String, Object> clientDownload() {
        return clientDownload;
    }

    List<Object> libraries() {
        return libraries;
    }

    List<Object> gameArguments() {
        return gameArguments;
    }

    List<Object> jvmArguments() {
        return jvmArguments;
    }

    String legacyMinecraftArguments() {
        return legacyMinecraftArguments;
    }
}
