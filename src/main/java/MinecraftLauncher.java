import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class MinecraftLauncher {
    private static final long MAX_PROCESS_LOG_SIZE = 5L * 1024L * 1024L;
    private final MinecraftInstaller installer;
    private final OptiFineService optiFineService;
    private final AuthlibInjectorService authlibInjectorService = new AuthlibInjectorService();
    private final JavaRuntimeService javaRuntimeService = new JavaRuntimeService();

    MinecraftLauncher(MinecraftInstaller installer, OptiFineService optiFineService) {
        this.installer = installer;
        this.optiFineService = optiFineService;
    }

    Process launch(String versionId, LauncherSettings settings, Account account, ProgressSink progress) {
        return launch(versionId, settings, account, progress, null, null);
    }

    /**
     * Launches Minecraft like {@link #launch(String, LauncherSettings, Account, ProgressSink)},
     * but if {@code quickPlayServerAddress} (format "host" or "host:port") is provided, the
     * game is started with the vanilla Quick Play argument so it connects directly to that
     * server, skipping the title screen and server list.
     */
    Process launch(String versionId, LauncherSettings settings, Account account, ProgressSink progress, String quickPlayServerAddress) {
        return launch(versionId, settings, account, progress, quickPlayServerAddress, null);
    }

    /**
     * Launches Minecraft like {@link #launch(String, LauncherSettings, Account, ProgressSink)},
     * but jumps straight into the given singleplayer world (vanilla Quick Play), skipping the
     * title screen and world list — used by the "Играть" button on a world row in the instance
     * "Миры" tab.
     */
    Process launchWorld(String versionId, LauncherSettings settings, Account account, ProgressSink progress, String worldName) {
        return launch(versionId, settings, account, progress, null, worldName);
    }

    private Process launch(String versionId, LauncherSettings settings, Account account, ProgressSink progress,
                            String quickPlayServerAddress, String quickPlaySingleplayerWorld) {
        Account launchAccount = prepareLaunchAccount(settings, account, progress);
        progress.status("Подготовка " + versionId);
        ResolvedVersion version = installer.prepareForLaunch(versionId, settings, progress);
        List<Path> classpath = installer.downloadLibraries(version, settings.gameDirectory(), progress);
        Path nativesDirectory = extractNatives(version, settings.gameDirectory(), progress);
        Path launchGameDirectory = ProfileDirectories.launchGameDirectory(settings.gameDirectory(), version.id());
        if (!launchGameDirectory.equals(settings.gameDirectory())) {
            progress.log("Forge profile gameDir: " + launchGameDirectory);
        }
        Path legacyOptiFineLaunchJar = ensureForgeOptiFineMod(version, settings.gameDirectory(), launchGameDirectory, progress);
        if (legacyOptiFineLaunchJar != null && !classpath.contains(legacyOptiFineLaunchJar)) {
            classpath.add(legacyOptiFineLaunchJar);
            progress.log("OptiFine добавлен в classpath ModLauncher: " + legacyOptiFineLaunchJar.getFileName());
        }
        Map<String, String> variables = variables(version, settings, launchGameDirectory, launchAccount, classpath, nativesDirectory);
        Path javaPath = javaRuntimeService.resolveJava(version, settings, progress);

        List<String> command = new ArrayList<>();
        command.add(javaPath.toString());
        addElyByAuthlibInjector(command, launchAccount, progress);
        command.add("-Xms" + settings.minMemoryMb() + "M");
        command.add("-Xmx" + settings.maxMemoryMb() + "M");

        List<String> jvmArgs = normalizeForgeIgnoreList(resolveArguments(version.jvmArguments(), variables), version, classpath);
        if (jvmArgs.isEmpty()) {
            command.add("-Djava.library.path=" + nativesDirectory);
            command.add("-Dminecraft.launcher.brand=MSCLauncher");
            command.add("-Dminecraft.launcher.version=1.0");
            command.add("-cp");
            command.add(variables.get("classpath"));
        } else {
            command.addAll(jvmArgs);
            if (!containsClasspathArgument(jvmArgs)) {
                command.add("-cp");
                command.add(variables.get("classpath"));
            }
        }

        if (version.mainClass().isBlank()) {
            throw new LauncherException("В version JSON не указан mainClass для " + version.id());
        }
        command.add(version.mainClass());
        List<String> gameArgs = version.gameArguments().isEmpty()
                ? splitLegacyArguments(replaceVariables(version.legacyMinecraftArguments(), variables))
                : resolveArguments(version.gameArguments(), variables);
        if (quickPlayServerAddress != null && !quickPlayServerAddress.isBlank()) {
            gameArgs.add("--quickPlayMultiplayer");
            gameArgs.add(quickPlayServerAddress);
            progress.log("Быстрое подключение к серверу: " + quickPlayServerAddress);
        } else if (quickPlaySingleplayerWorld != null && !quickPlaySingleplayerWorld.isBlank()) {
            gameArgs.add("--quickPlaySingleplayer");
            gameArgs.add(quickPlaySingleplayerWorld);
            progress.log("Быстрый запуск в мир: " + quickPlaySingleplayerWorld);
        }
        command.addAll(gameArgs);

        progress.log("Команда запуска: " + redact(command));
        return startProcess(command, launchGameDirectory, settings, progress);
    }

    private void addElyByAuthlibInjector(List<String> command, Account account, ProgressSink progress) {
        if (!usesElyByAuthlib(account)) {
            return;
        }
        Path injector = authlibInjectorService.ensureInstalled(progress);
        command.add("-javaagent:" + injector.toAbsolutePath() + "=ely.by");
        progress.log("Ely.by authlib-injector enabled: " + injector.getFileName());
    }

    private boolean usesElyByAuthlib(Account account) {
        if (account.type() == Account.Type.ELY_BY) {
            return true;
        }
        return account.type() == Account.Type.OFFLINE
                && !account.uuid().equals(Account.offlineUuid(account.username()));
    }

    private Path ensureForgeOptiFineMod(ResolvedVersion version, Path baseGameDirectory, Path launchGameDirectory, ProgressSink progress) {
        String versionId = version.id();
        String lower = versionId.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("forge") || lower.contains("neoforge")) {
            return null;
        }
        String gameVersion = VersionSort.baseGameVersion(version.rootId());
        // Forge (and Forge+OptiFine) is isolated again (see ProfileDirectories): launchGameDirectory
        // here is this profile's own msc-launcher/Profiles/<versionId> folder, not the shared
        // .minecraft, so this mods folder belongs to this exact Forge(+OptiFine) profile only —
        // no other profile's mods/OptiFine jar can end up here.
        Path modsDir = launchGameDirectory.resolve("mods");
        try {
            Files.createDirectories(modsDir);
            boolean forgeOptiFineProfile = lower.contains("optifine");
            boolean hasOptiFineInMods = findMatchingOptiFine(modsDir, gameVersion) != null;
            Path profileOptiFineDir = optiFineService.legacyForgeOptiFineDirectory(launchGameDirectory);
            boolean hasLegacyOptiFine = findMatchingOptiFine(profileOptiFineDir, gameVersion) != null;
            if (!forgeOptiFineProfile && !hasOptiFineInMods && !hasLegacyOptiFine) {
                return null;
            }
            // Self-heal profiles created before per-profile isolation existed: purge any
            // OptiFine jar in this profile's own mods folder that doesn't match the current
            // game version. Leftover OptiFine jars for other Minecraft versions are exactly
            // what causes Forge's ModLauncher to see two OptiFine automatic modules exporting
            // the same package on the module path (a fatal "split package" resolution error).
            purgeMismatchedOptiFine(modsDir, gameVersion, progress);
            purgeDuplicateOptiFine(modsDir, gameVersion, progress);
            boolean requiresOptiFineValidation = optiFineService.requiresModJarExtraction(gameVersion);
            // IMPORTANT: a jar that merely matches the OptiFine filename pattern isn't enough —
            // for Forge 1.13+ it must also be loadable by that generation of Forge. For 1.13-1.15
            // OptiFine is legacy-loaded through its Forge tweaker/transformation service and does
            // not need OptiForge or mods.toml; for newer versions we require normal Forge metadata.
            Path existing = findMatchingOptiFine(modsDir, gameVersion);
            if (existing != null) {
                if (!requiresOptiFineValidation || optiFineService.isUsableForgeOptiFineJar(existing, gameVersion)) {
                    if (optiFineService.usesLegacyForgeOptiFineLoading(gameVersion)) {
                        Path launchJar = moveLegacyOptiFineOutOfMods(existing, launchGameDirectory, progress);
                        if (!optiFineService.isForgeCompatibleRelease(gameVersion, launchJar.getFileName().toString(), progress)) {
                            progress.log("OptiFine " + launchJar.getFileName()
                                    + " не поддерживает Forge для Minecraft " + gameVersion
                                    + " без OptiForge. Запускаю обычный Forge без OptiFine.");
                            return null;
                        }
                        return optiFineService.prepareLegacyForgeLaunchJar(launchJar, launchGameDirectory, progress);
                    }
                    return null;
                }
                Files.delete(existing);
                progress.log("Удалён неизвлечённый/невалидный OptiFine jar из профиля: " + existing.getFileName());
            }
            // Prefer the launcher's own per-release download cache: each file there is named
            // after its exact OptiFine release and was never exposed to Forge's mod scanning,
            // so it can't have picked up contamination from other instances.
            Path source = findMatchingOptiFine(profileOptiFineDir, gameVersion);
            if (source == null) {
                source = findMatchingOptiFine(LauncherSettings.settingsDirectory().resolve("installers").resolve("optifine"), gameVersion);
            }
            if (source == null) {
                // Fallback for profiles installed/built back when Forge+OptiFine still had its
                // own isolated instance: the jar may still be sitting in this profile's old
                // msc-launcher/Profiles/<versionId>/mods folder. Without this, such a profile
                // silently launches as plain Forge (no crash, just missing OptiFine) because the
                // now-shared .minecraft/mods location has never actually seen its jar.
                Path legacyModsDir = ProfileDirectories.legacyIsolatedModsDirectory(versionId);
                if (legacyModsDir != null && !legacyModsDir.equals(modsDir)) {
                    source = findMatchingOptiFine(legacyModsDir, gameVersion);
                    if (source != null) {
                        progress.log("Найден OptiFine из старой изолированной папки профиля: " + source);
                    }
                }
            }
            if (source == null) {
                progress.log("Forge + OptiFine профиль \"" + versionId + "\": jar OptiFine нигде не найден (ни в "
                        + modsDir + ", ни в кэше загрузок, ни в старой папке профиля). Запускаю как обычный Forge "
                        + "без OptiFine — переустановите Forge + OptiFine для Minecraft " + gameVersion
                        + " из списка версий, чтобы включить его снова.");
                return null;
            }
            if (requiresOptiFineValidation && !optiFineService.isUsableForgeOptiFineJar(source, gameVersion)) {
                throw new LauncherException("Найден OptiFine jar, который Forge не сможет загрузить: " + source.getFileName()
                        + ". Переустановите Forge + OptiFine для Minecraft " + gameVersion + ".");
            }
            if (optiFineService.usesLegacyForgeOptiFineLoading(gameVersion)) {
                if (!optiFineService.isForgeCompatibleRelease(gameVersion, source.getFileName().toString(), progress)) {
                    progress.log("OptiFine " + source.getFileName()
                            + " не поддерживает Forge для Minecraft " + gameVersion
                            + " без OptiForge. Запускаю обычный Forge без OptiFine.");
                    return null;
                }
                Path target = optiFineService.legacyForgeOptiFineDirectory(launchGameDirectory).resolve(source.getFileName());
                Files.createDirectories(target.getParent());
                if (!source.equals(target)) {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                progress.log("OptiFine подготовлен вне mods для Forge 1.13-1.15: " + target);
                return optiFineService.prepareLegacyForgeLaunchJar(target, launchGameDirectory, progress);
            }
            Path target = modsDir.resolve(source.getFileName());
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            progress.log("OptiFine добавлен в профиль Forge + OptiFine: " + target);
            return null;
        } catch (IOException ex) {
            throw new LauncherException("Не удалось подготовить OptiFine для Forge-профиля: " + ex.getMessage(), ex);
        }
    }

    private Path moveLegacyOptiFineOutOfMods(Path existing, Path launchGameDirectory, ProgressSink progress) throws IOException {
        Path target = optiFineService.legacyForgeOptiFineDirectory(launchGameDirectory).resolve(existing.getFileName());
        Files.createDirectories(target.getParent());
        Files.move(existing, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        progress.log("OptiFine перенесён из mods в служебную папку профиля: " + target);
        return target;
    }

    /**
     * Removes any OptiFine jar from {@code modsDir} that belongs to a different Minecraft
     * version than {@code gameVersion}. Only touches files that are clearly OptiFine jars
     * (by filename pattern) — never touches other mods.
     */
    private void purgeMismatchedOptiFine(Path modsDir, String gameVersion, ProgressSink progress) throws IOException {
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (var stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isAnyOptiFineJar(path.getFileName().toString()))
                    .filter(path -> !isOptiFineFor(path.getFileName().toString(), gameVersion))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            progress.log("Удалён OptiFine jar другой версии из изолированного профиля: " + path.getFileName());
                        } catch (IOException ex) {
                            progress.log("Не удалось удалить старый OptiFine jar " + path.getFileName() + ": " + ex.getMessage());
                        }
                    });
        }
    }

    /**
     * Removes duplicate OptiFine jars for the SAME Minecraft version from {@code modsDir},
     * keeping only one. Windows-style "copy" duplicates (e.g. downloading/copying the jar twice
     * produces "OptiFine_1.20.1_HD_U_I6.jar" AND "OptiFine_1.20.1_HD_U_I6 (1).jar") both match
     * the current game version, so {@link #purgeMismatchedOptiFine} leaves both in place — and
     * Forge's ModLauncher then sees two separate OptiFine automatic modules exporting the exact
     * same repackaged Minecraft classes, which is a fatal "split package" module resolution
     * error at startup (java.lang.module.ResolutionException: Modules ... export package ...).
     * Prefers keeping the copy without a "(n)" suffix (that's the original) when there is one.
     */
    private void purgeDuplicateOptiFine(Path modsDir, String gameVersion, ProgressSink progress) throws IOException {
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        List<Path> matches;
        try (var stream = Files.list(modsDir)) {
            matches = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isOptiFineFor(path.getFileName().toString(), gameVersion))
                    .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toList());
        }
        if (matches.size() <= 1) {
            return;
        }
        Path keep = matches.stream()
                .filter(path -> !path.getFileName().toString().matches(".*\\s\\(\\d+\\)\\.jar$"))
                .findFirst()
                .orElse(matches.get(0));
        for (Path path : matches) {
            if (path.equals(keep)) {
                continue;
            }
            try {
                Files.delete(path);
                progress.log("Удалён дублирующийся OptiFine jar (вызывал конфликт модулей Forge при запуске): "
                        + path.getFileName());
            } catch (IOException ex) {
                progress.log("Не удалось удалить дублирующийся OptiFine jar " + path.getFileName() + ": " + ex.getMessage());
            }
        }
    }

    private boolean isAnyOptiFineJar(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jar") && lower.contains("optifine");
    }

    private Path findMatchingOptiFine(Path directory, String gameVersion) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isOptiFineFor(path.getFileName().toString(), gameVersion))
                    .findFirst()
                    .orElse(null);
        }
    }

    // Раньше требовалось точное имя "OptiFine_<gameVersion>_...jar" (как раздаёт optifine.net
    // через встроенный установщик). Сборки со сторонних сайтов (например minecraft-inside)
    // часто переименованы — другой регистр, другой разделитель вокруг номера версии — и точное
    // совпадение ломалось, хотя сам jar валиден и рабочий. Теперь достаточно: в имени файла
    // есть "optifine" (в любом регистре) и номер версии Minecraft как отдельный "токен",
    // окружённый не-буквенно-цифровыми символами (а не любая произвольная подстрока).
    private boolean isOptiFineFor(String fileName, String gameVersion) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".jar") || !lower.contains("optifine")) {
            return false;
        }
        String version = gameVersion.toLowerCase(java.util.Locale.ROOT);
        return java.util.regex.Pattern
                .compile("(^|[^0-9a-z])" + java.util.regex.Pattern.quote(version) + "($|[^0-9a-z])")
                .matcher(lower)
                .find();
    }

    private Account refreshAccountIfNeeded(LauncherSettings settings, Account account, ProgressSink progress) {
        if (account.type() == Account.Type.MICROSOFT) {
            if (settings.microsoftClientId().isBlank()) {
                return account;
            }
            Account refreshed = new MicrosoftAuthService(settings.microsoftClientId()).refresh(account, progress);
            if (refreshed != account) {
                AccountStore.save(refreshed);
                progress.log("Microsoft token обновлён");
            }
            return refreshed;
        }
        if (account.type() == Account.Type.ELY_BY) {
            if (settings.elyByClientId().isBlank() || settings.elyByClientSecret().isBlank() || settings.elyByRedirectUri().isBlank()) {
                return account;
            }
            Account refreshed = new ElyByAuthService(settings.elyByClientId(), settings.elyByClientSecret(), settings.elyByRedirectUri()).refresh(account, progress);
            if (refreshed != account) {
                AccountStore.save(refreshed);
                progress.log("Ely.by token refreshed");
            }
            return refreshed;
        }
        return account;
    }

    private Account prepareLaunchAccount(LauncherSettings settings, Account account, ProgressSink progress) {
        Account refreshed = refreshAccountIfNeeded(settings, account, progress);
        if (refreshed.type() != Account.Type.OFFLINE) {
            return refreshed;
        }
        return authlibInjectorService.resolveElyByUuid(refreshed.username(), progress)
                .map(uuid -> {
                    if (uuid.equals(refreshed.uuid())) {
                        return refreshed;
                    }
                    progress.log("External skin profile enabled for offline username " + refreshed.username()
                            + " via Ely.by UUID " + uuid);
                    return new Account(Account.Type.OFFLINE, refreshed.username(), uuid,
                            refreshed.accessToken(), refreshed.refreshToken(), refreshed.expiresAt(), refreshed.xuid());
                })
                .orElse(refreshed);
    }

    private Path extractNatives(ResolvedVersion version, Path gameDirectory, ProgressSink progress) {
        List<MinecraftInstaller.NativeLibrary> nativeLibraries = installer.downloadNativeLibraries(version, gameDirectory, progress);
        try {
            Path base = LauncherSettings.settingsDirectory().resolve("natives");
            Files.createDirectories(base);
            Path nativesDirectory = Files.createTempDirectory(base, sanitize(version.id()) + "-");
            for (MinecraftInstaller.NativeLibrary nativeLibrary : nativeLibraries) {
                extractNativeJar(nativeLibrary, nativesDirectory);
            }
            progress.log("Natives: " + nativesDirectory);
            return nativesDirectory;
        } catch (IOException ex) {
            throw new LauncherException("Не удалось распаковать natives: " + ex.getMessage(), ex);
        }
    }

    private void extractNativeJar(MinecraftInstaller.NativeLibrary nativeLibrary, Path targetDir) throws IOException {
        try (ZipFile zip = new ZipFile(nativeLibrary.path().toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || isExcluded(name, nativeLibrary.excludes())) {
                    continue;
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new IOException("Некорректный путь в native jar: " + name);
                }
                Files.createDirectories(target.getParent());
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private boolean isExcluded(String name, List<String> excludes) {
        if (name.startsWith("META-INF/")) {
            return true;
        }
        for (String exclude : excludes) {
            if (!exclude.isBlank() && name.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> variables(ResolvedVersion version, LauncherSettings settings, Path launchGameDirectory,
                                          Account account, List<Path> classpath, Path nativesDirectory) {
        Map<String, String> vars = new HashMap<>();
        String classpathValue = joinClasspath(classpath);
        Path assetsRoot = ProfileDirectories.assetsInstallDirectory();
        Path gameAssets = gameAssetsDirectory(version, assetsRoot);
        String assetIndexName = Json.string(version.assetIndex(), "id");
        vars.put("auth_player_name", account.username());
        vars.put("auth_session", legacySession(account));
        vars.put("version_name", version.id());
        vars.put("game_directory", launchGameDirectory.toString());
        vars.put("assets_root", assetsRoot.toString());
        vars.put("game_assets", gameAssets.toString());
        vars.put("assets_index_name", assetIndexName);
        vars.put("auth_uuid", account.uuid());
        vars.put("auth_access_token", account.accessToken());
        vars.put("auth_xuid", account.xuid());
        vars.put("clientid", "");
        vars.put("user_type", account.type() == Account.Type.MICROSOFT ? "msa" : account.type() == Account.Type.ELY_BY ? "mojang" : "legacy");
        vars.put("version_type", version.type().isBlank() ? "release" : version.type());
        vars.put("natives_directory", nativesDirectory.toString());
        vars.put("launcher_name", "MSCLauncher");
        vars.put("launcher_version", "1.0");
        vars.put("classpath", classpathValue);
        vars.put("library_directory", ProfileDirectories.librariesInstallDirectory().toString());
        vars.put("classpath_separator", java.io.File.pathSeparator);
        vars.put("user_properties", "{}");
        vars.put("resolution_width", "1280");
        vars.put("resolution_height", "720");
        return vars;
    }

    private Path gameAssetsDirectory(ResolvedVersion version, Path assetsRoot) {
        String assets = version.assets();
        if ("legacy".equals(assets) || "pre-1.6".equals(assets)) {
            return assetsRoot.resolve("virtual").resolve(assets);
        }
        return assetsRoot;
    }

    private String legacySession(Account account) {
        if ((account.type() == Account.Type.MICROSOFT || account.type() == Account.Type.ELY_BY) && !account.accessToken().isBlank()) {
            return "token:" + account.accessToken() + ":" + account.uuid();
        }
        return "0";
    }

    private String joinClasspath(List<Path> classpath) {
        return classpath.stream()
                .map(Path::toString)
                .reduce((left, right) -> left + java.io.File.pathSeparator + right)
                .orElse("");
    }

    private List<String> resolveArguments(List<Object> rawArguments, Map<String, String> variables) {
        List<String> output = new ArrayList<>();
        for (Object raw : rawArguments) {
            if (raw instanceof String string) {
                output.add(replaceVariables(string, variables));
            } else if (raw instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) rawMap;
                if (RuleEvaluator.isAllowed(map)) {
                    Object value = map.get("value");
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            output.add(replaceVariables(Json.string(item), variables));
                        }
                    } else {
                        output.add(replaceVariables(Json.string(value), variables));
                    }
                }
            }
        }
        return output;
    }

    private List<String> normalizeForgeIgnoreList(List<String> jvmArgs, ResolvedVersion version, List<Path> classpath) {
        if (jvmArgs.isEmpty() || !isBootstrapModLauncher(version)) {
            return jvmArgs;
        }
        List<String> clientJarNames = classpath.stream()
                .filter(path -> path.getParent() != null)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .filter(path -> path.getParent().getFileName() != null)
                .filter(path -> path.getParent().getFileName().toString().equals(path.getFileName().toString().replaceFirst("\\.jar$", "")))
                .map(path -> path.getFileName().toString())
                .distinct()
                .toList();
        if (clientJarNames.isEmpty()) {
            return jvmArgs;
        }
        ArrayList<String> normalized = new ArrayList<>(jvmArgs);
        for (int i = 0; i < normalized.size(); i++) {
            String arg = normalized.get(i);
            if (!arg.startsWith("-DignoreList=")) {
                continue;
            }
            String value = arg.substring("-DignoreList=".length());
            java.util.LinkedHashSet<String> ignored = new java.util.LinkedHashSet<>();
            if (!value.isBlank()) {
                ignored.addAll(java.util.Arrays.asList(value.split(",")));
            }
            boolean changed = false;
            for (String jarName : clientJarNames) {
                if (ignored.add(jarName)) {
                    changed = true;
                }
            }
            if (changed) {
                normalized.set(i, "-DignoreList=" + String.join(",", ignored));
            }
            return normalized;
        }
        return jvmArgs;
    }

    private boolean isBootstrapModLauncher(ResolvedVersion version) {
        String mainClass = version.mainClass().toLowerCase(java.util.Locale.ROOT);
        return mainClass.contains("bootstraplauncher") || mainClass.contains("modlauncher");
    }

    private String replaceVariables(String input, Map<String, String> variables) {
        String output = input == null ? "" : input;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            output = output.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private boolean containsClasspathArgument(List<String> args) {
        for (String arg : args) {
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("${classpath}")) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitLegacyArguments(String text) {
        List<String> args = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return args;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length() && shouldConsumeLegacyEscape(text.charAt(i + 1), quoted)) {
                current.append(text.charAt(++i));
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }
        return args;
    }

    private boolean shouldConsumeLegacyEscape(char next, boolean quoted) {
        return next == '"' || next == '\\' || (quoted && Character.isWhitespace(next));
    }

    private Process startProcess(List<String> command, Path gameDirectory, LauncherSettings settings, ProgressSink progress) {
        try {
            Files.createDirectories(gameDirectory);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(gameDirectory.toFile());
            builder.redirectErrorStream(true);
            Path processLog = LauncherSettings.settingsDirectory().resolve("minecraft-process.log");
            prepareProcessLog(processLog, gameDirectory, redact(command), progress);

            // If running on Linux, propagate Wayland/X11 environment to child process.
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("nux") || os.contains("linux")) {
                Map<String, String> env = builder.environment();
                String wayland = System.getenv("WAYLAND_DISPLAY");
                if (wayland != null && !wayland.isBlank()) {
                    // Prefer Wayland backends for launched process when parent runs on Wayland
                    env.put("GDK_BACKEND", "wayland");
                    env.put("SDL_VIDEODRIVER", "wayland");
                    env.put("WAYLAND_DISPLAY", wayland);
                    progress.log("Wayland detected, setting GDK_BACKEND=wayland and SDL_VIDEODRIVER=wayland");
                } else {
                    String display = System.getenv("DISPLAY");
                    if (display != null && !display.isBlank()) {
                        env.put("DISPLAY", display);
                        progress.log("X11 detected, using DISPLAY=" + display);
                    }
                }
            }

            Process process = builder.start();
            Thread outputThread = new Thread(() -> pipeProcessOutput(process, progress, processLog), "minecraft-output");
            outputThread.setDaemon(true);
            outputThread.start();
            return process;
        } catch (IOException ex) {
            throw new LauncherException("Не удалось запустить Minecraft: " + ex.getMessage(), ex);
        }
    }

    private void pipeProcessOutput(Process process, ProgressSink progress, Path processLog) {
        BufferedWriter writer = null;
        try {
            writer = Files.newBufferedWriter(processLog, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            progress.log("Не удалось открыть лог Minecraft: " + ex.getMessage());
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                progress.log("[MC] " + line);
                if (writer != null) {
                    try {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                    } catch (IOException ex) {
                        progress.log("Не удалось дописать лог Minecraft: " + ex.getMessage());
                        closeQuietly(writer);
                        writer = null;
                    }
                }
            }
            int code = process.waitFor();
            String exitMessage = "Minecraft завершился с кодом " + code;
            progress.log(exitMessage);
            if (writer != null) {
                writer.write("--- " + exitMessage + " ---");
                writer.newLine();
                writer.flush();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            progress.log("Ошибка чтения вывода Minecraft: " + ex.getMessage());
        } finally {
            closeQuietly(writer);
        }
    }

    private void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // The game process must not be affected by a diagnostic-log close failure.
        }
    }

    private void prepareProcessLog(Path logFile, Path gameDirectory, String command, ProgressSink progress) {
        try {
            Files.createDirectories(logFile.getParent());
            if (Files.isRegularFile(logFile) && Files.size(logFile) >= MAX_PROCESS_LOG_SIZE) {
                Files.move(logFile, logFile.resolveSibling("minecraft-process.previous.log"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write("--- Запуск " + Instant.now() + " ---");
                writer.newLine();
                writer.write("Папка игры: " + gameDirectory.toAbsolutePath().normalize());
                writer.newLine();
                writer.write("Команда: " + command);
                writer.newLine();
            }
            progress.log("Лог Minecraft: " + logFile);
        } catch (IOException ex) {
            progress.log("Не удалось подготовить лог Minecraft: " + ex.getMessage());
        }
    }

    private String redact(List<String> command) {
        List<String> copy = new ArrayList<>(command);
        for (int i = 0; i < copy.size(); i++) {
            String original = command.get(i);
            String previous = i > 0 ? command.get(i - 1) : "";
            if (previous.equals("--accessToken") || previous.equals("--auth_access_token")
                    || previous.equals("--uuid") || previous.equals("--xuid")
                    || previous.equals("--clientId")) {
                copy.set(i, "<redacted>");
            } else if (original.startsWith("--accessToken=")
                    || original.startsWith("--auth_access_token=")
                    || original.startsWith("--uuid=") || original.startsWith("--xuid=")
                    || original.startsWith("--clientId=")) {
                copy.set(i, original.substring(0, original.indexOf('=') + 1) + "<redacted>");
            }
        }
        return String.join(" ", copy);
    }

    private String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
