package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 存储的重放语义测试:按本实现声明的 key 协议(主 key + 消费标记 key)模拟 Redis,
 * 断言"消费后二次 verify 得到 REUSED",与 {@code InMemoryPowStorage} 保持一致。
 *
 * <p>本测试覆盖 Java 侧契约与校验链接线;真实 Lua 脚本在 Redis 上的行为由 Testcontainers
 * 集成测试(迭代路线 H 项)保障。
 */
class RedisPowReplayTest {

    private static final String SECRET = "unit-test-secret";

    private final Map<String, String> redisStore = new HashMap<>();

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final RedisPowStorage storage = new RedisPowStorage(redis, Clock.systemUTC());

    private final PowChallengeIssuer issuer = new PowChallengeIssuer(new PowConfig(SECRET), storage);

    private final PowChallengeVerifier verifier = new PowChallengeVerifier(new PowConfig(SECRET), storage);

    @BeforeEach
    void setUp() {
        doReturn(valueOps).when(redis).opsForValue();
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // 按声明的语义模拟 CONSUME_LUA:主 key 命中 → DEL 主 key + 写消费标记并返回 1;否则返回 0
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenAnswer(inv -> {
            List<?> keys = inv.getArgument(1);
            String mainKey = String.valueOf(keys.get(0));
            String markKey = String.valueOf(keys.get(1));
            String raw = redisStore.get(mainKey);
            if (raw == null) {
                return 0L;
            }
            redisStore.remove(mainKey);
            redisStore.put(markKey, raw);
            return 1L;
        });
    }

    @Test
    @DisplayName("消费后二次 verify 得到 REUSED 而非 EXPIRED(与内存实现语义一致)")
    void secondVerifyAfterConsumeIsReused() {
        PowChallenge challenge = issuer.issue(2000, 120_000);

        assertEquals(PowStatus.PASSED, verifier.verify(solutionJson(challenge)).status());
        assertEquals(PowStatus.REUSED, verifier.verify(solutionJson(challenge)).status());
    }

    @Test
    @DisplayName("消费标记到期被清理后,重放退化为 EXPIRED")
    void replayAfterMarkExpiryFallsBackToExpired() {
        PowChallenge challenge = issuer.issue(2000, 120_000);
        assertEquals(PowStatus.PASSED, verifier.verify(solutionJson(challenge)).status());

        redisStore.remove(RedisPowStorage.CONSUMED_PREFIX + challenge.challenge());

        assertEquals(PowStatus.EXPIRED, verifier.verify(solutionJson(challenge)).status());
    }

    /** 暴力解出 number(测试里 maxnumber 很小,毫秒级)并拼出 solution JSON。 */
    private static String solutionJson(PowChallenge challenge) {
        long number = -1;
        for (long n = 0; n <= challenge.maxnumber(); n++) {
            if (Hashes.sha256Hex(challenge.salt() + n).equals(challenge.challenge())) {
                number = n;
                break;
            }
        }
        if (number < 0) {
            throw new AssertionError("no solution within maxnumber");
        }
        return "{\"algorithm\":\"" + challenge.algorithm() + "\",\"challenge\":\"" + challenge.challenge()
                + "\",\"number\":" + number + ",\"salt\":\"" + challenge.salt()
                + "\",\"signature\":\"" + challenge.signature() + "\",\"took\":42}";
    }
}
