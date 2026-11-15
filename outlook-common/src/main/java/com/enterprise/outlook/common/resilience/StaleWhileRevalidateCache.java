package com.enterprise.outlook.common.resilience;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * L1 Caffeine + L2 (Redis) cache with stale-while-revalidate and stale-if-error semantics.
 *
 * Timeline of an entry:   [0, fresh)            serve directly
 *                         [fresh, fresh+swr)    serve stale, refresh in background (single-flight)
 *                         [.., staleIfError)    serve ONLY when the upstream (IdP / routing plane) fails
 * Nothing is ever served past {@code hardExpiry(value)} (e.g. the token's own exp).
 * This is mitigation #1 for the Nov 12-13 2026 outage: a degraded auth/routing control plane never blocks handshakes.
 */
public final class StaleWhileRevalidateCache<V> {

    public interface L2Store {
        CompletableFuture<String> get(String key);                       // completes with null when absent
        CompletableFuture<Void> put(String key, String value, Duration ttl);
    }

    public record Policy(Duration fresh, Duration staleWhileRevalidate, Duration staleIfError,
                         long maxSize, Duration upstreamTimeout) {}

    private record Entry<V>(V value, long freshUntil, long swrUntil, long errorUntil) {}

    private final String name;
    private final Policy policy;
    private final L2Store l2;
    private final Function<String, CompletableFuture<V>> loader;
    private final Function<V, String> serializer;
    private final Function<String, V> deserializer;
    private final ToLongFunction<V> hardExpiryMillis;
    private final Cache<String, Entry<V>> l1;
    private final ConcurrentHashMap<String, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong staleServed = new AtomicLong();
    private final AtomicLong upstreamFailures = new AtomicLong();

    public StaleWhileRevalidateCache(String name, Policy policy, L2Store l2,
                                     Function<String, CompletableFuture<V>> loader,
                                     Function<V, String> serializer, Function<String, V> deserializer,
                                     ToLongFunction<V> hardExpiryMillis) {
        this.name = name;
        this.policy = policy;
        this.l2 = l2;
        this.loader = loader;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.hardExpiryMillis = hardExpiryMillis;
        this.l1 = Caffeine.newBuilder().maximumSize(policy.maxSize()).build();
    }

    public CompletableFuture<V> get(String key) {
        long now = System.currentTimeMillis();
        Entry<V> e = l1.getIfPresent(key);
        if (e != null) {
            if (now < e.freshUntil()) return CompletableFuture.completedFuture(e.value());
            if (now < e.swrUntil()) {
                staleServed.incrementAndGet();
                refreshAsync(key);
                return CompletableFuture.completedFuture(e.value());
            }
        }
        return viaL2(key, e);
    }

    private CompletableFuture<V> viaL2(String key, Entry<V> l1Entry) {
        if (l2 == null) return loadOrFallback(key, l1Entry);
        CompletableFuture<String> raw;
        try { raw = l2.get(key); } catch (RuntimeException ex) { raw = CompletableFuture.completedFuture(null); }
        return raw.handle((s, err) -> err == null ? parse(s) : null).thenCompose(l2Entry -> {
            Entry<V> best = newest(l1Entry, l2Entry);
            long now = System.currentTimeMillis();
            if (best != null && now < best.freshUntil()) { l1.put(key, best); return CompletableFuture.completedFuture(best.value()); }
            if (best != null && now < best.swrUntil()) {
                l1.put(key, best); staleServed.incrementAndGet(); refreshAsync(key);
                return CompletableFuture.completedFuture(best.value());
            }
            return loadOrFallback(key, best);
        });
    }

    private CompletableFuture<V> loadOrFallback(String key, Entry<V> stale) {
        return singleFlight(key).handle((v, err) -> {
            if (err == null) return CompletableFuture.completedFuture(v);
            upstreamFailures.incrementAndGet();
            if (stale != null && System.currentTimeMillis() < stale.errorUntil()) {      // stale-if-error
                staleServed.incrementAndGet();
                return CompletableFuture.completedFuture(stale.value());
            }
            return CompletableFuture.<V>failedFuture(err);
        }).thenCompose(Function.identity());
    }

    private CompletableFuture<V> singleFlight(String key) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) return existing;
        try {
            loader.apply(key).orTimeout(policy.upstreamTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((v, err) -> {
                        inFlight.remove(key);
                        if (err == null) { store(key, v); mine.complete(v); } else mine.completeExceptionally(err);
                    });
        } catch (RuntimeException ex) {
            inFlight.remove(key);
            mine.completeExceptionally(ex);
        }
        return mine;
    }

    private void refreshAsync(String key) { singleFlight(key).exceptionally(t -> null); }

    private void store(String key, V v) {
        long now = System.currentTimeMillis();
        long hard = hardExpiryMillis == null ? Long.MAX_VALUE : hardExpiryMillis.applyAsLong(v);
        long fresh = Math.min(now + policy.fresh().toMillis(), hard);
        long swr = Math.min(now + policy.fresh().toMillis() + policy.staleWhileRevalidate().toMillis(), hard);
        long err = Math.min(now + policy.staleIfError().toMillis(), hard);
        Entry<V> entry = new Entry<>(v, fresh, swr, err);
        l1.put(key, entry);
        if (l2 != null && err > now) {
            try {
                l2.put(key, fresh + "|" + swr + "|" + err + "|" + serializer.apply(v), Duration.ofMillis(err - now))
                        .exceptionally(t -> null);
            } catch (RuntimeException ignored) { /* L2 is best effort */ }
        }
    }

    private Entry<V> parse(String raw) {
        if (raw == null) return null;
        try {
            String[] p = raw.split("\\|", 4);
            return new Entry<>(deserializer.apply(p[3]), Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]));
        } catch (RuntimeException e) { return null; }
    }

    private Entry<V> newest(Entry<V> a, Entry<V> b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.errorUntil() >= b.errorUntil() ? a : b;
    }

    public long staleServed() { return staleServed.get(); }
    public long upstreamFailures() { return upstreamFailures.get(); }
    public String name() { return name; }
}
