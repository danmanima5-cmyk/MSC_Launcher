import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;

final class ModContentInstaller {
    private final ModrinthService modrinthService;
    private final CurseForgeService curseForgeService;
    private final MinecraftInstaller minecraftInstaller;
    private final ForgeService forgeService;
    private final NeoForgeService neoForgeService;

    ModContentInstaller(ModrinthService modrinthService, MinecraftInstaller minecraftInstaller,
                        ForgeService forgeService, NeoForgeService neoForgeService) {
        this(modrinthService, new CurseForgeService(), minecraftInstaller, forgeService, neoForgeService);
    }

    ModContentInstaller(ModrinthService modrinthService, CurseForgeService curseForgeService,
                        MinecraftInstaller minecraftInstaller, ForgeService forgeService,
                        NeoForgeService neoForgeService) {
        this.modrinthService = modrinthService;
        this.curseForgeService = curseForgeService;
        this.minecraftInstaller = minecraftInstaller;
        this.forgeService = forgeService;
        this.neoForgeService = neoForgeService;
    }

    /** Downloads the exact selected artifact to a verified cache. */
    PreparedModInstall prepareModInstall(String source, ModrinthProject project,
                                         ModFileRelease release, String gameVersion,
                                         String loader, ProgressSink progress) {
        if (project == null || release == null) {
            throw new LauncherException("Не удалось подготовить выбранный мод.");
        }
        Path cacheDir = LauncherSettings.settingsDirectory()
                .resolve("installers")
                .resolve("mods")
                .resolve(safePathSegment(source))
                .resolve(safePathSegment(project.id()));
        Path cachedFile = dependencyTarget(cacheDir, release.fileName());
        if (!fileMatches(cachedFile, release.sha1(), release.size())) {
            if (source != null && source.equalsIgnoreCase("Modrinth")) {
                if (release.downloadUrl().isBlank()) {
                    throw new LauncherException("У выбранной версии Modrinth нет ссылки загрузки.");
                }
                modrinthService.http().download(release.downloadUrl(), cachedFile,
                        release.sha1(), release.size(), progress);
            } else if (source != null && source.equalsIgnoreCase("CurseForge")) {
                CurseForgeFile file = new CurseForgeFile(release.id(), release.fileName(),
                        release.downloadUrl(), release.size(), release.sha1(), release.dependencies());
                curseForgeService.downloadFile(project.id(), file, cachedFile, progress);
            } else {
                throw new LauncherException("Неизвестный источник мода: " + source);
            }
        } else {
            progress.log("Подготовленный файл уже есть: " + cachedFile.getFileName());
        }

        return new PreparedModInstall(release, cachedFile);
    }

    String installPreparedMod(ModrinthProject project, PreparedModInstall prepared,
                              String targetProfile, LauncherSettings settings,
                              ProgressSink progress) {
        if (targetProfile == null || targetProfile.isBlank()) {
            throw new LauncherException("Выберите установленную версию, куда добавить мод.");
        }
        ModFileRelease release = prepared.release();
        if (!fileMatches(prepared.cachedFile(), release.sha1(), release.size())) {
            throw new LauncherException("Подготовленный файл мода повреждён: " + release.fileName());
        }
        Path modsDir = ProfileDirectories.modsDirectory(settings.gameDirectory(), targetProfile);
        Path target = dependencyTarget(modsDir, release.fileName());
        if (fileMatches(target, release.sha1(), release.size())) {
            progress.log("Файл мода уже существует, установка пропущена: " + target.getFileName());
        } else {
            copyVerified(prepared.cachedFile(), target, release.sha1(), release.size());
            progress.log("Мод установлен: " + target.getFileName());
        }
        return "Мод установлен в " + targetProfile + ": " + project.title();
    }

    /**
     * Commits the selected root and add-ons as one file transaction. Every source is checked
     * and staged first; a failed commit restores all target files touched by this operation.
     */
    String installPreparedPlan(ModInstallPlan plan, List<PreparedModInstall> preparedFiles,
                               String targetProfile, LauncherSettings settings,
                               ProgressSink progress) {
        return installPreparedPlanIntoDirectory(plan, preparedFiles,
                dependencyModsDirectory(targetProfile, settings), targetProfile, progress);
    }

    String installPreparedPlanIntoDirectory(ModInstallPlan plan,
                                            List<PreparedModInstall> preparedFiles,
                                            Path modsDir, String targetProfile,
                                            ProgressSink progress) {
        validatePreparedPlan(plan, preparedFiles, modsDir, "", "");
        ArrayList<StagedMod> staged = new ArrayList<>();
        ArrayList<CommittedMod> committed = new ArrayList<>();
        LinkedHashMap<String, String> providedByFile = new LinkedHashMap<>();
        try {
            Files.createDirectories(modsDir);
            for (int index = 0; index < preparedFiles.size(); index++) {
                ModInstallEntry entry = plan.entries().get(index);
                PreparedModInstall prepared = preparedFiles.get(index);
                ModFileRelease release = prepared.release();
                if (!entry.release().id().equals(release.id())) {
                    throw new LauncherException("Подготовлена другая версия мода "
                            + entry.project().title() + ".");
                }
                if (!fileMatches(prepared.cachedFile(), release.sha1(), release.size())) {
                    throw new LauncherException("Подготовленный файл мода повреждён: "
                            + release.fileName());
                }
                Path target = dependencyTarget(modsDir, release.fileName());
                validateProvidedModIds(prepared.cachedFile(), target, providedByFile);
                if (Files.exists(target) && !Files.isRegularFile(target)) {
                    throw new LauncherException("Путь мода занят не файлом: " + target);
                }
                if (fileMatches(target, release.sha1(), release.size())) {
                    progress.log("Файл мода уже существует, установка пропущена: "
                            + target.getFileName());
                    continue;
                }
                Path staging = Files.createTempFile(modsDir, ".msc-install-", ".part");
                Files.copy(prepared.cachedFile(), staging, StandardCopyOption.REPLACE_EXISTING);
                if (!fileMatches(staging, release.sha1(), release.size())) {
                    Files.deleteIfExists(staging);
                    throw new LauncherException("Подготовленная копия мода повреждена: "
                            + release.fileName());
                }
                staged.add(new StagedMod(target, staging));
            }

            for (StagedMod item : staged) {
                Path backup = null;
                if (Files.isRegularFile(item.target())) {
                    backup = uniqueSibling(item.target(), ".msc-backup-", ".jar");
                    moveReplacing(item.target(), backup);
                }
                committed.add(new CommittedMod(item.target(), backup));
                moveReplacing(item.staging(), item.target());
                progress.log("Мод установлен: " + item.target().getFileName());
            }
        } catch (IOException | RuntimeException ex) {
            rollbackCommittedMods(committed, progress);
            if (ex instanceof LauncherException launcherException) {
                throw launcherException;
            }
            throw new LauncherException("Не удалось установить выбранные моды: "
                    + ex.getMessage(), ex);
        } finally {
            for (StagedMod item : staged) {
                try {
                    Files.deleteIfExists(item.staging());
                } catch (IOException ignored) {
                    // Staging files never replace a profile file unless the whole commit succeeds.
                }
            }
        }

        for (CommittedMod item : committed) {
            if (item.backup() != null) {
                try {
                    Files.deleteIfExists(item.backup());
                } catch (IOException ex) {
                    progress.log("Резервная копия оставлена: " + item.backup().getFileName());
                }
            }
        }
        return "Моды установлены в " + targetProfile + ": " + plan.entries().size();
    }

