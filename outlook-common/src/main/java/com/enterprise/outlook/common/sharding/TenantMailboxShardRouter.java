package com.enterprise.outlook.common.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/** Routes every JDBC connection to the logical shard bound for (tenant_id, mailbox_id). */
public class TenantMailboxShardRouter extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        String shard = ShardContextHolder.current();
        if (shard == null) throw new IllegalStateException("No shard bound: wrap the call in ShardExecutor");
        return shard;
    }
}
