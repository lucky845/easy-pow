package io.github.lucky845.basic.pow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("指标:拒绝分类与存储熔断")
class PoWMetricsRejectionTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PoWMetrics metrics = new PoWMetrics(registry, new PowConfig("test-secret"), null);

    @Test
    @DisplayName("每个拒绝原因独立计数,且不计入通过数")
    void countsRejectionsByReason() {
        metrics.onRejected("c1", PowStatus.REUSED);
        metrics.onRejected("c2", PowStatus.REUSED);
        metrics.onRejected("c3", PowStatus.BAD_SIGNATURE);
        metrics.onRejected("c4", PowStatus.STORAGE_ERROR);

        assertEquals(2.0, counter(PowStatus.REUSED));
        assertEquals(1.0, counter(PowStatus.BAD_SIGNATURE));
        assertEquals(1.0, counter(PowStatus.STORAGE_ERROR));
        assertEquals(0.0, registry.get(PoWMetrics.VERIFIED).counter().count(),
                "拒绝不得被记成通过——父接口默认实现正是这个坑");
    }

    @Test
    @DisplayName("存储故障额外计入 pow.storage.errors")
    void storageErrorCountsInfraCounter() {
        metrics.onRejected("c1", PowStatus.STORAGE_ERROR);
        assertEquals(1.0, registry.get(PoWMetrics.STORAGE_ERRORS).counter().count());
        metrics.onRejected("c2", PowStatus.EXPIRED);
        assertEquals(1.0, registry.get(PoWMetrics.STORAGE_ERRORS).counter().count(),
                "只有 STORAGE_ERROR 才计入基础设施故障");
    }

    @Test
    @DisplayName("签发路径的存储故障也计入 pow.storage.errors(不走校验结论)")
    void publisherStorageErrorCountsInfraCounter() {
        metrics.onStorageError("issue", new IllegalStateException("Redis 连接被拒绝"));
        assertEquals(1.0, registry.get(PoWMetrics.STORAGE_ERRORS).counter().count());
        metrics.onRejected("c1", PowStatus.STORAGE_ERROR);
        assertEquals(2.0, registry.get(PoWMetrics.STORAGE_ERRORS).counter().count(),
                "签发与校验两类故障共用同一个计数器,口径一致");
    }

    @Test
    @DisplayName("标签值为小写状态名,便于 Prometheus 查询")
    void reasonTagIsLowerCase() {
        assertEquals("bad_signature", PoWMetrics.reasonOf(PowStatus.BAD_SIGNATURE));
        assertEquals("storage_error", PoWMetrics.reasonOf(PowStatus.STORAGE_ERROR));
    }

    @Test
    @DisplayName("熔断时长随降级推进,恢复后归零")
    void trippedDurationTracksDegradation() {
        FailModePowStorage storage = new FailModePowStorage(
                new ThrowingStorage(), new InMemoryPowStorage(), PowStorageFailMode.OPEN, 60_000L);
        PoWMetrics metrics = new PoWMetrics(registry, new PowConfig("test-secret"), null, storage);

        assertEquals(0.0, registry.get(PoWMetrics.STORAGE_TRIPPED_FOR_MS).gauge().value(),
                "未熔断时长恒为 0");

        storage.find("c1"); // 主存储抛异常 → OPEN 模式降级
        assertEquals(1.0, registry.get(PoWMetrics.STORAGE_TRIPPED).gauge().value());
        assertTrue(registry.get(PoWMetrics.STORAGE_TRIPPED_FOR_MS).gauge().value() >= 0.0,
                "熔断时长不能为负(时钟回拨保护)");

        storage.reset();
        assertEquals(0.0, registry.get(PoWMetrics.STORAGE_TRIPPED).gauge().value());
        assertEquals(0.0, registry.get(PoWMetrics.STORAGE_TRIPPED_FOR_MS).gauge().value(),
                "恢复后时长归零,不能留历史值误导排障");
    }

    @Test
    @DisplayName("内存存储暴露条目数;Redis 等外部存储不暴露")
    void storageSizeOnlyForInMemory() {
        InMemoryPowStorage memory = new InMemoryPowStorage();
        memory.save("k1", System.currentTimeMillis() + 60_000, 1000);
        memory.save("k2", System.currentTimeMillis() + 60_000, 1000);
        new PoWMetrics(registry, new PowConfig("test-secret"), null,
                new FailModePowStorage(memory, null, PowStorageFailMode.CLOSED));

        assertEquals(2.0, registry.get(PoWMetrics.STORAGE_SIZE).gauge().value());
    }

    @Test
    @DisplayName("外部存储不暴露 pow.storage.size")
    void noSizeGaugeForExternalStorage() {
        new PoWMetrics(registry, new PowConfig("test-secret"), null,
                new FailModePowStorage(new ThrowingStorage(), null, PowStorageFailMode.CLOSED));

        assertEquals(0, registry.find(PoWMetrics.STORAGE_SIZE).gauges().size(),
                "Redis 的条目数不在本进程内,不能凭主存储对象编一个数");
    }

    /** 主存储故障替身:所有操作抛 RuntimeException。 */
    private static final class ThrowingStorage implements PowStorage {
        @Override
        public void save(String challengeKey, long expiresAtMs, long maxnumber) {
            throw new IllegalStateException("存储不可用");
        }

        @Override
        public ChallengeRecord find(String challengeKey) {
            throw new IllegalStateException("存储不可用");
        }

        @Override
        public boolean tryConsume(String challengeKey, long ttlMs) {
            throw new IllegalStateException("存储不可用");
        }
    }

    private double counter(PowStatus status) {
        return registry.get(PoWMetrics.REJECTED).tag("reason", PoWMetrics.reasonOf(status))
                .counter().count();
    }
}
