package com.huawei.hisi.ram.safety;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-process result memoization guard. Ensures that a supplier keyed by an
 * idempotency token executes at most once per process lifetime.
 *
 * <p>Backed by {@link ConcurrentHashMap#computeIfAbsent}; callers must not
 * recursively invoke methods on this guard with the same key from within
 * the supplied work, otherwise the underlying map will deadlock.
 */
@Component
public class IdempotencyGuard {

    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T executeOnce(String key, Supplier<T> work) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(work, "work");
        return (T) cache.computeIfAbsent(key, k -> work.get());
    }

    public void invalidate(String key) {
        cache.remove(key);
    }
}
