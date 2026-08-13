import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class LauncherAssets {
    static final String DIRECTORY_NAME = "msc-launcher";
    private static final boolean DEBUG = false; // turn on to print resource lookup diagnostics

    private LauncherAssets() {
    }

    static Path writableDirectory(Path gameDirectory) {
        return gameDirectory.resolve(DIRECTORY_NAME);
    }

    static void ensureWritableDirectory(Path gameDirectory) throws IOException {
        Files.createDirectories(writableDirectory(gameDirectory));
    }

    static BufferedImage readImage(Path gameDirectory, String... fileNames) {
        List<Path> directories = assetDirectories(gameDirectory);
        if (DEBUG) {
            System.out.println("[LauncherAssets] Checking filesystem asset directories:");
            for (Path d : directories) System.out.println("  -> " + d);
        }
        for (Path directory : directories) {
            for (String fileName : fileNames) {
                if (fileName == null || fileName.isBlank()) {
                    continue;
                }
                Path file = directory.resolve(fileName);
                if (DEBUG) System.out.println("[LauncherAssets] Trying file: " + file);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    BufferedImage image = ImageIO.read(file.toFile());
                    if (image != null) {
                        if (DEBUG) System.out.println("[LauncherAssets] Loaded image from file: " + file);
                        return image;
                    }
                } catch (IOException ignored) {
                    // Try the next candidate.
                }
            }
        }
        // If no filesystem match was found, attempt to load bundled resources from the classpath / inside the JAR.
        // Try a few common prefixes that may be used when packaging resources into the artifact.
        ClassLoader cl = LauncherAssets.class.getClassLoader();
        String[] prefixes = new String[] {"Resources/", "", "Resources/set/", "Resources/nav/", "Resources/pltika/", "Resources/Authentification/", "Resources/github/", "Resources/modern/", "Resources/Images/", "Resources/Launcher-icon/", "Images/", "resources/", "images/"};
        if (DEBUG) System.out.println("[LauncherAssets] Checking classpath resources (prefixes): " + String.join(",", prefixes));
        for (String prefix : prefixes) {
            for (String fileName : fileNames) {
                if (fileName == null || fileName.isBlank()) continue;
                String resourcePath = prefix + fileName;
                if (DEBUG) System.out.println("[LauncherAssets] Trying resource: " + resourcePath);
                try (InputStream is = cl.getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        if (DEBUG) System.out.println("[LauncherAssets] Resource not found: " + resourcePath);
                        continue;
                    }
                    BufferedImage image = ImageIO.read(is);
                    if (image != null) {
                        if (DEBUG) System.out.println("[LauncherAssets] Loaded image from resource: " + resourcePath);
                        return image;
                    }
                } catch (IOException ignored) {
                    // try next candidate
                }
            }
        }

        // Final fallback: scan the running JAR (if any) for matching entries (case-insensitive)
        try {
            var loc = LauncherAssets.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codePath = Path.of(loc);
            if (Files.isRegularFile(codePath)) {
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(codePath.toFile())) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = entries.nextElement();
                        String name = ze.getName();
                        for (String fileName : fileNames) {
                            if (fileName == null || fileName.isBlank()) continue;
                            if (name.equalsIgnoreCase(fileName) || name.toLowerCase(java.util.Locale.ROOT).endsWith("/" + fileName.toLowerCase(java.util.Locale.ROOT))) {
                                try (InputStream is = zf.getInputStream(ze)) {
                                    BufferedImage img = ImageIO.read(is);
                                    if (img != null) {
                                        if (DEBUG) System.out.println("[LauncherAssets] Loaded image by scanning JAR entry: " + name);
                                        return img;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }

        return null;
    }

    static ImageIcon readScaledIcon(Path gameDirectory, int width, int height, String... fileNames) {
        BufferedImage image = readImage(gameDirectory, fileNames);
        if (image == null) {
            return null;
        }
        Image scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    private static List<Path> assetDirectories(Path gameDirectory) {
        ArrayList<Path> directories = new ArrayList<>();
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve(DIRECTORY_NAME));
        Path projectResources = Path.of(System.getProperty("user.dir")).resolve("src").resolve("main").resolve("resources").resolve("Resources");
        addDirectory(directories, projectResources);
        addDirectory(directories, projectResources.resolve("set"));
        addDirectory(directories, projectResources.resolve("nav"));
        addDirectory(directories, projectResources.resolve("pltika"));
        addDirectory(directories, projectResources.resolve("Authentification"));
        addDirectory(directories, projectResources.resolve("github"));
        addDirectory(directories, projectResources.resolve("modern"));

        // Also check project-level/external Resources folders for development overrides.
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("Resources"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("Resources").resolve("set"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("Resources").resolve("nav"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("Resources").resolve("pltika"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("Resources").resolve("Authentification"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("Resources").resolve("github"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("Resources").resolve("modern"));

        // Keep the old layout as a fallback for local runs from older checkouts.
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("src").resolve("Resources"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("src").resolve("Resources").resolve("set"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("src").resolve("Resources").resolve("nav"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("src").resolve("Resources").resolve("pltika"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("src").resolve("Resources").resolve("Authentification"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("src").resolve("Resources").resolve("github"));
        addDirectory(directories, Path.of(System.getProperty("user.dir")).resolve("src").resolve("Resources").resolve("modern"));
        // Also check the directory containing the running JAR (or classes) so resources placed next to the JAR are found
        try {
            var codeLocation = LauncherAssets.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codePath = Path.of(codeLocation);
            Path parent = codePath.getParent();
            if (parent != null) {
                addDirectory(directories, parent);
                addDirectory(directories, parent.resolve("Resources"));
                addDirectory(directories, parent.resolve("Resources").resolve("set"));
                addDirectory(directories, parent.resolve("Resources").resolve("nav"));
                addDirectory(directories, parent.resolve("Resources").resolve("pltika"));
                addDirectory(directories, parent.resolve("Resources").resolve("Authentification"));
                addDirectory(directories, parent.resolve("Resources").resolve("github"));
                addDirectory(directories, parent.resolve("Resources").resolve("Launcher-icon"));
                addDirectory(directories, parent.resolve("Resources").resolve("Images"));
                addDirectory(directories, parent.resolve("Resources").resolve("modern"));
            }
        } catch (Exception ignored) {
            // best-effort: if we can't determine code location, continue with other directories
        }
        if (gameDirectory != null) {
            addDirectory(directories, gameDirectory.resolve(DIRECTORY_NAME));
        }

        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            addDirectory(directories, Path.of(appdata).resolve(".minecraft").resolve(DIRECTORY_NAME));
        }

        addDirectory(directories, LauncherSettings.settingsDirectory().resolve(DIRECTORY_NAME));
        return directories;
    }

    private static void addDirectory(List<Path> directories, Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!directories.contains(normalized)) {
            directories.add(normalized);
        }
    }
}
