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
 * <h3>原子消费的取舍</h3>
 * <p>{@code tryConsume} 用 Lua 脚本(GET 命中才 DEL 并返回 1)实现原子防重放:Redis
 * 单线程执行脚本,GET+DEL 之间不会插入其它命令,并发消费同一挑战恰好一次成功。
 * 不用 GETDEL 是因为它要求 Redis &gt;= 6.2,Lua 脚本兼容面更广(5.x 即可)。
 * 消费成功即 DEL 整个 key,不保留消费标记,因此 SPI 的 ttlMs 参数在此无需使用;
 * 过期语义由 save 时写入的 TTL 交给 Redis 管理,应用崩溃、无人消费的 key 也会被
 * Redis 自动回收,存储不会无界增长。
 *
 * <p>本类不绑定 Spring 上下文,自动装配见 {@link RedisPowAutoConfiguration}。
 *
 * @author created by lucky845
 */
public class RedisPowStorage implements PowStorage {

    /** key 前缀,完整 key 为 {@code easy-pow:challenge:{challenge}}。 */
    public static final String DEFAULT_PREFIX = "easy-pow:challenge:";

    /** 序列化格式版本。 */
    private static final String VERSION = "v1";

    /**
     * 原子消费脚本:GET 命中即 DEL 并返回 1,未命中(不存在或已过期被 Redis 回收)返回 0。
     */
    static final String CONSUME_LUA = "local v = redis.call('GET', KEYS[1]) "
            + "if not v then return 0 end "
            + "redis.call('DEL', KEYS[1]) "
            + "return 1";

    private static final RedisScript<Long> CONSUME_SCRIPT = RedisScript.of(CONSUME_LUA, Long.class);

    private final StringRedisTemplate redis;

    private final Clock clock;

    public RedisPowStorage(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    /** 测试可注入固定 Clock,使 TTL 计算可断言。 */
    public RedisPowStorage(StringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "StringRedisTemplate 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
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
        return raw == null ? null : decode(raw);
    }

    /**
     * 原子消费:Lua 脚本内 GET+DEL 不可分割,并发调用同一挑战恰好一次返回 true。
     * 消费成功即删除 key,无需 ttlMs 兜底标记。
     */
    @Override
    public boolean tryConsume(String challengeKey, long ttlMs) { // ttlMs 为 SPI 预留参数:消费即 DEL,无需保留标记
        if (challengeKey == null || challengeKey.isBlank()) {
            return false;
        }
        Long consumed = redis.execute(CONSUME_SCRIPT, List.of(keyOf(challengeKey)));
        return Long.valueOf(1L).equals(consumed);
    }

    private static String keyOf(String challengeKey) {
        return DEFAULT_PREFIX + challengeKey;
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
