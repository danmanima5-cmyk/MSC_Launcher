import java.util.List;
import java.util.Map;

record ModrinthVersion(String id, String name, String versionNumber, String projectType,
                       List<String> gameVersions, List<String> loaders, List<ModrinthFile> files,
                       Map<String, String> dependencies) {
    ModrinthFile primaryFile() {
        for (ModrinthFile file : files) {
            if (file.primary()) {
                return file;
            }
        }
        if (!files.isEmpty()) {
            return files.get(0);
        }
        throw new LauncherException("У версии Modrinth нет файлов для загрузки.");
    }
}
