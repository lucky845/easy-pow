package io.github.lucky845.basic.pow;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Redis 的 PoW 挑战存储,适用于多实例/分布式部署。
 *
 * <p>内存存储只在本进程内有效。应用被部署成多个副本时,用户在 A 实例拿到挑战、
 * 提交时被负载均衡打到 B 实例,校验必然失败——本实现把 expiresAtMs 与 maxnumber
 * 放进 Redis,让任意实例都能校验任意实例签发的挑战,防重放标记也因此天然共享。
 *
 * <h3>key 与 value 设计</h3>
 * <ul>
 *   <li>key:{@code easy-pow:challenge:{challenge}},前缀与其它业务在同一 Redis 里隔离;
 *       challenge 本身是 hex 字符串(签发端产出),拼进 key 无需转义。</li>
 *   <li>value:{@code v1;<expiresAtMs>;<maxnumber>}。两个数值字段不含分隔符,字段拼接
 *       即可,无需长度前缀或 JSON;版本前缀为将来字段演进留余地。刻意不引 Jackson:
 *       挑战记录是"两分钟内就被读走"的短命数据,自描述文本与类结构、依赖版本完全解耦。</li>
 * </ul>
 *
 * <h3>原子消费与重放可观测</h3>
 * <p>{@code tryConsume} 用 Lua 脚本实现原子防重放:Redis 单线程执行脚本,GET 与 DEL
 * 之间不会插入其它命令,并发消费同一挑战恰好一次成功。不用 GETDEL 是因为它要求
 * Redis &gt;= 6.2,Lua 脚本兼容面更广(5.x 即可)。
 *
 * <p>消费成功后主 key 被删除,同时把记录写入消费标记 key {@code easy-pow:consumed:{challenge}}
 * 并保留 SPI 的 ttlMs 作为标记存活时长,于是 {@link #find} 在标记窗口内仍能读回记录——
 * 与 {@code InMemoryPowStorage} 语义一致:消费过的挑战再次校验得到 {@link PowStatus#REUSED}
 * 而不是 EXPIRED,重放可观测、可计数、可被监听器/指标统计。标记到期后 find 返回 null,
 * 重放退化为 EXPIRED,存储依旧不会无界增长。
 * <p>本类不绑定 Spring 上下文,自动装配见 {@link RedisPowAutoConfiguration}。
 *
 * @author created by lucky845
 */
public class RedisPowStorage implements PowStorage {

    /** key 前缀,完整 key 为 {@code easy-pow:challenge:{challenge}}。 */
    public static final String DEFAULT_PREFIX = "easy-pow:challenge:";

    /** 序列化格式版本。 */
    private static final String VERSION = "v1";

    /** 消费标记 key 前缀,完整 key 为 {@code easy-pow:consumed:{challenge}}。 */
    public static final String CONSUMED_PREFIX = "easy-pow:consumed:";

    /**
     * 原子消费脚本:GET 命中即 DEL 主 key、把记录写入消费标记 key(KEYS[2],保留 ARGV[1] 毫秒)
     * 并返回 1;未命中返回 0(未签发、已过期被 Redis 回收,或已消费且标记仍在)。
     */
    static final String CONSUME_LUA = "local v = redis.call('GET', KEYS[1]) "
            + "if not v then return 0 end "
            + "redis.call('DEL', KEYS[1]) "
            + "redis.call('SET', KEYS[2], v, 'PX', ARGV[1]) "
            + "return 1";

    private static final RedisScript<Long> CONSUME_SCRIPT = RedisScript.of(CONSUME_LUA, Long.class);

    private final StringRedisTemplate redis;

    private final Clock clock;

    private final String prefix;

    private final String consumedPrefix;

    public RedisPowStorage(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    /** 测试可注入固定 Clock,使 TTL 计算可断言。 */
    public RedisPowStorage(StringRedisTemplate redis, Clock clock) {
        this(redis, clock, DEFAULT_PREFIX);
    }

    /**
     * 全量构造:自定义 key 前缀。
     *
     * <p>同一 Redis 被多个应用/多套环境共用,或需要按业务线隔离清理时使用。
     * 消费标记前缀由挑战前缀派生(去掉尾部冒号后加 {@code :consumed:}),
     * 保证一次配置同时覆盖两类 key,不会出现"挑战换了前缀、消费标记还留在旧前缀"的半迁移状态。</p>
     *
     * @param prefix 挑战 key 前缀,非空;完整 key 为 {@code prefix + challengeKey}
     */
    public RedisPowStorage(StringRedisTemplate redis, Clock clock, String prefix) {
        this.redis = Objects.requireNonNull(redis, "StringRedisTemplate 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix 不能为空或空白");
        }
        this.prefix = prefix.trim();
        this.consumedPrefix = deriveConsumedPrefix(this.prefix);
    }

    /**
     * 从挑战前缀派生消费标记前缀,保证 DEFAULT_PREFIX 派生出历史值 {@code easy-pow:consumed:}(升级不迁移数据)。
     *
     * <p>规则:带 {@code challenge} 段的把该段替换为 {@code consumed};否则去掉尾部冒号后加
     * {@code :consumed:}。两种结果都以冒号结尾。</p>
     */
    static String deriveConsumedPrefix(String challengePrefix) {
        String p = challengePrefix.trim();
        int idx = p.lastIndexOf("challenge");
        if (idx >= 0) {
            return p.substring(0, idx) + "consumed:";
        }
        String base = p.endsWith(":") ? p.substring(0, p.length() - 1) : p;
        return base + ":consumed:";
    }

    /** 当前挑战 key 前缀。 */
    public String prefix() {
        return prefix;
    }

    @Override
    public void save(String challengeKey, long expiresAtMs, long maxnumber) {
        Objects.requireNonNull(challengeKey, "challengeKey 不能为空");
        // TTL 交给 Redis 自己管;毫秒向上取整成秒,至少 1 秒(含已过期写入,交由校验器拒绝)。
        long deltaMs = expiresAtMs - clock.millis();
        long ttlSeconds = Math.max(1L, (deltaMs + 999L) / 1000L);
        redis.opsForValue().set(keyOf(challengeKey), encode(expiresAtMs, maxnumber),
                Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public ChallengeRecord find(String challengeKey) {
        if (challengeKey == null || challengeKey.isBlank()) {
            return null;
        }
        String raw = redis.opsForValue().get(keyOf(challengeKey));
        if (raw == null) {
            // 已消费:主 key 已删,回退读消费标记,让校验链走到 tryConsume 得到 REUSED
            raw = redis.opsForValue().get(consumedKeyOf(challengeKey));
        }
        return raw == null ? null : decode(raw);
    }

    /**
     * 原子消费:Lua 脚本内 GET+DEL 不可分割,并发调用同一挑战恰好一次返回 true。
     * 消费成功写入存活 ttlMs 的消费标记,使重复提交在标记窗口内得到 REUSED 而非 EXPIRED。
     */
    @Override
    public boolean tryConsume(String challengeKey, long ttlMs) {
        if (challengeKey == null || challengeKey.isBlank()) {
            return false;
        }
        Long consumed = redis.execute(CONSUME_SCRIPT,
                List.of(keyOf(challengeKey), consumedKeyOf(challengeKey)), String.valueOf(Math.max(1L, ttlMs)));
        return Long.valueOf(1L).equals(consumed);
    }

    private String keyOf(String challengeKey) {
        return prefix + challengeKey;
    }

    private String consumedKeyOf(String challengeKey) {
        return consumedPrefix + challengeKey;
    }

    static String encode(long expiresAtMs, long maxnumber) {
        return VERSION + ";" + expiresAtMs + ";" + maxnumber;
    }

    static ChallengeRecord decode(String raw) {
        String[] parts = raw.split(";", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("PoW 存储内容损坏或版本不识别: " + raw);
        }
        try {
            return new ChallengeRecord(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("PoW 存储内容损坏: " + raw, e);
        }
    }
}
