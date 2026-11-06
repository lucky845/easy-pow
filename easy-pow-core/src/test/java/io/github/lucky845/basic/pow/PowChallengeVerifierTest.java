package io.github.lucky845.basic.pow;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PowChallengeVerifierTest {

    private static final String SECRET = "unit-test-secret";

    private final PowStorage storage = new InMemoryPowStorage();
    private final PowChallengeIssuer issuer = new PowChallengeIssuer(config(), storage);
    private final PowChallengeVerifier verifier = new PowChallengeVerifier(config(), storage);

    private static PowConfig config() {
        return new PowConfig(SECRET);
    }

    /** 暴力解出 number(测试里 maxnumber 很小,毫秒级)。 */
    private static long solve(PowChallenge challenge) {
        for (long n = 0; n <= challenge.maxnumber(); n++) {
            if (Hashes.sha256Hex(challenge.salt() + n).equals(challenge.challenge())) {
                return n;
            }
        }
        throw new AssertionError("no solution within maxnumber");
    }

    private static String solutionJson(PowChallenge challenge, long number) {
        return "{\"algorithm\":\"" + challenge.algorithm() + "\",\"challenge\":\"" + challenge.challenge()
                + "\",\"number\":" + number + ",\"salt\":\"" + challenge.salt()
                + "\",\"signature\":\"" + challenge.signature() + "\",\"took\":42}";
    }

    private static String flipLastChar(String hex) {
        char last = hex.charAt(hex.length() - 1);
        return hex.substring(0, hex.length() - 1) + (last == '0' ? '1' : '0');
    }

    @Test
    void happyPath() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        PowResult result = verifier.verify(solutionJson(challenge, solve(challenge)));
        assertEquals(PowStatus.PASSED, result.status());
        assertEquals(challenge.challenge(), result.challenge());
    }

    @Test
    void happyPathWithBase64Payload() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        String base64 = Base64.getEncoder()
                .encodeToString(solutionJson(challenge, solve(challenge)).getBytes(StandardCharsets.UTF_8));
        assertEquals(PowStatus.PASSED, verifier.verify(base64).status());
    }

    @Test
    void replayIsRejectedAsReused() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        String payload = solutionJson(challenge, solve(challenge));
        assertEquals(PowStatus.PASSED, verifier.verify(payload).status());
        assertEquals(PowStatus.REUSED, verifier.verify(payload).status());
    }

    @Test
    void expiredChallengeIsRejected() throws InterruptedException {
        PowChallenge challenge = issuer.issue(1000, 50);
        String payload = solutionJson(challenge, solve(challenge));
        Thread.sleep(150); // ttl=50ms,确保已过期
        assertEquals(PowStatus.EXPIRED, verifier.verify(payload).status());
    }

    @Test
    void tamperedNumberIsBadSolution() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        long number = solve(challenge);
        assumeTrue(number < challenge.maxnumber(), "number 恰为上界时 +1 属于难度超限,概率 1/maxnumber");
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify(solutionJson(challenge, number + 1)).status());
    }

    @Test
    void tamperedSaltIsBadSolution() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        long number = solve(challenge);
        String payload = "{\"algorithm\":\"SHA-256\",\"challenge\":\"" + challenge.challenge()
                + "\",\"number\":" + number + ",\"salt\":\"" + flipLastChar(challenge.salt())
                + "\",\"signature\":\"" + challenge.signature() + "\"}";
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify(payload).status());
    }

    @Test
    void tamperedSignatureIsBadSignature() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        String payload = solutionJson(challenge, solve(challenge))
                .replace(challenge.signature(), flipLastChar(challenge.signature()));
        assertEquals(PowStatus.BAD_SIGNATURE, verifier.verify(payload).status());
    }

    @Test
    void differentSecretIsBadSignature() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        String payload = solutionJson(challenge, solve(challenge));
        PowResult result = new PowChallengeVerifier(new PowConfig("another-secret"), storage).verify(payload);
        assertEquals(PowStatus.BAD_SIGNATURE, result.status());
    }

    @Test
    void unsupportedAlgorithmIsBadSolution() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        String payload = solutionJson(challenge, solve(challenge)).replace("SHA-256", "SHA-512");
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify(payload).status());
    }

    @Test
    void numberAboveMaxnumberIsDifficultyExceeded() {
        String salt = "00112233445566778899aabbccddeeff";
        long number = 5000;
        String challenge = Hashes.sha256Hex(salt + number);
        String signature = Hashes.hmacSha256Hex(SECRET, challenge);
        storage.save(challenge, System.currentTimeMillis() + 60_000, 1000);
        String payload = "{\"algorithm\":\"SHA-256\",\"challenge\":\"" + challenge
                + "\",\"number\":" + number + ",\"salt\":\"" + salt
                + "\",\"signature\":\"" + signature + "\"}";
        assertEquals(PowStatus.DIFFICULTY_EXCEEDED, verifier.verify(payload).status());
    }

    @Test
    void unparseablePayloadIsBadSolution() {
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify(null).status());
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify("").status());
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify("###").status());
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify("{\"algorithm\":\"SHA-256\"}").status());
    }
}
