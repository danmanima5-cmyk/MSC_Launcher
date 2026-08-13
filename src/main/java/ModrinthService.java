import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ModrinthService {
    private static final String API = "https://api.modrinth.com/v2";
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_ADDON_CANDIDATES = 28;
    private static final int MAX_ADDON_RESULTS = 12;

    private final HttpService http = new HttpService();

    List<ModrinthProject> search(String query, String projectType, String gameVersion, String loader) {
        return search(query, projectType, gameVersion, loader, 0, DEFAULT_PAGE_SIZE);
    }

    List<ModrinthProject> search(String query, String projectType, String gameVersion, String loader, int offset, int limit) {
        return searchPage(query, projectType, gameVersion, loader, offset, limit).projects();
    }

    ModSearchPage searchPage(String query, String projectType, String gameVersion, String loader, int offset, int limit) {
        String searchQuery = query == null ? "" : query.trim();
        String type = projectType == null ? "" : projectType.trim();
        String version = gameVersion == null ? "" : gameVersion.trim();
        String modLoader = loader == null ? "" : loader.trim();
        ArrayList<String> facets = new ArrayList<>();
        if (!type.isBlank()) {
            facets.add("[\"project_type:" + type + "\"]");
        }
        if (!version.isBlank()) {
            facets.add("[\"versions:" + version + "\"]");
        }
        if (!modLoader.isBlank() && !"modpack".equals(type)) {
            facets.add("[\"categories:" + modLoader + "\"]");
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, limit);
        String index = searchQuery.isBlank() ? "downloads" : "relevance";
        StringBuilder url = new StringBuilder(API + "/search?limit=" + safeLimit + "&offset=" + safeOffset + "&index=" + index);
        if (!searchQuery.isBlank()) {
            url.append("&query=").append(encode(searchQuery));
        }
        if (!facets.isEmpty()) {
            url.append("&facets=").append(encode("[" + String.join(",", facets) + "]"));
        }

        Map<String, Object> root = http.getJsonObject(url.toString());
        ArrayList<ModrinthProject> projects = new ArrayList<>();
        for (Object item : Json.list(root, "hits")) {
            Map<String, Object> hit = Json.object(item);
            projects.add(new ModrinthProject(
                    Json.string(hit, "project_id"),
                    Json.string(hit, "slug"),
                    Json.string(hit, "title"),
                    Json.string(hit, "description"),
                    Json.string(hit, "project_type"),
                    Json.string(hit, "author"),
                    Json.longValue(hit, "downloads", 0),
                    Json.string(hit, "icon_url"),
                    parseGalleryUrls(hit),
                    ""
            ));
        }
        return new ModSearchPage(projects, (int) Json.longValue(root, "total_hits", projects.size()));
    }

    ModrinthProject projectDetails(ModrinthProject project) {
        Map<String, Object> json = http.getJsonObject(API + "/project/" + encode(pathId(project)));
        String body = Json.string(json, "body");
        return new ModrinthProject(
                fallback(Json.string(json, "id"), project.id()),
                fallback(Json.string(json, "slug"), project.slug()),
                fallback(Json.string(json, "title"), project.title()),
                fallback(Json.string(json, "description"), project.description()),
                fallback(Json.string(json, "project_type"), project.projectType()),
                project.author(),
                Json.longValue(json, "downloads", project.downloads()),
                fallback(Json.string(json, "icon_url"), project.iconUrl()),
                parseGalleryUrls(json),
                body
        );
    }

    ModrinthVersion latestVersion(ModrinthProject project, String gameVersion, String loader) {
        return latestVersion(pathId(project), project.projectType(), gameVersion, loader);
    }

    ModrinthVersion latestVersion(String projectId, String projectType, String gameVersion, String loader) {
        return parseVersion(latestVersionJson(projectId, projectType, gameVersion, loader), projectType);
    }

    /** Exact install candidate, including the dependency metadata attached to that version. */
    ModFileRelease latestInstallRelease(ModrinthProject project, String gameVersion, String loader) {
        return requireInstallable(installRelease(
                latestVersionJson(pathId(project), project.projectType(), gameVersion, loader)));
    }

    /** Same as {@link #latestInstallRelease(ModrinthProject, String, String)} for dependency project ids. */
    ModFileRelease latestInstallRelease(String projectId, String gameVersion, String loader) {
        return requireInstallable(installRelease(latestVersionJson(projectId, "mod", gameVersion, loader)));
    }

    /** Resolves a declared dependency, honoring a Modrinth-pinned version id when one is present. */
    ModFileRelease dependencyInstallRelease(ModDependencyLink dependency, String gameVersion, String loader) {
        if (dependency == null) {
            throw new LauncherException("Пустая зависимость Modrinth.");
        }
        if (!dependency.versionId().isBlank()) {
            Map<String, Object> version = http.getJsonObject(API + "/version/" + encode(dependency.versionId()));
            if (Json.string(version, "id").isBlank()) {
                throw new LauncherException("Версия зависимости Modrinth не найдена: " + dependency.versionId());
            }
            ModFileRelease release = requireInstallable(installRelease(version));
            if (!release.supportsTarget(gameVersion, loader)) {
                throw new LauncherException("Закреплённая версия зависимости " + dependency.name()
                        + " несовместима с Minecraft " + gameVersion + " / " + loader + ".");
            }
            return release;
        }
        if (!dependency.projectId().isBlank()) {
            List<Object> versions = versionJsons(dependency.projectId(), "mod", gameVersion, loader);
            for (Object item : versions) {
                Map<String, Object> candidate = Json.object(item);
                if (ModVersionConstraint.matches(Json.string(candidate, "version_number"),
                        dependency.versionConstraint())) {
                    return requireInstallable(installRelease(candidate));
                }
            }
            throw new LauncherException("Нет версии зависимости " + dependency.name()
                    + " для Minecraft " + gameVersion + " / " + loader
                    + ", удовлетворяющей требованию " + dependency.versionConstraint() + ".");
        }
        throw new LauncherException("У зависимости Modrinth нет project_id или version_id: " + dependency.name());
    }

    private Map<String, Object> latestVersionJson(String projectId, String projectType,
                                                   String gameVersion, String loader) {
        List<Object> versions = versionJsons(projectId, projectType, gameVersion, loader);
        if (versions.isEmpty()) {
            throw new LauncherException("На Modrinth нет подходящей версии для проекта " + projectId + ".");
        }
        return Json.object(versions.get(0));
    }

    private List<Object> versionJsons(String projectId, String projectType,
                                      String gameVersion, String loader) {
        String safeGameVersion = gameVersion == null ? "" : gameVersion.trim();
        String safeLoader = loader == null ? "" : loader.trim();
        String safeProjectType = projectType == null ? "mod" : projectType.trim();
        StringBuilder url = new StringBuilder(API + "/project/" + encode(projectId) + "/version");
        ArrayList<String> params = new ArrayList<>();
        if (!safeGameVersion.isBlank()) {
            params.add("game_versions=" + encode("[\"" + safeGameVersion + "\"]"));
        }
        if (!safeLoader.isBlank() && !"modpack".equals(safeProjectType)) {
            params.add("loaders=" + encode("[\"" + safeLoader + "\"]"));
        }
        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }
        return Json.list(http.getJson(url.toString()));
    }

    ModFileCatalog projectFiles(ModrinthProject project) {
        String url = API + "/project/" + encode(pathId(project)) + "/version?include_changelog=false";
        List<Object> rawVersions = Json.list(http.getJson(url));

        HashSet<String> dependencyIds = new HashSet<>();
        for (Object item : rawVersions) {
            for (Object dependencyItem : Json.list(Json.object(item), "dependencies")) {
                String projectId = Json.string(Json.object(dependencyItem), "project_id");
                if (!projectId.isBlank()) {
                    dependencyIds.add(projectId);
                }
            }
        }
        Map<String, DependencyProject> dependencyProjects = dependencyProjects(dependencyIds);

        ArrayList<ModFileRelease> releases = new ArrayList<>();
        for (Object item : rawVersions) {
            releases.add(installRelease(Json.object(item), dependencyProjects));
        }
        return new ModFileCatalog(releases, releases.size());
    }

    private ModFileRelease installRelease(Map<String, Object> version) {
        HashSet<String> dependencyIds = new HashSet<>();
        for (Object dependencyItem : Json.list(version, "dependencies")) {
            String projectId = Json.string(Json.object(dependencyItem), "project_id");
            if (!projectId.isBlank()) {
                dependencyIds.add(projectId);
            }
        }
        return installRelease(version, dependencyProjects(dependencyIds));
    }

    private ModFileRelease installRelease(Map<String, Object> version,
                                          Map<String, DependencyProject> dependencyProjects) {
        Map<String, Object> selectedFile = Map.of();
        for (Object fileItem : Json.list(version, "files")) {
            Map<String, Object> file = Json.object(fileItem);
            if (selectedFile.isEmpty() || Json.bool(file, "primary", false)) {
                selectedFile = file;
            }
            if (Json.bool(file, "primary", false)) {
                break;
            }
        }

        ArrayList<ModDependencyLink> dependencies = new ArrayList<>();
        for (Object dependencyItem : Json.list(version, "dependencies")) {
            Map<String, Object> dependency = Json.object(dependencyItem);
            String projectId = Json.string(dependency, "project_id");
            String versionId = Json.string(dependency, "version_id");
            String fileDependency = Json.string(dependency, "file_name");
            DependencyProject dependencyProject = dependencyProjects.get(projectId);
            String name = dependencyProject == null ? projectId : dependencyProject.title();
            String dependencyUrl = dependencyProject == null ? "" : dependencyProject.url();
            if (name.isBlank()) {
                name = !fileDependency.isBlank() ? fileDependency : versionId;
            }
            if (!name.isBlank()) {
                dependencies.add(new ModDependencyLink(projectId, versionId, name,
                        Json.string(dependency, "dependency_type"), dependencyUrl));
            }
        }

        return new ModFileRelease(
                Json.string(version, "id"),
                Json.string(version, "name"),
                Json.string(version, "version_number"),
                Json.string(selectedFile, "filename"),
                Json.string(version, "version_type"),
                Json.string(version, "date_published"),
                stringList(version, "game_versions"),
                stringList(version, "loaders"),
                Json.string(selectedFile, "url"),
                Json.string(Json.object(selectedFile, "hashes"), "sha1"),
                Json.longValue(selectedFile, "size", -1),
                dependencies
        );
    }

    private ModFileRelease requireInstallable(ModFileRelease release) {
        if (release.fileName().isBlank() || release.downloadUrl().isBlank()) {
            throw new LauncherException("У версии Modrinth нет файла для загрузки: "
                    + (!release.versionNumber().isBlank() ? release.versionNumber() : release.id()));
        }
        return release;
    }

    List<String> availableGameVersions(ModrinthProject project, String loader) {
        StringBuilder url = new StringBuilder(API + "/project/" + encode(pathId(project)) + "/version");
        String modLoader = loader == null ? "" : loader.trim();
        if (!modLoader.isBlank() && !"modpack".equals(project.projectType())) {
            url.append("?loaders=").append(encode("[\"" + modLoader + "\"]"));
        }
        ArrayList<String> versions = new ArrayList<>();
        for (Object item : Json.list(http.getJson(url.toString()))) {
            Map<String, Object> version = Json.object(item);
            for (Object value : Json.list(version, "game_versions")) {
                String gameVersion = Json.string(value);
                if (!gameVersion.isBlank() && !versions.contains(gameVersion)) {
                    versions.add(gameVersion);
                }
            }
        }
        versions.sort(VersionSort.latestFirst());
        return versions;
    }

    /** Builds the exact Minecraft-version -> loader matrix for the install picker. */
    ModInstallCompatibility installCompatibility(ModrinthProject project) {
        String url = API + "/project/" + encode(pathId(project))
                + "/version?include_changelog=false";
        ModInstallCompatibility.Builder builder = ModInstallCompatibility.builder();
        for (Object item : Json.list(http.getJson(url))) {
            Map<String, Object> version = Json.object(item);
            builder.addRelease(stringList(version, "game_versions"), stringList(version, "loaders"));
        }
        return builder.build();
    }

    /**
     * Finds reverse dependencies for the selected compatible release. Search text only creates
     * candidates; a project is offered as an add-on only when its release explicitly points back
     * to the root project id.
     */
    List<ModAddonCandidate> discoverAddons(ModrinthProject root, String gameVersion, String loader) {
        return discoverAddons(root, "", gameVersion, loader);
    }

    List<ModAddonCandidate> discoverAddons(ModrinthProject root, String rootVersionId,
                                           String gameVersion, String loader) {
        if (root == null || root.id().isBlank() || gameVersion == null || gameVersion.isBlank()
                || loader == null || loader.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, ModrinthProject> candidates = new LinkedHashMap<>();
        for (String query : addonQueries(root)) {
            for (ModrinthProject project : search(query, "mod", gameVersion, loader,
                    0, MAX_ADDON_CANDIDATES)) {
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
        ArrayList<ModrinthProject> orderedCandidates = new ArrayList<>(candidates.values());
        orderedCandidates.sort(java.util.Comparator.comparingLong(ModrinthProject::downloads)
                .reversed().thenComparing(ModrinthProject::title,
                        String.CASE_INSENSITIVE_ORDER));
        for (ModrinthProject candidate : orderedCandidates) {
            if (addons.size() >= MAX_ADDON_RESULTS) {
                break;
            }
            try {
                ArrayList<ModFileRelease> compatibleReleases = new ArrayList<>();
                for (Object item : versionJsons(pathId(candidate), "mod", gameVersion, loader)) {
                    try {
                        compatibleReleases.add(requireInstallable(
                                installRelease(Json.object(item), Map.of())));
                    } catch (RuntimeException ignored) {
                        // Keep scanning other releases of this candidate.
                    }
                }
                AddonReleaseMatch match = ModAddonSupport.firstCompatibleRelease(
                        root.id(), rootVersionId, compatibleReleases);
                if (match != null) {
                    addons.add(new ModAddonCandidate(
                            candidate, match.release(), match.relation()));
                }
            } catch (RuntimeException ignored) {
                // One stale search hit must not hide other verified add-ons.
            }
        }
        return List.copyOf(addons);
    }

    ModrinthFile fileBySha1(String sha1) {
        return versionBySha1(sha1).version().primaryFile();
    }

    ModrinthVersionLookup versionBySha1(String sha1) {
        if (sha1 == null || sha1.isBlank()) {
            throw new LauncherException("Пустой SHA-1 для файла Modrinth.");
        }
        Map<String, Object> version = http.getJsonObject(API + "/version_file/" + encode(sha1) + "?algorithm=sha1");
        String projectId = Json.string(version, "project_id");
        if (projectId.isBlank()) {
            throw new LauncherException("Файл Modrinth по SHA-1 не найден: " + sha1);
        }
        return new ModrinthVersionLookup(projectId, parseVersion(version, "mod"));
    }

    ModrinthProject findDependencyProject(String modId, String gameVersion, String loader) {
        List<ModrinthProject> projects = dependencyCandidates(modId, gameVersion, loader);
        if (projects.isEmpty()) {
            throw new LauncherException("Не удалось найти обязательную зависимость Modrinth: " + modId);
        }
        ModrinthProject selected = selectDependencyProject(projects, dependencyQueries(modId), loader);
        if (selected == null) {
            throw new LauncherException("Modrinth вернул неоднозначные результаты для обязательной зависимости "
                    + modId + ". Автоматическая установка отменена, чтобы не скачать другой мод.");
        }
        return selected;
    }

    /**
     * Selects only a strong identity match for a loader mod id. Search relevance alone is not
     * enough here: for example, a search for the id "create" may put Create: Steam 'n' Rails
     * ahead of the actual Create Fabric provider.
     */
    static ModrinthProject selectDependencyProject(List<ModrinthProject> projects,
                                                    List<String> aliases, String loader) {
        ModrinthProject selected = null;
        int selectedScore = 0;
        for (ModrinthProject project : projects == null ? List.<ModrinthProject>of() : projects) {
            int score = Math.max(
                    dependencyProjectScore(project.slug(), aliases, loader),
                    dependencyProjectScore(project.title(), aliases, loader));
            if (score > selectedScore
                    || (score == selectedScore && score > 0 && selected != null
                    && project.downloads() > selected.downloads())) {
                selected = project;
                selectedScore = score;
            }
        }
        return selectedScore == 0 ? null : selected;
    }

    private static int dependencyProjectScore(String candidate, List<String> aliases, String loader) {
        String normalizedCandidate = normalizeDependencyIdentity(candidate);
        String normalizedLoader = normalizeDependencyIdentity(loader);
        if (normalizedCandidate.isBlank()) {
            return 0;
        }
        for (String alias : aliases == null ? List.<String>of() : aliases) {
            String normalizedAlias = normalizeDependencyIdentity(alias);
            if (normalizedAlias.isBlank()) {
                continue;
            }
            if (normalizedCandidate.equals(normalizedAlias)) {
                return 100;
            }
            if (!normalizedLoader.isBlank()
                    && (normalizedCandidate.equals(normalizedAlias + normalizedLoader)
                    || normalizedCandidate.equals(normalizedLoader + normalizedAlias))) {
                return 95;
            }
            for (String suffix : List.of("api", "lib", "library", "loader", "mod")) {
                if (normalizedCandidate.equals(normalizedAlias + suffix)) {
                    return 90;
                }
            }
        }
        return 0;
    }

    private static String normalizeDependencyIdentity(String value) {
        return value == null ? ""
                : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Resolves a loader mod id discovered inside a JAR to an installable Modrinth project. */
    ModDependencyLink resolveManifestDependency(String modId, String versionConstraint,
                                                String gameVersion, String loader) {
        ModrinthProject project = findDependencyProject(modId, gameVersion, loader);
        String slug = project.slug().isBlank() ? project.id() : project.slug();
        String url = slug.isBlank() ? "" : "https://modrinth.com/mod/" + encode(slug);
        return new ModDependencyLink(project.id(), "", project.title(), "required", url, modId,
                versionConstraint);
    }

    List<ModrinthProject> dependencyCandidates(String modId, String gameVersion, String loader) {
        ArrayList<ModrinthProject> output = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String query : dependencyQueries(modId)) {
            for (ModrinthProject project : search(query, "mod", gameVersion, loader)) {
                if (seen.add(project.id())) {
                    output.add(project);
                }
            }
        }
        return output;
    }

    private ModrinthVersion parseVersion(Map<String, Object> json, String projectType) {
        ArrayList<String> gameVersions = new ArrayList<>();
        for (Object value : Json.list(json, "game_versions")) {
            gameVersions.add(Json.string(value));
        }
        ArrayList<String> loaders = new ArrayList<>();
        for (Object value : Json.list(json, "loaders")) {
            loaders.add(Json.string(value));
        }
        ArrayList<ModrinthFile> files = new ArrayList<>();
        for (Object value : Json.list(json, "files")) {
            Map<String, Object> file = Json.object(value);
            Map<String, Object> hashes = Json.object(file, "hashes");
            files.add(new ModrinthFile(
                    Json.string(file, "filename"),
                    Json.string(file, "url"),
                    Json.string(hashes, "sha1"),
                    Json.longValue(file, "size", -1),
                    Json.bool(file, "primary", false)
            ));
        }
        return new ModrinthVersion(
                Json.string(json, "id"),
                Json.string(json, "name"),
                Json.string(json, "version_number"),
                projectType,
                gameVersions,
                loaders,
                files,
                Map.of()
        );
    }

    private Map<String, DependencyProject> dependencyProjects(HashSet<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        ArrayList<String> projectIds = new ArrayList<>(ids);
        LinkedHashMap<String, DependencyProject> projects = new LinkedHashMap<>();
        for (int from = 0; from < projectIds.size(); from += 50) {
            List<String> batch = projectIds.subList(from, Math.min(from + 50, projectIds.size()));
            try {
                String url = API + "/projects?ids=" + encode(Json.stringify(batch));
                for (Object item : Json.list(http.getJson(url))) {
                    Map<String, Object> project = Json.object(item);
                    String id = Json.string(project, "id");
                    String slug = Json.string(project, "slug");
                    String type = webProjectType(Json.string(project, "project_type"));
                    String pageUrl = slug.isBlank() ? "" : "https://modrinth.com/" + type + "/" + encode(slug);
                    projects.put(id, new DependencyProject(Json.string(project, "title"), pageUrl));
                }
            } catch (RuntimeException ignored) {
                // File metadata is still useful if dependency title resolution is temporarily unavailable.
            }
        }
        return projects;
    }

    private ArrayList<String> stringList(Map<String, Object> json, String key) {
        ArrayList<String> values = new ArrayList<>();
        for (Object item : Json.list(json, key)) {
            String value = Json.string(item);
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String webProjectType(String type) {
        return switch (type) {
            case "modpack" -> "modpack";
            case "resourcepack" -> "resourcepack";
            case "shader" -> "shader";
            default -> "mod";
        };
    }

    private record DependencyProject(String title, String url) { }

    HttpService http() {
        return http;
    }

    private List<String> parseGalleryUrls(Map<String, Object> json) {
        ArrayList<String> urls = new ArrayList<>();
        for (Object value : Json.list(json, "gallery")) {
            String url;
            if (value instanceof Map<?, ?>) {
                Map<String, Object> item = Json.object(value);
                url = Json.string(item, "url");
                if (url.isBlank()) {
                    url = Json.string(item, "raw_url");
                }
            } else {
                url = Json.string(value);
            }
            if (!url.isBlank() && !urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private String pathId(ModrinthProject project) {
        return project.slug().isBlank() ? project.id() : project.slug();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private List<String> dependencyQueries(String modId) {
        String normalized = normalize(modId);
        ArrayList<String> queries = new ArrayList<>();
        queries.add(modId);
        switch (normalized) {
            case "biomesoplenty" -> queries.add("Biomes O Plenty");
            case "voicechat" -> queries.add("Simple Voice Chat");
            case "geckolib" -> queries.add("GeckoLib");
            case "yetanotherconfiglibv3", "yetanotherconfiglib" -> {
                queries.add("YetAnotherConfigLib");
                queries.add("YACL");
            }
            case "clothconfig", "clothconfig2" -> queries.add("Cloth Config API");
            case "fabricapi" -> queries.add("Fabric API");
            default -> {
            }
        }
        return queries;
    }

    private List<String> addonQueries(ModrinthProject root) {
        String title = root.title() == null ? "" : root.title().trim();
        String slug = root.slug() == null ? "" : root.slug().trim();
        ArrayList<String> queries = new ArrayList<>();
        String identity = !title.isBlank() ? title : slug;
        if (!identity.isBlank()) {
            queries.add(identity);
        }
        return queries;
    }
}
