package io.github.lucky845.basic.pow;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机 Redis 端到端冒烟:由 embedded-redis 拉起真实 redis-server(内置各平台二进制,无需本机安装),
 * 经 Lettuce 走真实 RESP 协议验证 {@link RedisPowStorage} 的 save / find / tryConsume 与消费标记语义。
 *
 * <p>与 {@code RedisPowStorageTest}(mock 脚本参数)互补:这里证的是"脚本在真 Redis 上真的这么跑"——
 * Lua 原子性、PX 标记 TTL、以及消费后重放仍能被识别为 REUSED。
 *
 * <p>环境不支持启动内嵌 redis(无二进制/无法执行)时以 JUnit 假设跳过,不把构建搞红。
 *
 * @author created by lucky845
 */
class RedisPowStorageRealRedisTest {

    /** 挑战 key 前缀(与 RedisPowStorage 内部一致,README 已公开该 key 结构)。 */
    private static final String CHALLENGE_PREFIX = "easy-pow:challenge:";

    private static final String SECRET = "real-redis-smoke-secret-0123456789abcdefghijklmn";

    private static RedisServer server;

    private static LettuceConnectionFactory connectionFactory;

    private static RedisPowStorage storage;

    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRealRedis() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        try {
            server = RedisServer.newRedisServer().port(port).setting("maxmemory 64M").build();
            server.start();
            connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
            connectionFactory.afterPropertiesSet();
            redis = new StringRedisTemplate(connectionFactory);
            redis.afterPropertiesSet();
        } catch (IOException | RuntimeException e) {
            Assumptions.abort("内嵌 redis 启动失败,跳过真机冒烟: " + e);
        }
        assertTrue(server.isActive(), "真机 redis 未就绪");
        storage = new RedisPowStorage(redis, Clock.systemUTC());
    }

    @AfterAll
    static void stopRealRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (server != null) {
            try {
                server.stop();
            } catch (IOException ignored) {
                // 进程已退出时 stop 抛错不影响断言结果
            }
        }
    }

    @BeforeEach
    void resetKeys() {
        redis.delete(List.of(CHALLENGE_PREFIX + key(), RedisPowStorage.CONSUMED_PREFIX + key()));
    }

    /** 每个用例独享的 challenge 名,避免用例间互相污染。 */
    private static String key() {
        return "smoke";
    }

    @Test
    @DisplayName("真机 Redis:save 写入的挑战记录带 TTL,find 读回同一份记录")
    void saveAndFindOnRealRedis() {
        long expiresAtMs = System.currentTimeMillis() + 60_000L;
        storage.save(key(), expiresAtMs, 5_000L);

        assertTrue(redis.getExpire(CHALLENGE_PREFIX + key()) > 0, "挑战 key 应由 Redis 托管 TTL");
        PowStorage.ChallengeRecord record = storage.find(key());
        assertNotNull(record, "find 应读回签发记录");
        assertEquals(expiresAtMs, record.expiresAtMs());
        assertEquals(5_000L, record.maxnumber());
        assertTrue(redis.opsForValue().get(CHALLENGE_PREFIX + key()).startsWith("v1;"),
                "存储格式应为自描述文本 v1;<expiresAtMs>;<maxnumber>");
    }

    @Test
    @DisplayName("真机 Redis:并发消费同一挑战恰好一次成功(脚本原子性)")
    void concurrentConsumeExactlyOnce() throws InterruptedException {
        storage.save(key(), System.currentTimeMillis() + 60_000L, 5_000L);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await(5, TimeUnit.SECONDS);
                        if (storage.tryConsume(key(), 5_000L)) {
                            wins.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "并发线程未就绪");
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "并发消费未在预期时间内结束");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "并发消费同一挑战只能有一次成功");
    }

    @Test
    @DisplayName("真机 Redis:消费后标记窗口内 find 仍读得到记录(重放可观测)")
    void consumedRecordStaysVisibleWithinMarkerWindow() {
        storage.save(key(), System.currentTimeMillis() + 60_000L, 5_000L);
        assertTrue(storage.tryConsume(key(), 5_000L), "首次消费应成功");

        Long markerTtl = redis.getExpire(RedisPowStorage.CONSUMED_PREFIX + key());
        assertNotNull(markerTtl, "消费标记 key 应存在");
        assertTrue(markerTtl > 0 && markerTtl <= 5L, "消费标记 TTL 应取自 SPI ttlMs,实际=" + markerTtl);

        assertNull(redis.opsForValue().get(CHALLENGE_PREFIX + key()), "主 key 应已被消费删除");
        assertNotNull(storage.find(key()), "标记窗口内 find 应回退读消费标记,重放才能判为 REUSED");
    }

    @Test
    @DisplayName("真机 Redis:标记到期后 find 返回 null,重放退化为 EXPIRED")
    void consumedMarkerExpiryDegradesToMissing() throws InterruptedException {
        storage.save(key(), System.currentTimeMillis() + 60_000L, 5_000L);
        assertTrue(storage.tryConsume(key(), 150L));
        assertNotNull(storage.find(key()));

        Thread.sleep(500L);

        assertNull(storage.find(key()), "标记到期后记录应不可见(退化为 EXPIRED)");
    }

    @Test
    @DisplayName("真机 Redis:issue -> solve -> verify 全链路 PASSED,重复提交 REUSED")
    void fullVerificationChainOnRealRedis() throws NoSuchAlgorithmException {
        PowConfig config = new PowConfig(SECRET);
        PowChallengeIssuer issuer = new PowChallengeIssuer(config, storage);
        PowChallengeVerifier verifier = new PowChallengeVerifier(config, storage);

        PowChallenge challenge = issuer.issue(2_000L, 120_000L);
        String payload = solutionJson(challenge, solve(challenge));

        assertEquals(PowStatus.PASSED, verifier.verify(payload).status(), "首次校验应通过");
        assertEquals(PowStatus.REUSED, verifier.verify(payload).status(), "同一 payload 再提交应为 REUSED,而非 EXPIRED");
    }

    /** 暴力解出 number(maxnumber 很小,测试内可控)。 */
    private static long solve(PowChallenge challenge) throws NoSuchAlgorithmException {
        for (long n = 0; n <= challenge.maxnumber(); n++) {
            if (sha256Hex(challenge.salt() + n).equals(challenge.challenge())) {
                return n;
            }
        }
        throw new AssertionError("no solution within maxnumber");
    }

    private static String solutionJson(PowChallenge challenge, long number) {
        return "{\"algorithm\":\"" + challenge.algorithm() + "\",\"challenge\":\"" + challenge.challenge()
                + "\",\"number\":" + number + ",\"salt\":\"" + challenge.salt()
                + "\",\"signature\":\"" + challenge.signature() + "\",\"took\":42}";
    }

    private static String sha256Hex(String input) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
