package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PowConfigTest {

    @Test
    void defaults() {
        PowConfig config = new PowConfig("s3cret");
        assertEquals(1_000_000L, config.defaultMaxnumber());
        assertEquals(120_000L, config.defaultTtlMs());
        assertEquals("SHA-256", config.algorithm());
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new PowConfig(null));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig(""));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("   "));
    }

    @Test
    void rejectsUnsupportedAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("s", 1_000_000, 1000, "SHA3-256"));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("s", 1_000_000, 1000, "md5"));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("s", 1_000_000, 1000, null));
    }

    @Test
    void acceptsSupportedAlgorithm() {
        assertEquals("SHA-384", new PowConfig("s", 1_000_000, 1000, "SHA-384").algorithm());
    }

    @Test
    void rejectsBadDefaults() {
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("s", 999, 1000));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("s", 1_000_000, 0));
    }
}
