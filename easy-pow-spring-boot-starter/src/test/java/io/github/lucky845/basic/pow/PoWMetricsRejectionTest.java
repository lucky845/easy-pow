package io.github.lucky845.basic.pow;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    @DisplayName("标签值为小写状态名,便于 Prometheus 查询")
    void reasonTagIsLowerCase() {
        assertEquals("bad_signature", PoWMetrics.reasonOf(PowStatus.BAD_SIGNATURE));
        assertEquals("storage_error", PoWMetrics.reasonOf(PowStatus.STORAGE_ERROR));
    }

    private double counter(PowStatus status) {
        return registry.get(PoWMetrics.REJECTED).tag("reason", PoWMetrics.reasonOf(status))
                .counter().count();
    }
}
