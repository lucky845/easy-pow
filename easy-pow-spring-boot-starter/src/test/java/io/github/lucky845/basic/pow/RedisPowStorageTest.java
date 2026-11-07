package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Redis PoW 存储的序列化与读写语义测试。
 *
 * <p><b>未连真实 Redis,行为经 mock 佐证</b>:序列化直接测 {@code encode}/{@code decode}
 * 往返;读写与 Lua 消费用 Mockito 模拟 {@link StringRedisTemplate} 验证参数与分支。
 * Lua 脚本的原子性由 Redis 单线程执行模型保证,本测试只验证调用形态与 true/false 分支。
 *
 * @author created by lucky845
 */
@DisplayName("Redis PoW 存储(未连真实 Redis,行为经 mock 佐证)")
class RedisPowStorageTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final RedisPowStorage storage = new RedisPowStorage(redis, CLOCK);

    @BeforeEach
    void setUp() {
        doReturn(valueOps).when(redis).opsForValue();
    }

    @Test
    @DisplayName("save 以 SET(key, value, EX) 写入,TTL 毫秒向上取整成秒")
    void saveSetsKeyWithCeiledSecondTtl() {
        // delta = 2500ms → ceil 3s
        storage.save("abc", 1_002_500L, 1_000_000L);
        verify(valueOps).set("easy-pow:challenge:abc", "v1;1002500;1000000", Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("save 的 TTL 至少 1 秒(临界与已过期写入)")
    void saveEnforcesMinimumOneSecondTtl() {
        storage.save("abc", 1_000_000L, 1_000_000L); // delta = 0
        verify(valueOps).set("easy-pow:challenge:abc", "v1;1000000;1000000", Duration.ofSeconds(1));

        storage.save("abc", 999_000L, 1_000_000L); // delta = -1000ms,已过期
        verify(valueOps).set("easy-pow:challenge:abc", "v1;999000;1000000", Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("find 反序列化回 ChallengeRecord,GET 未命中返回 null")
    void findDeserializesRecord() {
        when(valueOps.get("easy-pow:challenge:abc")).thenReturn("v1;1002500;1000000");
        PowStorage.ChallengeRecord record = storage.find("abc");
        assertNotNull(record);
        assertEquals(1_002_500L, record.expiresAtMs());
        assertEquals(1_000_000L, record.maxnumber());

        when(valueOps.get("easy-pow:challenge:missing")).thenReturn(null);
        assertNull(storage.find("missing"));
    }

    @Test
    @DisplayName("find 空白 key 直接返回 null,不触碰 Redis")
    void findBlankKeyShortCircuits() {
        assertNull(storage.find(null));
        assertNull(storage.find(" "));
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("tryConsume 经 Lua 脚本原子消费,命中返回 true 且删除的是完整 key")
    void tryConsumeHitsLuaScriptAndReturnsTrue() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        assertTrue(storage.tryConsume("abc", 120_000L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass((Class) List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> ttlArg = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), ttlArg.capture());
        assertEquals(List.of("easy-pow:challenge:abc", "easy-pow:consumed:abc"), keys.getValue());
        assertEquals("120000", ttlArg.getValue());
    }

    @Test
    @DisplayName("tryConsume 未命中(0L)或空返回(null)均为 false")
    void tryConsumeMissReturnsFalse() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);
        assertFalse(storage.tryConsume("abc", 120_000L));

        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);
        assertFalse(storage.tryConsume("abc", 120_000L));
    }

    @Test
    @DisplayName("tryConsume 空白 key 直接返回 false,不触碰 Redis")
    void tryConsumeBlankKeyShortCircuits() {
        assertFalse(storage.tryConsume(null, 120_000L));
        assertFalse(storage.tryConsume(" ", 120_000L));
        verifyNoInteractions(valueOps);
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("encode/decode 往返无损")
    void encodeDecodeRoundtrip() {
        PowStorage.ChallengeRecord record = RedisPowStorage.decode(RedisPowStorage.encode(123L, 456L));
        assertEquals(123L, record.expiresAtMs());
        assertEquals(456L, record.maxnumber());
    }

    @Test
    @DisplayName("损坏或未知版本的内容抛 IllegalStateException,不静默错读")
    void decodeRejectsCorruptValue() {
        assertThrows(IllegalStateException.class, () -> RedisPowStorage.decode("v9;1;2"));
        assertThrows(IllegalStateException.class, () -> RedisPowStorage.decode("v1;1"));
        assertThrows(IllegalStateException.class, () -> RedisPowStorage.decode("v1;x;2"));
        assertThrows(IllegalStateException.class, () -> RedisPowStorage.decode(""));
    }

    @Test
    @DisplayName("find 主 key 未命中时回退读消费标记 key(重放可观测)")
    void findFallsBackToConsumedMark() {
        when(valueOps.get("easy-pow:challenge:abc")).thenReturn(null);
        when(valueOps.get("easy-pow:consumed:abc")).thenReturn("v1;1002500;1000000");

        PowStorage.ChallengeRecord record = storage.find("abc");

        assertNotNull(record);
        assertEquals(1_002_500L, record.expiresAtMs());
        assertEquals(1_000_000L, record.maxnumber());
    }

    @Test
    @DisplayName("消费脚本契约:写消费标记 key 且按 ARGV[1] 毫秒过期")
    void consumeScriptWritesConsumedMark() {
        assertTrue(RedisPowStorage.CONSUME_LUA.contains("redis.call('SET', KEYS[2], v, 'PX', ARGV[1])"),
                "脚本须把记录写入 KEYS[2] 消费标记并按 ARGV[1] 毫秒保留");
        assertTrue(RedisPowStorage.CONSUME_LUA.contains("redis.call('DEL', KEYS[1])"));
        assertEquals("easy-pow:consumed:", RedisPowStorage.CONSUMED_PREFIX);
    }
}
