package io.github.lucky845.basic.pow;

import java.security.SecureRandom;
import java.util.function.LongSupplier;

/**
 * 挑战签发器:生成随机 salt 与 number,计算 challenge 与 HMAC 签名,
 * 并把签发记录写入 {@link PowStorage}(须与 Verifier 使用同一存储)。
 * 线程安全({@link SecureRandom} 线程安全)。
 */
public final class PowChallengeIssuer {

    /** 允许的最小难度上限。 */
    public static final long MIN_MAXNUMBER = 1000L;

    private static final int SALT_BYTES = 16; // 128 bit 熵

    private final PowConfig config;
    private final PowStorage storage;
    private final SecureRandom random = new SecureRandom();
    /** 当前难度供应商;未挂载自适应时恒为配置默认值。 */
    private final LongSupplier maxnumberSupplier;
    /** 签发事件监听(难度自适应观测点);可为 null。 */
    private final PowDifficultyListener listener;

    public PowChallengeIssuer(PowConfig config) {
        this(config, new InMemoryPowStorage());
    }

    /** 注入自定义存储;必须与 {@link PowChallengeVerifier} 使用同一实例(或同一共享存储)。 */
    public PowChallengeIssuer(PowConfig config, PowStorage storage) {
        this(config, storage, null, null);
    }

    /**
     * 挂载难度自适应:每次签发以 {@code maxnumberSupplier.getAsLong()} 为难度上限,
     * 并回调 {@code listener.onIssued(challenge)};两者均可为 null(退化为静态难度)。
     * 典型用法见 {@link AdaptiveDifficulty} 类注释。
     */
    public PowChallengeIssuer(PowConfig config, PowStorage storage,
                              LongSupplier maxnumberSupplier, PowDifficultyListener listener) {
        this.config = config;
        this.storage = storage;
        this.maxnumberSupplier = maxnumberSupplier == null ? () -> config.defaultMaxnumber() : maxnumberSupplier;
        this.listener = listener;
    }

    /** 使用当前难度(挂载自适应时为 {@code maxnumberSupplier.getAsLong()})与默认有效期签发。 */
    public PowChallenge issue() {
        return issue(maxnumberSupplier.getAsLong(), config.defaultTtlMs());
    }

    /**
     * 签发挑战:challenge = algorithm(salt + number),signature = HMAC-algorithm(secret, challenge);
     * 算法取自 {@link PowConfig#algorithm()}(SHA-256 / SHA-384 / SHA-512)。
     *
     * @param maxnumber 难度上限,number ∈ [0, maxnumber],需 ≥ 1000
     * @param ttlMs     有效期(毫秒),需 > 0
     * @throws IllegalArgumentException maxnumber {@code <} 1000(或溢出)或 ttlMs {@code <=} 0
     * @throws IllegalStateException    存储达到容量上限时拒绝签发,调用方应转成 HTTP 429
     */
    public PowChallenge issue(long maxnumber, long ttlMs) {
        // 上界 +1 供 randomUpTo 生成闭区间;Long.MAX_VALUE 会溢出成负数,一并拒绝
        if (maxnumber < MIN_MAXNUMBER || maxnumber >= Long.MAX_VALUE) {
            throw new IllegalArgumentException("maxnumber 非法(需 >= " + MIN_MAXNUMBER + "): " + maxnumber);
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs 必须大于 0: " + ttlMs);
        }
        byte[] saltBytes = new byte[SALT_BYTES];
        random.nextBytes(saltBytes);
        String salt = Hex.encode(saltBytes);
        long number = randomUpTo(maxnumber + 1); // [0, maxnumber] 闭区间
        String algorithm = config.algorithm();
        String challenge = Hashes.shaHex(algorithm, salt + number);
        String signature = Hashes.hmacHex(algorithm, config.secret(), challenge);
        long expiresAt = System.currentTimeMillis() + ttlMs;
        storage.save(challenge, expiresAt, maxnumber);
        if (listener != null) {
            listener.onIssued(challenge);
        }
        return new PowChallenge(algorithm, challenge, maxnumber, salt, signature, expiresAt);
    }

    /** 均匀分布的 [0, bound) 拒绝采样,无modulo偏差。 */
    private long randomUpTo(long bound) {
        long r = random.nextLong();
        long m = bound - 1;
        if ((bound & m) == 0L) { // 2 的幂直接掩码
            return r & m;
        }
        long u = r >>> 1;
        while (u + m - (r = u % bound) < 0L) {
            u = random.nextLong() >>> 1;
        }
        return r;
    }
}
