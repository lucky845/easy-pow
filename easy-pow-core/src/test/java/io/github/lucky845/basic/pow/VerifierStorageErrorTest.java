package io.github.lucky845.basic.pow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("校验器:存储故障与拒绝观测")
class VerifierStorageErrorTest {

    private static final String SECRET = "unit-test-secret";

    /** find 抛异常、tryConsume 抛异常的存储。 */
    private static final class BrokenStorage implements PowStorage {
        final InMemoryPowStorage delegate = new InMemoryPowStorage();
        boolean failOnFind;
        boolean failOnConsume;

        @Override
        public void save(String challengeKey, long expiresAtMs, long maxnumber) {
            delegate.save(challengeKey, expiresAtMs, maxnumber);
        }

        @Override
        public ChallengeRecord find(String challengeKey) {
            if (failOnFind) {
                throw new IllegalStateException("连接被拒绝");
            }
            return delegate.find(challengeKey);
        }

        @Override
        public boolean tryConsume(String challengeKey, long ttlMs) {
            if (failOnConsume) {
                throw new IllegalStateException("连接被拒绝");
            }
            return delegate.tryConsume(challengeKey, ttlMs);
        }
    }

    private final List<String> rejected = new ArrayList<>();
    private final List<String> verified = new ArrayList<>();
    private final PowDifficultyListener observer = new PowDifficultyListener() {
        @Override
        public void onVerified(String challengeKey) {
            verified.add(challengeKey);
        }

        @Override
        public void onRejected(String challengeKey, PowStatus status) {
            rejected.add(status.name());
        }
    };

    @Test
    @DisplayName("find 抛异常 → STORAGE_ERROR,不是 EXPIRED")
    void findFailureMapsToStorageError() {
        BrokenStorage storage = new BrokenStorage();
        PowConfig config = new PowConfig(SECRET);
        PowChallenge challenge = new PowChallengeIssuer(config, storage).issue();
        PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage, observer);

        storage.failOnFind = true;
        String payload = VerifierTestSupport.validSolution(challenge);
        PowResult result = verifier.verify(payload);

        assertEquals(PowStatus.STORAGE_ERROR, result.status(),
                "存储故障必须与「挑战过期」区分:归错类别会让面板显示大量过期,比 500 更难查");
        assertEquals(List.of("STORAGE_ERROR"), rejected);
    }

    @Test
    @DisplayName("tryConsume 抛异常 → STORAGE_ERROR,绝不 fail-open")
    void consumeFailureNeverFailsOpen() {
        BrokenStorage storage = new BrokenStorage();
        PowConfig config = new PowConfig(SECRET);
        PowChallengeIssuer issuer = new PowChallengeIssuer(config, storage);
        PowChallenge challenge = issuer.issue();
        PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage, observer);

        storage.failOnConsume = true;
        PowResult result = verifier.verify(VerifierTestSupport.validSolution(challenge));

        assertEquals(PowStatus.STORAGE_ERROR, result.status(), "消费失败放行等于关闭防重放");
        assertTrue(verified.isEmpty(), "失败路径不得计入通过数");
    }

    @Test
    @DisplayName("存储故障旁路收到操作名与原始异常,且不影响校验结论")
    void storageErrorListenerReceivesOperationAndCause() {
        BrokenStorage storage = new BrokenStorage();
        PowConfig config = new PowConfig(SECRET);
        PowChallenge challenge = new PowChallengeIssuer(config, storage).issue();
        List<String> seen = new ArrayList<>();
        PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage, observer,
                (operation, cause) -> seen.add(operation + ":" + cause.getClass().getSimpleName()));

        storage.failOnFind = true;
        assertEquals(PowStatus.STORAGE_ERROR,
                verifier.verify(VerifierTestSupport.validSolution(challenge)).status());
        storage.failOnFind = false;
        storage.failOnConsume = true;
        assertEquals(PowStatus.STORAGE_ERROR,
                verifier.verify(VerifierTestSupport.validSolution(challenge)).status());

        assertEquals(List.of("find:IllegalStateException", "consume:IllegalStateException"), seen,
                "两个故障点都要上报,且带上操作名区分 find / consume");
    }

    @Test
    @DisplayName("旁路自己抛异常也不得把 STORAGE_ERROR 变成别的结论")
    void brokenListenerDoesNotMaskVerdict() {
        BrokenStorage storage = new BrokenStorage();
        PowConfig config = new PowConfig(SECRET);
        PowChallenge challenge = new PowChallengeIssuer(config, storage).issue();
        PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage, observer,
                (operation, cause) -> {
                    throw new IllegalArgumentException("告警通道炸了");
                });

        storage.failOnFind = true;
        assertEquals(PowStatus.STORAGE_ERROR,
                verifier.verify(VerifierTestSupport.validSolution(challenge)).status());
    }

    @Test
    @DisplayName("每个拒绝分支都会回调 onRejected,包括签名与解析失败")
    void everyRejectBranchIsObserved() {
        BrokenStorage storage = new BrokenStorage();
        PowConfig config = new PowConfig(SECRET);
        PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage, observer);

        verifier.verify("not-a-valid-payload");
        verifier.verify(null);

        assertEquals(List.of("BAD_SOLUTION", "BAD_SOLUTION"), rejected);
        assertTrue(verified.isEmpty());
    }
}
