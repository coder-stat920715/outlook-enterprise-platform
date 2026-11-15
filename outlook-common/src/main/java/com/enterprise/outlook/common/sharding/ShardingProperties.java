package com.enterprise.outlook.common.sharding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Shard i lives on hosts[i * hosts.size() / shardCount] in database shard_ii.
 * The unsharded control-plane database is "directory" on hosts[0].
 */
@ConfigurationProperties(prefix = "outlook.sharding")
public record ShardingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("16") int shardCount,
        @DefaultValue("256") int virtualNodes,
        @DefaultValue("localhost") List<String> hosts,
        @DefaultValue("5432") int port,
        @DefaultValue("outlook") String username,
        @DefaultValue("outlook") String password,
        @DefaultValue("directory") String directoryDatabase,
        @DefaultValue("8") int maxPoolSizePerShard,
        @DefaultValue("false") boolean migrateOnStartup) {

    public String hostFor(int shardIndex) { return hosts.get(shardIndex * hosts.size() / shardCount); }
    public String shardUrl(int i) { return "jdbc:postgresql://%s:%d/shard_%02d".formatted(hostFor(i), port, i); }
    public String directoryUrl() { return "jdbc:postgresql://%s:%d/%s".formatted(hosts.get(0), port, directoryDatabase); }
}
