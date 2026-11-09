package io.github.lucky845.basic.pow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给任意 {@link PowStorage} 套上 {@link PowStorageFailMode} 策略。
 *
 * <h3>为什么需要这一层</h3>
 * <p>存储实现(如 Redis)不应自己决定"故障时放行还是拒绝"——那是业务取舍。
 * 之前的实现把异常原样抛给调用方,结果是 HTTP 500,既不 fail-closed 也不 fail-open,
 * 而是<b>第三种</b>最糟的行为:错误暴露给用户,且指标不可见。</p>
 *
 * <h3>熔断</h3>
 * <p>故障后进入 {@code cooldownMs} 冷却期,期间直接走降级路径、不再打主存储。
 * 否则每次请求都要等一次 Redis 超时,故障期间响应时间会整体劣化到超时值。</p>
 */
public final class FailModePowStorage implements PowStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FailModePowStorage.class);

    /** 默认冷却时长:30 秒。 */
    public static final long DEFAULT_COOLDOWN_MS = 30_000L;

    private final PowStorage primary;
    private final PowStorage fallback;
    private final PowStorageFailMode mode;
    private final long cooldownMs;

    /** 熔断状态:0 表示健康,否则为主存储最近一次故障的时间戳。 */
    private volatile long trippedAt;
    private volatile boolean tripped;

    /**
     * @param primary  主存储,不能为空
     * @param fallback 降级存储;{@code fail-mode=open} 时使用,{@code null} 表示完全放弃存储校验
     * @param mode     故障策略
     * @param cooldownMs 熔断冷却时长(毫秒),期间不再尝试主存储;必须为正
     */
    public FailModePowStorage(PowStorage primary, PowStorage fallback, PowStorageFailMode mode, long cooldownMs) {
        if (primary == null) {
            throw new IllegalArgumentException("primary 不能为空");
        }
        if (cooldownMs <= 0) {
            throw new IllegalArgumentException("cooldownMs 必须大于 0: " + cooldownMs);
        }
        this.primary = primary;
        this.fallback = fallback;
        this.mode = mode == null ? PowStorageFailMode.CLOSED : mode;
        this.cooldownMs = cooldownMs;
    }

    /** 使用默认冷却时长。 */
    public FailModePowStorage(PowStorage primary, PowStorage fallback, PowStorageFailMode mode) {
        this(primary, fallback, mode, DEFAULT_COOLDOWN_MS);
    }

    /** 被包装的主存储(内存 / Redis)。诊断接口据此告诉运维真实用的是哪一个,而不是只看到包装层。 */
    public PowStorage primary() {
        return primary;
    }

    /** 降级时实际使用的存储;未配置降级时为 {@code null}。便于测试与排障确认"按名称选中了哪一个"。 */
    public PowStorage fallback() {
        return fallback;
    }

    /** 熔断开始至今的毫秒数(未熔断为 0)。只暴露 1/0 看不出"已经降级 5 分钟没人管"。 */
    public long trippedForMs() {
        if (!isTripped()) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - trippedAt);
    }

    /** 当前是否处于熔断(主存储被跳过)状态。供指标暴露。 */
    public boolean isTripped() {
        return tripped && System.currentTimeMillis() - trippedAt < cooldownMs;
    }

    /** 手工复位熔断(如健康检查确认主存储已恢复)。 */
    public void reset() {
        tripped = false;
        trippedAt = 0L;
    }

    @Override
    public void save(String challengeKey, long expiresAtMs, long maxnumber) {
        // 签发失败直接影响用户(拿不到挑战),OPEN 也不把签发请求转给降级存储;
        // 但失败必须计入熔断状态,否则"写了半天没写进去"在指标上完全不可见
        try {
            primary.save(challengeKey, expiresAtMs, maxnumber);
            reset();
        } catch (RuntimeException e) {
            markTripped(e);
            throw e;
        }
    }

    @Override
    public ChallengeRecord find(String challengeKey) {
        if (isTripped() && mode == PowStorageFailMode.OPEN) {
            // 只有 OPEN 会短路:CLOSED 短路返回 null 会被校验器判成 EXPIRED,
            // 把"存储挂了"伪装成"挑战过期",比 500 更难查
            return fallback != null ? fallback.find(challengeKey) : null;
        }
        try {
            ChallengeRecord record = primary.find(challengeKey);
            reset();
            return record;
        } catch (RuntimeException e) {
            return degrade(() -> {
                if (fallback != null) {
                    return fallback.find(challengeKey);
                }
                return null;
            }, e);
        }
    }

    @Override
    public boolean tryConsume(String challengeKey, long ttlMs) {
        if (isTripped()) {
            if (mode == PowStorageFailMode.OPEN) {
                return fallback == null || fallback.tryConsume(challengeKey, ttlMs);
            }
            return false;
        }
        try {
            boolean ok = primary.tryConsume(challengeKey, ttlMs);
            reset();
            return ok;
        } catch (RuntimeException e) {
            return degrade(() -> fallback == null || fallback.tryConsume(challengeKey, ttlMs), e);
        }
    }

    /**
     * 故障处理:OPEN 走降级,CLOSED 记熔断后把异常抛回给校验器(它会转成 STORAGE_ERROR)。
     */
    private <T> T degrade(StorageSupplier<T> degraded, RuntimeException cause) {
        if (mode == PowStorageFailMode.CLOSED) {
            // CLOSED 拒绝一切校验,这是比 OPEN 更重的降级:不置位的话 pow.storage.tripped 永远是 0,运维看不到服务已经不可用
            markTripped(cause);
            throw cause;
        }
        markTripped(cause);
        try {
            return degraded.get();
        } catch (RuntimeException fallbackFailure) {
            LOG.warn("PoW 降级存储同样失败: {}", fallbackFailure.toString());
            throw fallbackFailure;
        }
    }

    /** 记录熔断起点;仅状态翻转时打一条日志,避免故障期打爆日志。 */
    private void markTripped(RuntimeException cause) {
        boolean wasTripped = tripped;
        tripped = true;
        trippedAt = System.currentTimeMillis();
        if (!wasTripped) {
            // 降级会改变校验语义(CLOSED 下所有校验都失败),只在状态翻转时打一条,避免故障期打爆日志
            LOG.warn("PoW 主存储故障,进入 {} 降级,冷却 {} ms: {}", mode, cooldownMs, cause.toString());
        }
    }

    @FunctionalInterface
    private interface StorageSupplier<T> {
        T get();
    }
}
