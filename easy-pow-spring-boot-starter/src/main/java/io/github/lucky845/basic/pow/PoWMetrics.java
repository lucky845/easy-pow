package io.github.lucky845.basic.pow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PoW 指标暴露:实现 {@link PowDifficultyListener},把签发/校验事件发布成 Micrometer 指标,
 * 使关闭自适应({@code easy-pow.adaptive.enabled=false})时依然能观测。
 *
 * <p>指标(<b>无</b> challenge 标签——challenge 是逐请求的 hex,打标签会撑爆注册表):</p>
 * <ul>
 *   <li>{@code pow.challenge.issued} — 已签发挑战数(Counter)</li>
 *   <li>{@code pow.challenge.verified} — 校验通过数(Counter)</li>
 *   <li>{@code pow.maxnumber} — 当前难度上限(Gauge);未挂载自适应时恒为配置值</li>
 *   <li>{@code pow.solve.ms.ewma} — 解题耗时 EWMA 毫秒(Gauge);无观测时为 -1</li>
 *   <li>{@code pow.adaptive.hashes.per.ms} — 客户端算力 EWMA 次/毫秒(Gauge);无观测时为 -1</li>
 *   <li>{@code pow.verify.rejected} — 按 {@code reason} 标签分类的拒绝计数(Counter),
 *       reason 取值见 {@link PowStatus} 的小写名(bad_signature / bad_solution / expired /
 *       difficulty_exceeded / reused / storage_error)</li>
 *   <li>{@code pow.storage.errors} — 存储故障次数(Counter)</li>
 *   <li>{@code pow.storage.tripped} — 存储是否熔断(Gauge,1/0);未使用故障策略存储时不暴露</li>
 * </ul>
 *
 * <p>本类不采集挑战内容,{@code challengeKey} 入参不进入任何指标标签。</p>
 */
public class PoWMetrics implements PowDifficultyListener {

    /** 签发计数。 */
    public static final String ISSUED = "pow.challenge.issued";
    /** 校验通过计数。 */
    public static final String VERIFIED = "pow.challenge.verified";
    /** 当前难度上限。 */
    public static final String MAXNUMBER = "pow.maxnumber";
    /** 解题耗时 EWMA(毫秒);无观测时为 -1,不参与聚合。 */
    public static final String SOLVE_MS_EWMA = "pow.solve.ms.ewma";
    /** 客户端算力 EWMA(次/毫秒);无观测时为 -1。 */
    public static final String HASHES_PER_MS = "pow.adaptive.hashes.per.ms";
    /** 按拒绝原因分类的拒绝计数,标签名 {@code reason}。 */
    public static final String REJECTED = "pow.verify.rejected";
    /** 存储故障累计次数(含熔断与恢复)。 */
    public static final String STORAGE_ERRORS = "pow.storage.errors";
    /** 存储是否处于熔断(1/0)。 */
    public static final String STORAGE_TRIPPED = "pow.storage.tripped";

    private final Counter issued;
    private final Counter verified;
    private final AdaptiveDifficulty adaptive;
    private final long fallbackMaxnumber;
    private final MeterRegistry registry;
    /** 按状态缓存的拒绝计数器;状态集合有限,预建避免热路径上查注册表。 */
    private final Map<PowStatus, Counter> rejectedByStatus = new EnumMap<>(PowStatus.class);
    private final Counter storageErrors;
    private final FailModePowStorage failModeStorage;

    /**
     * @param registry 指标注册表
     * @param config   当前配置(未挂载自适应时作为 {@code pow.maxnumber} 的取值)
     * @param adaptive 可选的难度自适应控制器;可为 null
     */
    public PoWMetrics(MeterRegistry registry, PowConfig config, AdaptiveDifficulty adaptive) {
        this(registry, config, adaptive, null);
    }

