import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;

final class ModBuildService {
    private final MinecraftInstaller minecraftInstaller;
    private final ForgeService forgeService;
    private final NeoForgeService neoForgeService;
    private final OptiFineService optiFineService;

    ModBuildService(MinecraftInstaller minecraftInstaller, ForgeService forgeService,
                    NeoForgeService neoForgeService, OptiFineService optiFineService) {
        this.minecraftInstaller = minecraftInstaller;
        this.forgeService = forgeService;
        this.neoForgeService = neoForgeService;
        this.optiFineService = optiFineService;
    }

    String createBuild(String name, String gameVersion, String loader, boolean optiFine,
                       LauncherSettings settings, ProgressSink progress) {
        if (gameVersion == null || gameVersion.isBlank()) {
            throw new LauncherException("Minecraft version is required for a mod build.");
        }
        String normalizedLoader = loader == null || loader.isBlank() ? "vanilla" : loader.trim().toLowerCase(java.util.Locale.ROOT);
        if ("forge".equals(normalizedLoader) && optiFine) {
            return createForgeOptiFineBuild(name, gameVersion.trim(), settings, progress);
        }
        String parentProfile = installParentProfile(gameVersion.trim(), normalizedLoader, optiFine, settings, progress);
        String profileId = uniqueProfileId(settings.gameDirectory(), name, gameVersion.trim(), normalizedLoader, optiFine);
        createInheritedProfile(profileId, parentProfile, settings, progress);
        createInstanceFolders(profileId, settings, progress);
        return profileId;
    }

    String createForgeOptiFineBuild(String name, String gameVersion, OptiFineRelease release, String forgeMavenVersion,
                                    LauncherSettings settings, ProgressSink progress) {
        if (gameVersion == null || gameVersion.isBlank()) {
            throw new LauncherException("Minecraft version is required for a mod build.");
        }
        String profileId = uniqueProfileId(settings.gameDirectory(), name, gameVersion.trim(), "forge", true);
        createInstanceFolders(profileId, settings, progress);
        String parentProfile = optiFineService.installWithForgeManualIntoProfile(
                gameVersion.trim(), release, forgeMavenVersion, profileId, settings, progress);
        createInheritedProfile(profileId, parentProfile, settings, progress);
        return profileId;
    }

    private String createForgeOptiFineBuild(String name, String gameVersion, LauncherSettings settings, ProgressSink progress) {
        String profileId = uniqueProfileId(settings.gameDirectory(), name, gameVersion.trim(), "forge", true);
        createInstanceFolders(profileId, settings, progress);
        String parentProfile = optiFineService.installWithForgeIntoProfile(gameVersion.trim(), profileId, settings, progress);
        createInheritedProfile(profileId, parentProfile, settings, progress);
        return profileId;
    }

    private String installParentProfile(String gameVersion, String loader, boolean optiFine,
                                        LauncherSettings settings, ProgressSink progress) {
        return switch (loader) {
            case "fabric" -> minecraftInstaller.installFabric(gameVersion, settings, progress);
            case "quilt" -> minecraftInstaller.installQuilt(gameVersion, settings, progress);
            case "neoforge" -> neoForgeService.installLatestNeoForge(gameVersion, settings, progress);
            case "forge" -> optiFine
                    ? optiFineService.installWithForge(gameVersion, settings, progress)
                    : forgeService.installLatestForge(gameVersion, settings, progress);
            case "vanilla" -> {
                minecraftInstaller.installVanilla(gameVersion, settings, progress);
                if (optiFine) {
                    String optiFineProfile = findInstalledOptiFineProfile(settings.gameDirectory(), gameVersion);
                    if (optiFineProfile != null) {
                        yield optiFineProfile;
                    }
                    progress.log("OptiFine for vanilla uses the official OptiFine installer. No installed OptiFine profile was found, so the build will inherit vanilla for now.");
                    optiFineService.installStandalone(gameVersion, settings, progress);
                }
                yield gameVersion;
            }
            default -> throw new LauncherException("Unsupported mod loader: " + loader);
        };
    }

    private String uniqueProfileId(Path gameDirectory, String name, String gameVersion, String loader, boolean optiFine) {
        String base = sanitize(name == null || name.isBlank() ? "build" : name);
        String candidate = base;
        int suffix = 2;
        while (Files.exists(ProfileDirectories.versionsInstallDirectory().resolve(candidate).resolve(candidate + ".json"))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private void createInheritedProfile(String profileId, String parentProfile, LauncherSettings settings, ProgressSink progress) {
        Path profileDir = ProfileDirectories.versionsInstallDirectory().resolve(profileId);
        Path profileJson = profileDir.resolve(profileId + ".json");
        LinkedHashMap<String, Object> json = new LinkedHashMap<>();
        String now = Instant.now().toString();
        json.put("id", profileId);
        json.put("inheritsFrom", parentProfile);
        json.put("time", now);
        json.put("releaseTime", now);
        json.put("type", "release");
        json.put("mscBuild", true);
        try {
            Files.createDirectories(profileDir);
            Files.writeString(profileJson, Json.stringify(json), StandardCharsets.UTF_8);
            progress.log("Mod build profile saved: " + profileId);
        } catch (IOException ex) {
            throw new LauncherException("Could not save mod build profile: " + ex.getMessage(), ex);
        }
    }

    private void createInstanceFolders(String profileId, LauncherSettings settings, ProgressSink progress) {
        try {
            Path instance = ProfileDirectories.launchGameDirectory(settings.gameDirectory(), profileId);
            Files.createDirectories(instance.resolve("mods"));
            Files.createDirectories(instance.resolve("resourcepacks"));
            Files.createDirectories(instance.resolve("shaderpacks"));
            progress.log("Mod build instance folder: " + instance);
        } catch (IOException ex) {
            throw new LauncherException("Could not create mod build folders: " + ex.getMessage(), ex);
        }
    }

    private String findInstalledOptiFineProfile(Path gameDirectory, String gameVersion) {
        // Standalone OptiFine profiles ("OptiFine_<gameVersion>_...") now live in the real
        // .minecraft/versions (see ProfileDirectories.storesInGameVersionsFolder), not
        // msc-launcher/Profiles.
        Path versionsDir = ProfileDirectories.versionsInstallDirectory(gameDirectory, "OptiFine_" + gameVersion);
        if (!Files.isDirectory(versionsDir)) {
            return null;
        }
        try (var stream = Files.list(versionsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(id -> id.startsWith("OptiFine_"))
                    .filter(id -> id.contains("_" + gameVersion + "_"))
                    .filter(id -> Files.isRegularFile(versionsDir.resolve(id).resolve(id + ".json")))
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private String sanitize(String value) {
        String sanitized = value == null ? "" : value.trim().replaceAll("[^\\p{L}\\p{N} ._-]", "_");
        sanitized = sanitized.replaceAll("_+", "_").replaceAll("\\s+", " ").trim();
        sanitized = sanitized.replaceAll("[. ]+$", "");
        return sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized) ? "build" : sanitized;
    }
}
