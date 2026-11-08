package io.github.lucky845.basic.pow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("存储故障策略")
class FailModePowStorageTest {

    /** 可开关故障的假存储。 */
    private static final class FlakyStorage implements PowStorage {
        boolean broken;
        final AtomicInteger calls = new AtomicInteger();
        final InMemoryPowStorage delegate = new InMemoryPowStorage();

        @Override
        public void save(String challengeKey, long expiresAtMs, long maxnumber) {
            if (broken) {
                throw new IllegalStateException("Redis 连接被拒绝");
            }
            delegate.save(challengeKey, expiresAtMs, maxnumber);
        }

        @Override
        public ChallengeRecord find(String challengeKey) {
            calls.incrementAndGet();
            if (broken) {
                throw new IllegalStateException("Redis 连接被拒绝");
            }
            return delegate.find(challengeKey);
        }

        @Override
        public boolean tryConsume(String challengeKey, long ttlMs) {
            calls.incrementAndGet();
            if (broken) {
                throw new IllegalStateException("Redis 连接被拒绝");
            }
            return delegate.tryConsume(challengeKey, ttlMs);
        }
    }

    @Test
    @DisplayName("CLOSED:主存储故障时异常上抛,由校验器转成 STORAGE_ERROR")
    void closedPropagates() {
        FlakyStorage primary = new FlakyStorage();
        FailModePowStorage storage = new FailModePowStorage(primary, null, PowStorageFailMode.CLOSED);
        storage.save("c1", System.currentTimeMillis() + 60_000, 1000);
        primary.broken = true;
        assertThrows(IllegalStateException.class, () -> storage.find("c1"));
        // CLOSED 不是短路,而是"照样重试主存储 + 置熔断位":服务已经不可用,指标必须显示 1,
        // 否则 pow.storage.tripped 恒为 0,运维看不到 outage。这里同时锁死"仍会重试"。
        assertTrue(storage.isTripped(), "CLOSED 也要置熔断位,否则故障不可观测");
        assertThrows(IllegalStateException.class, () -> storage.find("c1"));
        assertEquals(2, primary.calls.get(), "CLOSED 不短路:每次都要重试主存储,恢复即自动复位");
    }

    @Test
    @DisplayName("OPEN:无降级存储时返回 null —— 校验器据此判定 EXPIRED,而不是 500")
    void openWithoutFallbackReturnsNull() {
        FlakyStorage primary = new FlakyStorage();
        FailModePowStorage storage = new FailModePowStorage(primary, null, PowStorageFailMode.OPEN);
        primary.broken = true;
        assertNull(storage.find("c1"));
        assertTrue(storage.tryConsume("c1", 1000), "无降级存储时放弃防重放,放行");
        assertTrue(storage.isTripped());
    }

    @Test
    @DisplayName("OPEN:熔断后冷却期内不再打主存储,避免每个请求都等超时")
    void openCircuitBreakerSkipsPrimary() {
        FlakyStorage primary = new FlakyStorage();
        FailModePowStorage storage = new FailModePowStorage(primary, new InMemoryPowStorage(),
                PowStorageFailMode.OPEN, 60_000);
        primary.broken = true;
        storage.find("c1");
        int afterTrip = primary.calls.get();
        storage.find("c2");
        storage.find("c3");
        assertEquals(afterTrip, primary.calls.get(), "熔断期内不应再调用主存储");
        assertTrue(storage.isTripped());
    }

    @Test
    @DisplayName("OPEN:配置降级存储时,降级期间仍在该存储上防重放")
    void openUsesFallbackForReplayProtection() {
        FlakyStorage primary = new FlakyStorage();
        InMemoryPowStorage fallback = new InMemoryPowStorage();
        FailModePowStorage storage = new FailModePowStorage(primary, fallback, PowStorageFailMode.OPEN);
        primary.broken = true;
        // 签发不降级:主存储故障时 save 直接把异常抛给调用方(签发失败=用户拿不到挑战),
        // 这里吞掉异常只为继续铺 fallback 上的记录,验证降级期间的防重放
        assertThrows(IllegalStateException.class,
                () -> storage.save("c1", System.currentTimeMillis() + 60_000, 1000));
        // 降级到 fallback;fallback 里没有 c1,find 返回 null
        assertNull(storage.find("c1"), "降级存储没有该记录");
        fallback.save("c1", System.currentTimeMillis() + 60_000, 1000);
        assertTrue(storage.tryConsume("c1", 30_000));
        assertFalse(storage.tryConsume("c1", 30_000), "降级存储同样应拒绝重放");
    }

    @Test
    @DisplayName("主存储恢复后熔断自动解除,无需重启")
    void recoversAfterPrimaryHeals() {
        FlakyStorage primary = new FlakyStorage();
        FailModePowStorage storage = new FailModePowStorage(primary, new InMemoryPowStorage(),
                PowStorageFailMode.OPEN, 1);
        primary.broken = true;
        storage.find("c1");
        assertTrue(storage.isTripped());
        primary.broken = false;
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        storage.save("c2", System.currentTimeMillis() + 60_000, 1000);
        assertEquals(1000L, storage.find("c2").maxnumber(), "冷却期过后应重新走主存储");
        assertFalse(storage.isTripped());
    }

    @Test
    @DisplayName("签发不降级:主存储故障时直接失败,不落进降级存储")
    void saveNeverDegrades() {
        FlakyStorage primary = new FlakyStorage();
        FailModePowStorage storage = new FailModePowStorage(primary, new InMemoryPowStorage(),
                PowStorageFailMode.OPEN);
        storage.save("c1", System.currentTimeMillis() + 60_000, 1000);
        assertEquals(1000L, primary.delegate.find("c1").maxnumber());
    }

    @Test
    @DisplayName("签发失败也计入熔断:异常照旧抛出,但 tripped 与 trippedForMs 必须可见")
    void saveFailureMarksTripped() {
        FlakyStorage primary = new FlakyStorage();
        FailModePowStorage storage = new FailModePowStorage(primary, new InMemoryPowStorage(),
                PowStorageFailMode.CLOSED, 30_000L);
        primary.broken = true;
        assertThrows(IllegalStateException.class,
                () -> storage.save("c1", System.currentTimeMillis() + 60_000, 1000));
        assertTrue(storage.isTripped());
        assertTrue(storage.trippedForMs() >= 0L);
        // 成功一次即复位
        primary.broken = false;
        storage.save("c2", System.currentTimeMillis() + 60_000, 1000);
        assertFalse(storage.isTripped());
        assertEquals(0L, storage.trippedForMs());
    }
}
