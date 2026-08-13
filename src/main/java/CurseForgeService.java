import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

record CurseForgeFile(String id, String fileName, String downloadUrl, long size, String sha1,
                      List<ModDependencyLink> dependencies) {
    CurseForgeFile(String id, String fileName, String downloadUrl, long size) {
        this(id, fileName, downloadUrl, size, "", List.of());
    }

    CurseForgeFile(String id, String fileName, String downloadUrl, long size,
                   List<ModDependencyLink> dependencies) {
        this(id, fileName, downloadUrl, size, "", dependencies);
    }

    CurseForgeFile {
        id = id == null ? "" : id;
        fileName = fileName == null ? "" : fileName;
        downloadUrl = downloadUrl == null ? "" : downloadUrl;
        sha1 = sha1 == null ? "" : sha1;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}

final class CurseForgeService {
    private static final String API = "https://api.curseforge.com/v1";
    private static final int PAGE_SIZE = 50;
    private static final int DEFAULT_SEARCH_PAGE_SIZE = 25;
    private static final int MAX_ADDON_CANDIDATES = 28;
    private static final int MAX_ADDON_RESULTS = 12;
    private static final String[] BUNDLED_API_KEY_PARTS = {
            "01$a2$",
            "Ars/c$",
            "lCKjQ.",
            "XBpnNE",
            "rOU6rD",
            "5m4emI",
            "KpKDMr",
            "Vv6Xlz",
            "3Ni/tR",
            "mVjVr4"
    };
    private static final String BUNDLED_API_KEY = decodeBundledApiKey();
    private final HttpService http = new HttpService();
    private final Map<String, DependencyProject> dependencyProjectCache = new java.util.HashMap<>();

    boolean isConfigured() {
        return !apiKey().isBlank();
    }

    List<ModrinthProject> search(String query, String projectType, String gameVersion, String loader) {
        return search(query, projectType, gameVersion, loader, 0, DEFAULT_SEARCH_PAGE_SIZE);
    }

    List<ModrinthProject> search(String query, String projectType, String gameVersion, String loader, int offset, int pageSize) {
        return searchPage(query, projectType, gameVersion, loader, offset, pageSize).projects();
    }

    ModSearchPage searchPage(String query, String projectType, String gameVersion, String loader, int offset, int pageSize) {
        return searchPage(query, projectType, gameVersion, loader, offset, pageSize, false);
    }

    private ModSearchPage searchPage(String query, String projectType, String gameVersion,
                                     String loader, int offset, int pageSize,
                                     boolean sortByDownloads) {
        String searchQuery = query == null ? "" : query.trim();
        String type = projectType == null ? "" : projectType.trim();
        String version = gameVersion == null ? "" : gameVersion.trim();
        int safeOffset = Math.max(0, offset);
        int safePageSize = Math.max(1, pageSize);
        StringBuilder url = new StringBuilder(API + "/mods/search?gameId=432&pageSize=" + safePageSize + "&index=" + safeOffset);
        Integer classId = classId(type);
        if (classId != null) {
            url.append("&classId=").append(classId);
        }
        if (!searchQuery.isBlank()) url.append("&searchFilter=").append(encode(searchQuery));
        if (!version.isBlank()) url.append("&gameVersion=").append(encode(version));
        if (sortByDownloads) {
            url.append("&sortField=6&sortOrder=desc");
        }
        Integer modLoaderType = modLoaderType(loader);
        if (modLoaderType != null) {
            url.append("&modLoaderType=").append(modLoaderType);
        }
        Map<String, Object> root = http.getJsonObjectWithApiKey(url.toString(), apiKey());
        ArrayList<ModrinthProject> result = new ArrayList<>();
        for (Object item : Json.list(root, "data")) {
            Map<String, Object> mod = Json.object(item);
            String id = Json.string(mod, "id");
            String slug = Json.string(mod, "slug");
            if (slug.isBlank()) slug = id;
            String title = Json.string(mod, "name");
            String description = Json.string(mod, "summary");
            if (description.isBlank()) description = Json.string(mod, "description");
            String author = "";
            List<Object> authors = Json.list(mod, "authors");
            if (!authors.isEmpty() && authors.get(0) instanceof Map<?, ?>) {
                author = Json.string(Json.object(authors.get(0)), "name");
            }
            long downloads = Json.longValue(mod, "downloadCount", 0);
            String iconUrl = "";
            Map<String, Object> logo = Json.object(mod, "logo");
            if (!logo.isEmpty()) {
                iconUrl = Json.string(logo, "url");
            } else {
                iconUrl = Json.string(mod, "logoUrl");
            }
            String mappedType = "modpack".equals(type) ? "modpack" : "mod";
            result.add(new ModrinthProject(id, slug, title, description, mappedType, author, downloads, iconUrl, galleryUrls(mod), ""));
        }
        int total = (int) Json.longValue(Json.object(root, "pagination"), "totalCount", result.size());
        return new ModSearchPage(result, total);
    }

    ModrinthProject projectDetails(ModrinthProject project) {
        Map<String, Object> root = http.getJsonObjectWithApiKey(API + "/mods/" + encode(project.id()), apiKey());
        Map<String, Object> data = Json.object(root, "data");
        String id = Json.string(data, "id");
        String slug = Json.string(data, "slug");
        String title = Json.string(data, "name");
        String description = Json.string(data, "description");
        String author = "";
        List<Object> authors = Json.list(data, "authors");
        if (!authors.isEmpty() && authors.get(0) instanceof Map<?, ?>) {
            author = Json.string(Json.object(authors.get(0)), "name");
        }
        long downloads = Json.longValue(data, "downloadCount", project.downloads());
        String iconUrl = "";
        Map<String, Object> logo = Json.object(data, "logo");
        if (!logo.isEmpty()) iconUrl = Json.string(logo, "url");
        return new ModrinthProject(id, slug, title, description, project.projectType(), author, downloads, iconUrl, galleryUrls(data), description);
    }

    CurseForgeFile latestFile(ModrinthProject project, String gameVersion, String loader) {
        ModFileRelease release = latestInstallRelease(project, gameVersion, loader);
        return new CurseForgeFile(release.id(), release.fileName(), release.downloadUrl(),
                release.size(), release.sha1(), release.dependencies());
    }

    /** Exact install candidate, including CurseForge dependency metadata for its selected file. */
    ModFileRelease latestInstallRelease(ModrinthProject project, String gameVersion, String loader) {
        return installRelease(project.id(),
                latestFileJson(project.id(), project.title(), gameVersion, loader, true), true);
    }

    /** Resolves the latest compatible file for a dependency project id. */
    ModFileRelease latestInstallRelease(String projectId, String gameVersion, String loader) {
        return installRelease(projectId,
                latestFileJson(projectId, projectId, gameVersion, loader, true), true);
    }

    ModFileRelease dependencyInstallRelease(ModDependencyLink dependency, String gameVersion, String loader) {
        if (dependency == null || dependency.projectId().isBlank()) {
            throw new LauncherException("У зависимости CurseForge нет project id: "
                    + (dependency == null ? "" : dependency.name()));
        }
        return latestInstallRelease(dependency.projectId(), gameVersion, loader);
    }

    /** Resolves a loader mod id discovered inside a JAR to an installable CurseForge project. */
    ModDependencyLink resolveManifestDependency(String modId, String versionConstraint,
                                                String gameVersion, String loader) {
        ArrayList<ModrinthProject> candidates = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String query : dependencyQueries(modId)) {
            for (ModrinthProject project : search(query, "mod", gameVersion, loader)) {
                if (seen.add(project.id())) {
                    candidates.add(project);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new LauncherException("Не удалось найти обязательную зависимость CurseForge: " + modId);
        }
        String normalized = normalizeDependencyName(modId);
        ModrinthProject selected = candidates.get(0);
        for (ModrinthProject candidate : candidates) {
            String slug = normalizeDependencyName(candidate.slug());
            String title = normalizeDependencyName(candidate.title());
            if (slug.equals(normalized) || title.equals(normalized) || slug.contains(normalized)) {
                selected = candidate;
                break;
            }
        }
        String url = selected.slug().isBlank() ? ""
                : "https://www.curseforge.com/minecraft/mc-mods/" + encode(selected.slug());
        return new ModDependencyLink(selected.id(), "", selected.title(), "required", url, modId,
                versionConstraint);
    }

    private Map<String, Object> latestFileJson(String projectId, String projectTitle,
                                                String gameVersion, String loader) {
        return latestFileJson(projectId, projectTitle, gameVersion, loader, false);
    }

    private Map<String, Object> latestFileJson(String projectId, String projectTitle,
                                                String gameVersion, String loader,
                                                boolean requireCompatible) {
        String key = apiKey();
        Map<String, Object> root = http.getJsonObjectWithApiKey(filesUrl(projectId, gameVersion, loader, true), key);
        List<Object> files = Json.list(root, "data");
        if (files.isEmpty() && loader != null && !loader.isBlank()) {
            root = http.getJsonObjectWithApiKey(filesUrl(projectId, gameVersion, "", false), key);
            files = Json.list(root, "data");
        }
        if (files.isEmpty()) {
            throw new LauncherException("На CurseForge нет файлов для " + projectTitle + ".");
        }
        Map<String, Object> selected = selectCompatibleFile(files, gameVersion, loader);
        if (selected.isEmpty()) {
            throw new LauncherException("На CurseForge нет доступного файла для " + projectTitle + ".");
        }
        if (requireCompatible
                && (!matchesGameVersion(selected, gameVersion) || !matchesLoader(selected, loader))) {
            throw new LauncherException("На CurseForge нет совместимой зависимости " + projectTitle
                    + filterSuffix(gameVersion, loader) + ".");
        }
        return selected;
    }

    private String directDownloadUrl(String projectId, Map<String, Object> file) {
        String fileId = Json.string(file, "id");
        String downloadUrl = Json.string(file, "downloadUrl");
        if (downloadUrl.isBlank()) {
            try {
                Map<String, Object> dr = http.getJsonObjectWithApiKey(API + "/mods/" + encode(projectId)
                        + "/files/" + encode(fileId) + "/download-url", apiKey());
                if (dr.containsKey("data") && dr.get("data") instanceof Map) {
                    Map<String, Object> d = Json.object(dr, "data");
                    downloadUrl = Json.string(d, "downloadUrl");
                } else {
                    downloadUrl = Json.string(dr, "data");
                }
            } catch (Exception ex) {
                // ignore
            }
        }
        return downloadUrl;
    }

    private ModFileRelease installRelease(String projectId, Map<String, Object> file,
                                          boolean resolveDirectDownload) {
        return installRelease(projectId, file, resolveDirectDownload, true);
    }

    private ModFileRelease installRelease(String projectId, Map<String, Object> file,
                                          boolean resolveDirectDownload,
                                          boolean resolveDependencyProjects) {
        ArrayList<String> gameVersions = new ArrayList<>();
        ArrayList<String> loaders = new ArrayList<>();
        for (Object versionItem : Json.list(file, "gameVersions")) {
            String value = Json.string(versionItem);
            if (isMinecraftGameVersion(value)) {
                if (!gameVersions.contains(value)) {
                    gameVersions.add(value);
                }
            } else if (isLoaderName(value)) {
                String normalized = normalizedLoaderName(value);
                if (!loaders.contains(normalized)) {
                    loaders.add(normalized);
                }
            }
        }
        gameVersions.sort(VersionSort.latestFirst());

        String downloadUrl = resolveDirectDownload
                ? directDownloadUrl(projectId, file)
                : Json.string(file, "downloadUrl");
        String displayName = Json.string(file, "displayName");
        return new ModFileRelease(
                Json.string(file, "id"),
                displayName,
                displayName,
                Json.string(file, "fileName"),
                releaseType(Json.longValue(file, "releaseType", 0)),
                Json.string(file, "fileDate"),
                gameVersions,
                loaders,
                downloadUrl,
                sha1(file),
                Json.longValue(file, "fileLength", Json.longValue(file, "fileSize", -1)),
                dependencyLinks(file, resolveDependencyProjects)
        );
    }

    private ArrayList<ModDependencyLink> dependencyLinks(Map<String, Object> file) {
        return dependencyLinks(file, true);
    }

    private ArrayList<ModDependencyLink> dependencyLinks(Map<String, Object> file,
                                                         boolean resolveProjects) {
        ArrayList<ModDependencyLink> dependencies = new ArrayList<>();
        for (Object dependencyItem : Json.list(file, "dependencies")) {
            Map<String, Object> dependency = Json.object(dependencyItem);
            String dependencyId = Json.string(dependency, "modId");
            DependencyProject dependencyProject = resolveProjects
                    ? dependencyProject(dependencyId)
                    : new DependencyProject(dependencyId, "");
            dependencies.add(new ModDependencyLink(
                    dependencyId,
                    dependencyProject.title().isBlank() ? dependencyId : dependencyProject.title(),
                    dependencyRelation(Json.longValue(dependency, "relationType", 0)),
                    dependencyProject.url()
            ));
        }
        return dependencies;
    }

    List<String> availableGameVersions(ModrinthProject project, String loader) {
        ArrayList<String> versions = collectAvailableGameVersions(project, loader, loader != null && !loader.isBlank());
        if (versions.isEmpty() && loader != null && !loader.isBlank()) {
            versions = collectAvailableGameVersions(project, "", false);
        }
        versions.sort(VersionSort.latestFirst());
        return versions;
    }

    /** Builds the exact Minecraft-version -> loader matrix without resolving file dependencies. */
    ModInstallCompatibility installCompatibility(ModrinthProject project) {
        ModInstallCompatibility.Builder builder = ModInstallCompatibility.builder();
        int index = 0;
        while (index < 500) {
            Map<String, Object> root = http.getJsonObjectWithApiKey(
                    filesUrl(project.id(), "", "", false) + "&index=" + index, apiKey());
            List<Object> files = Json.list(root, "data");
            if (files.isEmpty()) {
                break;
            }
            for (Object item : files) {
                Map<String, Object> file = Json.object(item);
                if (!Json.bool(file, "isAvailable", true)) {
                    continue;
                }
                ArrayList<String> gameVersions = new ArrayList<>();
                ArrayList<String> loaders = new ArrayList<>();
                splitGameVersionsAndLoaders(file, gameVersions, loaders);
                builder.addRelease(gameVersions, loaders);
            }
            long total = Json.longValue(Json.object(root, "pagination"),
                    "totalCount", index + files.size());
            index += files.size();
            if (index >= total || files.size() < PAGE_SIZE) {
                break;
            }
        }
        return builder.build();
    }

    /** Finds only compatible projects whose selected file points back to the root CurseForge id. */
    List<ModAddonCandidate> discoverAddons(ModrinthProject root, String gameVersion, String loader) {
        return discoverAddons(root, "", gameVersion, loader);
    }

    List<ModAddonCandidate> discoverAddons(ModrinthProject root, String rootVersionId,
                                           String gameVersion, String loader) {
        if (root == null || root.id().isBlank() || gameVersion == null || gameVersion.isBlank()
                || loader == null || loader.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashMap<String, ModrinthProject> candidates = new java.util.LinkedHashMap<>();
        for (String query : addonQueries(root)) {
            for (ModrinthProject project : searchPage(query, "mod", gameVersion, loader,
                    0, MAX_ADDON_CANDIDATES, true).projects()) {
                if (!project.id().equals(root.id())) {
                    candidates.putIfAbsent(project.id(), project);
                }
                if (candidates.size() >= MAX_ADDON_CANDIDATES) {
                    break;
                }
            }
            if (candidates.size() >= MAX_ADDON_CANDIDATES) {
                break;
            }
        }
        ArrayList<ModAddonCandidate> addons = new ArrayList<>();
        for (ModrinthProject candidate : candidates.values()) {
            if (addons.size() >= MAX_ADDON_RESULTS) {
                break;
            }
            try {
                Map<String, Object> file = latestFileJson(candidate.id(), candidate.title(),
                        gameVersion, loader, true);
                ModFileRelease release = installRelease(candidate.id(), file, false, false);
                String relation = ModAddonSupport.relationshipToRoot(
                        root.id(), rootVersionId, release);
                if (!relation.isBlank()) {
                    addons.add(new ModAddonCandidate(candidate, release, relation));
                }
            } catch (RuntimeException ignored) {
                // One unavailable candidate must not hide the rest of the verified list.
            }
        }
        return List.copyOf(addons);
    }

    ModFileCatalog projectFiles(ModrinthProject project) {
        ArrayList<Object> rawFiles = new ArrayList<>();
        int index = 0;
        int total = 0;
        while (true) {
            Map<String, Object> page = http.getJsonObjectWithApiKey(
                    API + "/mods/" + encode(project.id()) + "/files?pageSize=" + PAGE_SIZE + "&index=" + index,
                    apiKey());
            List<Object> pageFiles = Json.list(page, "data");
            if (pageFiles.isEmpty()) {
                break;
            }
            rawFiles.addAll(pageFiles);
            Map<String, Object> pagination = Json.object(page, "pagination");
            total = (int) Json.longValue(pagination, "totalCount", rawFiles.size());
            index += pageFiles.size();
            if (index >= total || pageFiles.size() < PAGE_SIZE) {
                break;
            }
        }
        DependencyProject currentProject = dependencyProject(project.id());
        ArrayList<ModFileRelease> releases = new ArrayList<>();

        for (Object item : rawFiles) {
            Map<String, Object> file = Json.object(item);
            if (!Json.bool(file, "isAvailable", true)) {
                continue;
            }
            ArrayList<String> gameVersions = new ArrayList<>();
            ArrayList<String> loaders = new ArrayList<>();
            for (Object versionItem : Json.list(file, "gameVersions")) {
                String value = Json.string(versionItem);
                if (isMinecraftGameVersion(value)) {
                    if (!gameVersions.contains(value)) {
                        gameVersions.add(value);
                    }
                } else if (isLoaderName(value)) {
                    String loader = normalizedLoaderName(value);
                    if (!loaders.contains(loader)) {
                        loaders.add(loader);
                    }
                }
            }
            gameVersions.sort(VersionSort.latestFirst());

            ArrayList<ModDependencyLink> dependencies = new ArrayList<>();
            for (Object dependencyItem : Json.list(file, "dependencies")) {
                Map<String, Object> dependency = Json.object(dependencyItem);
                String dependencyId = Json.string(dependency, "modId");
                DependencyProject dependencyProject = dependencyProject(dependencyId);
                dependencies.add(new ModDependencyLink(
                        dependencyId,
                        dependencyProject.title().isBlank() ? dependencyId : dependencyProject.title(),
                        dependencyRelation(Json.longValue(dependency, "relationType", 0)),
                        dependencyProject.url()
                ));
            }

            String fileId = Json.string(file, "id");
            String downloadUrl = Json.string(file, "downloadUrl");
            if (downloadUrl.isBlank() && !currentProject.url().isBlank()) {
                downloadUrl = currentProject.url() + "/files/" + encode(fileId);
            }
            String displayName = Json.string(file, "displayName");
            releases.add(new ModFileRelease(
                    fileId,
                    displayName,
                    displayName,
                    Json.string(file, "fileName"),
                    releaseType(Json.longValue(file, "releaseType", 0)),
                    Json.string(file, "fileDate"),
                    gameVersions,
                    loaders,
                    downloadUrl,
                    sha1(file),
                    Json.longValue(file, "fileLength", Json.longValue(file, "fileSize", -1)),
                    dependencies
            ));
        }
        return new ModFileCatalog(releases, total);
    }

    private ArrayList<String> collectAvailableGameVersions(ModrinthProject project, String loader, boolean includeLoader) {
        String key = apiKey();
        ArrayList<String> versions = new ArrayList<>();
        int index = 0;
        while (index < 200) {
            String url = filesUrl(project.id(), "", loader, includeLoader) + "&index=" + index;
            Map<String, Object> root = http.getJsonObjectWithApiKey(url, key);
            List<Object> files = Json.list(root, "data");
            if (files.isEmpty()) {
                break;
            }
            for (Object item : files) {
                Map<String, Object> file = Json.object(item);
                if (!Json.bool(file, "isAvailable", true)) {
                    continue;
                }
                for (Object value : Json.list(file, "gameVersions")) {
                    String version = Json.string(value);
                    if (isMinecraftGameVersion(version) && !versions.contains(version)) {
                        versions.add(version);
                    }
                }
            }
            Map<String, Object> pagination = Json.object(root, "pagination");
            long total = Json.longValue(pagination, "totalCount", index + files.size());
            index += files.size();
            if (index >= total || files.size() < PAGE_SIZE) {
                break;
            }
        }
        return versions;
    }

    void downloadFile(String modId, String fileId, java.nio.file.Path target, ProgressSink progress) {
        downloadFile(modId, new CurseForgeFile(fileId, target.getFileName().toString(), "", -1), target, progress);
    }

    void downloadFile(String modId, CurseForgeFile file, java.nio.file.Path target, ProgressSink progress) {
        String fileId = file.id();
        String downloadUrl = file.downloadUrl();
        String url = API + "/mods/" + encode(modId) + "/files/" + encode(fileId) + "/download-url";
        try {
            if (downloadUrl == null || downloadUrl.isBlank()) {
                Map<String, Object> root = http.getJsonObjectWithApiKey(url, apiKey());
                if (root.containsKey("data") && root.get("data") instanceof Map) {
                    Map<String, Object> d = Json.object(root, "data");
                    downloadUrl = Json.string(d, "downloadUrl");
                } else {
                    downloadUrl = Json.string(root, "data");
                }
            }
            if (downloadUrl.isBlank()) {
                throw new LauncherException("Не удалось получить ссылку загрузки CurseForge.");
            }
            http.download(downloadUrl, target, file.sha1(), file.size(), progress);
        } catch (Exception ex) {
            throw new LauncherException("Не удалось скачать файл CurseForge: " + ex.getMessage(), ex);
        }
    }

    private String filesUrl(String projectId, String gameVersion, String loader, boolean includeLoader) {
        StringBuilder url = new StringBuilder(API + "/mods/" + encode(projectId) + "/files?pageSize=" + PAGE_SIZE);
        if (gameVersion != null && !gameVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(gameVersion));
        }
        Integer modLoaderType = includeLoader ? modLoaderType(loader) : null;
        if (modLoaderType != null) {
            url.append("&modLoaderType=").append(modLoaderType);
        }
        return url.toString();
    }

    private Map<String, Object> selectCompatibleFile(List<Object> files, String gameVersion, String loader) {
        Map<String, Object> fallback = Map.of();
        for (Object item : files) {
            Map<String, Object> file = Json.object(item);
            if (!Json.bool(file, "isAvailable", true)) {
                continue;
            }
            if (fallback.isEmpty()) {
                fallback = file;
            }
            if (matchesGameVersion(file, gameVersion) && matchesLoader(file, loader)) {
                return file;
            }
        }
        return fallback;
    }

    private boolean matchesGameVersion(Map<String, Object> file, String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return true;
        }
        for (Object version : Json.list(file, "gameVersions")) {
            if (gameVersion.equalsIgnoreCase(Json.string(version))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLoader(Map<String, Object> file, String loader) {
        if (loader == null || loader.isBlank()) {
            return true;
        }
        String expected = switch (loader.toLowerCase(java.util.Locale.ROOT)) {
            case "forge" -> "forge";
            case "fabric" -> "fabric";
            case "quilt" -> "quilt";
            case "neoforge" -> "neoforge";
            default -> "";
        };
        if (expected.isBlank()) {
            return true;
        }
        for (Object version : Json.list(file, "gameVersions")) {
            String normalized = Json.string(version).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (normalized.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private Integer modLoaderType(String loader) {
        if (loader == null || loader.isBlank()) {
            return null;
        }
        return switch (loader.toLowerCase(java.util.Locale.ROOT)) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> null;
        };
    }

    private Integer classId(String projectType) {
        return switch (projectType) {
            case "modpack" -> 4471;
            case "mod" -> 6;
            default -> null;
        };
    }

    private List<String> galleryUrls(Map<String, Object> mod) {
        ArrayList<String> urls = new ArrayList<>();
        for (Object value : Json.list(mod, "screenshots")) {
            Map<String, Object> screenshot = Json.object(value);
            String url = Json.string(screenshot, "url");
            if (url.isBlank()) {
                url = Json.string(screenshot, "thumbnailUrl");
            }
            if (!url.isBlank() && !urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private String sha1(Map<String, Object> file) {
        for (Object hashItem : Json.list(file, "hashes")) {
            Map<String, Object> hash = Json.object(hashItem);
            if (Json.longValue(hash, "algo", 0) == 1) {
                return Json.string(hash, "value");
            }
        }
        return "";
    }

    private void splitGameVersionsAndLoaders(Map<String, Object> file,
                                             List<String> gameVersions,
                                             List<String> loaders) {
        for (Object versionItem : Json.list(file, "gameVersions")) {
            String value = Json.string(versionItem);
            if (isMinecraftGameVersion(value)) {
                if (!gameVersions.contains(value)) {
                    gameVersions.add(value);
                }
            } else if (isLoaderName(value)) {
                String normalized = normalizedLoaderName(value);
                if (!loaders.contains(normalized)) {
                    loaders.add(normalized);
                }
            }
        }
    }

    private List<String> addonQueries(ModrinthProject root) {
        String title = root.title() == null ? "" : root.title().trim();
        String slug = root.slug() == null ? "" : root.slug().trim();
        String identity = !title.isBlank() ? title : slug;
        if (identity.isBlank()) {
            return List.of();
        }
        return List.of(identity);
    }

    private boolean isMinecraftGameVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String normalized = version.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("\\d+\\.\\d+(?:\\.\\d+)?(?:[-+].*)?")
                || normalized.matches("\\d+w\\d+[a-z]");
    }

    private List<String> dependencyQueries(String modId) {
        ArrayList<String> queries = new ArrayList<>();
        queries.add(modId == null ? "" : modId);
        String normalized = normalizeDependencyName(modId);
        switch (normalized) {
            case "yetanotherconfiglibv3", "yetanotherconfiglib" -> {
                queries.add("YetAnotherConfigLib");
                queries.add("YACL");
            }
            case "geckolib" -> queries.add("GeckoLib");
            case "clothconfig", "clothconfig2" -> queries.add("Cloth Config API");
            case "fabricapi" -> queries.add("Fabric API");
            default -> {
            }
        }
        return queries;
    }

    private String normalizeDependencyName(String value) {
        return value == null ? ""
                : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private DependencyProject dependencyProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return new DependencyProject("", "");
        }
        DependencyProject cached = dependencyProjectCache.get(projectId);
        if (cached != null) {
            return cached;
        }
        DependencyProject project = new DependencyProject(projectId, "");
        try {
            Map<String, Object> root = http.getJsonObjectWithApiKey(API + "/mods/" + encode(projectId), apiKey());
            Map<String, Object> data = Json.object(root, "data");
            String title = Json.string(data, "name");
            String url = Json.string(Json.object(data, "links"), "websiteUrl");
            project = new DependencyProject(title.isBlank() ? projectId : title, url);
        } catch (RuntimeException ignored) {
            // Keep the numeric id visible if CurseForge cannot resolve a dependency page.
        }
        dependencyProjectCache.put(projectId, project);
        return project;
    }

    private boolean isLoaderName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return normalized.equals("forge") || normalized.equals("fabric") || normalized.equals("quilt")
                || normalized.equals("neoforge") || normalized.equals("cauldron") || normalized.equals("liteloader");
    }

    private String normalizedLoaderName(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return switch (normalized) {
            case "neoforge" -> "NeoForge";
            case "liteloader" -> "LiteLoader";
            default -> Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
        };
    }

    private String dependencyRelation(long relationType) {
        return switch ((int) relationType) {
            case 1 -> "embedded";
            case 2 -> "optional";
            case 3 -> "required";
            case 4 -> "tool";
            case 5 -> "incompatible";
            case 6 -> "include";
            default -> "dependency";
        };
    }

    private String releaseType(long releaseType) {
        return switch ((int) releaseType) {
            case 1 -> "release";
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "";
        };
    }

    private record DependencyProject(String title, String url) { }

    private String filterSuffix(String gameVersion, String loader) {
        String version = gameVersion == null ? "" : gameVersion.trim();
        String modLoader = loader == null ? "" : loader.trim();
        if (version.isBlank() && modLoader.isBlank()) {
            return "";
        }
        if (modLoader.isBlank()) {
            return " for Minecraft " + version;
        }
        if (version.isBlank()) {
            return " for " + modLoader;
        }
        return " for Minecraft " + version + " " + modLoader;
    }

    private String apiKey() {
        String key = firstNonBlank(
                System.getenv("CURSEFORGE_API_KEY"),
                System.getenv("CFC_API_KEY"),
                System.getenv("CF_API_KEY"),
                BUNDLED_API_KEY
        );
        return key == null ? "" : key.trim();
    }

    private static String decodeBundledApiKey() {
        StringBuilder key = new StringBuilder();
        for (String part : BUNDLED_API_KEY_PARTS) {
            key.append(new StringBuilder(part).reverse());
        }
        return key.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
