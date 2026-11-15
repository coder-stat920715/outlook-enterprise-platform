package com.enterprise.outlook.common.sharding;

import com.enterprise.outlook.common.resilience.JitterBackoff;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Applies db/shard to every logical shard and db/directory to the control-plane DB (Flyway advisory-locks, so replicas can race safely). */
public class SchemaMigrator {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
    private final ShardingProperties p;

    public SchemaMigrator(ShardingProperties p) { this.p = p; }

    public void migrateAll() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < p.shardCount(); i++) {
                String url = p.shardUrl(i);
                fs.add(pool.submit(() -> migrate(url, "classpath:db/shard")));
            }
            fs.add(pool.submit(() -> migrate(p.directoryUrl(), "classpath:db/directory")));
            for (Future<?> f : fs) f.get();
        }
    }

    private void migrate(String url, String location) {
        JitterBackoff.execute(12, Duration.ofMillis(500), Duration.ofSeconds(10), () -> {
            Flyway.configure().dataSource(url, p.username(), p.password()).locations(location)
                    .baselineOnMigrate(true).load().migrate();
            log.info("Schema up to date: {}", url);
            return null;
        }, t -> true);
    }

    /** Idempotently creates upcoming monthly partitions on all shards (also invoked by the maintenance scheduler). */
    public static final String ENSURE_PARTITION_SQL = "SELECT create_monthly_partition(CAST(:parent AS regclass), CAST(:month AS date), 4)";
}
