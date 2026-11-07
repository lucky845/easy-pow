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

    /** 难度自适应(默认关闭):按"签发 → 校验通过"实际耗时动态调整难度上限。 */
    private final Adaptive adaptive = new Adaptive();

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
