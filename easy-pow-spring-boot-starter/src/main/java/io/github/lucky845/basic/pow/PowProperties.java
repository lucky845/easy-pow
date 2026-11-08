package io.github.lucky845.basic.pow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置项,统一使用 {@code easy-pow} 前缀。
 *
 * <pre>
 * easy-pow:
 *   secret: change-me   # 必填,无默认值,严禁下发到前端
 *   maxnumber: 1000000  # 难度上限,默认 1000000
 *   ttl-ms: 120000      # 有效期(毫秒),默认 120000
 *   algorithm: SHA-256  # PoW 算法,可选 SHA-256 / SHA-384 / SHA-512,默认 SHA-256
 *   max-pending-challenges: 0  # 可选,内存存储待处理挑战上限;0=不限,超限拒绝签发(转 HTTP 429)
 *   storage-fail-mode: CLOSED  # 存储故障策略:CLOSED=拒绝放行(默认),OPEN=降级放行
 *   storage-cooldown-ms: 30000 # 熔断冷却时长(毫秒)
 *   fallback-storage:         # 可选,fail-mode=OPEN 时的降级存储 Bean 名称
 * </pre>
 */
@ConfigurationProperties(prefix = PowProperties.PREFIX)
public class PowProperties {

    public static final String PREFIX = "easy-pow";

    /** HMAC-SHA256 签名密钥,必填,无默认值,严禁下发到前端。 */
    private String secret;

    /** 默认难度上限,number ∈ [0, maxnumber]。 */
    private long maxNumber = PowConfig.DEFAULT_MAXNUMBER;

    /** 默认有效期(毫秒)。 */
    private long ttlMs = PowConfig.DEFAULT_TTL_MS;

    /** PoW 算法名,须在 {@link Hashes#SUPPORTED} 内;与 altcha 前端支持的清单一致。 */
    private String algorithm = PowConfig.DEFAULT_ALGORITHM;

    /** 内存存储待处理挑战上限;0 = 不限,负数非法。仅作用于自动配置的 InMemoryPowStorage。 */
    private int maxPendingChallenges = 0;

    /** 难度自适应(默认关闭):按"签发 → 校验通过"实际耗时动态调整难度上限。 */
    private final Adaptive adaptive = new Adaptive();

    /** 存储故障策略(默认严格):CLOSED 拒绝放行,OPEN 降级放行。 */
    private PowStorageFailMode storageFailMode = PowStorageFailMode.CLOSED;

    /** 熔断冷却时长(毫秒):故障后该时段内不再尝试主存储,避免每个请求都等一次超时。 */
    private long storageCooldownMs = FailModePowStorage.DEFAULT_COOLDOWN_MS;

    /** fail-mode=OPEN 时使用的降级存储 Bean 名称;缺省且无唯一候选时退化为内存存储。 */
    private String fallbackStorage;

    /** 转为核心 {@link PowConfig};secret 缺失或空白时快速失败,阻止应用启动。 */
    public PowConfig toConfig() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("配置 " + PREFIX + ".secret 必填,且不能为空白字符串");
        }
        return new PowConfig(secret, maxNumber, ttlMs, algorithm);
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getMaxNumber() {
        return maxNumber;
    }

    public void setMaxNumber(long maxNumber) {
        this.maxNumber = maxNumber;
    }

    public long getTtlMs() {
        return ttlMs;
    }

    public void setTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public int getMaxPendingChallenges() {
        return maxPendingChallenges;
    }

    public void setMaxPendingChallenges(int maxPendingChallenges) {
        this.maxPendingChallenges = maxPendingChallenges;
    }

    public PowStorageFailMode getStorageFailMode() {
        return storageFailMode;
    }

    public void setStorageFailMode(PowStorageFailMode storageFailMode) {
        this.storageFailMode = storageFailMode;
    }

    public long getStorageCooldownMs() {
        return storageCooldownMs;
    }

    public void setStorageCooldownMs(long storageCooldownMs) {
        this.storageCooldownMs = storageCooldownMs;
    }

    public String getFallbackStorage() {
        return fallbackStorage;
    }

    public void setFallbackStorage(String fallbackStorage) {
        this.fallbackStorage = fallbackStorage;
    }

    public Adaptive getAdaptive() {
        return adaptive;
    }

    /** 难度自适应配置项。 */
    public static class Adaptive {

        /** 是否启用;关闭时难度恒为 {@code easy-pow.maxnumber}。 */
        private boolean enabled = false;

        /** 目标解题耗时(毫秒):EWMA 偏高则下调难度,偏低则上调。 */
        private long targetSolveMs = 500;

        /** 难度下限(≥ 1000)。 */
        private long minMaxnumber = 1_000;

        /** 难度上限(≥ min-maxnumber)。 */
        private long maxMaxnumber = PowConfig.DEFAULT_MAXNUMBER;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTargetSolveMs() {
            return targetSolveMs;
        }

        public void setTargetSolveMs(long targetSolveMs) {
            this.targetSolveMs = targetSolveMs;
        }

        public long getMinMaxNumber() {
            return minMaxnumber;
        }

        public void setMinMaxNumber(long minMaxnumber) {
            this.minMaxnumber = minMaxnumber;
        }

        public long getMaxMaxNumber() {
            return maxMaxnumber;
        }

        public void setMaxMaxNumber(long maxMaxnumber) {
            this.maxMaxnumber = maxMaxnumber;
        }
    }
}
