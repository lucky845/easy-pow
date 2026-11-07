package io.github.lucky845.basic.pow;

/**
 * 挑战存储 SPI。默认实现 {@link InMemoryPowStorage}(单机);多实例/分布式部署时自行实现
 * (如 Redis)并同时注入 {@link PowChallengeIssuer} 与 {@link PowChallengeVerifier}。
 * 三个方法都必须线程安全。
 */
public interface PowStorage {

    /** 签发记录:校验时用于判断有效期与难度上限。 */
    record ChallengeRecord(long expiresAtMs, long maxnumber) {
    }

    /** 记录已签发挑战的元数据;同 challengeKey 重复签发时新记录覆盖旧记录。 */
    void save(String challengeKey, long expiresAtMs, long maxnumber);

    /**
     * @return 签发记录;不存在(未签发、已过期被清理、或已消费且消费标记也已过期)返回 null。
     *         实现须保证:已消费但消费标记仍在保留期内时仍返回记录,让校验链把重放判定为
     *         {@link PowStatus#REUSED} 而不是 {@link PowStatus#EXPIRED}
     */
    ChallengeRecord find(String challengeKey);

    /**
     * 原子消费,防重放:并发调用同一 challengeKey 时恰好一次返回 true。
     * 实现应同时拒绝已过期条目,并保证条目最终被清理、存储不会无界增长。
     *
     * @param ttlMs 消费标记保留时长提示(毫秒);实现应以此为消费标记的存续时间,
     *              使重复提交在标记窗口内可被识别为重放
     */
    boolean tryConsume(String challengeKey, long ttlMs);
}
