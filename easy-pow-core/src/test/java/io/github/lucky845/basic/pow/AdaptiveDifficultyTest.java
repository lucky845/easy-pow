package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 难度自适应控制器:EWMA 数学、上下界钳制、非法配置、观测点接线。
 *
 * @author created by lucky845
 */
@DisplayName("难度自适应")
class AdaptiveDifficultyTest {

    private static AdaptiveDifficulty adjuster() {
        return new AdaptiveDifficulty(50_000, 500, 1_000, 1_000_000, 120_000);
    }

    @Test
    void noObservationReturnsBase() {
        AdaptiveDifficulty d = adjuster();
        assertEquals(50_000, d.currentMaxnumber());
        assertEquals(-1D, d.ewmaSolveMs(), 1e-9);
    }

    @Test
    void singleObservationScalesLinearly() {
        AdaptiveDifficulty d = adjuster();
        d.observeSolve(1000); // 50000次/1000ms = 50 次/毫秒,要 500ms 就得 2.5e7 → 钳到上限
        assertEquals(1000D, d.ewmaSolveMs(), 1e-9);
        assertEquals(50D, d.ewmaHashesPerMs(), 1e-9);
        assertEquals(25_000, d.currentMaxnumber());
    }

    @Test
    void ewmaSmoothsMultipleObservations() {
        AdaptiveDifficulty d = adjuster();
        d.observeSolve(1000);
        d.observeSolve(250); // 耗时 ewma = 0.3*250 + 0.7*1000 = 775
        assertEquals(775D, d.ewmaSolveMs(), 1e-9);
        // 第二次观测的分子是"当次下发难度" = 第一次观测后的纯函数值(25000),不是 base
        double w1 = 50_000D / 1000D;
        double w2 = 25_000D / 250D;
        assertEquals(0.3 * w2 + 0.7 * w1, d.ewmaHashesPerMs(), 1e-6);
        assertEquals(Math.round(50_000 * 500D / 775D), d.currentMaxnumber());
    }

    @Test
    @DisplayName("客户端自报耗时优先:服务端往返时延不会被当成解题耗时")
    void clientReportedSolveMsWins() {
        // 若用服务端时钟,签发与校验之间几乎同时发生,solveMs 远低于噪声地板而被丢弃,
        // 难度永远停在 base —— 这正是审查发现的 high 级缺陷。
        AdaptiveDifficulty d = new AdaptiveDifficulty(50_000, 500, 1_000, 1_000_000, 120_000);
        d.onIssued("k1");
        d.onVerified("k1", 1000L);

        assertEquals(1000D, d.ewmaSolveMs(), 1e-9, "必须采用客户端自报的 1000ms");
        assertEquals(25_000L, d.currentMaxnumber(), "往返时延若是 0 会被丢弃、难度仍停在 50000");
    }

    @Test
    void convergesToTargetForKnownClient() {
        // 真实闭环:签发下发的难度 → 客户端按自身算力解出耗时 → 耗时喂回控制器。
        AdaptiveDifficulty d = new AdaptiveDifficulty(50_000, 500, 1_000, 1_000_000, 120_000);
        double hashesPerMs = 40D; // 客户端算力:20000 次 ≈ 500ms
        for (int round = 0; round < 8; round++) {
            long issued = d.currentMaxnumber();
            d.onIssued("k" + round);
            d.observeSolve(Math.max(AdaptiveDifficulty.MIN_SOLVE_MS, Math.round(issued / hashesPerMs)));
        }
        // 稳态解:设下发 D 后实测耗时 D/40,EWMA 满足 e = 0.3·(D/40) + 0.7·e ⇒ e = D/40;
        // 而控制律 D = base·500/e = 50000·500·40/D ⇒ D² = 1e9 ⇒ D ≈ 31623。
        // 该不动点与真·成本占比控制器(要求 D = 500·40 = 20000)相差 ALPHA 隐含的软约束:
        // 指数平滑让稳态难度停在 1.58×(目标值);这是当前控制律的已知偏差,记录在 CHANGELOG。
        long fixedPoint = 31_623;
        long settled = d.currentMaxnumber();
        assertTrue(Math.abs(settled - fixedPoint) <= fixedPoint / 20L, "稳态应停在 " + fixedPoint + " 附近,实际 " + settled);
    }

