package com.enterprise.outlook.common.sharding;

/** Thread-bound shard key read by TenantMailboxShardRouter (virtual-thread safe). */
public final class ShardContextHolder {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private ShardContextHolder() {}

    public static String current() { return CURRENT.get(); }

    public static Binding bind(String shard) {
        String previous = CURRENT.get();
        CURRENT.set(shard);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    @FunctionalInterface public interface Binding extends AutoCloseable { @Override void close(); }
}
