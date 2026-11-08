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
            if (Hashes.shaHex(challenge.algorithm(), challenge.salt() + n).equals(challenge.challenge())) {
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
        String payload = solutionJson(challenge, solve(challenge)).replace("SHA-256", "SHA3-256");
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify(payload).status());
    }

    @Test
    void numberAboveMaxnumberIsDifficultyExceeded() {
        String salt = "00112233445566778899aabbccddeeff";
        long number = 5000;
        String challenge = Hashes.shaHex("SHA-256", salt + number);
        String signature = Hashes.hmacHex("SHA-256", SECRET, challenge);
        storage.save(challenge, System.currentTimeMillis() + 60_000, 1000);
        String payload = "{\"algorithm\":\"SHA-256\",\"challenge\":\"" + challenge
                + "\",\"number\":" + number + ",\"salt\":\"" + salt
                + "\",\"signature\":\"" + signature + "\"}";
        assertEquals(PowStatus.DIFFICULTY_EXCEEDED, verifier.verify(payload).status());
    }

    @Test
    void clientSuppliedMaxnumberCannotRaiseTheBound() {
        // solution 里的 maxnumber 是客户端自填的:攻击者塞一个巨大值也不能把上界抬起来,
        // 上界只能来自服务端存储的签发记录。
        String salt = "00112233445566778899aabbccddeeff";
        long number = 5000;
        String challenge = Hashes.shaHex("SHA-256", salt + number);
        String signature = Hashes.hmacHex("SHA-256", SECRET, challenge);
        storage.save(challenge, System.currentTimeMillis() + 60_000, 1000);
        String payload = "{\"algorithm\":\"SHA-256\",\"challenge\":\"" + challenge
                + "\",\"number\":" + number + ",\"salt\":\"" + salt
                + "\",\"signature\":\"" + signature + "\",\"maxnumber\":900000000}";
        assertEquals(PowStatus.DIFFICULTY_EXCEEDED, verifier.verify(payload).status());
    }

    @Test
    void unparseablePayloadIsBadSolution() {
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify(null).status());
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify("").status());
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify("###").status());
        assertEquals(PowStatus.BAD_SOLUTION, verifier.verify("{\"algorithm\":\"SHA-256\"}").status());
    }

    private static PowConfig sha512Config() {
        return new PowConfig(SECRET, PowConfig.DEFAULT_MAXNUMBER, PowConfig.DEFAULT_TTL_MS, "SHA-512");
    }

    @Test
    void sha512RoundTripPasses() {
        PowStorage shared = new InMemoryPowStorage();
        PowChallenge challenge = new PowChallengeIssuer(sha512Config(), shared).issue(2000, 120_000);
        assertEquals("SHA-512", challenge.algorithm());
        assertEquals(128, challenge.challenge().length()); // SHA-512 = 64 字节 hex
        PowChallengeVerifier sha512Verifier = new PowChallengeVerifier(sha512Config(), shared);
        assertEquals(PowStatus.PASSED, sha512Verifier.verify(solutionJson(challenge, solve(challenge))).status());
    }

    @Test
    void algorithmMismatchIsBadSolution() {
        // 算法名不参与签名:用 SHA-512 签发却声明 SHA-256,必须被配置层拒绝(防降级)
        PowStorage shared = new InMemoryPowStorage();
        PowChallenge challenge = new PowChallengeIssuer(sha512Config(), shared).issue(2000, 120_000);
        String payload = solutionJson(challenge, solve(challenge)).replace("SHA-512", "SHA-256");
        assertEquals(PowStatus.BAD_SOLUTION, new PowChallengeVerifier(sha512Config(), shared).verify(payload).status());
    }
}
