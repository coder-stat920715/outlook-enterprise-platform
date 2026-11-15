package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.common.sharding.ShardingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** Pre-creates the next 3 monthly partitions (+hash sub-partitions) on every shard so inserts never hit a missing partition. */
@Component
public class PartitionMaintenanceJob {
    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private final ShardExecutor shards;
    private final NamedParameterJdbcTemplate jdbc;
    private final ShardingProperties props;

    public PartitionMaintenanceJob(ShardExecutor shards, @Qualifier("shardJdbc") NamedParameterJdbcTemplate jdbc, ShardingProperties props) {
        this.shards = shards; this.jdbc = jdbc; this.props = props;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 6 * 3600 * 1000L)
    public void ensurePartitions() {
        LocalDate first = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        for (String shard : shards.resolver().shardNames()) {
            try {
                shards.onShard(shard, () -> {
                    for (int i = 0; i <= 3; i++) for (String parent : new String[]{"mail_messages", "mail_audit_events"}) {
                        jdbc.query("SELECT create_monthly_partition(CAST(:p AS regclass), CAST(:d AS date), 4)",
                                new MapSqlParameterSource().addValue("p", parent).addValue("d", first.plusMonths(i).toString()),
                                (RowCallbackHandler) rs -> { });
                    }
                    return null;
                }, false);
            } catch (RuntimeException e) { log.warn("partition maintenance skipped for {}: {}", shard, e.toString()); }
        }
    }
}
