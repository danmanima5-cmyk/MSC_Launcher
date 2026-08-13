import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class MinecraftInstaller {
    private final MinecraftApi minecraftApi;
    private final FabricApi fabricApi;
    private final QuiltApi quiltApi;
    private final InstalledVersionCache installedVersionCache = new InstalledVersionCache();

    MinecraftInstaller(MinecraftApi minecraftApi, FabricApi fabricApi, QuiltApi quiltApi) {
        this.minecraftApi = minecraftApi;
        this.fabricApi = fabricApi;
        this.quiltApi = quiltApi;
    }

    List<String> listInstalledVersions(Path gameDirectory) {
        ProfileDirectories.reconcileLegacySanitizedProfiles(
                ProfileDirectories.versionsInstallDirectory(), ProgressSink.NONE);
        // Version folders now live under two roots: msc-launcher/Profiles for Forge/Fabric/
        // Quilt/NeoForge/modpacks, and the real .minecraft/versions for vanilla and standalone
        // OptiFine (see ProfileDirectories.storesInGameVersionsFolder). Scan both and merge.
        List<Path> roots = new ArrayList<>();
        roots.add(ProfileDirectories.versionsInstallDirectory());
        if (gameDirectory != null) {
            Path gameVersions = gameDirectory.resolve("versions");
            if (!roots.contains(gameVersions)) {
                roots.add(gameVersions);
            }
        }
        Map<String, Integer> manifestOrder = manifestOrder();
        List<String> installed = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Path versions : roots) {
            if (!Files.isDirectory(versions)) {
                continue;
            }
            try (var stream = Files.list(versions)) {
                for (Path path : stream.filter(Files::isDirectory).toList()) {
                    String id = path.getFileName().toString();
                    if (seenIds.contains(id)) {
                        continue;
                    }
                    if (!Files.isRegularFile(versions.resolve(id).resolve(id + ".json"))) {
                        // Не резервируем id за папкой без собственного <id>.json (например, за
                        // изолированной игровой папкой msc-launcher/Profiles/<id>, которую
                        // MinecraftLauncher создаёт для standalone OptiFine при запуске —
                        // там лежат только mods/saves, а не файл версии). Иначе такая пустая
                        // папка "занимает" id раньше, чем сканирование дойдёт до настоящей
                        // .minecraft/versions/<id> с валидным json — и версия навсегда
                        // пропадает из библиотеки после первого же запуска.
                        continue;
                    }
                    seenIds.add(id);
                    if (isMetadataOnlyVanillaVersion(gameDirectory, id, manifestOrder)) {
                        continue;
                    }
                    installed.add(id);
                }
            } catch (IOException ex) {
                throw new LauncherException("Не удалось прочитать версии: " + ex.getMessage(), ex);
            }
        }
        Map<String, String> baseVersions = new HashMap<>();
        for (String id : installed) {
            baseVersions.put(id, installedBaseVersion(gameDirectory, id));
        }
        List<String> deduped = deduplicateForgeVersions(installed, gameDirectory);
        Set<String> baseProfiles = collectBaseProfiles(deduped, gameDirectory, manifestOrder);
        return deduped.stream()
                .filter(id -> !baseProfiles.contains(id))
                .sorted(installedVersionComparator(baseVersions, manifestOrder))
                .toList();
    }

    private List<String> deduplicateForgeVersions(List<String> ids, Path gameDirectory) {
        // key → first seen id for that loader+mcVersion+loaderVersion combination
        Map<String, String> seen = new java.util.LinkedHashMap<>();
        Set<String> toRemove = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            // Skip modpack profiles and vanilla — they are never duplicates
            if (lower.startsWith("modrinth-") || lower.startsWith("curseforge-")) continue;
            if (isInheritingProfile(gameDirectory, id)) continue;

            String mcVersion = VersionSort.baseGameVersion(id);
            String key = null;

            // Forge / Forge+OptiFine
            if (lower.contains("forge") && !lower.contains("neo")) {
                String forgeNumber = extractLoaderNumber(id, mcVersion, "forge");
                if (!forgeNumber.isBlank()) {
                    key = "forge::" + mcVersion + "::" + forgeNumber;
                }
            }
            // NeoForge
            else if (lower.contains("neoforge")) {
                String neoNumber = extractLoaderNumber(id, mcVersion, "neoforge");
                if (!neoNumber.isBlank()) {
                    key = "neoforge::" + mcVersion + "::" + neoNumber;
                }
            }
            // Fabric
            else if (lower.contains("fabric")) {
                String loaderVer = extractFabricQuiltNumber(id, mcVersion, "fabric-loader-");
                if (!loaderVer.isBlank()) {
                    key = "fabric::" + mcVersion + "::" + loaderVer;
                }
            }
            // Quilt
            else if (lower.contains("quilt")) {
                String loaderVer = extractFabricQuiltNumber(id, mcVersion, "quilt-loader-");
                if (!loaderVer.isBlank()) {
                    key = "quilt::" + mcVersion + "::" + loaderVer;
                }
            }

            if (key == null) continue;
            if (seen.containsKey(key)) {
                toRemove.add(id);
            } else {
                seen.put(key, id);
            }
        }
        if (toRemove.isEmpty()) return ids;
        return ids.stream().filter(id -> !toRemove.contains(id)).toList();
    }

    /**
     * Returns the set of profile IDs that are used as the base ("inheritsFrom", possibly
     * transitively) by at least one installed profile, EXCLUDING plain vanilla ids — e.g. Forge
     * under a Forge+OptiFine install, a loader under a modpack profile (modrinth-*, curseforge-*,
     * msc-build-*), and so on.
     * <p>
     * These are hidden from the installed versions list so that installing Forge+OptiFine doesn't
     * also surface a separately-visible bare Forge entry underneath it, a modpack doesn't also
     * surface its underlying loader as its own entry, etc. Only the most-derived profile of each
     * such chain (the one nothing else inherits from) is shown; everything it needs is still on
     * disk underneath it, just not listed separately.
     * <p>
     * Vanilla ids (anything present in the Mojang version manifest) are deliberately never added
     * to this set: a user who installed vanilla for a game version expects it to keep showing up
     * in the library as its own playable entry even after installing Forge/Fabric/Quilt/NeoForge/
     * OptiFine for that same version, instead of one silently making the other disappear from
     * the list.
     * <p>
     * Standalone OptiFine is unaffected either way: the official OptiFine installer produces a
     * single self-contained profile with no inheritsFrom, so there is nothing to hide.
     */
    private Set<String> collectBaseProfiles(List<String> ids, Path gameDirectory, Map<String, Integer> manifestOrder) {
        Set<String> bases = new java.util.HashSet<>();
        for (String id : ids) {
            String current = id;
            Set<String> chainGuard = new java.util.HashSet<>();
            while (chainGuard.add(current)) {
                Path jsonPath = ProfileDirectories.versionsInstallDirectory(gameDirectory, current).resolve(current).resolve(current + ".json");
                String parent;
                try {
                    Map<String, Object> json = Json.object(Json.parse(Files.readString(jsonPath, StandardCharsets.UTF_8)));
                    parent = Json.string(json, "inheritsFrom");
                } catch (RuntimeException | IOException ignored) {
                    break;
                }
                if (parent.isBlank()) {
                    break;
                }
                // A plain vanilla id (a known release/snapshot from the Mojang manifest) must
                // never be hidden just because some loader for the same game version happens to
                // inherit from it — the user may well have installed vanilla on purpose and
                // expects it to keep showing up in the library next to Forge/Fabric/Quilt/
                // NeoForge/OptiFine profiles for that same version, instead of one silently
                // replacing the other in the list. Only intermediate loader scaffolding (e.g. a
                // bare Forge profile that exists solely to back a combined Forge+OptiFine
                // profile) should still be folded away.
                if (!manifestOrder.containsKey(parent)) {
                    bases.add(parent);
                }
                current = parent;
            }
        }
        return bases;
    }

    private String extractLoaderNumber(String id, String mcVersion, String loaderName) {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        String mcLower = mcVersion.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : List.of(
                mcLower + "-" + loaderName + "-",
                mcLower + "-" + loaderName,
                loaderName + "-" + mcLower + "-"
        )) {
            if (lower.startsWith(prefix)) {
                return id.substring(prefix.length()).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "";
    }

    private String extractFabricQuiltNumber(String id, String mcVersion, String prefix) {
        // fabric-loader-0.16.5-1.21.11 → "0.16.5"
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        String prefixLower = prefix.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith(prefixLower)) return "";
        String rest = id.substring(prefix.length()); // "0.16.5-1.21.11"
        String mcSuffix = "-" + mcVersion;
        int idx = rest.lastIndexOf(mcSuffix);
        if (idx > 0) return rest.substring(0, idx).toLowerCase(java.util.Locale.ROOT);
        return "";
    }

    private boolean isInheritingProfile(Path gameDirectory, String id) {
        Path jsonPath = ProfileDirectories.versionsInstallDirectory(gameDirectory, id).resolve(id).resolve(id + ".json");
        try {
            Map<String, Object> json = Json.object(Json.parse(Files.readString(jsonPath, StandardCharsets.UTF_8)));
            return !Json.string(json, "inheritsFrom").isBlank();
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }



    private boolean isMetadataOnlyVanillaVersion(Path gameDirectory, String id, Map<String, Integer> manifestOrder) {
        Path versionsDirectory = ProfileDirectories.versionsInstallDirectory(gameDirectory, id);
        if (!manifestOrder.containsKey(id) || Files.isRegularFile(versionsDirectory.resolve(id).resolve(id + ".jar"))) {
            return false;
        }
        Path jsonPath = versionsDirectory.resolve(id).resolve(id + ".json");
        try {
            Map<String, Object> json = Json.object(Json.parse(Files.readString(jsonPath, StandardCharsets.UTF_8)));
            return Json.string(json, "inheritsFrom").isBlank();
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private Comparator<String> installedVersionComparator(Map<String, String> baseVersions, Map<String, Integer> manifestOrder) {
        return (left, right) -> {
            String leftBase = baseVersions.getOrDefault(left, left);
            String rightBase = baseVersions.getOrDefault(right, right);
            int leftOrder = manifestOrder.getOrDefault(leftBase, Integer.MAX_VALUE);
            int rightOrder = manifestOrder.getOrDefault(rightBase, Integer.MAX_VALUE);
            if (leftOrder != rightOrder) {
                return Integer.compare(leftOrder, rightOrder);
            }
            int base = VersionSort.compareVersions(rightBase, leftBase);
            if (base != 0) {
                return base;
            }
            int kind = Integer.compare(VersionSort.profileKindWeight(left), VersionSort.profileKindWeight(right));
            if (kind != 0) {
                return kind;
            }
            return left.compareToIgnoreCase(right);
        };
    }

    private Map<String, Integer> manifestOrder() {
        try {
            HashMap<String, Integer> order = new HashMap<>();
            List<VersionInfo> versions = minecraftApi.fetchManifest().versions();
            for (int i = 0; i < versions.size(); i++) {
                order.put(versions.get(i).id(), i);
            }
            return order;
        } catch (LauncherException ex) {
            return Map.of();
        }
    }

    private String installedBaseVersion(Path gameDirectory, String id) {
        return installedBaseVersion(gameDirectory, id, new HashSet<>());
    }

    String installedGameVersion(Path gameDirectory, String id) {
        return installedBaseVersion(gameDirectory, id);
    }

    private String installedBaseVersion(Path gameDirectory, String id, HashSet<String> chain) {
        if (!chain.add(id)) {
            return VersionSort.baseGameVersion(id);
        }
        Path jsonPath = ProfileDirectories.versionsInstallDirectory(gameDirectory, id).resolve(id).resolve(id + ".json");
        try {
            Map<String, Object> json = Json.object(Json.parse(Files.readString(jsonPath, StandardCharsets.UTF_8)));
            String parent = Json.string(json, "inheritsFrom");
            if (!parent.isBlank()) {
                Path parentJson = ProfileDirectories.versionsInstallDirectory(gameDirectory, parent).resolve(parent).resolve(parent + ".json");
                if (Files.isRegularFile(parentJson)) {
                    return installedBaseVersion(gameDirectory, parent, chain);
                }
                return parent;
            }
        } catch (RuntimeException | IOException ignored) {
            // Fall through to profile-id heuristics for hand-made or damaged version JSONs.
        }
        return VersionSort.baseGameVersion(id);
    }

    void installVanilla(String versionId, LauncherSettings settings, ProgressSink progress) {
        progress.status("Установка vanilla " + versionId);
        ensureVersionJson(versionId, settings.gameDirectory(), progress);
        ResolvedVersion version = resolve(versionId, settings.gameDirectory(), progress);

        // Download client, libraries and assets in parallel to speed up installation
        ExecutorService exec = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            Future<?> clientFuture = exec.submit(() -> downloadClient(version, settings.gameDirectory(), progress));
            Future<List<Path>> libsFuture = exec.submit(() -> downloadLibraries(version, settings.gameDirectory(), progress));
            Future<?> assetsFuture = exec.submit(() -> { downloadAssets(version, settings.gameDirectory(), progress); return null; });

            // wait for all to complete
            clientFuture.get();
            libsFuture.get();
            assetsFuture.get();
            installedVersionCache.markReady(version, settings.gameDirectory(), progress);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new LauncherException("Ошибка при установке: " + (cause == null ? ex.getMessage() : cause.getMessage()), cause == null ? ex : cause);
        } finally {
            exec.shutdownNow();
        }
    }

    void ensureVanillaMetadata(String versionId, LauncherSettings settings, ProgressSink progress) {
        ensureVersionJson(versionId, settings.gameDirectory(), progress);
    }

    /**
     * Убеждается, что ванильный client.jar для versionId скачан на диск.
     * <p>
     * Официальный установщик OptiFine (и кнопка "Install", и кнопка "Extract")
     * патчит именно локальный versions/&lt;id&gt;/&lt;id&gt;.jar — если его нет
     * (например, эта версия ещё ни разу не запускалась), установщик либо
     * показывает "File not found", либо тихо не создаёт валидный mod jar.
     */
    void ensureVanillaClientJar(String versionId, LauncherSettings settings, ProgressSink progress) {
        ensureVanillaMetadata(versionId, settings, progress);
        ResolvedVersion version = resolve(versionId, settings.gameDirectory(), progress);
        downloadClient(version, settings.gameDirectory(), progress);
    }

    String installFabric(String gameVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Поиск Fabric Loader");
        String loaderVersion = fabricApi.latestLoaderFor(gameVersion);
        return installFabric(gameVersion, loaderVersion, settings, progress);
    }

    String latestFabricLoaderFor(String gameVersion) {
        return fabricApi.latestLoaderFor(gameVersion);
    }

    String installFabric(String gameVersion, String loaderVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Установка Fabric Loader");
        progress.log("Fabric Loader: " + loaderVersion);
        // Check if this exact Fabric profile is already installed.
        String candidateId = "fabric-loader-" + loaderVersion + "-" + gameVersion;
        Path candidateDir = ProfileDirectories.versionsInstallDirectory().resolve(candidateId);
        if (Files.isRegularFile(candidateDir.resolve(candidateId + ".json"))) {
            progress.log("Fabric уже установлен, пропуск: " + candidateId);
            return candidateId;
        }
        // Also check any existing fabric profile for the same game version / loader version.
        List<String> installed = listInstalledVersions(settings.gameDirectory());
        String gameVersionLower = gameVersion.toLowerCase(java.util.Locale.ROOT);
        String loaderVersionLower = loaderVersion.toLowerCase(java.util.Locale.ROOT);
        for (String id : installed) {
            String idLower = id.toLowerCase(java.util.Locale.ROOT);
            if (idLower.contains("fabric") && idLower.contains(gameVersionLower) && idLower.contains(loaderVersionLower)) {
                progress.log("Fabric уже установлен (широкая проверка), пропуск: " + id);
                return id;
            }
        }
        String profileJson = fabricApi.fetchProfileJson(gameVersion, loaderVersion);
        Map<String, Object> profile = Json.object(Json.parse(profileJson));
        String id = Json.string(profile, "id");
        if (id.isBlank()) {
            id = candidateId;
        }
        Path versionDir = ProfileDirectories.versionsInstallDirectory().resolve(id);
        try {
            Files.createDirectories(versionDir);
            Files.writeString(versionDir.resolve(id + ".json"), profileJson, StandardCharsets.UTF_8);
            progress.log("Fabric профиль сохранён: " + id);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось сохранить Fabric профиль: " + ex.getMessage(), ex);
        }
        ensureVanillaMetadata(gameVersion, settings, progress);
        ResolvedVersion version = resolve(id, settings.gameDirectory(), progress);
        downloadLibraries(version, settings.gameDirectory(), progress);
        return id;
    }

    String installQuilt(String gameVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Поиск Quilt Loader");
        String loaderVersion = quiltApi.latestLoaderFor(gameVersion);
        return installQuilt(gameVersion, loaderVersion, settings, progress);
    }

    String latestQuiltLoaderFor(String gameVersion) {
        return quiltApi.latestLoaderFor(gameVersion);
    }

    String installQuilt(String gameVersion, String loaderVersion, LauncherSettings settings, ProgressSink progress) {
        progress.status("Установка Quilt Loader");
        progress.log("Quilt Loader: " + loaderVersion);
        // Check if this exact Quilt profile is already installed.
        String candidateId = "quilt-loader-" + loaderVersion + "-" + gameVersion;
        Path candidateDir = ProfileDirectories.versionsInstallDirectory().resolve(candidateId);
        if (Files.isRegularFile(candidateDir.resolve(candidateId + ".json"))) {
            progress.log("Quilt уже установлен, пропуск: " + candidateId);
            return candidateId;
        }
        List<String> installed = listInstalledVersions(settings.gameDirectory());
        String gameVersionLower = gameVersion.toLowerCase(java.util.Locale.ROOT);
        String loaderVersionLower = loaderVersion.toLowerCase(java.util.Locale.ROOT);
        for (String id : installed) {
            String idLower = id.toLowerCase(java.util.Locale.ROOT);
            if (idLower.contains("quilt") && idLower.contains(gameVersionLower) && idLower.contains(loaderVersionLower)) {
                progress.log("Quilt уже установлен (широкая проверка), пропуск: " + id);
                return id;
            }
        }
        String profileJson = quiltApi.fetchProfileJson(gameVersion, loaderVersion);
        Map<String, Object> profile = Json.object(Json.parse(profileJson));
        String id = Json.string(profile, "id");
        if (id.isBlank()) {
            id = candidateId;
        }
        Path versionDir = ProfileDirectories.versionsInstallDirectory().resolve(id);
        try {
            Files.createDirectories(versionDir);
            Files.writeString(versionDir.resolve(id + ".json"), profileJson, StandardCharsets.UTF_8);
            progress.log("Quilt профиль сохранён: " + id);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось сохранить Quilt профиль: " + ex.getMessage(), ex);
        }
        ensureVanillaMetadata(gameVersion, settings, progress);
        ResolvedVersion version = resolve(id, settings.gameDirectory(), progress);
        downloadLibraries(version, settings.gameDirectory(), progress);
        return id;
    }

    ResolvedVersion prepareForLaunch(String versionId, LauncherSettings settings, ProgressSink progress) {
        ResolvedVersion version = resolve(versionId, settings.gameDirectory(), progress);
        if (installedVersionCache.isReady(version, settings.gameDirectory())) {
            progress.status("Запуск установленной версии " + versionId);
            progress.log("Версия уже готова: используются сохранённые локальные файлы.");
            return version;
        }
        if (installedVersionCache.adoptExistingInstall(version, settings.gameDirectory(), progress)) {
            progress.status("Запуск установленной версии " + versionId);
            progress.log("Найдена готовая версия из предыдущего лаунчера; повторная установка не нужна.");
            return version;
        }
        progress.log("Проверка и восстановление файлов версии " + versionId);
        downloadClient(version, settings.gameDirectory(), progress);
        downloadLibraries(version, settings.gameDirectory(), progress);
        downloadAssets(version, settings.gameDirectory(), progress);
        installedVersionCache.markReady(version, settings.gameDirectory(), progress);
        return version;
    }

    ResolvedVersion resolve(String versionId, Path gameDirectory, ProgressSink progress) {
        return resolve(versionId, gameDirectory, progress, new ArrayList<>());
    }

    private ResolvedVersion resolve(String versionId, Path gameDirectory, ProgressSink progress, List<String> chain) {
        if (chain.contains(versionId)) {
            throw new LauncherException("Циклическое наследование version JSON: " + chain + " -> " + versionId);
        }
        chain.add(versionId);
        Map<String, Object> json = readVersionJson(versionId, gameDirectory, progress);
        String parent = Json.string(json, "inheritsFrom");
        // Self-heal a known corruption pattern from an older bug: a "...-optifine" profile whose
        // own inheritsFrom was mistakenly written as itself, which otherwise always throws the
        // cycle error above. Strip the stray "-optifine" suffix, persist the corrected JSON so
        // this only needs to happen once, and continue resolving normally.
        if (parent.equals(versionId) && versionId.toLowerCase(java.util.Locale.ROOT).endsWith("-optifine")) {
            String fixedParent = versionId.substring(0, versionId.length() - "-optifine".length());
            progress.log("Исправлен профиль \"" + versionId + "\": inheritsFrom указывал сам на себя, "
                    + "заменён на \"" + fixedParent + "\".");
            json.put("inheritsFrom", fixedParent);
            try {
                Files.writeString(versionJsonPath(gameDirectory, versionId), Json.stringify(json), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new LauncherException("Не удалось исправить version JSON " + versionId + ": " + ex.getMessage(), ex);
            }
            parent = fixedParent;
        }
        if (parent.isBlank()) {
            return ResolvedVersion.fromJson(versionId, json);
        }
        ResolvedVersion parentVersion = resolve(parent, gameDirectory, progress, chain);
        return parentVersion.mergeChild(json, versionId);
    }

    private Map<String, Object> readVersionJson(String versionId, Path gameDirectory, ProgressSink progress) {
        Path jsonPath = versionJsonPath(gameDirectory, versionId);
        if (!Files.isRegularFile(jsonPath)) {
            ensureVersionJson(versionId, gameDirectory, progress);
        }
        try {
            return Json.object(Json.parse(Files.readString(jsonPath, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new LauncherException("Не удалось прочитать " + jsonPath + ": " + ex.getMessage(), ex);
        }
    }

    private void ensureVersionJson(String versionId, Path gameDirectory, ProgressSink progress) {
        Path jsonPath = versionJsonPath(gameDirectory, versionId);
        if (Files.isRegularFile(jsonPath)) {
            return;
        }
        if (isStandaloneOptiFineProfile(versionId)) {
            if (copyExistingStandaloneOptiFineJson(versionId, gameDirectory, jsonPath, progress)) {
                return;
            }
            writeStandaloneOptiFineJson(versionId, jsonPath, progress);
            return;
        }
        progress.status("Загрузка JSON версии " + versionId);
        String json = minecraftApi.fetchVersionJson(versionId);
        try {
            Files.createDirectories(jsonPath.getParent());
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
            progress.log("Version JSON сохранён: " + versionId);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось сохранить JSON версии " + versionId + ": " + ex.getMessage(), ex);
        }
    }

    private boolean isStandaloneOptiFineProfile(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        return lower.contains("optifine")
                && !lower.contains("forge")
                && !VersionSort.baseGameVersion(versionId).equals(versionId);
    }

    private boolean copyExistingStandaloneOptiFineJson(String versionId, Path gameDirectory, Path target, ProgressSink progress) {
        ArrayList<Path> candidates = new ArrayList<>();
        if (gameDirectory != null) {
            candidates.add(gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json"));
        }
        candidates.add(Path.of(System.getProperty("user.home"), ".minecraft", "versions", versionId, versionId + ".json"));
        candidates.add(ProfileDirectories.versionsInstallDirectory().resolve(versionId).resolve(versionId + ".json"));

        Path normalizedTarget = target.toAbsolutePath().normalize();
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Path normalizedCandidate = candidate.toAbsolutePath().normalize();
            if (normalizedCandidate.equals(normalizedTarget) || !Files.isRegularFile(normalizedCandidate)) {
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.copy(normalizedCandidate, target, StandardCopyOption.REPLACE_EXISTING);
                progress.log("OptiFine profile JSON copied: " + versionId);
                return true;
            } catch (IOException ex) {
                progress.log("Не удалось скопировать JSON профиля OptiFine " + versionId + ": " + ex.getMessage());
            }
        }
        return false;
    }

    private void writeStandaloneOptiFineJson(String versionId, Path target, ProgressSink progress) {
        String gameVersion = VersionSort.baseGameVersion(versionId);
        String optiFineLibraryVersion = optiFineLibraryVersion(versionId, gameVersion);
        LinkedHashMap<String, Object> json = new LinkedHashMap<>();
        String now = Instant.now().toString();
        json.put("id", versionId);
        json.put("inheritsFrom", gameVersion);
        json.put("time", now);
        json.put("releaseTime", now);
        json.put("type", "release");

        ArrayList<Object> libraries = new ArrayList<>();
        libraries.add(Map.of("name", "optifine:OptiFine:" + optiFineLibraryVersion));
        libraries.add(Map.of("name", "optifine:launchwrapper-of:" + optiFineLaunchWrapperVersion(gameVersion)));
        json.put("libraries", libraries);

        json.put("mainClass", "net.minecraft.launchwrapper.Launch");
        json.put("arguments", Map.of("game", List.of("--tweakClass", "optifine.OptiFineTweaker")));

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, Json.stringify(json), StandardCharsets.UTF_8);
            progress.log("Восстановлен JSON профиля OptiFine: " + versionId);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось восстановить JSON профиля OptiFine " + versionId + ": " + ex.getMessage(), ex);
        }
    }

    private String optiFineLibraryVersion(String versionId, String gameVersion) {
        String marker = "-OptiFine_";
        int markerIndex = versionId.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if (markerIndex >= 0) {
            return gameVersion + "_" + versionId.substring(markerIndex + marker.length());
        }
        String prefix = "OptiFine_" + gameVersion + "_";
        if (versionId.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return gameVersion + "_" + versionId.substring(prefix.length());
        }
        return gameVersion;
    }

    private String optiFineLaunchWrapperVersion(String gameVersion) {
        return VersionSort.compareVersions(gameVersion, "1.14") >= 0 ? "2.3" : "2.2";
    }

    private void downloadClient(ResolvedVersion version, Path gameDirectory, ProgressSink progress) {
        Map<String, Object> client = version.clientDownload();
        String url = Json.string(client, "url");
        if (url.isBlank()) {
            progress.log("Client jar отсутствует в metadata для " + version.id());
            return;
        }
        Path jar = versionJarPath(gameDirectory, version.rootId());
        minecraftApi.http().download(url, jar, Json.string(client, "sha1"), Json.longValue(client, "size", -1), progress);
    }

    List<Path> downloadLibraries(ResolvedVersion version, Path gameDirectory, ProgressSink progress) {
        List<Path> classpath = new ArrayList<>();
        List<Callable<Path>> tasks = new ArrayList<>();
        boolean preferInstalledFiles = installedVersionCache.isReady(version, gameDirectory);

        // build tasks in order so results can be appended preserving original order
        for (Object item : version.libraries()) {
            Map<String, Object> library = Json.object(item);
            if (!RuleEvaluator.isAllowed(library)) {
                continue;
            }
            Map<String, Object> downloads = Json.object(library, "downloads");
            Map<String, Object> artifact = Json.object(downloads, "artifact");
            if (!artifact.isEmpty()) {
                Map<String, Object> libCopy = library;
                Map<String, Object> artCopy = artifact;
                tasks.add(() -> downloadLibraryArtifact(libCopy, artCopy, gameDirectory, progress,
                        preferInstalledFiles));
            } else if (Json.object(library, "natives").isEmpty()) {
                MavenArtifact fallback = MavenArtifact.fromLibrary(library);
                if (fallback != null) {
                    String url = fallback.url();
                    String path = fallback.path();
                    tasks.add(() -> {
                        Path target = ProfileDirectories.librariesInstallDirectory().resolve(path);
                        if (!preferInstalledFiles || !InstalledVersionCache.looksUsable(target, -1)) {
                            minecraftApi.http().download(url, target, "", -1, progress);
                        }
                        return target;
                    });
                }
            } else {
                progress.log("Native-only library skipped from classpath: " + Json.string(library, "name"));
            }
        }

        if (!tasks.isEmpty()) {
            int threads = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Path>> futures = exec.invokeAll(tasks);
                for (Future<Path> f : futures) {
                    try {
                        classpath.add(f.get());
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new LauncherException("Ошибка при скачивании библиотеки: " + (cause == null ? ex.getMessage() : cause.getMessage()), cause == null ? ex : cause);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LauncherException("Операция прервана.", ex);
            } finally {
                exec.shutdownNow();
            }
        }

        // ВАЖНО: version jar (versions/<id>/<id>.jar — ванильный клиентский jar с обфусцированными
        // именами классов Mojang) должен попадать в classpath ВСЕГДА, даже для современного Forge/
        // NeoForge с mainClass = cpw.mods.bootstraplauncher.BootstrapLauncher. Раньше здесь было
        // исключение для bootstraplauncher/modlauncher (в предположении, что модульная система сама
        // находит клиентский jar), но версия JSON Forge подставляет ту же самую переменную
        // ${classpath} не только в "-cp", но и в JVM-аргумент "-DlegacyClassPath=${classpath}" —
        // именно через это системное свойство OptiFine (работающий как LAYER SERVICE
        // optifine.OptiFineTransformer) находит и патчит ванильные классы по их обфусцированным
        // Mojang-именам (fke, eip, eio, fjq и т.п.). Без jar'а в classpath/legacyClassPath OptiFine
        // получает "Base resource not found: <имя>.class" на каждый такой класс и падает при
        // создании FontManager/Minecraft. Обычный Forge без OptiFine при этом запускается нормально
        // (FML умеет находить client.jar по пути versions/<id>/<id>.jar независимо от classpath),
        // поэтому баг был незаметен до использования OptiFine — Modrinth и официальный лаунчер
        // такого исключения не делают и всегда включают version jar в classpath.
        classpath.add(classpathVersionJar(version, gameDirectory));
        return classpath;
    }

    private Path classpathVersionJar(ResolvedVersion version, Path gameDirectory) {
        Path childJar = versionJarPath(gameDirectory, version.id());
        if (!version.id().equals(version.rootId()) && Files.isRegularFile(childJar)) {
            return childJar;
        }
        return versionJarPath(gameDirectory, version.rootId());
    }

    List<NativeLibrary> downloadNativeLibraries(ResolvedVersion version, Path gameDirectory, ProgressSink progress) {
        List<NativeLibrary> natives = new ArrayList<>();
        String os = OsRules.currentOsName();
        boolean preferInstalledFiles = installedVersionCache.isReady(version, gameDirectory);
        for (Object item : version.libraries()) {
            Map<String, Object> library = Json.object(item);
            if (!RuleEvaluator.isAllowed(library)) {
                continue;
            }
            Map<String, Object> nativesMap = Json.object(library, "natives");
            String classifierName = Json.string(nativesMap, os).replace("${arch}", OsRules.archBits());
            if (classifierName.isBlank()) {
                continue;
            }
            Map<String, Object> classifiers = Json.object(Json.object(library, "downloads"), "classifiers");
            Map<String, Object> classifier = Json.object(classifiers, classifierName);
            if (classifier.isEmpty()) {
                continue;
            }
            Path path = downloadLibraryArtifact(library, classifier, gameDirectory, progress,
                    preferInstalledFiles);
            List<String> excludes = new ArrayList<>();
            for (Object exclude : Json.list(Json.object(library, "extract"), "exclude")) {
                excludes.add(Json.string(exclude));
            }
            natives.add(new NativeLibrary(path, excludes));
        }
        return natives;
    }

    private Path downloadLibraryArtifact(Map<String, Object> library, Map<String, Object> artifact, Path gameDirectory, ProgressSink progress) {
        return downloadLibraryArtifact(library, artifact, gameDirectory, progress, false);
    }

    private Path downloadLibraryArtifact(Map<String, Object> library, Map<String, Object> artifact,
                                         Path gameDirectory, ProgressSink progress,
                                         boolean preferInstalledFiles) {
        String path = Json.string(artifact, "path");
        String url = Json.string(artifact, "url");
        if (path.isBlank()) {
            MavenArtifact fallback = MavenArtifact.fromLibrary(library);
            if (fallback == null) {
                throw new LauncherException("Некорректная библиотека без path: " + Json.string(library, "name"));
            }
            path = fallback.path();
            url = fallback.url();
        }
        if (url.isBlank()) {
            MavenArtifact fallback = MavenArtifact.fromLibrary(library);
            if (fallback == null) {
                throw new LauncherException("Некорректная библиотека без URL: " + Json.string(library, "name"));
            }
            url = fallback.baseUrl() + path.replace("\\", "/");
        }
        Path target = ProfileDirectories.librariesInstallDirectory().resolve(path.replace("/", java.io.File.separator));
        long expectedSize = Json.longValue(artifact, "size", -1);
        if (preferInstalledFiles && InstalledVersionCache.looksUsable(target, expectedSize)) {
            return target;
        }
        if (!Files.isRegularFile(target) && gameDirectory != null) {
            // Some libraries (notably OptiFine's own "optifine:OptiFine:<id>" artifact) are
            // never actually hosted on a real Maven/CDN — the official installer just drops the
            // jar straight into .minecraft/libraries and expects it to already be there. If our
            // shared libraries folder doesn't have it yet but the real .minecraft does, copy it
            // over instead of trying (and failing, with a 404) to download it.
            Path fromGameDirectory = gameDirectory.resolve("libraries").resolve(path.replace("/", java.io.File.separator));
            if (Files.isRegularFile(fromGameDirectory)) {
                try {
                    Files.createDirectories(target.getParent());
                    Files.copy(fromGameDirectory, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    progress.log("Библиотека скопирована из .minecraft/libraries: " + target.getFileName());
                    return target;
                } catch (IOException ex) {
                    progress.log("Не удалось скопировать библиотеку из .minecraft/libraries: " + ex.getMessage());
                }
            }
        }
        minecraftApi.http().download(url, target, Json.string(artifact, "sha1"), expectedSize, progress);
        return target;
    }

    void downloadAssets(ResolvedVersion version, Path gameDirectory, ProgressSink progress) {
        Map<String, Object> assetIndex = version.assetIndex();
        String id = Json.string(assetIndex, "id");
        String url = Json.string(assetIndex, "url");
        if (id.isBlank() || url.isBlank()) {
            progress.log("Asset index отсутствует для " + version.id());
            return;
        }
        Path indexPath = ProfileDirectories.assetsInstallDirectory().resolve("indexes").resolve(id + ".json");
        minecraftApi.http().download(url, indexPath, Json.string(assetIndex, "sha1"), Json.longValue(assetIndex, "size", -1), progress);

        Map<String, Object> index;
        try {
            index = Json.object(Json.parse(Files.readString(indexPath, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new LauncherException("Не удалось прочитать asset index: " + ex.getMessage(), ex);
        }
        Map<String, Object> objects = Json.object(index, "objects");
        boolean legacyAssets = isLegacyAssets(version);
        boolean virtualAssets = legacyAssets || Json.bool(index, "virtual", false);
        boolean resourcesAssets = Json.bool(index, "map_to_resources", false);
        String virtualRootName = version.assets().isBlank() ? id : version.assets();

        // download assets in parallel; report completion count
        int total = objects.size();
        java.util.concurrent.atomic.AtomicInteger doneCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Map.Entry<String, Object> entry : objects.entrySet()) {
            String logicalName = entry.getKey();
            Object value = entry.getValue();
            Map<String, Object> object = Json.object(value);
            String hash = Json.string(object, "hash");
            if (hash.length() < 2) {
                continue;
            }
            String objectUrl = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            long size = Json.longValue(object, "size", -1);
            tasks.add(() -> {
                Path target = ProfileDirectories.assetsInstallDirectory().resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                minecraftApi.http().download(objectUrl, target, hash, size, progress);
                if (virtualAssets) {
                    copyAsset(target, ProfileDirectories.assetsInstallDirectory().resolve("virtual").resolve(virtualRootName), logicalName, size);
                }
                if (resourcesAssets) {
                    copyAsset(target, ProfileDirectories.installRoot().resolve("resources"), logicalName, size);
                }
                int done = doneCounter.incrementAndGet();
                progress.progress(done, total);
                return null;
            });
        }

        if (!tasks.isEmpty()) {
            int threads = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors()));
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Void>> futures = exec.invokeAll(tasks);
                for (Future<Void> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new LauncherException("Ошибка при скачивании asset: " + (cause == null ? ex.getMessage() : cause.getMessage()), cause == null ? ex : cause);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LauncherException("Операция прервана.", ex);
            } finally {
                exec.shutdownNow();
            }
        }
    }

    private boolean isLegacyAssets(ResolvedVersion version) {
        String assets = version.assets();
        return "legacy".equals(assets) || "pre-1.6".equals(assets);
    }

    private void copyAsset(Path source, Path root, String logicalName, long expectedSize) {
        Path normalizedRoot = root.normalize();
        Path target = normalizedRoot.resolve(logicalName).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new LauncherException("Некорректный путь asset: " + logicalName);
        }
        try {
            if (Files.isRegularFile(target) && (expectedSize <= 0 || Files.size(target) == expectedSize)) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new LauncherException("Не удалось разложить legacy asset " + logicalName + ": " + ex.getMessage(), ex);
        }
    }

    Path versionJsonPath(Path gameDirectory, String versionId) {
        return ProfileDirectories.versionsInstallDirectory(gameDirectory, versionId).resolve(versionId).resolve(versionId + ".json");
    }

    Path versionJarPath(Path gameDirectory, String versionId) {
        return ProfileDirectories.versionsInstallDirectory(gameDirectory, versionId).resolve(versionId).resolve(versionId + ".jar");
    }

    private record MavenArtifact(String path, String baseUrl) {
        static MavenArtifact fromLibrary(Map<String, Object> library) {
            String name = Json.string(library, "name");
            if (name.isBlank()) {
                return null;
            }
            String extension = "jar";
            int extensionMarker = name.indexOf('@');
            if (extensionMarker >= 0) {
                extension = name.substring(extensionMarker + 1);
                name = name.substring(0, extensionMarker);
            }
            String[] parts = name.split(":");
            if (parts.length < 3) {
                return null;
            }
            String group = parts[0].replace('.', '/');
            String artifact = parts[1];
            String version = parts[2];
            String classifier = parts.length >= 4 ? "-" + parts[3] : "";
            String file = artifact + "-" + version + classifier + "." + extension;
            String path = group + "/" + artifact + "/" + version + "/" + file;
            String base = Json.string(library, "url");
            if (base.isBlank()) {
                base = "https://libraries.minecraft.net/";
            }
            if (!base.endsWith("/")) {
                base += "/";
            }
            return new MavenArtifact(path, base);
        }

        String url() {
            return baseUrl + path;
        }
    }

    record NativeLibrary(Path path, List<String> excludes) {
    }
}