    /**
     * Read-only safety barrier used before any selected add-on or dependency can change a profile.
     * It rejects wrong target metadata, damaged cached files, duplicate mod ids and occupied paths.
     */
    void validatePreparedPlan(ModInstallPlan plan, List<PreparedModInstall> preparedFiles,
                              Path modsDir, String gameVersion, String loader) {
        if (plan == null || preparedFiles == null
                || plan.entries().size() != preparedFiles.size()) {
            throw new LauncherException("Подготовленные файлы не совпадают с планом установки.");
        }
        if (modsDir == null) {
            throw new LauncherException("Папка модов для установки не указана.");
        }
        plan.validatePinnedVersions();

        LinkedHashMap<String, String> providedByFile = new LinkedHashMap<>();
        ArrayList<PreparedManifestCheck> manifestChecks = new ArrayList<>();
        Set<String> replacedFiles = preparedFiles.stream()
                .map(item -> item.release().fileName().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        seedInstalledModMetadata(modsDir, providedByFile, manifestChecks, replacedFiles);
        for (int index = 0; index < preparedFiles.size(); index++) {
            ModInstallEntry entry = plan.entries().get(index);
            PreparedModInstall prepared = preparedFiles.get(index);
            ModFileRelease release = prepared.release();
            if (!entry.release().id().equals(release.id())) {
                throw new LauncherException("Подготовлена другая версия мода "
                        + entry.project().title() + ".");
            }
            if (!release.supportsTarget(gameVersion, loader)) {
                throw new LauncherException("Мод " + entry.project().title()
                        + " несовместим с Minecraft " + gameVersion + " / " + loader + ".");
            }
            if (!fileMatches(prepared.cachedFile(), release.sha1(), release.size())) {
                throw new LauncherException("Подготовленный файл мода повреждён: "
                        + release.fileName());
            }
            Path target = dependencyTarget(modsDir, release.fileName());
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                throw new LauncherException("Путь мода занят не файлом: " + target);
            }
            try {
                ModManifestMetadata metadata = ModManifestInspector.inspect(prepared.cachedFile());
                validateProvidedModIds(metadata.providedModIds(), target, providedByFile);
                manifestChecks.add(new PreparedManifestCheck(target, metadata));
            } catch (IOException ex) {
                throw new LauncherException("Не удалось проверить mod id файла "
                        + release.fileName() + ": " + ex.getMessage(), ex);
            }
        }
        // Loader manifest conflict entries can be conditional version predicates.
        // Keep only the unambiguous duplicate-id check above.
    }

    private record PreparedManifestCheck(Path target, ModManifestMetadata metadata) { }

    private void seedInstalledModMetadata(Path modsDir, Map<String, String> providedByFile,
                                          List<PreparedManifestCheck> manifestChecks,
                                          Set<String> excludedFileNames) {
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (var files = Files.list(modsDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                if (excludedFileNames.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                try {
                    ModManifestMetadata metadata = ModManifestInspector.inspect(file);
                    for (String modId : metadata.providedModIds()) {
                        providedByFile.putIfAbsent(
                                modId.toLowerCase(java.util.Locale.ROOT), name);
                    }
                    manifestChecks.add(new PreparedManifestCheck(file, metadata));
                } catch (IOException | RuntimeException ignored) {
                    // A pre-existing unreadable file is left untouched; new files are still checked.
                }
            }
        } catch (IOException ex) {
            throw new LauncherException("Не удалось проверить установленные моды: "
                    + ex.getMessage(), ex);
        }
    }

    private void validateManifestConflicts(List<PreparedManifestCheck> manifestChecks,
                                           Map<String, String> providedByFile) {
        for (PreparedManifestCheck check : manifestChecks) {
            String currentFile = check.target().getFileName().toString();
            for (String incompatibleId : check.metadata().incompatibleModIds()) {
                String conflictingFile = providedByFile.get(
                        incompatibleId.toLowerCase(java.util.Locale.ROOT));
                if (conflictingFile != null && !conflictingFile.equals(currentFile)) {
                    throw new LauncherException("Несовместимые моды: " + currentFile
                            + " конфликтует с " + conflictingFile
                            + " (mod id " + incompatibleId + ")");
                }
            }
        }
    }

    private void validateDependencyCandidate(Path candidate, Path target, Path modsDir,
                                             ModFileRelease release,
                                             String gameVersion, String loader) {
        if (!release.supportsTarget(gameVersion, loader)) {
            throw new LauncherException("Зависимость " + release.fileName()
                    + " несовместима с Minecraft " + gameVersion + " / " + loader + ".");
        }
        if (!fileMatches(candidate, release.sha1(), release.size())) {
            throw new LauncherException("Файл зависимости повреждён: " + release.fileName());
        }
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            throw new LauncherException("Путь зависимости занят не файлом: " + target);
        }
        LinkedHashMap<String, String> providedByFile = new LinkedHashMap<>();
        ArrayList<PreparedManifestCheck> checks = new ArrayList<>();
        seedInstalledModMetadata(modsDir, providedByFile, checks,
                Set.of(target.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
        try {
            ModManifestMetadata metadata = ModManifestInspector.inspect(candidate);
            validateProvidedModIds(metadata.providedModIds(), target, providedByFile);
            checks.add(new PreparedManifestCheck(target, metadata));
        } catch (IOException ex) {
            throw new LauncherException("Не удалось проверить зависимость "
                    + release.fileName() + ": " + ex.getMessage(), ex);
        }
        // Loader manifest conflict entries can be conditional version predicates.
    }

    private void validateProvidedModIds(Path source, Path target,
                                        Map<String, String> providedByFile) throws IOException {
        validateProvidedModIds(ModManifestInspector.providedModIds(source), target, providedByFile);
    }

    private void validateProvidedModIds(Set<String> providedIds, Path target,
                                        Map<String, String> providedByFile) {
        for (String modId : providedIds) {
            String normalized = modId.toLowerCase(java.util.Locale.ROOT);
            String existing = providedByFile.putIfAbsent(
                    normalized, target.getFileName().toString());
            if (existing != null && !existing.equals(target.getFileName().toString())) {
                throw new LauncherException("Конфликт mod id " + modId + " между "
                        + existing + " и " + target.getFileName());
            }
        }
    }

    private Path uniqueSibling(Path target, String prefix, String suffix) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), prefix, suffix);
        Files.deleteIfExists(temporary);
        return temporary;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rollbackCommittedMods(List<CommittedMod> committed, ProgressSink progress) {
        for (int index = committed.size() - 1; index >= 0; index--) {
            CommittedMod item = committed.get(index);
            try {
                Files.deleteIfExists(item.target());
                if (item.backup() != null && Files.exists(item.backup())) {
                    moveReplacing(item.backup(), item.target());
                }
            } catch (IOException rollbackError) {
                progress.log("Не удалось восстановить " + item.target().getFileName()
                        + ": " + rollbackError.getMessage());
            }
        }
    }

