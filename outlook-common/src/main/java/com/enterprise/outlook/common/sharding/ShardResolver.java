package com.enterprise.outlook.common.sharding;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Maps (tenant_id, mailbox_id) -> logical shard name shard_00 .. shard_NN via consistent hashing. */
public final class ShardResolver {
    private final ConsistentHashRing ring;
    private final List<String> shardNames;

    public ShardResolver(int shardCount, int virtualNodes) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) names.add(String.format("shard_%02d", i));
        this.shardNames = List.copyOf(names);
        this.ring = new ConsistentHashRing(shardNames, virtualNodes);
    }

    public String resolve(UUID tenantId, UUID mailboxId) { return ring.nodeFor(tenantId + ":" + mailboxId); }
    public List<String> shardNames() { return shardNames; }
}
