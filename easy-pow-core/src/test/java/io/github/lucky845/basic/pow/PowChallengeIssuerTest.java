package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowChallengeIssuerTest {

    private final PowConfig config = new PowConfig("test-secret");
    private final PowChallengeIssuer issuer = new PowChallengeIssuer(config);

    @Test
    void rejectsMaxnumberBelow1000() {
        assertThrows(IllegalArgumentException.class, () -> issuer.issue(999, 60_000));
        assertThrows(IllegalArgumentException.class, () -> issuer.issue(0, 60_000));
    }

    @Test
    void issuesWellFormedChallenge() {
        PowChallenge challenge = issuer.issue(5000, 60_000);
        assertEquals("SHA-256", challenge.algorithm());
        assertEquals(5000, challenge.maxnumber());
        assertEquals(32, challenge.salt().length()); // 16 字节熵 = 32 hex 字符
        assertTrue(challenge.expiresAt() > System.currentTimeMillis());
        // 签名可用同一 secret 复算验证
        assertEquals(Hashes.hmacSha256Hex(config.secret(), challenge.challenge()), challenge.signature());
    }
}
