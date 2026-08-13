import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MinecraftLauncherRedactionTest {
    @Test
    void launchLogNeverContainsAccountSecrets() throws Exception {
        MinecraftLauncher launcher = new MinecraftLauncher(null, null);
        Method redact = MinecraftLauncher.class.getDeclaredMethod("redact", List.class);
        redact.setAccessible(true);

        String output = (String) redact.invoke(launcher, List.of(
                "java", "--uuid", "private-uuid", "--accessToken", "private-token",
                "--xuid", "private-xuid", "--clientId=private-client"));

        assertFalse(output.contains("private-"));
        assertTrue(output.contains("--accessToken <redacted>"));
        assertTrue(output.contains("--clientId=<redacted>"));
    }
}