    private record StagedMod(Path target, Path staging) { }

    private record CommittedMod(Path target, Path backup) { }

    private ModFileRelease mergeManifestDependencies(String source, ModFileRelease release,
                                                      ModManifestMetadata manifest,
                                                      String gameVersion, String loader,
                                                      ProgressSink progress) {
        ArrayList<ModDependencyLink> merged = new ArrayList<>(release.dependencies());
        for (ModManifestDependency declared : manifest.requiredDependencies()) {
            ModDependencyLink resolved;
            try {
                if (source != null && source.equalsIgnoreCase("Modrinth")) {
                    resolved = modrinthService.resolveManifestDependency(
                            declared.modId(), declared.versionConstraint(), gameVersion, loader);
                } else if (source != null && source.equalsIgnoreCase("CurseForge")) {
                    resolved = curseForgeService.resolveManifestDependency(
                            declared.modId(), declared.versionConstraint(), gameVersion, loader);
                } else {
                    throw new LauncherException("Неизвестный источник зависимостей: " + source);
                }
            } catch (RuntimeException ex) {
                progress.log("Зависимость " + declared.modId()
                        + " найдена в манифесте, но проект не распознан автоматически: "
                        + dependencyError(ex));
                resolved = new ModDependencyLink("", "", humanizeModId(declared.modId()),
                        "required", "", declared.modId(), declared.versionConstraint());
            }
            mergeRequiredDependency(merged, resolved);
        }
        return new ModFileRelease(release.id(), release.versionName(), release.versionNumber(),
                release.fileName(), release.releaseType(), release.publishedAt(),
                release.gameVersions(), release.loaders(), release.downloadUrl(), release.sha1(),
                release.size(), merged);
    }

    private void mergeRequiredDependency(List<ModDependencyLink> dependencies,
                                         ModDependencyLink required) {
        for (int i = 0; i < dependencies.size(); i++) {
            ModDependencyLink existing = dependencies.get(i);
            if (!sameDependency(existing, required)) {
                continue;
            }
            if (!existing.requiredForInstall() || existing.declaredModId().isBlank()) {
                dependencies.set(i, new ModDependencyLink(
                        firstNonBlank(existing.projectId(), required.projectId()),
                        firstNonBlank(existing.versionId(), required.versionId()),
                        firstNonBlank(existing.name(), required.name()), "required",
                        firstNonBlank(existing.url(), required.url()), required.declaredModId(),
                        firstNonBlank(existing.versionConstraint(), required.versionConstraint())));
            }
            return;
        }
        dependencies.add(required);
    }

    private boolean sameDependency(ModDependencyLink left, ModDependencyLink right) {
        if (!left.projectId().isBlank() && left.projectId().equalsIgnoreCase(right.projectId())) {
            return true;
        }
        if (!left.declaredModId().isBlank()
                && left.declaredModId().equalsIgnoreCase(right.declaredModId())) {
            return true;
        }
        String declared = normalizeDependencyName(right.declaredModId());
        return !declared.isBlank() && normalizeDependencyName(left.name()).equals(declared);
    }

