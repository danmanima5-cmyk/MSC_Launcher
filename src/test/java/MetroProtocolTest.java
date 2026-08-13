import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MetroProtocolTest {
    @Test
    void acceptsOnlyKnownAppxCompanionCommands() {
        assertEquals("library", MetroProtocol.section("msc-launcher://open/library"));
        assertEquals("mods", MetroProtocol.section("MSC-LAUNCHER://OPEN/mods"));
        assertEquals("main", MetroProtocol.section("msc-launcher://open/unknown"));
        assertEquals("", MetroProtocol.section("https://example.com/open/library"));
        assertEquals("", MetroProtocol.section("not a uri"));
    }
}
