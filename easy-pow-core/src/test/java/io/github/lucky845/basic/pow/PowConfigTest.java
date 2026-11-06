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
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new PowConfig(null));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig(""));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("   "));
    }

    @Test
    void rejectsBadDefaults() {
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("s", 999, 1000));
        assertThrows(IllegalArgumentException.class, () -> new PowConfig("s", 1_000_000, 0));
    }
}
