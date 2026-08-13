import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ModLoaderUpdateService {
    private final MinecraftInstaller minecraftInstaller;
    private final ForgeService forgeService;
    private final NeoForgeService neoForgeService;
    private final OptiFineService optiFineService;

    ModLoaderUpdateService(MinecraftInstaller minecraftInstaller, ForgeService forgeService,
                           NeoForgeService neoForgeService, OptiFineService optiFineService) {
        this.minecraftInstaller = minecraftInstaller;
        this.forgeService = forgeService;
        this.neoForgeService = neoForgeService;
        this.optiFineService = optiFineService;
    }

    String updateBeforeLaunch(String profileId, LauncherSettings settings, ProgressSink progress) {
        if (!settings.autoUpdateModLoaders() || profileId == null || profileId.isBlank()) {
            return profileId;
        }
        try {
            return updateBeforeLaunchInternal(profileId, settings, progress);
        } catch (LauncherException ex) {
            progress.log("Mod loader auto-update skipped: " + ex.getMessage());
            return profileId;
        }
    }

    private String updateBeforeLaunchInternal(String profileId, LauncherSettings settings, ProgressSink progress) {
        List<ProfileEntry> chain = readProfileChain(profileId, settings.gameDirectory());
        LoaderTarget target = findLoaderTarget(chain);
        if (target == null) {
            return profileId;
        }

        String gameVersion = gameVersionFor(target.entry(), settings.gameDirectory());
        if (gameVersion.isBlank()) {
            progress.log("Mod loader auto-update skipped: could not detect Minecraft version for " + target.entry().id());
            return profileId;
        }

        progress.status("Checking mod loader updates");
        String latestProfile = switch (target.kind()) {
            case FORGE -> ensureLatestForge(target, gameVersion, settings, progress);
            case NEOFORGE -> ensureLatestNeoForge(target, gameVersion, settings, progress);
            case FABRIC -> ensureLatestFabric(target, gameVersion, settings, progress);
            case QUILT -> ensureLatestQuilt(target, gameVersion, settings, progress);
            case OPTIFINE -> {
                ensureLatestStandaloneOptiFine(target, gameVersion, settings, progress);
                yield target.entry().id();
            }
            case FORGE_OPTIFINE -> ensureLatestForgeOptiFine(target, chain, gameVersion, settings, progress);
            case NONE -> target.entry().id();
        };

        if (latestProfile == null || latestProfile.isBlank() || latestProfile.equals(target.entry().id())) {
            return profileId;
        }
        if (target.index() == 0) {
            progress.log("Launching updated loader profile: " + latestProfile);
            return latestProfile;
        }

        ProfileEntry child = chain.get(target.index() - 1);
        updateInheritedParent(child, latestProfile, progress);
        return profileId;
    }

    private String ensureLatestForge(LoaderTarget target, String gameVersion,
                                     LauncherSettings settings, ProgressSink progress) {
        String latest = forgeService.latestForgeFor(gameVersion);
        String current = forgeMavenVersion(target.entry(), gameVersion);
        if (latest.equals(current)) {
            progress.log("Forge already latest: " + latest);
            return target.entry().id();
        }
        progress.log("Forge update: " + displayVersion(current) + " -> " + latest);
        return forgeService.installForge(gameVersion, latest, settings, progress);
    }

    private String ensureLatestNeoForge(LoaderTarget target, String gameVersion,
                                        LauncherSettings settings, ProgressSink progress) {
        String latest = neoForgeService.latestNeoForgeFor(gameVersion);
        String current = neoForgeVersion(target.entry(), gameVersion);
        if (latest.equals(current)) {
            progress.log("NeoForge already latest: " + latest);
            return target.entry().id();
        }
        progress.log("NeoForge update: " + displayVersion(current) + " -> " + latest);
        return neoForgeService.installNeoForge(gameVersion, latest, settings, progress);
    }

    private String ensureLatestFabric(LoaderTarget target, String gameVersion,
                                      LauncherSettings settings, ProgressSink progress) {
        String latest = minecraftInstaller.latestFabricLoaderFor(gameVersion);
        String current = loaderVersionFromProfileId(target.entry().id(), "fabric-loader-", gameVersion);
        if (current.isBlank()) {
            current = libraryVersion(target.entry(), "net.fabricmc:fabric-loader:");
        }
        if (latest.equals(current)) {
            progress.log("Fabric Loader already latest: " + latest);
            return target.entry().id();
        }
        progress.log("Fabric Loader update: " + displayVersion(current) + " -> " + latest);
        return minecraftInstaller.installFabric(gameVersion, latest, settings, progress);
    }

    private String ensureLatestQuilt(LoaderTarget target, String gameVersion,
                                     LauncherSettings settings, ProgressSink progress) {
        String latest = minecraftInstaller.latestQuiltLoaderFor(gameVersion);
        String current = loaderVersionFromProfileId(target.entry().id(), "quilt-loader-", gameVersion);
        if (current.isBlank()) {
            current = libraryVersion(target.entry(), "org.quiltmc:quilt-loader:");
        }
        if (latest.equals(current)) {
            progress.log("Quilt Loader already latest: " + latest);
            return target.entry().id();
        }
        progress.log("Quilt Loader update: " + displayVersion(current) + " -> " + latest);
        return minecraftInstaller.installQuilt(gameVersion, latest, settings, progress);
    }

    private void ensureLatestStandaloneOptiFine(LoaderTarget target, String gameVersion,
                                                LauncherSettings settings, ProgressSink progress) {
        OptiFineRelease latest = optiFineService.latestFor(gameVersion);
        String latestProfileName = stripJar(latest.fileName());
        if (latestProfileName.equals(target.entry().id())) {
            progress.log("OptiFine already latest: " + latest.fileName());
            return;
        }
        Path jar = optiFineService.download(latest, settings, progress);
        progress.log("Latest OptiFine downloaded: " + jar.getFileName()
                + ". Standalone OptiFine profiles still require the official installer window.");
    }

    private String ensureLatestForgeOptiFine(LoaderTarget target, List<ProfileEntry> chain, String gameVersion,
                                             LauncherSettings settings, ProgressSink progress) {
        OptiFineRelease latestOptiFine = optiFineService.latestWithForgeSupport(gameVersion);
        String latestForge = forgeService.resolveForgeForOptiFine(gameVersion, latestOptiFine.forgeText());
        String currentForge = forgeMavenVersion(forgeParentFor(target, chain, settings.gameDirectory()), gameVersion);
        if (latestForge.equals(currentForge) && optiFineService.isDownloaded(latestOptiFine)) {
            progress.log("Forge + OptiFine already latest: " + latestForge + " / " + latestOptiFine.fileName());
            return target.entry().id();
        }
        progress.log("Forge + OptiFine update: Forge " + displayVersion(currentForge)
                + " -> " + latestForge + ", OptiFine " + latestOptiFine.fileName());
        return optiFineService.installWithForge(gameVersion, settings, progress);
    }

    private ProfileEntry forgeParentFor(LoaderTarget target, List<ProfileEntry> chain, Path gameDirectory) {
        String parentId = Json.string(target.entry().json(), "inheritsFrom");
        if (parentId.isBlank()) {
            return target.entry();
        }
        for (ProfileEntry entry : chain) {
            if (parentId.equals(entry.id())) {
                return entry;
            }
        }
        Path jsonPath = ProfileDirectories.versionsInstallDirectory(gameDirectory, parentId).resolve(parentId).resolve(parentId + ".json");
        if (Files.isRegularFile(jsonPath)) {
            return readProfileEntry(parentId, jsonPath);
        }
        return new ProfileEntry(parentId, Map.of(), jsonPath);
    }

    private List<ProfileEntry> readProfileChain(String profileId, Path gameDirectory) {
        ArrayList<ProfileEntry> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = profileId;
        while (current != null && !current.isBlank() && seen.add(current)) {
            Path jsonPath = ProfileDirectories.versionsInstallDirectory(gameDirectory, current).resolve(current).resolve(current + ".json");
            if (!Files.isRegularFile(jsonPath)) {
                break;
            }
            ProfileEntry entry = readProfileEntry(current, jsonPath);
            chain.add(entry);
            current = Json.string(entry.json(), "inheritsFrom");
        }
        return chain;
    }

    private ProfileEntry readProfileEntry(String id, Path jsonPath) {
        try {
            Map<String, Object> json = Json.object(Json.parse(Files.readString(jsonPath, StandardCharsets.UTF_8)));
            return new ProfileEntry(id, json, jsonPath);
        } catch (IOException ex) {
            throw new LauncherException("Could not read profile JSON " + jsonPath + ": " + ex.getMessage(), ex);
        }
    }

    private LoaderTarget findLoaderTarget(List<ProfileEntry> chain) {
        for (int i = 0; i < chain.size(); i++) {
            ProfileEntry entry = chain.get(i);
            LoaderKind kind = detectKind(entry);
            if (kind != LoaderKind.NONE) {
                return new LoaderTarget(i, entry, kind);
            }
        }
        return null;
    }

    private LoaderKind detectKind(ProfileEntry entry) {
        String id = entry.id().toLowerCase(Locale.ROOT);
        if (isCustomContainer(id)) {
            return LoaderKind.NONE;
        }
        if (id.contains("forge") && id.contains("optifine")) {
            return LoaderKind.FORGE_OPTIFINE;
        }
        if (id.startsWith("optifine_") || id.startsWith("preview_optifine_")) {
            return LoaderKind.OPTIFINE;
        }
        if (id.startsWith("fabric-loader-") || hasLibrary(entry, "net.fabricmc:fabric-loader:")
                || Json.string(entry.json(), "mainClass").toLowerCase(Locale.ROOT).contains("fabricmc")) {
            return LoaderKind.FABRIC;
        }
        if (id.startsWith("quilt-loader-") || hasLibrary(entry, "org.quiltmc:quilt-loader:")
                || Json.string(entry.json(), "mainClass").toLowerCase(Locale.ROOT).contains("quiltmc")) {
            return LoaderKind.QUILT;
        }
        if (id.startsWith("neoforge-") || id.contains("-neoforge-") || hasLibrary(entry, "net.neoforged:neoforge:")) {
            return LoaderKind.NEOFORGE;
        }
        if (id.startsWith("forge-") || id.contains("-forge-") || hasLibrary(entry, "net.minecraftforge:forge:")
                || Json.string(entry.json(), "mainClass").toLowerCase(Locale.ROOT).contains("modlauncher")) {
            return LoaderKind.FORGE;
        }
        return LoaderKind.NONE;
    }

    private boolean isCustomContainer(String lowerId) {
        return lowerId.startsWith("msc-build-")
                || lowerId.startsWith("modrinth-")
                || lowerId.startsWith("curseforge-");
    }

    private String gameVersionFor(ProfileEntry entry, Path gameDirectory) {
        String detected = minecraftInstaller.installedGameVersion(gameDirectory, entry.id());
        if (!detected.isBlank() && !detected.equals(entry.id())) {
            return detected;
        }
        String fallback = VersionSort.baseGameVersion(entry.id());
        return fallback == null ? "" : fallback;
    }

    private String loaderVersionFromProfileId(String profileId, String prefix, String gameVersion) {
        String lower = profileId.toLowerCase(Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        String suffix = "-" + gameVersion;
        if (!lower.startsWith(lowerPrefix) || !profileId.endsWith(suffix)) {
            return "";
        }
        return profileId.substring(prefix.length(), profileId.length() - suffix.length());
    }

    private String forgeMavenVersion(ProfileEntry entry, String gameVersion) {
        String fromLibrary = libraryVersion(entry, "net.minecraftforge:forge:");
        if (!fromLibrary.isBlank()) {
            return fromLibrary;
        }

        String id = stripOptiFineSuffix(entry.id());
        String lower = id.toLowerCase(Locale.ROOT);
        String forgePrefix = "forge-";
        if (lower.startsWith(forgePrefix + gameVersion.toLowerCase(Locale.ROOT) + "-")) {
            return id.substring(forgePrefix.length());
        }

        String modernPrefix = gameVersion.toLowerCase(Locale.ROOT) + "-forge-";
        if (lower.startsWith(modernPrefix)) {
            return gameVersion + "-" + id.substring(modernPrefix.length());
        }

        String legacyPrefix = gameVersion.toLowerCase(Locale.ROOT) + "-forge";
        if (lower.startsWith(legacyPrefix)) {
            return gameVersion + "-" + id.substring(legacyPrefix.length());
        }
        return "";
    }

    private String neoForgeVersion(ProfileEntry entry, String gameVersion) {
        String fromLibrary = libraryVersion(entry, "net.neoforged:neoforge:");
        if (!fromLibrary.isBlank()) {
            return fromLibrary;
        }

        String id = entry.id();
        String lower = id.toLowerCase(Locale.ROOT);
        String prefix = "neoforge-";
        if (lower.startsWith(prefix)) {
            return id.substring(prefix.length());
        }

        String gamePrefix = gameVersion.toLowerCase(Locale.ROOT) + "-neoforge-";
        if (lower.startsWith(gamePrefix)) {
            return id.substring(gamePrefix.length());
        }
        return "";
    }

    private boolean hasLibrary(ProfileEntry entry, String coordinatePrefix) {
        return !libraryVersion(entry, coordinatePrefix).isBlank();
    }

    private String libraryVersion(ProfileEntry entry, String coordinatePrefix) {
        String lowerPrefix = coordinatePrefix.toLowerCase(Locale.ROOT);
        for (Object item : Json.list(entry.json(), "libraries")) {
            Map<String, Object> library = Json.object(item);
            String name = Json.string(library, "name");
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                return mavenVersion(name);
            }
        }
        return "";
    }

    private String mavenVersion(String coordinate) {
        int extensionIndex = coordinate.indexOf('@');
        String normalized = extensionIndex >= 0 ? coordinate.substring(0, extensionIndex) : coordinate;
        String[] parts = normalized.split(":");
        return parts.length >= 3 ? parts[2] : "";
    }

    private void updateInheritedParent(ProfileEntry child, String latestProfile, ProgressSink progress) {
        String oldParent = Json.string(child.json(), "inheritsFrom");
        if (latestProfile.equals(oldParent)) {
            return;
        }
        child.json().put("inheritsFrom", latestProfile);
        child.json().put("time", Instant.now().toString());
        try {
            Files.writeString(child.jsonPath(), Json.stringify(child.json()), StandardCharsets.UTF_8);
            progress.log("Updated " + child.id() + " parent loader: " + oldParent + " -> " + latestProfile);
        } catch (IOException ex) {
            throw new LauncherException("Could not update profile " + child.id() + ": " + ex.getMessage(), ex);
        }
    }

    private String stripOptiFineSuffix(String value) {
        String suffix = "-optifine";
        return value.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? value.substring(0, value.length() - suffix.length())
                : value;
    }

    private String stripJar(String fileName) {
        return fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private String displayVersion(String version) {
        return version == null || version.isBlank() ? "unknown" : version;
    }

    private enum LoaderKind {
        NONE,
        FORGE,
        NEOFORGE,
        FABRIC,
        QUILT,
        OPTIFINE,
        FORGE_OPTIFINE
    }

    private record ProfileEntry(String id, Map<String, Object> json, Path jsonPath) {
    }

    private record LoaderTarget(int index, ProfileEntry entry, LoaderKind kind) {
    }
}
