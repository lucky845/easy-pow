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
        // 上界 Long.MAX_VALUE 在 maxnumber+1 时溢出,必须同样拒绝而不是签发负难度挑战
        assertThrows(IllegalArgumentException.class, () -> issuer.issue(Long.MAX_VALUE, 60_000));
    }

    @Test
    void issuesWellFormedChallenge() {
        PowChallenge challenge = issuer.issue(5000, 60_000);
        assertEquals("SHA-256", challenge.algorithm());
        assertEquals(5000, challenge.maxnumber());
        assertEquals(32, challenge.salt().length()); // 16 字节熵 = 32 hex 字符
        assertTrue(challenge.expiresAt() > System.currentTimeMillis());
        // 签名可用同一 secret 复算验证
        assertEquals(Hashes.hmacHex("SHA-256", config.secret(), challenge.challenge()), challenge.signature());
    }

    @Test
    void issuesWithConfiguredAlgorithm() {
        PowConfig sha512 = new PowConfig("test-secret", PowConfig.DEFAULT_MAXNUMBER, PowConfig.DEFAULT_TTL_MS, "SHA-512");
        PowChallenge challenge = new PowChallengeIssuer(sha512).issue(2000, 60_000);
        assertEquals("SHA-512", challenge.algorithm());
        assertEquals(128, challenge.challenge().length());
        assertEquals(Hashes.hmacHex("SHA-512", sha512.secret(), challenge.challenge()), challenge.signature());
    }

    @Test
    void rejectsIssueWhenStorageLimitReached() {
        PowChallengeIssuer limited = new PowChallengeIssuer(config, new InMemoryPowStorage(1));
        limited.issue(5000, 60_000);
        assertThrows(IllegalStateException.class, () -> limited.issue(5000, 60_000));
    }
}
