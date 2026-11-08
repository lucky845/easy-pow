package io.github.lucky845.basic.pow;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认存储:ConcurrentHashMap + 懒清理。条目存活不超过 expiresAt(写入达到阈值时顺带清理
 * 过期项)。默认不限量;可通过构造器设置 maxPendingChallenges 上限,超限拒绝签发,避免耗尽攻击。
 * 线程安全,仅适用单机;分布式请实现 {@link PowStorage} 接入 Redis 等共享存储。
 */
public final class InMemoryPowStorage implements PowStorage {

    /** 触发懒清理的条目数阈值。 */
    static final int COMPACT_THRESHOLD = 4096;

    /**
     * @param expiresAtMs  挑战有效期
     * @param maxnumber    难度上限
     * @param consumed     是否已消费
     * @param consumedAtMs  消费时刻;未消费为 {@link Long#MIN_VALUE}。消费后条目在
     *                      {@code consumedAtMs + consumedTtlMs} 之前仍可被 {@link #find} 读回,
     *                      使重放被判为 {@link PowStatus#REUSED} 而不是 {@link PowStatus#EXPIRED}
     *                      ——与 {@code RedisPowStorage} 的消费标记语义一致;窗口结束后条目即不可见
     * @param consumedTtlMs 消费标记保留时长,来自 {@link #tryConsume} 的 ttlMs 入参
     */
    private record Entry(long expiresAtMs, long maxnumber, AtomicBoolean consumed,
                         long consumedAtMs, long consumedTtlMs) {

        /**
         * 条目可被保留到的时刻:未消费看挑战有效期;已消费只看到消费标记到期——主 key 已等效删除,
         * 续留的只是消费标记(与 Redis 实现一致:消费后主 key 被删,只留 ttlMs 的标记 key)。
         */
        long retainUntilMs() {
            return consumedAtMs == Long.MIN_VALUE ? expiresAtMs : consumedAtMs + consumedTtlMs;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();
    /** 0 = 不限;> 0 时待处理挑战(含消费保留窗口内记录)达到该数量即拒绝新 key 签发。 */
    private final int maxPendingChallenges;

    /** 不限制待处理挑战数,行为与旧版本一致。 */
    public InMemoryPowStorage() {
        this(0);
    }

    /**
     * @param maxPendingChallenges 待处理挑战上限;0 = 不限,负数抛 {@link IllegalArgumentException}
     */
    public InMemoryPowStorage(int maxPendingChallenges) {
        if (maxPendingChallenges < 0) {
            throw new IllegalArgumentException("maxPendingChallenges 不能为负数: " + maxPendingChallenges);
        }
        this.maxPendingChallenges = maxPendingChallenges;
    }

    @Override
    public void save(String challengeKey, long expiresAtMs, long maxnumber) {
        synchronized (saveLock) {
            long now = System.currentTimeMillis();
            if (entries.size() >= COMPACT_THRESHOLD || maxPendingChallenges > 0) {
                sweep(now);
            }
            if (maxPendingChallenges > 0
                    && entries.size() >= maxPendingChallenges
                    && !entries.containsKey(challengeKey)) {
                throw new IllegalStateException("待处理挑战数达到上限 " + maxPendingChallenges + ",拒绝签发");
            }
            entries.put(challengeKey, new Entry(expiresAtMs, maxnumber, new AtomicBoolean(false), Long.MIN_VALUE, 0L));
        }
    }

    /** 已消费的条目在消费标记窗口内仍返回记录,窗口过后返回 null(与 Redis 消费标记一致)。 */
    @Override
    public ChallengeRecord find(String challengeKey) {
        Entry entry = entries.get(challengeKey);
        if (entry == null) {
            return null;
        }
        if (entry.consumedAtMs() != Long.MIN_VALUE && entry.retainUntilMs() <= System.currentTimeMillis()) {
            return null; // 消费标记已过期
        }
        return new ChallengeRecord(entry.expiresAtMs(), entry.maxnumber());
    }

    @Override
    public boolean tryConsume(String challengeKey, long ttlMs) {
        // 记录消费时刻,使 find 在 ttlMs 窗口内仍能读回条目,重放得以判为 REUSED。
        // 并发消费同一 key 时只有 CAS 成功的那次写入时刻,其余调用直接返回 false。
        long[] won = {0L};
        entries.computeIfPresent(challengeKey, (k, e) -> {
            if (e.expiresAtMs() <= System.currentTimeMillis()) {
                return e; // 已过期,保持原样让下面判定为失败
            }
            if (!e.consumed().compareAndSet(false, true)) {
                return e; // 已被消费
            }
            won[0] = System.currentTimeMillis();
            return new Entry(e.expiresAtMs(), e.maxnumber(), e.consumed(), won[0], ttlMs);
        });
        return won[0] != 0L;
    }

    private void sweep(long now) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().retainUntilMs() <= now) {
                it.remove();
            }
        }
    }
}
