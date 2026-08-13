import java.nio.file.Path;
import java.util.List;

/** A dependency declared by the author for one downloadable mod file. */
record ModDependencyLink(String projectId, String versionId, String name, String relation, String url,
                         String declaredModId, String versionConstraint) {
    /** Keeps existing callers source-compatible while allowing Modrinth to retain a pinned version id. */
    ModDependencyLink(String projectId, String name, String relation, String url) {
        this(projectId, "", name, relation, url, "", "");
    }

    /** Keeps the five-argument constructor used by provider metadata parsers. */
    ModDependencyLink(String projectId, String versionId, String name, String relation, String url) {
        this(projectId, versionId, name, relation, url, "", "");
    }

    /** Keeps callers which only know the loader mod id source-compatible. */
    ModDependencyLink(String projectId, String versionId, String name, String relation, String url,
                      String declaredModId) {
        this(projectId, versionId, name, relation, url, declaredModId, "");
    }

    ModDependencyLink {
        projectId = projectId == null ? "" : projectId;
        versionId = versionId == null ? "" : versionId;
        name = name == null || name.isBlank()
                ? (!projectId.isBlank() ? projectId : versionId)
                : name;
        relation = relation == null ? "" : relation;
        url = url == null ? "" : url;
        declaredModId = declaredModId == null ? "" : declaredModId;
        versionConstraint = versionConstraint == null ? "" : versionConstraint.trim();
    }

    /**
     * Only dependencies needed for the mod to run are eligible for automatic installation.
     * Blank and legacy "unknown"/"dependency" relations are treated as required;
     * every other unrecognized relation is left for manual installation.
     */
    boolean requiredForInstall() {
        return switch (relation.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "", "required", "dependency" -> true;
            default -> false;
        };
    }
}

/** A verified local artifact prepared before the dependency confirmation dialog is shown. */
record PreparedModInstall(ModFileRelease release, Path cachedFile) {
    PreparedModInstall {
        if (release == null) {
            throw new IllegalArgumentException("release");
        }
        if (cachedFile == null) {
            throw new IllegalArgumentException("cachedFile");
        }
    }
}

/** Version/file metadata shared by the Modrinth and CurseForge project views. */
record ModFileRelease(String id, String versionName, String versionNumber, String fileName,
                      String releaseType, String publishedAt, List<String> gameVersions,
                      List<String> loaders, String downloadUrl, String sha1, long size,
                      List<ModDependencyLink> dependencies) {
    ModFileRelease {
        id = id == null ? "" : id;
        versionName = versionName == null ? "" : versionName;
        versionNumber = versionNumber == null ? "" : versionNumber;
        fileName = fileName == null ? "" : fileName;
        releaseType = releaseType == null ? "" : releaseType;
        publishedAt = publishedAt == null ? "" : publishedAt;
        gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        downloadUrl = downloadUrl == null ? "" : downloadUrl;
        sha1 = sha1 == null ? "" : sha1;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    List<ModDependencyLink> requiredDependencies() {
        return dependencies.stream().filter(ModDependencyLink::requiredForInstall).toList();
    }

    boolean supportsTarget(String gameVersion, String loader) {
        String targetGameVersion = gameVersion == null ? "" : gameVersion.trim();
        String targetLoader = loader == null ? "" : loader.trim();
        boolean gameMatches = targetGameVersion.isBlank() || gameVersions.isEmpty()
                || gameVersions.stream().anyMatch(targetGameVersion::equalsIgnoreCase);
        boolean loaderMatches = targetLoader.isBlank() || loaders.isEmpty()
                || loaders.stream().anyMatch(targetLoader::equalsIgnoreCase);
        return gameMatches && loaderMatches;
    }
}

record ModFileCatalog(List<ModFileRelease> releases, int totalCount) {
    ModFileCatalog {
        releases = releases == null ? List.of() : List.copyOf(releases);
        totalCount = Math.max(releases.size(), totalCount);
    }
}
