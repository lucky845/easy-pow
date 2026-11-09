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

 * <p>为什么锚在 base 而不是"上一次下发值":后者等于把输出接回输入,给控制器加了一阶极点,
 * 样本一有噪声就按 {@code (1/ALPHA)^n} 发散(实测 200 轮由 20000 漂到 31619)。
 * 锚在 base 时这是无状态映射:同一组 EWMA 永远给出同一个结果,读多少次都一样。</p>

 * <p><b>已知偏差</b>:稳态难度停在 {@code current = √(base × targetSolveMs × 实测算力)},
 * 而不是"恰好 targetSolveMs"所需的 {@code targetSolveMs × 实测算力}。客户端算力 40 次/毫秒、
 * base 50000、target 500ms 时,前者 31623、后者 20000,即难度系统性偏高约 1.58 倍
 * (对应实际耗时 ≈ 0.79×target)。根因是 EWMA 在稳态下不等于当次实测值:
 * {@code e = ALPHA·s + (1-ALPHA)·e ⇒ e = s = D/h},而控制律又把 base 当锚,
 * 于是 D 与 1/D 相乘形成二次不动点。修正需要把控制律改成显式估计客户端算力
 * {@code h = D/s} 并按 {@code D' = h × targetSolveMs} 反解(开环),代价是算力突变时超调;
 * 当前保留闭环形式,偏差写入 CHANGELOG 而不是假装没有。</p>

 * <p>为什么不用"成本占比 × 目标时长"反解:{@code w = 下发难度 / 实际耗时},
 * 分子分母里都含难度,相除后难度被约掉,剩下的只是<b>客户端算力</b>;
 * 拿它乘目标时长得到的是"该客户端跑满目标时长需要多少哈希",与我们要控制的难度无关。
 * w 作为观测指标保留({@link #ewmaHashesPerMs()}),用来回答"客户端到底多快",但不进控制律。</p>
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

    /** challengeKey → 签发时刻与当次下发的难度;校验通过即移除。 */
    private final Map<String, Issued> issuedAt = new ConcurrentHashMap<>();
    /** 解题耗时 EWMA(毫秒);0 表示尚无有效观测。控制律的唯一输入。 */
    private volatile double ewmaMs = 0D;
    /** 成本占比 EWMA(次/毫秒);仅观测/告警用,不参与控制律。 */
    private volatile double ewmaHashesPerMs = 0D;
    /** 求解速度的硬下界(次/毫秒):低于它视为时钟异常或伪造样本,丢弃该观测。 */
    static final double MIN_HASHES_PER_MS = 1e-3;

    /** 一次签发的现场:签发时刻 + 当次下发的难度。 */
    private record Issued(long atMs, long maxnumber) {
    }

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

    /** 成本占比 EWMA(次/毫秒),回答"客户端到底多快";尚无有效观测返回 -1。不参与控制律。 */
    public double ewmaHashesPerMs() {
        double e = ewmaHashesPerMs;
        return e <= 0D ? -1D : e;
    }

    /** 挑战已签发:记录签发时刻与<b>当次下发的难度</b>(成本占比的分子必须配当次的签发)。 */
    @Override
    public void onIssued(String challengeKey) {
        long now = System.currentTimeMillis();
        evictIfNeeded(now);
        issuedAt.put(challengeKey, new Issued(now, currentMaxnumber()));
    }

    /** 校验通过(无耗时信息):只能用服务端往返时延近似。 */
    @Override
    public void onVerified(String challengeKey) {
        onVerified(challengeKey, null);
    }

    /**
     * 校验通过:优先用客户端自报的解题耗时,其次用"签发 → 校验"的往返时延兜底。
     *
     * <p>服务端时钟量的往返里混着网络、排队和浏览器调度,与客户端算力不是一回事;
     * 只有前端回传 {@code took} 时才是真实解题耗时。</p>
     */
    @Override
    public void onVerified(String challengeKey, Long clientSolveMs) {
        Issued issued = issuedAt.remove(challengeKey);
        if (issued == null) {
            return; // 非本节点签发(多实例)或无记录,不构成有效观测
        }
        long solveMs = clientSolveMs != null ? clientSolveMs : System.currentTimeMillis() - issued.atMs();
        if (solveMs > ttlMs) {
            return; // 超过有效期才到达,视为异常样本
        }
        recordSample(issued.maxnumber(), solveMs);
    }

    /**
     * 注入一次观测样本(毫秒):低于 {@link #MIN_SOLVE_MS} 的样本忽略;
     * 供单测/外部指标源直接喂入。
     */
    public void observeSolve(long solveMs) {
        recordSample(currentMaxnumber(), solveMs);
    }

    /** 一次有效观测:耗时 EWMA 供控制,成本占比 EWMA 供观测。 */
    private void recordSample(long maxnumber, long solveMs) {
        if (solveMs < MIN_SOLVE_MS) {
            return; // 时钟抖动,分母不可信
        }
        double w = (double) maxnumber / solveMs;
        if (w < MIN_HASHES_PER_MS) {
            return; // 快到不可信(时钟回拨或伪造样本),不参与统计
        }
        double prevW = ewmaHashesPerMs;
        ewmaHashesPerMs = prevW <= 0D ? w : ALPHA * w + (1D - ALPHA) * prevW;
        double prev = ewmaMs;
        ewmaMs = prev <= 0D ? solveMs : ALPHA * solveMs + (1D - ALPHA) * prev;
    }

    /** 超过清理窗口的条目懒删除;突发洪峰压到上界时整表清空(仅影响观测连续性,不影响正确性)。 */
    private void evictIfNeeded(long now) {
        if (issuedAt.size() < MAX_TRACKED) {
            return;
        }
        issuedAt.values().removeIf(t -> now - t.atMs() > ttlMs);
        if (issuedAt.size() >= MAX_TRACKED) {
            issuedAt.clear();
        }
    }

    private long clamp(long v) {
        return Math.max(minMaxnumber, Math.min(maxMaxnumber, v));
    }
}
