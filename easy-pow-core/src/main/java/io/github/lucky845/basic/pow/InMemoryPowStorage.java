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

    private record Entry(long expiresAtMs, long maxnumber, AtomicBoolean consumed) {
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
            entries.put(challengeKey, new Entry(expiresAtMs, maxnumber, new AtomicBoolean(false)));
        }
    }

    @Override
    public ChallengeRecord find(String challengeKey) {
        Entry entry = entries.get(challengeKey);
        return entry == null ? null : new ChallengeRecord(entry.expiresAtMs(), entry.maxnumber());
    }

    @Override
    public boolean tryConsume(String challengeKey, long ttlMs) { // ttlMs 为 SPI 预留参数,内存实现不使用
        Entry entry = entries.get(challengeKey);
        if (entry == null || entry.expiresAtMs() <= System.currentTimeMillis()) {
            return false; // 不存在或已过期
        }
        return entry.consumed().compareAndSet(false, true);
    }

    private void sweep(long now) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs() <= now) {
                it.remove();
            }
        }
    }
}
