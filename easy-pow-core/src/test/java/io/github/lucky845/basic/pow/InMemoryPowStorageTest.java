package io.github.lucky845.basic.pow;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPowStorageTest {

    @Test
    void concurrentTryConsumeSucceedsExactlyOnce() throws Exception {
        InMemoryPowStorage storage = new InMemoryPowStorage();
        storage.save("challenge-key", System.currentTimeMillis() + 60_000, 1000);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (storage.tryConsume("challenge-key", 60_000)) {
                        successes.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
                return null;
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "所有线程应在超时前完成");
        pool.shutdownNow();
        assertEquals(1, successes.get(), "同一 key 并发消费必须恰好一次成功");
    }

    @Test
    void findAndConsumeBehaviour() {
        InMemoryPowStorage storage = new InMemoryPowStorage();
        assertNull(storage.find("missing"));
        assertFalse(storage.tryConsume("missing", 60_000));
        storage.save("k1", System.currentTimeMillis() + 60_000, 7777);
        PowStorage.ChallengeRecord record = storage.find("k1");
        assertNotNull(record);
        assertEquals(7777, record.maxnumber());
        assertTrue(storage.tryConsume("k1", 60_000)); // 首次消费成功
        assertFalse(storage.tryConsume("k1", 60_000)); // 重复消费被拒
        storage.save("k2", System.currentTimeMillis() - 1, 1000); // 已过期
        assertFalse(storage.tryConsume("k2", 60_000));
    }
}
