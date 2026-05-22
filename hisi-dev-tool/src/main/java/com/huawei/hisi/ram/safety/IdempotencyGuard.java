package com.huawei.hisi.ram.safety;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * In-process duplicate-suppression guard. Backed by a bounded LRU map
 * ({@code Collections.synchronizedMap(LinkedHashMap access-order)} with a
 * cap of {@value #MAX_ENTRIES}) so long-running processes cannot leak
 * unbounded memory.
 *
 * <p>Two contracts are exposed:
 * <ul>
 *   <li>{@link #tryClaim(String, String)} — boolean first-claim semantics
 *       used to prevent duplicate event append per {@code (sid, payloadHash)};</li>
 *   <li>{@link #executeOnce(String, Supplier)} — memoization wrapper that
 *       caches and returns the supplier's result.</li>
 * </ul>
 *
 * <p>Callers must not recursively invoke methods on this guard with the same
 * key from within a supplied work block.
 */
@Component
public class IdempotencyGuard {

    /** Maximum number of cached entries before LRU eviction. */
    public static final int MAX_ENTRIES = 10_000;

    private static final Object CLAIMED = new Object();

    private final Map<String, Object> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Object>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * Claim a {@code (sid, payloadHash)} pair. Returns {@code true} the first
     * time the pair is seen, {@code false} for every subsequent call. Thread-safe.
     */
    public boolean tryClaim(String sid, String payloadHash) {
        Objects.requireNonNull(sid, "sid");
        Objects.requireNonNull(payloadHash, "payloadHash");
        String key = sid + "\0" + payloadHash;
        synchronized (cache) {
            if (cache.containsKey(key)) {
                // touch for LRU recency
                cache.get(key);
                return false;
            }
            cache.put(key, CLAIMED);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T executeOnce(String key, Supplier<T> work) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(work, "work");
        synchronized (cache) {
            Object existing = cache.get(key);
            if (existing != null && existing != CLAIMED) {
                return (T) existing;
            }
        }
        // Compute outside the lock to avoid holding it during user work.
        T value = work.get();
        synchronized (cache) {
            Object existing = cache.get(key);
            if (existing != null && existing != CLAIMED) {
                return (T) existing;
            }
            cache.put(key, value);
            return value;
        }
    }

    public void invalidate(String key) {
        synchronized (cache) {
            cache.remove(key);
        }
    }
}
