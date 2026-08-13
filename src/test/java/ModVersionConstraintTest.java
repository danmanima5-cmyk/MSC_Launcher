import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModVersionConstraintTest {
    @Test
    void acceptsFabricPredicateAgainstVersionWithBuildMetadata() {
        assertTrue(ModVersionConstraint.matches("0.142.0+1.21.11", ">=0.142.0"));
        assertFalse(ModVersionConstraint.matches("0.141.6+1.21.11", ">=0.142.0"));
    }

    @Test
    void supportsAlternativesAndMavenRangesFromLoaderMetadata() {
        assertTrue(ModVersionConstraint.matches("4.5.0", ">=4.4.0 <5.0.0 || >=6.0.0"));
        assertFalse(ModVersionConstraint.matches("5.0.0", ">=4.4.0 <5.0.0"));
        assertTrue(ModVersionConstraint.matches("3.6.1", "[3.6,)"));
        assertFalse(ModVersionConstraint.matches("3.5.9", "[3.6,)"));
    }
}
