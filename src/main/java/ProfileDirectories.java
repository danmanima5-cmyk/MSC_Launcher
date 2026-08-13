import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ProfileDirectories {
    private static final String LEGACY_PATH_MIGRATION_MARKER = ".msc-profile-path-migrated";

    private ProfileDirectories() {
    }

    /**
     * Root directory under which shared libraries and assets (and, for most profile kinds,
     * per-version JSON/jar files too) are stored. This is always inside the launcher's own
     * {@code msc-launcher} settings folder, never inside the user's chosen {@code .minecraft}
     * directory. The one exception is per-version JSON/jar files for vanilla and standalone
     * OptiFine — see {@link #versionsInstallDirectory(Path, String)} — which are installed
     * straight into the real {@code .minecraft/versions}, matching the official launcher.
     */
    static Path installRoot() {
        return LauncherSettings.settingsDirectory();
    }

    /** Where per-version {@code <id>/<id>.json} and {@code <id>/<id>.jar} files are stored. */
    static Path versionsInstallDirectory() {
        return installRoot().resolve("Profiles");
    }

    /**
     * True for plain vanilla version ids and standalone OptiFine ids (OptiFine installed
     * directly on top of vanilla — not the Forge+OptiFine combo, and not a loader or modpack).
     * These two kinds are installed straight into the real, shared {@code .minecraft/versions}
     * — the same convention the official Minecraft Launcher (and tools like the Modrinth App)
     * use — instead of the launcher's own {@code msc-launcher/Profiles}, so they show up for
     * and can be reused by other launchers without any extra copying.
     * <p>
     * Everything else (Forge, Forge+OptiFine, NeoForge, Fabric, Quilt, and modpacks) still goes
     * into {@code msc-launcher/Profiles}, since those need the isolation {@link #isolatedRoot}
     * already gives them anyway.
     */
    static boolean storesInGameVersionsFolder(String versionId) {
        if (versionId == null) {
            return true;
        }
        if (Files.isDirectory(profileDirectory(versionId))) {
            return false;
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.startsWith("modrinth-") || lower.startsWith("curseforge-") || lower.startsWith("msc-build-")) {
            return false;
        }
        return !(lower.contains("forge") || lower.contains("fabric") || lower.contains("quilt"));
    }

    /**
     * Where a specific version's {@code <id>/<id>.json} and {@code <id>/<id>.jar} live: the
     * real {@code .minecraft/versions} for vanilla and standalone OptiFine (see
     * {@link #storesInGameVersionsFolder}), or {@link #versionsInstallDirectory()} for
     * everything else. Falls back to the latter if {@code gameDirectory} is unknown.
     */
    static Path versionsInstallDirectory(Path gameDirectory, String versionId) {
        if (gameDirectory != null && storesInGameVersionsFolder(versionId)) {
            return gameDirectory.resolve("versions");
        }
        return versionsInstallDirectory();
    }

    /** Shared library jars, common to every installed version. */
    static Path librariesInstallDirectory() {
        return installRoot().resolve("libraries");
    }

    /** Shared asset objects/indexes, common to every installed version. */
    static Path assetsInstallDirectory() {
        return installRoot().resolve("assets");
    }

    /**
     * Standalone OptiFine's official installer is now launched directly inside the real
     * {@code .minecraft} (see {@code OptiFineService#installStandalone}), so its own hardcoded
     * {@code libraries/optifine/...} output lands in {@code .minecraft/libraries} — the same
     * layout the official Minecraft Launcher uses. Library resolution in this launcher, though,
     * always looks under the shared {@link #librariesInstallDirectory()}, so copy (never move —
     * this folder is shared with other launchers) that one small "optifine" subtree there too,
     * right after the installer finishes. Without this, downloading libraries for that profile
     * would not find the jar locally and would try (and fail, with a 404) to fetch it from
     * Mojang's library CDN, which never hosted OptiFine.
     */
    static void migrateOptiFineLibraries(Path gameDirectory, ProgressSink progress) {
        if (gameDirectory == null) {
            return;
        }
        Path source = gameDirectory.resolve("libraries").resolve("optifine");
        if (!Files.isDirectory(source)) {
            return;
        }
        Path dest = librariesInstallDirectory().resolve("optifine");
        try {
            copyTree(source, dest);
        } catch (IOException ex) {
            progress.log("Не удалось скопировать библиотеки OptiFine: " + ex.getMessage());
        }
    }

    /**
     * Official Forge/NeoForge/OptiFine installer jars are pointed at {@link #installRoot()}
     * (never the user's real {@code .minecraft}), but they always create their own hardcoded
     * {@code versions/<id>} subfolder there — that folder name is baked into those external
     * jars and can't be configured. Call this right after such an installer finishes to fold
     * whatever it just wrote into our canonical {@code Profiles/<id>} location instead.
     */
    static void migrateOfficialInstallerVersions(ProgressSink progress) {
        Path produced = installRoot().resolve("versions");
        if (!Files.isDirectory(produced)) {
            return;
        }
        Path target = versionsInstallDirectory();
        try {
            Files.createDirectories(target);
        } catch (IOException ex) {
            progress.log("Не удалось подготовить msc-launcher/Profiles: " + ex.getMessage());
            return;
        }
        try (var stream = Files.list(produced)) {
            for (Path versionDir : stream.filter(Files::isDirectory).toList()) {
                Path dest = target.resolve(versionDir.getFileName().toString());
                try {
                    if (Files.exists(dest)) {
                        mergeTree(versionDir, dest);
                        deleteTree(versionDir);
                    } else {
                        Files.move(versionDir, dest);
                    }
                } catch (IOException ex) {
                    progress.log("Не удалось перенести " + versionDir.getFileName() + " в msc-launcher/Profiles: " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            progress.log("Не удалось прочитать временную папку versions установщика: " + ex.getMessage());
        }
        deleteIfEmpty(produced);
    }

    /**
     * Makes manually copied version folders show up without needing the launcher to have
     * downloaded them itself. Three things trip people up when they just drag a folder in:
     * <ol>
     *   <li>They drop it in {@code .minecraft/versions} (the vanilla launcher / Modrinth App
     *       convention) instead of {@code msc-launcher/Profiles} — so we also import from
     *       there (copying, since that folder is shared with other launchers and we
     *       shouldn't delete anyone else's data out of it).</li>
     *   <li>They drop it in {@code msc-launcher/versions} — already handled by
     *       {@link #migrateOfficialInstallerVersions}, invoked here too so both cases are
     *       covered by one call.</li>
     *   <li>The json file inside doesn't share the folder's exact name (case difference, a
     *       generic name like {@code version.json}, a trailing " (1)" from the browser/OS
     *       adding a duplicate suffix, etc.) — every other part of this launcher assumes
     *       {@code <id>/<id>.json}, so we normalize that here rather than loosen the
     *       assumption everywhere it's relied on.</li>
     * </ol>
     * Call this before listing installed versions so freshly copied folders are picked up
     * immediately, without requiring a restart.
     */
    static void importCopiedVersions(Path gameDirectory, ProgressSink progress) {
        migrateOfficialInstallerVersions(progress);

        Path target = versionsInstallDirectory();
        try {
            Files.createDirectories(target);
        } catch (IOException ex) {
            progress.log("Не удалось подготовить msc-launcher/Profiles: " + ex.getMessage());
            return;
        }

        if (gameDirectory != null) {
            Path vanillaVersions = gameDirectory.resolve("versions");
            if (Files.isDirectory(vanillaVersions) && !vanillaVersions.equals(target)) {
                try (var stream = Files.list(vanillaVersions)) {
                    for (Path versionDir : stream.filter(Files::isDirectory).toList()) {
                        String id = versionDir.getFileName().toString();
                        // Vanilla and standalone-OptiFine folders are already in their canonical
                        // home here (see storesInGameVersionsFolder) — only fold Forge/Fabric/
                        // Quilt/modpack folders that someone dropped into .minecraft/versions by
                        // habit into msc-launcher/Profiles, where those kinds actually live.
                        if (storesInGameVersionsFolder(id)) {
                            continue;
                        }
                        Path dest = target.resolve(id);
                        if (Files.exists(dest)) {
                            continue;
                        }
                        try {
                            copyTree(versionDir, dest);
                            progress.log("Скопирован профиль из .minecraft/versions: " + id);
                        } catch (IOException ex) {
                            progress.log("Не удалось скопировать " + id + " из .minecraft/versions: " + ex.getMessage());
                        }
                    }
                } catch (IOException ex) {
                    progress.log("Не удалось прочитать .minecraft/versions: " + ex.getMessage());
                }
                normalizeFolderNaming(vanillaVersions, progress);
            }
        }

        normalizeFolderNaming(target, progress);
        reconcileLegacySanitizedProfiles(target, progress);
    }

    /**
     * Returns the canonical directory for an isolated profile id. A valid direct child name is
     * preserved verbatim, including spaces and parentheses used by duplicate ids such as
     * {@code NeoForge_1.21.1 (1)}. Sanitizing that id produced a second directory named
     * {@code NeoForge_1.21.1 _1_}, so the version JSON and its mods ended up in different places.
     */
    static Path profileDirectory(String versionId) {
        Path root = versionsInstallDirectory().toAbsolutePath().normalize();
        return directChildOrSanitized(root, versionId);
    }

    private static Path directChildOrSanitized(Path root, String versionId) {
        String id = versionId == null ? "" : versionId.trim();
        if (!id.isBlank() && !".".equals(id) && !"..".equals(id)
                && !id.contains("/") && !id.contains("\\")) {
            try {
                Path direct = root.resolve(id).normalize();
                if (direct.getParent() != null && direct.getParent().equals(root)) {
                    return direct;
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // Fall through to the filesystem-safe legacy normalization below.
            }
        }
        return root.resolve(sanitize(versionId)).normalize();
    }

    /**
     * One-time repair for profiles split by the old path sanitizer. The formerly active
     * sanitized directory wins on conflicts, then remains in place as a recoverable backup.
     */
    static void reconcileLegacySanitizedProfiles(Path profilesRoot, ProgressSink progress) {
        if (profilesRoot == null || !Files.isDirectory(profilesRoot)) {
            return;
        }
        try (var stream = Files.list(profilesRoot)) {
            for (Path canonical : stream.filter(Files::isDirectory).toList()) {
                String id = canonical.getFileName().toString();
                String legacyName = sanitize(id);
                if (legacyName.equals(id)) {
                    continue;
                }
                Path legacy = profilesRoot.resolve(legacyName).normalize();
                if (legacy.equals(canonical) || !Files.isDirectory(legacy)
                        || Files.isRegularFile(legacy.resolve(LEGACY_PATH_MIGRATION_MARKER))) {
                    continue;
                }
                try {
                    mergeTree(legacy, canonical);
                    Files.writeString(legacy.resolve(LEGACY_PATH_MIGRATION_MARKER), id);
                    progress.log("Объединены данные профиля " + id
                            + " из старой папки " + legacy.getFileName() + ".");
                } catch (IOException ex) {
                    progress.log("Не удалось объединить старую папку профиля " + legacy.getFileName()
                            + " с " + id + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            progress.log("Не удалось проверить старые папки профилей: " + ex.getMessage());
        }
    }

    /**
     * For every {@code Profiles/<name>} folder that doesn't already have {@code <name>.json},
     * looks for exactly one json/jar file inside and renames it to match the folder — the
     * naming convention every version-resolving code path in this launcher relies on.
     * Left alone (not an error) if a folder has zero or more than one candidate, since we
     * can't guess which file is the version definition.
     */
    private static void normalizeFolderNaming(Path target, ProgressSink progress) {
        try (var stream = Files.list(target)) {
            for (Path versionDir : stream.filter(Files::isDirectory).toList()) {
                String name = versionDir.getFileName().toString();
                Path expectedJson = versionDir.resolve(name + ".json");
                if (Files.isRegularFile(expectedJson)) {
                    continue;
                }
                Path foundJson = singleFileWithExtension(versionDir, ".json");
                if (foundJson == null) {
                    continue;
                }
                try {
                    Files.copy(foundJson, expectedJson, StandardCopyOption.REPLACE_EXISTING);
                    Path foundJar = singleFileWithExtension(versionDir, ".jar");
                    if (foundJar != null) {
                        Files.copy(foundJar, versionDir.resolve(name + ".jar"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    progress.log("Профиль \"" + name + "\": подогнано имя файла версии под имя папки (" + foundJson.getFileName() + " -> " + expectedJson.getFileName() + ")");
                } catch (IOException ex) {
                    progress.log("Не удалось нормализовать профиль " + name + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            progress.log("Не удалось просканировать msc-launcher/Profiles: " + ex.getMessage());
        }
    }

    private static Path singleFileWithExtension(Path dir, String extension) {
        try (var stream = Files.list(dir)) {
            List<Path> matches = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .toList();
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path rel = source.relativize(path);
                Path targetPath = rel.toString().isEmpty() ? dest : dest.resolve(rel.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void mergeTree(Path source, Path dest) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path rel = source.relativize(path);
                Path target = rel.toString().isEmpty() ? dest : dest.resolve(rel.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteIfEmpty(Path dir) {
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns the game directory for a given version profile.
     * <p>
     * - Legacy versions 1.5 / 1.5.1 / 1.5.2 use their own isolated folder under
     *   {@code msc-launcher/saves_old/<versionId>} (with their own options.txt),
     *   because their old options.txt format crashes 1.5.2 when shared with
     *   other (modern) versions.
     * - Vanilla is the only profile type that is NOT isolated: it uses the
     *   real, shared {@code .minecraft} directory, exactly like the official
     *   Minecraft launcher.
     * - Every other profile — Forge, Forge + OptiFine, NeoForge, Fabric,
     *   Quilt, plain OptiFine, and modpacks (modrinth-, curseforge-,
     *   msc-build-) — gets its own isolated folder under
     *   {@code msc-launcher/Profiles/<versionId>} with its own {@code mods},
     *   {@code saves}, {@code config}, {@code resourcepacks}, etc. This way
     *   two different loader versions (or two different Forge/OptiFine
     *   combos) never share mods/libraries/worlds and can't hit a Java
     *   module-system "split package" conflict or a stray leftover mod from
     *   a different version.
     */
    static Path launchGameDirectory(Path baseGameDirectory, String versionId) {
        Path isolated = isolatedRoot(versionId);
        return isolated != null ? isolated : baseGameDirectory;
    }

    static Path modsDirectory(Path baseGameDirectory, String versionId) {
        return launchGameDirectory(baseGameDirectory, versionId).resolve("mods");
    }

    static boolean usesIsolatedGameDirectory(String versionId) {
        return isolatedRoot(versionId) != null;
    }

    /**
     * The profile's own {@code Profiles/<versionId>/mods} folder, used as a migration source for
     * an OptiFine jar left over from a brief period when Forge(+OptiFine) profiles were not
     * isolated (so their mods lived directly in the shared {@code .minecraft/mods} instead). Now
     * that Forge is isolated again this is normally the very same folder profiles launch from —
     * see {@link #isolatedRoot(String)} — so this lookup is effectively a no-op safety net.
     */
    static Path legacyIsolatedModsDirectory(String versionId) {
        if (versionId == null) {
            return null;
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (!(lower.contains("forge") || lower.contains("neoforge") || lower.contains("fabric-loader-")
                || lower.contains("quilt-loader-") || lower.contains("optifine"))) {
            return null;
        }
        return profileDirectory(versionId).resolve("mods");
    }

    /** True for 1.5 / 1.5.1 / 1.5.2 (or any loader profile built on top of them). */
    static boolean isLegacyOptionsVersion(String versionId) {
        if (versionId == null) {
            return false;
        }
        String base = VersionSort.baseGameVersion(versionId);
        return "1.5".equals(base) || "1.5.1".equals(base) || "1.5.2".equals(base);
    }

    private static Path isolatedRoot(String versionId) {
        if (versionId == null) {
            return null;
        }
        if (isLegacyOptionsVersion(versionId)) {
            // Отдельная папка для 1.5–1.5.2 со своим options.txt — старый формат
            // настроек этих версий конфликтует с другими версиями и роняет 1.5.2.
            return directChildOrSanitized(
                    LauncherSettings.settingsDirectory().resolve("saves_old").toAbsolutePath().normalize(),
                    versionId);
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        Path profileRoot = profileDirectory(versionId);
        if (lower.startsWith("msc-build-") || lower.startsWith("modrinth-") || lower.startsWith("curseforge-")) {
            return profileRoot;
        }
        if (Files.isDirectory(profileRoot)) {
            return profileRoot;
        }
        // Every non-vanilla profile gets its own isolated folder — Forge, Forge + OptiFine,
        // NeoForge ("forge" substring covers it too), Fabric, Quilt, and plain OptiFine. Only a
        // bare vanilla version id (no loader marker at all) falls through to "return null" below
        // and runs out of the real, shared .minecraft, same as the official launcher.
        if (lower.contains("forge") || lower.contains("fabric") || lower.contains("quilt") || lower.contains("optifine")) {
            return profileRoot;
        }
        return null;
    }

    private static String sanitize(String id) {
        if (id == null) return "";
        String sanitized = id.trim()
                .replaceAll("[^\\p{L}\\p{N} ._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("[. ]+$", "");
        return sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized) ? "profile" : sanitized;
    }
}
