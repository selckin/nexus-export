package be.selckin.nexus.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialResolutionTest {

    @Test
    void flagWinsOverEnv() {
        assertEquals("flagval", Main.resolveRequired("flagval", "envval", "url"));
    }

    @Test
    void fallsBackToEnv() {
        assertEquals("envval", Main.resolveRequired(null, "envval", "url"));
    }

    @Test
    void requiredThrowsWhenBothMissing() {
        assertThrows(IllegalArgumentException.class, () -> Main.resolveRequired(null, null, "url"));
    }

    @Test
    void optionalReturnsNullWhenBothMissing() {
        assertNull(Main.resolveOptional(null, null));
    }
}
