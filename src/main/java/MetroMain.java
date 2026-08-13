/** Entry point used only by the separately packaged Metro edition. */
public final class MetroMain {
    private MetroMain() {
    }

    public static void main(String[] args) {
        System.setProperty(LauncherEdition.EDITION_PROPERTY, "metro");
        Main.launch(args);
    }
}