    /**
     * @param failModeStorage 可选的故障策略存储;为空时不暴露熔断指标
     */
    public PoWMetrics(MeterRegistry registry, PowConfig config, AdaptiveDifficulty adaptive,
                      FailModePowStorage failModeStorage) {
        this.registry = registry;
        this.failModeStorage = failModeStorage;
        if (registry == null) {
            // 注册表缺席(容器里没有 Micrometer 的导出实现)时退化为空实现,不阻断启动
            this.issued = null;
            this.verified = null;
            this.storageErrors = null;
            this.adaptive = adaptive;
            this.fallbackMaxnumber = config.defaultMaxnumber();
            return;
        }
        this.adaptive = adaptive;
        this.fallbackMaxnumber = config.defaultMaxnumber();
        this.issued = Counter.builder(ISSUED).description("已签发的 PoW 挑战数").register(registry);
        this.verified = Counter.builder(VERIFIED).description("校验通过的 PoW 挑战数").register(registry);
        // 预建所有非 PASSED 状态的计数器:拒绝是热路径,计数器按标签懒查找会有注册表锁竞争
        for (PowStatus status : PowStatus.values()) {
            if (status != PowStatus.PASSED) {
                rejectedByStatus.put(status, Counter.builder(REJECTED)
                        .description("被拒绝的 PoW 校验数,按原因分类")
                        .tag("reason", reasonOf(status))
                        .register(registry));
            }
        }
        this.storageErrors = Counter.builder(STORAGE_ERRORS)
                .description("存储层故障次数(每次校验遇到存储异常计一次)")
                .register(registry);
        if (failModeStorage != null) {
            Gauge.builder(STORAGE_TRIPPED, failModeStorage, s -> s.isTripped() ? 1D : 0D)
                    .description("存储是否处于熔断状态:1=熔断中(已降级),0=健康")
                    .register(registry);
        }
        Gauge.builder(MAXNUMBER, this, PoWMetrics::currentMaxnumber)
                .description("当前难度上限(自适应开启时随 EWMA 变化)")
                .register(registry);
        // 只暴露结果(maxnumber)不暴露原因(ewma),运维无法判断控制器是否在有效工作
        Gauge.builder(SOLVE_MS_EWMA, this, PoWMetrics::currentSolveMsEwma)
                .description("解题耗时 EWMA 毫秒(无观测时为 -1)")
                .register(registry);
        // 耗时只说"慢了"还是"快了";算力才说"该不该调难度",两者缺一都会误判
        Gauge.builder(HASHES_PER_MS, this, PoWMetrics::currentHashesPerMs)
                .description("客户端算力 EWMA(次/毫秒,无观测时为 -1)")
                .register(registry);
    }

    @Override
    public void onIssued(String challengeKey) {
        if (issued != null) {
            issued.increment();
        }
    }

    @Override
    public void onVerified(String challengeKey) {
        if (verified != null) {
            verified.increment();
        }
    }

    /**
     * 按拒绝原因计数。
     *
     * <p>覆盖父接口默认实现(父接口会转调 {@link #onVerified})——否则每被拒一次就会被
     * 记成一次"校验通过",面板比没有更糟。</p>
     */
    @Override
    public void onRejected(String challengeKey, PowStatus status) {
        if (registry == null || status == null || status == PowStatus.PASSED) {
            return;
        }
        Counter counter = rejectedByStatus.get(status);
        if (counter != null) {
            counter.increment();
        }
        if (status == PowStatus.STORAGE_ERROR && storageErrors != null) {
            storageErrors.increment();
        }
    }

    /**
     * 状态名转指标标签值:小写蛇形({@code BAD_SIGNATURE} → {@code bad_signature}),
     * 与 Prometheus 命名习惯一致,也避免标签值大小写在查询时踩坑。
     */
    static String reasonOf(PowStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 当前难度上限:挂载自适应时取实时值,否则回落配置值。 */
    public double currentMaxnumber() {
        return adaptive == null ? fallbackMaxnumber : adaptive.currentMaxnumber();
    }

    /** 解题耗时 EWMA:未挂载自适应时返回 -1(表示"不适用",而非 0ms)。 */
    public double currentSolveMsEwma() {
        return adaptive == null ? -1D : adaptive.ewmaSolveMs();
    }

    /** 客户端算力 EWMA(次/毫秒):未挂载自适应时返回 -1。回答"难度该不该动",与耗时互补。 */
    public double currentHashesPerMs() {
        return adaptive == null ? -1D : adaptive.ewmaHashesPerMs();
    }
}
