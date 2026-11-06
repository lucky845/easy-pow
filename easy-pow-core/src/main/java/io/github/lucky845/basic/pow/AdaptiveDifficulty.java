package io.github.lucky845.basic.pow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 难度自适应控制器(线程安全):根据近期"签发 → 校验通过"的实际解题耗时(EWMA 平滑),
 * 动态调整难度上限,把浏览器解题时长锁定在目标值附近。
 *
 * <p>调整公式:{@code current = clamp(base × targetSolveMs / ewmaSolveMs, min, max)}。
 * 解题偏慢(移动端/低算力设备)自动下调,偏快(脚本批量请求性价比上升)自动上调;
 * 尚无任何观测时返回 base(即配置的 defaultMaxnumber),行为与静态难度完全一致。</p>
 *
 * <p>挂载方式(签发与校验须共用同一实例):</p>
 * <pre>{@code
 * AdaptiveDifficulty adjuster = new AdaptiveDifficulty(50_000, 500, 1_000, 1_000_000, config.defaultTtlMs());
 * PowChallengeIssuer issuer = new PowChallengeIssuer(config, storage, adjuster::currentMaxnumber, adjuster);
 * PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage, adjuster);
 * }</pre>
 *
 * <p>ponytail: 签发时间记录在本 JVM 内存(有界懒清理),多实例部署时"签发与校验落在不同节点"的
 * 样本不参与观测(优雅降级为部分观测);若需跨节点全量观测,把 issuedAtMs 纳入
 * {@link PowStorage.ChallengeRecord} 后改造本类即可。</p>
 */
public final class AdaptiveDifficulty implements PowDifficultyListener {

    /** 观测噪声下限(毫秒):低于该值视为时钟抖动,不计入统计。 */
    static final long MIN_SOLVE_MS = 20L;
    /** EWMA 平滑系数:新观测权重。 */
    static final double ALPHA = 0.3;
    /** 签发时间表条目上界:懒清理兜底,不会无界增长。 */
    private static final int MAX_TRACKED = 4096;

    private final long baseMaxnumber;
    private final long targetSolveMs;
    private final long minMaxnumber;
    private final long maxMaxnumber;
    private final long ttlMs;

    /** challengeKey → 签发时刻(epoch 毫秒);校验成功即移除。 */
    private final Map<String, Long> issuedAt = new ConcurrentHashMap<>();
    /** 解题耗时 EWMA(毫秒);0 表示尚无有效观测。 */
    private volatile double ewmaMs = 0D;

    /**
     * @param baseMaxnumber  基准难度(即 {@link PowConfig#defaultMaxnumber()}),无观测时的初始难度
     * @param targetSolveMs  目标解题耗时(毫秒)
     * @param minMaxnumber   难度下限,须 ≥ {@link PowChallengeIssuer#MIN_MAXNUMBER}
     * @param maxMaxnumber   难度上限,须 ≥ minMaxnumber
     * @param ttlMs          挑战有效期(毫秒),同时用作签发时间表清理窗口
     */
    public AdaptiveDifficulty(long baseMaxnumber, long targetSolveMs,
                              long minMaxnumber, long maxMaxnumber, long ttlMs) {
        if (targetSolveMs <= 0) {
            throw new IllegalArgumentException("targetSolveMs 必须大于 0: " + targetSolveMs);
        }
        if (minMaxnumber < PowChallengeIssuer.MIN_MAXNUMBER) {
            throw new IllegalArgumentException("minMaxnumber 非法(需 >= " + PowChallengeIssuer.MIN_MAXNUMBER + "): " + minMaxnumber);
        }
        if (maxMaxnumber < minMaxnumber) {
            throw new IllegalArgumentException("maxMaxnumber(" + maxMaxnumber + ") 不能小于 minMaxnumber(" + minMaxnumber + ")");
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs 必须大于 0: " + ttlMs);
        }
        if (baseMaxnumber < minMaxnumber || baseMaxnumber > maxMaxnumber) {
            throw new IllegalArgumentException(
                    "baseMaxnumber(" + baseMaxnumber + ") 必须落在 [minMaxnumber(" + minMaxnumber + "), maxMaxnumber(" + maxMaxnumber + ")] 内");
        }
        this.baseMaxnumber = baseMaxnumber;
        this.targetSolveMs = targetSolveMs;
        this.minMaxnumber = minMaxnumber;
        this.maxMaxnumber = maxMaxnumber;
        this.ttlMs = ttlMs;
    }

    /** 供签发器使用的当前难度上限({@code adjuster::currentMaxnumber})。 */
    public long currentMaxnumber() {
        double e = ewmaMs;
        if (e <= 0D) {
            return baseMaxnumber;
        }
        return clamp(Math.round(baseMaxnumber * ((double) targetSolveMs / e)));
    }

    /** 解题耗时 EWMA(毫秒);尚无有效观测返回 -1,可作为监控指标暴露。 */
    public double ewmaSolveMs() {
        double e = ewmaMs;
        return e <= 0D ? -1D : e;
    }

    /** 挑战已签发:记录签发时刻(供校验通过时计算实际耗时)。 */
    @Override
    public void onIssued(String challengeKey) {
        long now = System.currentTimeMillis();
        evictIfNeeded(now);
        issuedAt.put(challengeKey, now);
    }

    /** 校验通过:计算"签发 → 校验"耗时并纳入 EWMA;无本节点签发记录或样本异常时忽略。 */
    @Override
    public void onVerified(String challengeKey) {
        Long issued = issuedAt.remove(challengeKey);
        if (issued == null) {
            return; // 非本节点签发(多实例)或无记录,不构成有效观测
        }
        long solveMs = System.currentTimeMillis() - issued;
        if (solveMs > ttlMs) {
            return; // 超过有效期才到达,视为异常样本
        }
        observeSolve(solveMs);
    }

    /**
     * 注入一次观测样本(毫秒):低于 {@link #MIN_SOLVE_MS} 的样本忽略;
     * 供单测/外部指标源直接喂入。
     */
    public void observeSolve(long solveMs) {
        if (solveMs < MIN_SOLVE_MS) {
            return;
        }
        double prev = ewmaMs;
        ewmaMs = prev <= 0D ? solveMs : ALPHA * solveMs + (1D - ALPHA) * prev;
    }

    /** 超过清理窗口的条目懒删除;突发洪峰压到上界时整表清空(仅影响观测连续性,不影响正确性)。 */
    private void evictIfNeeded(long now) {
        if (issuedAt.size() < MAX_TRACKED) {
            return;
        }
        issuedAt.values().removeIf(t -> now - t > ttlMs);
        if (issuedAt.size() >= MAX_TRACKED) {
            issuedAt.clear();
        }
    }

    private long clamp(long v) {
        return Math.max(minMaxnumber, Math.min(maxMaxnumber, v));
    }
}