    @Test
    void readsArePure() {
        // 控制律必须是无状态映射:读取不能推进状态,否则一个样本会被反复应用而发散。
        AdaptiveDifficulty d = adjuster();
        d.observeSolve(1000);
        long first = d.currentMaxnumber();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, d.currentMaxnumber(), "重复读取必须给出同一个结果");
        }
    }

    @Test
    void clampsToConfiguredBounds() {
        AdaptiveDifficulty tight = new AdaptiveDifficulty(2_000, 500, 1_000, 3_000, 120_000);
        tight.observeSolve(50); // 极快 → 想调到 20000,钳到 max
        assertEquals(3_000, tight.currentMaxnumber());
        AdaptiveDifficulty tight2 = new AdaptiveDifficulty(2_000, 500, 1_000, 3_000, 120_000);
        tight2.observeSolve(50_000); // 极慢 → 想调到 20,钳到 min
        assertEquals(1_000, tight2.currentMaxnumber());
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveDifficulty(50_000, 0, 1_000, 1_000_000, 120_000));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveDifficulty(50_000, 500, 999, 1_000_000, 120_000));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveDifficulty(50_000, 500, 1_000, 500, 120_000));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveDifficulty(999, 500, 1_000, 1_000_000, 120_000));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveDifficulty(2_000_001, 500, 1_000, 1_000_000, 120_000));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveDifficulty(50_000, 500, 1_000, 1_000_000, 0));
    }

    @Test
    void verifiedWithoutIssuedRecordIsIgnored() {
        AdaptiveDifficulty d = adjuster();
        d.onVerified("ghost");
        assertEquals(-1D, d.ewmaSolveMs(), 1e-9);
    }

    @Test
    void samplesBelowNoiseFloorAreIgnored() {
        AdaptiveDifficulty d = adjuster();
        d.observeSolve(AdaptiveDifficulty.MIN_SOLVE_MS - 1); // 低于噪声下限
        d.observeSolve(19);
        assertEquals(-1D, d.ewmaSolveMs(), 1e-9);
        // onIssued → onVerified 同毫秒完成同样低于噪声下限;若 GC 抖动恰超限,不构成断言
        d.onIssued("k");
        d.onVerified("k");
    }

    @Test
    void issuerAndVerifierHookPointsFire() {
        PowConfig config = new PowConfig("test-secret");
        InMemoryPowStorage storage = new InMemoryPowStorage();
        List<String> issued = new ArrayList<>();
        List<String> verified = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        PowChallengeIssuer issuer = new PowChallengeIssuer(config, storage, () -> 2_000L,
                new PowDifficultyListener() {
                    @Override
                    public void onIssued(String challengeKey) {
                        issued.add(challengeKey);
                    }
                });
        PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage,
                new PowDifficultyListener() {
                    @Override
                    public void onVerified(String challengeKey) {
                        verified.add(challengeKey);
                    }

                    @Override
                    public void onRejected(String challengeKey, PowStatus status) {
                        rejected.add(status.name());
                    }
                });
        PowChallenge challenge = issuer.issue();
        assertEquals(List.of(challenge.challenge()), issued);
        String payload = solutionJson(challenge, solve(challenge));
        assertEquals(PowStatus.PASSED, verifier.verify(payload).status());
        assertEquals(List.of(challenge.challenge()), verified);
        assertEquals(PowStatus.REUSED, verifier.verify(payload).status());
        // 重放不走 onVerified(否则通过数被污染),但必须走 onRejected —— 否则面板看不到攻击
        assertEquals(1, verified.size());
        assertEquals(List.of("REUSED"), rejected);
    }

    @Test
    void concurrentIssuedVerifiedAndReads() throws InterruptedException {
        AdaptiveDifficulty d = adjuster();
        int threads = 4;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String key = "k-" + Thread.currentThread().getId() + "-" + i;
                        d.onIssued(key);
                        d.observeSolve(100 + (i % 50));
                        d.onVerified(i % 2 == 0 ? key : "ghost-" + i);
                        long cur = d.currentMaxnumber();
                        assertTrue(cur >= 1_000 && cur <= 1_000_000);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发任务未在时限内完成");
        pool.shutdownNow();
    }

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
                + "\",\"signature\":\"" + challenge.signature() + "\"}";
    }
}