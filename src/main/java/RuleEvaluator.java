import java.util.List;
import java.util.Map;

final class RuleEvaluator {
    private RuleEvaluator() {
    }

    static boolean isAllowed(Map<String, Object> object) {
        List<Object> rules = Json.list(object, "rules");
        if (rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Object item : rules) {
            Map<String, Object> rule = Json.object(item);
            if (matches(rule)) {
                allowed = "allow".equals(Json.string(rule, "action"));
            }
        }
        return allowed;
    }

    private static boolean matches(Map<String, Object> rule) {
        Map<String, Object> os = Json.object(rule, "os");
        if (!os.isEmpty() && !OsRules.matches(os)) {
            return false;
        }
        Map<String, Object> features = Json.object(rule, "features");
        if (!features.isEmpty()) {
            for (Map.Entry<String, Object> entry : features.entrySet()) {
                boolean required = Boolean.TRUE.equals(entry.getValue());
                boolean actual = featureValue(entry.getKey());
                if (required != actual) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean featureValue(String name) {
        return switch (name) {
            case "is_demo_user", "has_custom_resolution", "has_quick_plays_support", "is_quick_play_singleplayer",
                 "is_quick_play_multiplayer", "is_quick_play_realms" -> false;
            default -> false;
        };
    }
}
