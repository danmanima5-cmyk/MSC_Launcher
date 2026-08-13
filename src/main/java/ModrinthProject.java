import java.util.ArrayList;
import java.util.List;

record ModSearchPage(List<ModrinthProject> projects, int totalHits) {
    ModSearchPage {
        projects = projects == null ? List.of() : List.copyOf(projects);
        totalHits = Math.max(0, totalHits);
    }
}

record ModrinthProject(String id, String slug, String title, String description, String projectType,
                       String author, long downloads, String iconUrl, List<String> galleryUrls, String body) {
    ModrinthProject {
        galleryUrls = galleryUrls == null ? List.of() : List.copyOf(galleryUrls);
        body = body == null ? "" : body;
    }

    String displayDescription() {
        return body.isBlank() ? description : body;
    }

    List<String> galleryImageUrls() {
        ArrayList<String> urls = new ArrayList<>();
        if (galleryUrls != null) {
            for (String url : galleryUrls) {
                if (url != null && !url.isBlank() && !urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        return urls;
    }

    @Override
    public String toString() {
        String type = "modpack".equals(projectType) ? "Modpack" : "Mod";
        return title + " (" + type + ", " + downloads + " downloads)";
    }
}
