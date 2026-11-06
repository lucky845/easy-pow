package io.github.lucky845.basic.pow;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认存储:ConcurrentHashMap + 懒清理。条目存活不超过 expiresAt(写入达到阈值时顺带清理
 * 过期项),存储量上界为"一个 TTL 窗口内的签发量 + 阈值",不会无界增长。
 * 线程安全,仅适用单机;分布式请实现 {@link PowStorage} 接入 Redis 等共享存储。
 */
public final class InMemoryPowStorage implements PowStorage {

    /** 触发懒清理的条目数阈值。 */
    static final int COMPACT_THRESHOLD = 4096;

    private record Entry(long expiresAtMs, long maxnumber, AtomicBoolean consumed) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void save(String challengeKey, long expiresAtMs, long maxnumber) {
        if (entries.size() >= COMPACT_THRESHOLD) {
            sweep(System.currentTimeMillis());
        }
        entries.put(challengeKey, new Entry(expiresAtMs, maxnumber, new AtomicBoolean(false)));
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