    private String normalizeDependencyName(String value) {
        return value == null ? ""
                : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String humanizeModId(String modId) {
        String text = modId == null ? "" : modId.replace('_', ' ').replace('-', ' ').trim();
        if (text.isBlank()) {
            return "Неизвестная зависимость";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private String safePathSegment(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private void copyVerified(Path source, Path target, String sha1, long size) {
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        boolean hadTarget = Files.isRegularFile(target);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!fileMatches(temp, sha1, size)) {
                throw new IOException("копия не прошла проверку целостности");
            }
            if (hadTarget) {
                Files.move(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(backup);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temp);
                if (Files.isRegularFile(backup)) {
                    Files.move(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
                // Preserve the original failure; restoration is best effort.
            }
            throw new LauncherException("Не удалось установить подготовленный мод: " + ex.getMessage(), ex);
        }
    }

    String installModrinthMod(ModrinthProject project, ModrinthVersion version, String targetProfile,
                              LauncherSettings settings, ProgressSink progress) {
        if (targetProfile == null || targetProfile.isBlank()) {
            throw new LauncherException("Выберите установленную версию, куда добавить мод.");
        }
        ModrinthFile file = version.primaryFile();
        Path modsDir = ProfileDirectories.modsDirectory(settings.gameDirectory(), targetProfile);
        Path target = modsDir.resolve(file.fileName());
        if (fileMatches(target, file.sha1(), file.size())) {
            progress.log("Файл мода уже существует, загрузка пропущена: " + target.getFileName());
        } else {
            modrinthService.http().download(file.url(), target, file.sha1(), file.size(), progress);
        }
        return "Мод установлен в " + targetProfile + ": " + project.title();
    }

    /**
     * Provider-neutral entry point for the UI after the user agrees to install dependencies.
     * The return value is the number of newly downloaded dependency files.
     */
    int installRequiredDependencies(String source, ModFileRelease release, String targetProfile,
                                    String gameVersion, String loader, LauncherSettings settings,
                                    ProgressSink progress) {
        return installRequiredDependencies(source, null, release, targetProfile,
                gameVersion, loader, settings, progress);
    }

    int installRequiredDependencies(String source, ModrinthProject rootProject, ModFileRelease release,
                                    String targetProfile, String gameVersion, String loader,
                                    LauncherSettings settings, ProgressSink progress) {
        if (source != null && source.equalsIgnoreCase("Modrinth")) {
            return installModrinthRequiredDependencies(
                    rootProject, release, targetProfile, gameVersion, loader, settings, progress);
        }
        if (source != null && source.equalsIgnoreCase("CurseForge")) {
            return installCurseForgeRequiredDependencies(
                    rootProject, release, targetProfile, gameVersion, loader, settings, progress);
        }
        throw new LauncherException("Неизвестный источник зависимостей: " + source);
    }

    /** Traverses dependencies for the root and all selected add-ons with one shared visited set. */
    int installPlanRequiredDependencies(String source, ModInstallPlan plan,
                                        String targetProfile, String gameVersion, String loader,
                                        LauncherSettings settings, ProgressSink progress) {
        if (plan == null) {
            throw new LauncherException("План установки модов отсутствует.");
        }
        Path modsDir = dependencyModsDirectory(targetProfile, settings);
        if (source != null && source.equalsIgnoreCase("Modrinth")) {
            Set<String> visited = new HashSet<>();
            Map<String, String> selectedProjectVersions = new LinkedHashMap<>();
            for (ModInstallEntry entry : plan.entries()) {
                String projectId = entry.project().id();
                if (!projectId.isBlank()) {
                    visited.add("project:" + projectId);
                    selectedProjectVersions.put(projectId, entry.release().id());
                }
                if (!entry.release().id().isBlank()) {
                    visited.add("version:" + entry.release().id());
                }
            }
            ArrayList<String> failures = new ArrayList<>();
            Map<String, String> pinnedConstraints = new LinkedHashMap<>();
            for (ModInstallEntry root : plan.dependencyRoots()) {
                collectPinnedConstraints(requiredDependencies(root.release()), pinnedConstraints);
            }
            int installed = 0;
            for (ModInstallEntry root : plan.dependencyRoots()) {
                installed += installModrinthDependencies(requiredDependencies(root.release()), modsDir,
                        gameVersion, loader, progress, visited, selectedProjectVersions,
                        pinnedConstraints, failures);
            }
            throwIfDependencyFailures("Modrinth", failures);
            return installed;
        }
        if (source != null && source.equalsIgnoreCase("CurseForge")) {
            Set<String> visited = new HashSet<>();
            for (ModInstallEntry entry : plan.entries()) {
                if (!entry.project().id().isBlank()) {
                    visited.add("project:" + entry.project().id());
                }
                if (!entry.release().id().isBlank()) {
                    visited.add("file:" + entry.release().id());
                }
            }
            ArrayList<String> failures = new ArrayList<>();
            int installed = 0;
            for (ModInstallEntry root : plan.dependencyRoots()) {
                installed += installCurseForgeDependencies(requiredDependencies(root.release()), modsDir,
                        gameVersion, loader, progress, visited, failures);
            }
            throwIfDependencyFailures("CurseForge", failures);
            return installed;
        }
        throw new LauncherException("Неизвестный источник зависимостей: " + source);
    }

    /**
     * Resolves and downloads every required library to the installer cache, but never writes to
     * the profile.  The caller can consequently validate the root, add-ons and their complete
     * dependency graph together before committing a single file.
     */
    PreparedDependencyPlan preparePlanRequiredDependencies(String source, ModInstallPlan plan,
                                                            String gameVersion, String loader,
                                                            ProgressSink progress) {
        if (plan == null) {
            throw new LauncherException("План установки модов отсутствует.");
        }
        if (!"Modrinth".equalsIgnoreCase(source) && !"CurseForge".equalsIgnoreCase(source)) {
            throw new LauncherException("Неизвестный источник зависимостей: " + source);
        }

        LinkedHashMap<String, String> selectedVersions = new LinkedHashMap<>();
        LinkedHashMap<String, String> pinnedVersions = new LinkedHashMap<>();
        ArrayList<ModDependencyLink> pending = new ArrayList<>();
        for (ModInstallEntry entry : plan.entries()) {
            String projectId = entry.project().id();
            if (!projectId.isBlank()) {
                selectedVersions.put(projectId, entry.release().id());
            }
        }
        for (ModInstallEntry root : plan.dependencyRoots()) {
            pending.addAll(requiredDependencies(root.release()));
        }

        ArrayList<ModInstallEntry> entries = new ArrayList<>();
        ArrayList<PreparedModInstall> files = new ArrayList<>();
        for (int index = 0; index < pending.size(); index++) {
            ModDependencyLink declared = pending.get(index);
            ModDependencyLink dependency = applyPinnedConstraint(declared, pinnedVersions,
                    new ArrayList<>());
            if (dependency == null) {
                throw new LauncherException("Не удалось согласовать версию библиотеки "
                        + declared.name() + ".");
            }
            if (dependency.projectId().isBlank()) {
                throw new LauncherException("Не удалось автоматически подобрать обязательную библиотеку: "
                        + dependency.name());
            }

            ModFileRelease release = "Modrinth".equalsIgnoreCase(source)
                    ? modrinthService.dependencyInstallRelease(dependency, gameVersion, loader)
                    : curseForgeService.dependencyInstallRelease(dependency, gameVersion, loader);
            String existing = selectedVersions.putIfAbsent(dependency.projectId(), release.id());
            if (existing != null) {
                if (!existing.isBlank() && !release.id().isBlank() && !existing.equals(release.id())) {
                    throw new LauncherException("Конфликт версий библиотеки " + dependency.name()
                            + ": требуются " + existing + " и " + release.id() + ".");
                }
                continue;
            }

            ModrinthProject dependencyProject = dependencyProject(dependency);
            progress.status("Подготовка библиотеки: " + dependencyProject.title());
            PreparedModInstall prepared = prepareModInstall(source, dependencyProject, release,
                    gameVersion, loader, progress);
            verifyDeclaredModId(prepared.cachedFile(), dependency, false);
            entries.add(new ModInstallEntry(dependencyProject, prepared.release()));
            files.add(prepared);
            pending.addAll(requiredDependencies(prepared.release()));
        }
        return new PreparedDependencyPlan(entries, files);
    }

    private ModrinthProject dependencyProject(ModDependencyLink dependency) {
        String title = dependency.name().isBlank() ? dependency.projectId() : dependency.name();
        return new ModrinthProject(dependency.projectId(), dependency.projectId(), title,
                "", "mod", "", 0, "", List.of(), "");
    }

    int installModrinthRequiredDependencies(ModFileRelease release, String targetProfile,
                                             String gameVersion, String loader,
                                             LauncherSettings settings, ProgressSink progress) {
        return installModrinthRequiredDependencies(null, release, targetProfile,
                gameVersion, loader, settings, progress);
    }

    int installModrinthRequiredDependencies(ModrinthProject rootProject, ModFileRelease release,
                                             String targetProfile, String gameVersion, String loader,
                                             LauncherSettings settings, ProgressSink progress) {
        Path modsDir = dependencyModsDirectory(targetProfile, settings);
        Set<String> visited = new HashSet<>();
        Map<String, String> selectedProjectVersions = new LinkedHashMap<>();
        addRootProject(visited, rootProject);
        if (rootProject != null && rootProject.id() != null && !rootProject.id().isBlank()) {
            selectedProjectVersions.put(rootProject.id(), release == null ? "" : release.id());
        }
        if (release != null && !release.id().isBlank()) {
            visited.add("version:" + release.id());
        }
        ArrayList<String> failures = new ArrayList<>();
        Map<String, String> pinnedConstraints = new LinkedHashMap<>();
        collectPinnedConstraints(requiredDependencies(release), pinnedConstraints);
        int installed = installModrinthDependencies(requiredDependencies(release), modsDir,
                gameVersion, loader, progress, visited, selectedProjectVersions,
                pinnedConstraints, failures);
        throwIfDependencyFailures("Modrinth", failures);
        return installed;
    }

    int installCurseForgeRequiredDependencies(ModFileRelease release, String targetProfile,
                                               String gameVersion, String loader,
                                               LauncherSettings settings, ProgressSink progress) {
        return installCurseForgeRequiredDependencies(null, release, targetProfile,
                gameVersion, loader, settings, progress);
    }

    int installCurseForgeRequiredDependencies(ModrinthProject rootProject, ModFileRelease release,
                                               String targetProfile, String gameVersion, String loader,
                                               LauncherSettings settings, ProgressSink progress) {
        Path modsDir = dependencyModsDirectory(targetProfile, settings);
        Set<String> visited = new HashSet<>();
        addRootProject(visited, rootProject);
        if (release != null && !release.id().isBlank()) {
            visited.add("file:" + release.id());
        }
        ArrayList<String> failures = new ArrayList<>();
        int installed = installCurseForgeDependencies(requiredDependencies(release), modsDir,
                gameVersion, loader, progress, visited, failures);
        throwIfDependencyFailures("CurseForge", failures);
        return installed;
    }

    private void addRootProject(Set<String> visited, ModrinthProject rootProject) {
        if (rootProject != null && rootProject.id() != null && !rootProject.id().isBlank()) {
            visited.add("project:" + rootProject.id());
        }
    }

    private Path dependencyModsDirectory(String targetProfile, LauncherSettings settings) {
        if (targetProfile == null || targetProfile.isBlank()) {
            throw new LauncherException("Выберите установленную версию, куда добавить зависимости мода.");
        }
        if (settings == null) {
            throw new LauncherException("Настройки лаунчера недоступны для установки зависимостей.");
        }
        return ProfileDirectories.modsDirectory(settings.gameDirectory(), targetProfile);
    }

    private List<ModDependencyLink> requiredDependencies(ModFileRelease release) {
        if (release == null || release.dependencies().isEmpty()) {
            return List.of();
        }
        return release.requiredDependencies();
    }

    private int installModrinthDependencies(List<ModDependencyLink> dependencies, Path modsDir,
                                            String gameVersion, String loader, ProgressSink progress,
                                            Set<String> visited, Map<String, String> selectedProjectVersions,
                                            Map<String, String> pinnedConstraints,
                                            List<String> failures) {
        int downloaded = 0;
        for (ModDependencyLink declaredDependency : dependencies) {
            ModDependencyLink dependency = applyPinnedConstraint(
                    declaredDependency, pinnedConstraints, failures);
            if (dependency == null) {
                continue;
            }
            DependencyVisit visit = registerModrinthDependency(
                    dependency, visited, selectedProjectVersions);
            if (!visit.failure().isBlank()) {
                failures.add(visit.failure());
                progress.log("Конфликт обязательных зависимостей Modrinth: " + visit.failure());
                continue;
            }
            if (!visit.install()) {
                progress.log("Циклическая или повторная зависимость Modrinth пропущена: " + dependency.name());
                continue;
            }
            try {
                ModFileRelease dependencyRelease = modrinthService.dependencyInstallRelease(
                        dependency, gameVersion, loader);
                if (!dependencyRelease.id().isBlank()) {
                    visited.add("version:" + dependencyRelease.id());
                    if (!dependency.projectId().isBlank()) {
                        selectedProjectVersions.put(dependency.projectId(), dependencyRelease.id());
                    }
                }
                Path target = dependencyTarget(modsDir, dependencyRelease.fileName());
                boolean alreadyPresent = fileMatches(target, dependencyRelease.sha1(), dependencyRelease.size());
                if (alreadyPresent) {
                    validateDependencyCandidate(target, target, modsDir, dependencyRelease,
                            gameVersion, loader);
                    verifyDeclaredModId(target, dependency, false);
                    progress.log("Файл зависимости уже существует, загрузка пропущена: " + target.getFileName());
                } else {
                    if (dependencyRelease.downloadUrl().isBlank()) {
                        throw new LauncherException("У зависимости нет ссылки загрузки: " + dependency.name());
                    }
                    Path staging = dependencyStagingFile(modsDir);
                    try {
                        modrinthService.http().download(dependencyRelease.downloadUrl(), staging,
                                dependencyRelease.sha1(), dependencyRelease.size(), progress);
                        validateDependencyCandidate(staging, target, modsDir, dependencyRelease,
                                gameVersion, loader);
                        verifyDeclaredModId(staging, dependency, false);
                        copyVerified(staging, target, dependencyRelease.sha1(), dependencyRelease.size());
                        downloaded++;
                        progress.log("Безопасная зависимость установлена: " + dependency.name());
                    } finally {
                        deleteDependencyStaging(staging);
                    }
                }
                downloaded += installModrinthDependencies(requiredDependencies(dependencyRelease), modsDir,
                        gameVersion, loader, progress, visited, selectedProjectVersions,
                        pinnedConstraints, failures);
            } catch (RuntimeException ex) {
                String failure = dependency.name() + ": " + dependencyError(ex);
                failures.add(failure);
                progress.log("Не удалось установить обязательную зависимость Modrinth " + failure);
            }
        }
        return downloaded;
    }

    private void collectPinnedConstraints(List<ModDependencyLink> dependencies,
                                          Map<String, String> constraints) {
        for (ModDependencyLink dependency : dependencies) {
            if (!dependency.projectId().isBlank() && !dependency.versionId().isBlank()) {
                String existing = constraints.putIfAbsent(
                        dependency.projectId(), dependency.versionId());
                if (existing != null && !existing.equals(dependency.versionId())) {
                    throw new LauncherException("Конфликт закреплённых версий " + dependency.name()
                            + ": " + existing + " и " + dependency.versionId());
                }
            }
        }
    }

    private ModDependencyLink applyPinnedConstraint(ModDependencyLink dependency,
                                                    Map<String, String> constraints,
                                                    List<String> failures) {
        if (dependency.projectId().isBlank()) {
            return dependency;
        }
        String pinned = constraints.get(dependency.projectId());
        if (!dependency.versionId().isBlank()) {
            if (pinned != null && !pinned.equals(dependency.versionId())) {
                failures.add(dependency.name() + ": требуются версии " + pinned
                        + " и " + dependency.versionId());
                return null;
            }
            constraints.putIfAbsent(dependency.projectId(), dependency.versionId());
            return dependency;
        }
        if (pinned == null || pinned.isBlank()) {
            return dependency;
        }
        return new ModDependencyLink(dependency.projectId(), pinned, dependency.name(),
                dependency.relation(), dependency.url(), dependency.declaredModId(),
                dependency.versionConstraint());
    }

    private int installCurseForgeDependencies(List<ModDependencyLink> dependencies, Path modsDir,
                                              String gameVersion, String loader, ProgressSink progress,
                                              Set<String> visited, List<String> failures) {
        int downloaded = 0;
        for (ModDependencyLink dependency : dependencies) {
            if (dependency.projectId().isBlank()) {
                String failure = dependency.name() + ": отсутствует CurseForge project id";
                failures.add(failure);
                progress.log("Не удалось установить обязательную зависимость CurseForge " + failure);
                continue;
            }
            String key = "project:" + dependency.projectId();
            if (!visited.add(key)) {
                progress.log("Циклическая или повторная зависимость CurseForge пропущена: " + dependency.name());
                continue;
            }
            try {
                ModFileRelease dependencyRelease = curseForgeService.dependencyInstallRelease(
                        dependency, gameVersion, loader);
                Path target = dependencyTarget(modsDir, dependencyRelease.fileName());
                boolean alreadyPresent = fileMatches(target, dependencyRelease.sha1(), dependencyRelease.size());
                if (alreadyPresent) {
                    validateDependencyCandidate(target, target, modsDir, dependencyRelease,
                            gameVersion, loader);
                    verifyDeclaredModId(target, dependency, false);
                    progress.log("Файл зависимости уже существует, загрузка пропущена: " + target.getFileName());
                } else {
                    CurseForgeFile file = new CurseForgeFile(
                            dependencyRelease.id(), dependencyRelease.fileName(),
                            dependencyRelease.downloadUrl(), dependencyRelease.size(), dependencyRelease.sha1(),
                            dependencyRelease.dependencies());
                    Path staging = dependencyStagingFile(modsDir);
                    try {
                        curseForgeService.downloadFile(dependency.projectId(), file, staging, progress);
                        validateDependencyCandidate(staging, target, modsDir, dependencyRelease,
                                gameVersion, loader);
                        verifyDeclaredModId(staging, dependency, false);
                        copyVerified(staging, target, dependencyRelease.sha1(), dependencyRelease.size());
                        downloaded++;
                        progress.log("Безопасная зависимость установлена: " + dependency.name());
                    } finally {
                        deleteDependencyStaging(staging);
                    }
                }
                downloaded += installCurseForgeDependencies(requiredDependencies(dependencyRelease), modsDir,
                        gameVersion, loader, progress, visited, failures);
            } catch (RuntimeException ex) {
                String failure = dependency.name() + ": " + dependencyError(ex);
                failures.add(failure);
                progress.log("Не удалось установить обязательную зависимость CurseForge " + failure);
            }
        }
        return downloaded;
    }

    private DependencyVisit registerModrinthDependency(ModDependencyLink dependency, Set<String> visited,
                                                       Map<String, String> selectedProjectVersions) {
        String projectKey = dependency.projectId().isBlank() ? "" : "project:" + dependency.projectId();
        String versionKey = dependency.versionId().isBlank() ? "" : "version:" + dependency.versionId();
        if (!dependency.projectId().isBlank()
                && selectedProjectVersions.containsKey(dependency.projectId())) {
            String selectedVersion = selectedProjectVersions.get(dependency.projectId());
            if (!selectedVersion.isBlank() && !dependency.versionId().isBlank()
                    && !selectedVersion.equals(dependency.versionId())) {
                return new DependencyVisit(false, dependency.name() + ": требуются версии "
                        + selectedVersion + " и " + dependency.versionId());
            }
            return new DependencyVisit(false, "");
        }
        if (!versionKey.isBlank() && visited.contains(versionKey)) {
            return new DependencyVisit(false, "");
        }
        if (!projectKey.isBlank()) {
            visited.add(projectKey);
            selectedProjectVersions.put(dependency.projectId(), dependency.versionId());
        }
        if (!versionKey.isBlank()) {
            visited.add(versionKey);
        }
        if (projectKey.isBlank() && versionKey.isBlank()) {
            return new DependencyVisit(visited.add("unresolved:" + dependency.name()), "");
        }
        return new DependencyVisit(true, "");
    }

    private record DependencyVisit(boolean install, String failure) {
    }

    private Path dependencyStagingFile(Path modsDir) {
        try {
            Files.createDirectories(modsDir);
            return Files.createTempFile(modsDir, ".msc-dependency-", ".part");
        } catch (IOException ex) {
            throw new LauncherException("Не удалось подготовить проверку зависимости: "
                    + ex.getMessage(), ex);
        }
    }

    private void deleteDependencyStaging(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException ignored) {
            // The original installation/validation result remains more useful.
        }
    }

    private Path dependencyTarget(Path modsDir, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new LauncherException("У файла зависимости отсутствует имя.");
        }
        Path normalizedDir = modsDir.normalize();
        Path target = normalizedDir.resolve(fileName).normalize();
        if (!target.startsWith(normalizedDir) || target.getParent() == null
                || !target.getParent().equals(normalizedDir)) {
            throw new LauncherException("Некорректное имя файла зависимости: " + fileName);
        }
        return target;
    }

    private void verifyDeclaredModId(Path jar, ModDependencyLink dependency,
                                     boolean removeOnFailure) {
        if (dependency.declaredModId().isBlank()) {
            return;
        }
        try {
            if (ModManifestInspector.providedModIds(jar).contains(
                    dependency.declaredModId().toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
            if (removeOnFailure) {
                Files.deleteIfExists(jar);
            }
            throw new LauncherException("Файл " + jar.getFileName()
                    + " не содержит обязательный mod id " + dependency.declaredModId() + ".");
        } catch (IOException ex) {
            if (removeOnFailure) {
                try {
                    Files.deleteIfExists(jar);
                } catch (IOException ignored) {
                    // The validation error below remains the useful failure.
                }
            }
            throw new LauncherException("Не удалось проверить mod id зависимости "
                    + dependency.declaredModId() + ": " + ex.getMessage(), ex);
        }
    }

    private String dependencyError(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.toString() : ex.getMessage();
    }

    private void throwIfDependencyFailures(String source, List<String> failures) {
        if (!failures.isEmpty()) {
            throw new LauncherException("Некоторые обязательные зависимости " + source
                    + " не установлены из-за ошибки или риска несовместимости. "
                    + "Основной мод не установлен: " + String.join("; ", failures));
        }
    }

    String installModrinthModpack(ModrinthProject project, ModrinthVersion version,
                                  LauncherSettings settings, ProgressSink progress) {
        ModrinthFile file = version.primaryFile();
        Path packFile = LauncherSettings.settingsDirectory()
                .resolve("installers")
                .resolve("modrinth")
                .resolve(file.fileName());
        if (fileMatches(packFile, file.sha1(), file.size())) {
            progress.log("Файл modpack уже загружен, пропуск: " + packFile.getFileName());
        } else {
            modrinthService.http().download(file.url(), packFile, file.sha1(), file.size(), progress);
        }
        String profileId = installMrpack(project, version, packFile, settings, progress);
        return "Modrinth modpack установлен: " + profileId;
    }

    void repairInstalledModpack(String profileId, LauncherSettings settings, ProgressSink progress) {
        if (profileId == null || !profileId.toLowerCase(java.util.Locale.ROOT).startsWith("modrinth-")) {
            return;
        }
        ResolvedVersion version = minecraftInstaller.resolve(profileId, settings.gameDirectory(), progress);
        String loader = loaderForResolvedVersion(version);
        if (loader.isBlank()) {
            return;
        }
        try {
            resolveMissingModDependencies(
                    ProfileDirectories.modsDirectory(settings.gameDirectory(), profileId),
                    version.rootId(),
                    loader,
                    progress
            );
        } catch (IOException ex) {
            throw new LauncherException("Не удалось проверить зависимости modpack: " + ex.getMessage(), ex);
        }
    }

    private String installMrpack(ModrinthProject project, ModrinthVersion version, Path packFile,
                                 LauncherSettings settings, ProgressSink progress) {
        try (ZipFile zip = new ZipFile(packFile.toFile())) {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null) {
                throw new LauncherException("В .mrpack нет modrinth.index.json.");
            }
            Map<String, Object> index;
            try (var in = zip.getInputStream(indexEntry)) {
                index = Json.object(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            Map<String, Object> dependencies = Json.object(index, "dependencies");
            String gameVersion = Json.string(dependencies, "minecraft");
            if (gameVersion.isBlank()) {
                throw new LauncherException("Modpack не указывает версию Minecraft.");
            }
            String loaderProfile = installRequiredLoader(gameVersion, dependencies, settings, progress);
            String baseProfile = "modrinth-" + sanitize(project.slug().isBlank() ? project.id() : project.slug())
                    + "-" + sanitize(version.versionNumber().isBlank() ? version.id() : version.versionNumber());
            String profileId = uniqueProfileId(settings.gameDirectory(), baseProfile);
            Path instanceDir = ProfileDirectories.launchGameDirectory(settings.gameDirectory(), profileId);
            downloadPackFiles(index, instanceDir, progress);
            extractOverrides(zip, "overrides/", instanceDir, progress);
            extractOverrides(zip, "client-overrides/", instanceDir, progress);
            resolveMissingModDependencies(instanceDir.resolve("mods"), gameVersion, loaderForDependencies(dependencies), progress);
            createInheritedProfile(profileId, loaderProfile, settings, progress);
            return profileId;
        } catch (IOException ex) {
            throw new LauncherException("Не удалось установить Modrinth modpack: " + ex.getMessage(), ex);
        }
    }

    private String installRequiredLoader(String gameVersion, Map<String, Object> dependencies,
                                         LauncherSettings settings, ProgressSink progress) {
        String fabric = Json.string(dependencies, "fabric-loader");
        if (!fabric.isBlank()) {
            return minecraftInstaller.installFabric(gameVersion, fabric, settings, progress);
        }
        String quilt = Json.string(dependencies, "quilt-loader");
        if (!quilt.isBlank()) {
            return minecraftInstaller.installQuilt(gameVersion, quilt, settings, progress);
        }
        String forge = Json.string(dependencies, "forge");
        if (!forge.isBlank()) {
            return forgeService.installForge(gameVersion, gameVersion + "-" + forge, settings, progress);
        }
        String neoForge = Json.string(dependencies, "neoforge");
        if (!neoForge.isBlank()) {
            return neoForgeService.installNeoForge(gameVersion, neoForge, settings, progress);
        }
        minecraftInstaller.ensureVanillaMetadata(gameVersion, settings, progress);
        return gameVersion;
    }

    private String loaderForDependencies(Map<String, Object> dependencies) {
        if (!Json.string(dependencies, "fabric-loader").isBlank()) {
            return "fabric";
        }
        if (!Json.string(dependencies, "quilt-loader").isBlank()) {
            return "quilt";
        }
        if (!Json.string(dependencies, "neoforge").isBlank()) {
            return "neoforge";
        }
        if (!Json.string(dependencies, "forge").isBlank()) {
            return "forge";
        }
        return "";
    }

    private String loaderForResolvedVersion(ResolvedVersion version) {
        String id = version.id().toLowerCase(java.util.Locale.ROOT);
        String main = version.mainClass().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("fabric") || main.contains("fabricmc")) {
            return "fabric";
        }
        if (id.contains("quilt") || main.contains("quiltmc")) {
            return "quilt";
        }
        if (id.contains("neoforge")) {
            return "neoforge";
        }
        if (id.contains("forge") || main.contains("modlauncher")) {
            return "forge";
        }
        return "";
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
        try {
            Files.createDirectories(profileDir);
            // Do not overwrite existing profile JSON to avoid losing customizations or accidental data changes.
            if (Files.isRegularFile(profileJson)) {
                progress.log("Профиль modpack уже существует, не перезаписывается: " + profileId);
            } else {
                Files.writeString(profileJson, Json.stringify(json), StandardCharsets.UTF_8);
                progress.log("Профиль modpack сохранён: " + profileId);
            }
        } catch (IOException ex) {
            throw new LauncherException("Не удалось сохранить профиль modpack: " + ex.getMessage(), ex);
        }
    }

    private void downloadPackFiles(Map<String, Object> index, Path instanceDir, ProgressSink progress) throws IOException {
        int done = 0;
        int skipped = 0;
        for (Object item : Json.list(index, "files")) {
            Map<String, Object> file = Json.object(item);
            if (!isClientFile(file)) {
                continue;
            }
            String path = Json.string(file, "path");
            Map<String, Object> hashes = Json.object(file, "hashes");
            String sha1 = Json.string(hashes, "sha1");
            String url = firstDownloadUrl(file);
            long size = Json.longValue(file, "fileSize", -1);
            if (url.isBlank() && !sha1.isBlank()) {
                try {
                    ModrinthFile resolved = modrinthService.fileBySha1(sha1);
                    url = resolved.url();
                    size = resolved.size();
                    progress.log("Ссылка файла восстановлена по SHA-1: " + path);
                } catch (LauncherException ex) {
                    progress.log("Не удалось восстановить ссылку файла по SHA-1 " + sha1 + ": " + ex.getMessage());
                }
            }
            if (path.isBlank() || url.isBlank()) {
                skipped++;
                progress.log("Файл modpack пропущен без прямой ссылки: " + path);
                continue;
            }
            Path target = safeResolve(instanceDir, path);
            if (fileMatches(target, sha1, size)) {
                progress.log("Файл уже существует, пропуск: " + path);
            } else {
                modrinthService.http().download(url, target, sha1, size, progress);
            }
            progress.progress(++done, Json.list(index, "files").size());
        }
        if (skipped > 0) {
            progress.log("В modpack были файлы без прямой ссылки: " + skipped + ". Зависимости будут проверены отдельно.");
        }
    }

    private boolean isClientFile(Map<String, Object> file) {
        Map<String, Object> env = Json.object(file, "env");
        String client = Json.string(env, "client");
        return client.isBlank() || !"unsupported".equalsIgnoreCase(client);
    }

    private String firstDownloadUrl(Map<String, Object> file) {
        for (Object url : Json.list(file, "downloads")) {
            String text = Json.string(url);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private void extractOverrides(ZipFile zip, String prefix, Path instanceDir, ProgressSink progress) throws IOException {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(prefix)) {
                continue;
            }
            Path target = safeResolve(instanceDir, name.substring(prefix.length()));
            Files.createDirectories(target.getParent());
            // Do not overwrite existing user files (worlds, configs, etc.). Only copy if target does not exist.
            if (Files.exists(target)) {
                progress.log("Файл overrides пропущен (существует): " + target.getFileName());
            } else {
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
        }
        progress.log("Overrides применены: " + prefix);
    }

    private String uniqueProfileId(Path gameDirectory, String baseProfile) {
        Path baseJson = ProfileDirectories.versionsInstallDirectory().resolve(baseProfile).resolve(baseProfile + ".json");
        // If a profile with the canonical name already exists, reuse it instead of creating a duplicate.
        if (Files.exists(baseJson)) {
            return baseProfile;
        }
        String candidate = baseProfile;
        int suffix = 2;
        while (Files.exists(ProfileDirectories.versionsInstallDirectory().resolve(candidate).resolve(candidate + ".json"))) {
            candidate = baseProfile + "-" + suffix++;
        }
        return candidate;
    }

    private Path safeResolve(Path root, String relativePath) {
        Path normalizedRoot = root.normalize();
        Path target = normalizedRoot.resolve(relativePath).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new LauncherException("Некорректный путь в modpack: " + relativePath);
        }
        return target;
    }

    private String sanitize(String value) {
        return value == null || value.isBlank() ? "pack" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String computeSha1(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buffer = new byte[8192];
                while (dis.read(buffer) != -1) {
                    // reading stream to update digest
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException ex) {
            return "";
        }
    }

    private static boolean fileMatches(Path target, String sha1, long size) {
        try {
            if (!Files.exists(target) || !Files.isRegularFile(target)) return false;
            if (size >= 0) {
                try {
                    long actual = Files.size(target);
                    if (actual != size) return false;
                } catch (IOException ex) {
                    return false;
                }
            }
            if (sha1 != null && !sha1.isBlank()) {
                String actualSha1 = computeSha1(target);
                if (actualSha1.isBlank()) return false;
                return actualSha1.equalsIgnoreCase(sha1);
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void resolveMissingModDependencies(Path modsDir, String gameVersion, String loader, ProgressSink progress) throws IOException {
        if (!Files.isDirectory(modsDir) || loader.isBlank()) {
            return;
        }
        for (int pass = 0; pass < 4; pass++) {
            ModDependencyState state = scanForgeDependencies(modsDir);
            Set<String> missing = new HashSet<>(state.required());
            missing.removeAll(state.present());
            missing.removeAll(ignoredModIds());
            if (missing.isEmpty()) {
                return;
            }
            progress.log("Найдены отсутствующие зависимости modpack: " + String.join(", ", missing));
            boolean installedAny = false;
            for (String modId : missing) {
                boolean installed = false;
                for (ModrinthProject dependency : modrinthService.dependencyCandidates(modId, gameVersion, loader)) {
                    if (installed) {
                        break;
                    }
                    try {
                    ModrinthVersion version = modrinthService.latestVersion(dependency, gameVersion, loader);
                    ModrinthFile file = version.primaryFile();
                    Path target = modsDir.resolve(file.fileName());
                    if (fileMatches(target, file.sha1(), file.size())) {
                        progress.log("Файл зависимости уже существует, пропуск: " + target.getFileName());
                    } else {
                        modrinthService.http().download(file.url(), target, file.sha1(), file.size(), progress);
                    }
                    if (!jarHasForgeModId(target, modId)) {
                        Files.deleteIfExists(target);
                        progress.log("Кандидат зависимости не содержит modId " + modId + ": " + dependency.title());
                        continue;
                    }
                    progress.log("Зависимость установлена: " + modId + " -> " + dependency.title());
                    installedAny = true;
                    installed = true;
                    } catch (LauncherException | IOException ex) {
                        progress.log("Не удалось скачать кандидат зависимости " + modId + " (" + dependency.title() + "): " + ex.getMessage());
                    }
                }
                if (!installed) {
                    progress.log("Не удалось автоматически скачать зависимость " + modId + ".");
                }
            }
            if (!installedAny) {
                return;
            }
        }
    }

    private ModDependencyState scanForgeDependencies(Path modsDir) throws IOException {
        HashSet<String> present = new HashSet<>();
        HashSet<String> required = new HashSet<>();
        try (var stream = Files.list(modsDir)) {
            for (Path jar : stream.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                try {
                    ModManifestMetadata metadata = ModManifestInspector.inspect(jar);
                    present.addAll(metadata.providedModIds());
                    for (ModManifestDependency dependency : metadata.requiredDependencies()) {
                        required.add(dependency.modId());
                    }
                } catch (IOException ignored) {
                    // Damaged or non-standard jars are left to the loader; dependency scan is best effort.
                }
            }
        }
        return new ModDependencyState(present, required);
    }

    private boolean jarHasForgeModId(Path jar, String modId) throws IOException {
        return ModManifestInspector.providedModIds(jar).contains(
                modId == null ? "" : modId.toLowerCase(java.util.Locale.ROOT));
    }

    private Set<String> ignoredModIds() {
        return ModManifestInspector.ignoredPlatformIds();
    }

    private record ModDependencyState(Set<String> present, Set<String> required) {
    }
}
