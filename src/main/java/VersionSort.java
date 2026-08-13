import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VersionSort {
    private static final Pattern FORGE_PROFILE = Pattern.compile("^(.+?)-forge-.+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_FORGE_PROFILE = Pattern.compile("^(.+?)-forge\\d.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORGE_MAVEN_PROFILE = Pattern.compile("^forge-([0-9][A-Za-z0-9_.-]*)-.+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEOFORGE_GAME_PROFILE = Pattern.compile("^(.+?)-neoforge-.+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEOFORGE_PROFILE = Pattern.compile("^neoforge-(\\d+)\\.(\\d+)(?:\\..*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FABRIC_PROFILE = Pattern.compile("^fabric-loader-[^-]+-(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUILT_PROFILE = Pattern.compile("^quilt-loader-[^-]+-(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTIFINE_PROFILE = Pattern.compile("^OptiFine_(.+?)_[A-Z].+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTIFINE_DASH_PROFILE = Pattern.compile("^(.+?)-OptiFine_[A-Z].+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("\\d+|\\D+");

    private VersionSort() {
    }

    static Comparator<String> latestFirst() {
        return (left, right) -> compareVersions(right, left);
    }

    static int compareVersions(String left, String right) {
        List<String> leftTokens = tokens(left);
        List<String> rightTokens = tokens(right);
        int size = Math.max(leftTokens.size(), rightTokens.size());
        for (int i = 0; i < size; i++) {
            if (i >= leftTokens.size()) {
                return -1;
            }
            if (i >= rightTokens.size()) {
                return 1;
            }
            String a = leftTokens.get(i);
            String b = rightTokens.get(i);
            boolean aNumber = isNumber(a);
            boolean bNumber = isNumber(b);
            int result;
            if (aNumber && bNumber) {
                result = compareNumberTokens(a, b);
            } else if (aNumber) {
                result = 1;
            } else if (bNumber) {
                result = -1;
            } else {
                result = a.compareToIgnoreCase(b);
            }
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    static String baseGameVersion(String profileId) {
        Matcher fabric = FABRIC_PROFILE.matcher(profileId);
        if (fabric.matches()) {
            return fabric.group(1);
        }
        Matcher quilt = QUILT_PROFILE.matcher(profileId);
        if (quilt.matches()) {
            return quilt.group(1);
        }
        Matcher neoForgeGame = NEOFORGE_GAME_PROFILE.matcher(profileId);
        if (neoForgeGame.matches()) {
            return neoForgeGame.group(1);
        }
        Matcher neoForge = NEOFORGE_PROFILE.matcher(profileId);
        if (neoForge.matches()) {
            String major = neoForge.group(1);
            String minor = neoForge.group(2);
            return "0".equals(minor) ? "1." + major : "1." + major + "." + minor;
        }
        Matcher forge = FORGE_PROFILE.matcher(profileId);
        if (forge.matches()) {
            return forge.group(1);
        }
        Matcher legacyForge = LEGACY_FORGE_PROFILE.matcher(profileId);
        if (legacyForge.matches()) {
            return legacyForge.group(1);
        }
        Matcher forgeMaven = FORGE_MAVEN_PROFILE.matcher(profileId);
        if (forgeMaven.matches()) {
            return forgeMaven.group(1);
        }
        Matcher optiFine = OPTIFINE_PROFILE.matcher(profileId);
        if (optiFine.matches()) {
            return optiFine.group(1);
        }
        Matcher optiFineDash = OPTIFINE_DASH_PROFILE.matcher(profileId);
        if (optiFineDash.matches()) {
            return optiFineDash.group(1);
        }
        return profileId;
    }

    static int profileKindWeight(String profileId) {
        String lower = profileId.toLowerCase(Locale.ROOT);
        if (lower.contains("forge") && lower.contains("optifine")) {
            return 3;
        }
        if (lower.contains("neoforge")) {
            return 1;
        }
        if (lower.contains("forge")) {
            return 1;
        }
        if (lower.contains("fabric")) {
            return 2;
        }
        if (lower.contains("quilt")) {
            return 2;
        }
        if (lower.contains("optifine")) {
            return 3;
        }
        return 0;
    }

    private static List<String> tokens(String value) {
        Matcher matcher = TOKEN.matcher(value == null ? "" : value);
        ArrayList<String> output = new ArrayList<>();
        while (matcher.find()) {
            output.add(matcher.group());
        }
        return output;
    }

    private static boolean isNumber(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return !token.isEmpty();
    }

    private static int compareNumberTokens(String left, String right) {
        String a = stripLeadingZeroes(left);
        String b = stripLeadingZeroes(right);
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index + 1 < value.length() && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }
}
