import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A compatible project whose selected release explicitly depends on the main mod. */
record ModAddonCandidate(ModrinthProject project, ModFileRelease release, String relationToRoot) {
    ModAddonCandidate {
        if (project == null) {
            throw new IllegalArgumentException("project");
        }
        if (release == null) {
            throw new IllegalArgumentException("release");
        }
        relationToRoot = relationToRoot == null ? "" : relationToRoot;
    }
}

/** First release in provider order that is usable with the exact selected root release. */
record AddonReleaseMatch(ModFileRelease release, String relation) { }

/** Root artifact plus verified compatible add-ons offered before profile writes begin. */
record PreparedModOffer(PreparedModInstall root, List<ModAddonCandidate> addons) {
    PreparedModOffer {
        if (root == null) {
            throw new IllegalArgumentException("root");
        }
        addons = addons == null ? List.of() : List.copyOf(addons);
    }
}

/** Result of the install-options dialog; add-ons remain independent from root libraries. */
record ModInstallChoices(boolean installRootDependencies, List<ModAddonCandidate> addons) {
    ModInstallChoices {
        addons = addons == null ? List.of() : List.copyOf(addons);
    }
}

/** Dependencies cached and verified before they join the same install transaction as the mod. */
record PreparedDependencyPlan(List<ModInstallEntry> entries, List<PreparedModInstall> files) {
    PreparedDependencyPlan {
        entries = entries == null ? List.of() : List.copyOf(entries);
        files = files == null ? List.of() : List.copyOf(files);
        if (entries.size() != files.size()) {
            throw new IllegalArgumentException("Dependency entries and files must match.");
        }
    }
}

/** One already selected project/file pair in a unified dependency traversal. */
record ModInstallEntry(ModrinthProject project, ModFileRelease release) {
    ModInstallEntry {
        if (project == null) {
            throw new IllegalArgumentException("project");
        }
        if (release == null) {
            throw new IllegalArgumentException("release");
        }
    }
}

/**
 * Immutable install graph. The first entry is the main mod; later entries are user-selected
 * add-ons. Main-mod libraries are optional by user choice, while selected add-on libraries
 * are always traversed so an enabled add-on is runnable.
 */
record ModInstallPlan(List<ModInstallEntry> entries, boolean installRootDependencies) {
    ModInstallPlan {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("An install plan needs a root mod.");
        }
    }

    Set<String> selectedProjectIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ModInstallEntry entry : entries) {
            if (!entry.project().id().isBlank()) {
                ids.add(entry.project().id());
            }
        }
        return Set.copyOf(ids);
    }

    List<ModInstallEntry> dependencyRoots() {
        ArrayList<ModInstallEntry> roots = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0 || installRootDependencies) {
                roots.add(entries.get(index));
            }
        }
        return List.copyOf(roots);
    }

    /** Checks pinned Modrinth versions before any prepared file is copied into the profile. */
    void validatePinnedVersions() {
        LinkedHashMap<String, String> selectedVersions = new LinkedHashMap<>();
        LinkedHashMap<String, String> selectedTitles = new LinkedHashMap<>();
        LinkedHashMap<String, ModInstallEntry> targetFiles = new LinkedHashMap<>();
        for (ModInstallEntry entry : entries) {
            String projectId = entry.project().id();
            String versionId = entry.release().id();
            if (!projectId.isBlank()) {
                selectedTitles.putIfAbsent(projectId, entry.project().title());
            }
            if (!projectId.isBlank() && !versionId.isBlank()) {
                String existing = selectedVersions.putIfAbsent(projectId, versionId);
                if (existing != null && !existing.equals(versionId)) {
                    throw versionConflict(projectId, existing, versionId);
                }
            }
            String fileName = entry.release().fileName().trim().toLowerCase(Locale.ROOT);
            if (!fileName.isBlank()) {
                ModInstallEntry existing = targetFiles.putIfAbsent(fileName, entry);
                if (existing != null && (!existing.release().id().equals(entry.release().id())
                        || !existing.project().id().equals(entry.project().id()))) {
                    throw new LauncherException("Конфликт имени файла " + entry.release().fileName()
                            + " между " + existing.project().title() + " и " + entry.project().title());
                }
            }
        }
        for (ModInstallEntry entry : entries) {
            for (ModDependencyLink dependency : entry.release().dependencies()) {
                if (!"incompatible".equalsIgnoreCase(dependency.relation().trim())
                        || dependency.projectId().isBlank()) {
                    continue;
                }
                String conflictingTitle = selectedTitles.get(dependency.projectId());
                if (conflictingTitle != null) {
                    throw new LauncherException("Несовместимые моды: "
                            + entry.project().title() + " и " + conflictingTitle);
                }
            }
        }
        for (ModInstallEntry entry : dependencyRoots()) {
            for (ModDependencyLink dependency : entry.release().requiredDependencies()) {
                if (dependency.projectId().isBlank() || dependency.versionId().isBlank()) {
                    continue;
                }
                String existing = selectedVersions.putIfAbsent(
                        dependency.projectId(), dependency.versionId());
                if (existing != null && !existing.equals(dependency.versionId())) {
                    throw versionConflict(dependency.name(), existing, dependency.versionId());
                }
            }
        }
    }

    private LauncherException versionConflict(String name, String first, String second) {
        return new LauncherException("Конфликт версий " + name + ": " + first + " и " + second);
    }
}

/** Pure relationship and selection helpers shared by both providers and the UI. */
final class ModAddonSupport {
    private ModAddonSupport() {
    }

    static String relationshipToRoot(String rootProjectId, ModFileRelease candidateRelease) {
        return relationshipToRoot(rootProjectId, "", candidateRelease);
    }

    /**
     * Returns the verified reverse relationship only when an optional pinned root version
     * matches the exact root release selected for installation.
     */
    static String relationshipToRoot(String rootProjectId, String rootVersionId,
                                     ModFileRelease candidateRelease) {
        if (rootProjectId == null || rootProjectId.isBlank() || candidateRelease == null) {
            return "";
        }
        String selectedVersion = rootVersionId == null ? "" : rootVersionId.trim();
        for (ModDependencyLink dependency : candidateRelease.dependencies()) {
            if (rootProjectId.equals(dependency.projectId())
                    && isAddonRelationship(dependency.relation())
                    && (selectedVersion.isBlank() || dependency.versionId().isBlank()
                    || selectedVersion.equals(dependency.versionId()))) {
                return normalizeRelation(dependency.relation());
            }
        }
        return "";
    }

    static boolean isAddonRelationship(String relation) {
        String normalized = normalizeRelation(relation);
        return normalized.equals("required") || normalized.equals("optional");
    }

    static AddonReleaseMatch firstCompatibleRelease(String rootProjectId, String rootVersionId,
                                                     List<ModFileRelease> releases) {
        if (releases == null) {
            return null;
        }
        for (ModFileRelease release : releases) {
            String relation = relationshipToRoot(rootProjectId, rootVersionId, release);
            if (!relation.isBlank()) {
                return new AddonReleaseMatch(release, relation);
            }
        }
        return null;
    }

    static List<ModAddonCandidate> selectAll(List<ModAddonCandidate> candidates) {
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    static List<ModAddonCandidate> clearAll() {
        return List.of();
    }

    private static String normalizeRelation(String relation) {
        String normalized = relation == null ? "" : relation.trim().toLowerCase(Locale.ROOT);
        return normalized;
    }
}
