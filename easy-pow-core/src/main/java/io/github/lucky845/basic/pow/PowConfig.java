package io.github.lucky845.basic.pow;

/**
 * 全局配置。
 *
 * @param secret           HMAC-SHA256 签名密钥,必填,严禁下发到前端
 * @param defaultMaxnumber 默认难度上限,{@link PowChallengeIssuer#issue()} 使用
 * @param defaultTtlMs     默认有效期(毫秒)
 */
public record PowConfig(String secret, long defaultMaxnumber, long defaultTtlMs) {

    /** 默认难度上限。 */
    public static final long DEFAULT_MAXNUMBER = 1_000_000L;
    /** 默认有效期:120 秒。 */
    public static final long DEFAULT_TTL_MS = 120_000L;

    public PowConfig {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret 必填,且不能为空白字符串");
        }
        if (defaultMaxnumber < 1000 || defaultMaxnumber == Long.MAX_VALUE) {
            throw new IllegalArgumentException("defaultMaxnumber 非法(需 >= 1000): " + defaultMaxnumber);
        }
        if (defaultTtlMs <= 0) {
            throw new IllegalArgumentException("defaultTtlMs 必须大于 0: " + defaultTtlMs);
        }
    }

    /** 使用默认难度(1_000_000)与默认有效期(120_000ms)。 */
    public PowConfig(String secret) {
        this(secret, DEFAULT_MAXNUMBER, DEFAULT_TTL_MS);
    }
}
