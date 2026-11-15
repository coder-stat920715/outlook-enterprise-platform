package com.enterprise.outlook.common.sharding;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Consistent hash ring with virtual nodes (FNV-1a 64 + murmur3 finalizer). */
public final class ConsistentHashRing {
    private final NavigableMap<Long, String> ring = new TreeMap<>();

    public ConsistentHashRing(List<String> nodes, int virtualNodes) {
        for (String n : nodes) for (int v = 0; v < virtualNodes; v++) ring.put(hash(n + "#" + v), n);
    }

    public String nodeFor(String key) {
        Map.Entry<Long, String> e = ring.ceilingEntry(hash(key));
        return (e != null ? e : ring.firstEntry()).getValue();
    }

    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) { h ^= (b & 0xff); h *= 0x100000001b3L; }
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return h;
    }
}
