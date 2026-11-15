package com.enterprise.outlook.common.sharding;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(ShardingProperties.class)
@ConditionalOnProperty(name = "outlook.sharding.enabled", havingValue = "true")
public class ShardedDataSourceConfig {

    private static HikariDataSource pool(String name, String url, ShardingProperties p, int size) {
        HikariConfig c = new HikariConfig();
        c.setPoolName(name);
        c.setJdbcUrl(url);
        c.setUsername(p.username());
        c.setPassword(p.password());
        c.setMaximumPoolSize(size);
        c.setMinimumIdle(0);
        c.setConnectionTimeout(2_000);              // fail fast: let the circuit breaker open instead of piling up threads
        c.setInitializationFailTimeout(-1);         // application boots even when a shard is down
        return new HikariDataSource(c);
    }

    @Bean
    ShardResolver shardResolver(ShardingProperties p) { return new ShardResolver(p.shardCount(), p.virtualNodes()); }

    @Bean(name = "shardRoutingDataSource")
    @Primary
    DataSource shardRoutingDataSource(ShardingProperties p) {
        TenantMailboxShardRouter router = new TenantMailboxShardRouter();
        Map<Object, Object> targets = new HashMap<>();
        for (int i = 0; i < p.shardCount(); i++) {
            String name = String.format("shard_%02d", i);
            targets.put(name, pool(name, p.shardUrl(i), p, p.maxPoolSizePerShard()));
        }
        router.setTargetDataSources(targets);
        router.setLenientFallback(false);
        router.afterPropertiesSet();
        return router;
    }

    @Bean(name = "directoryDataSource")
    DataSource directoryDataSource(ShardingProperties p) { return pool("directory", p.directoryUrl(), p, 10); }

    @Bean(name = "shardTransactionManager")
    @Primary
    PlatformTransactionManager shardTransactionManager(@Qualifier("shardRoutingDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean(name = "shardJdbc")
    NamedParameterJdbcTemplate shardJdbc(@Qualifier("shardRoutingDataSource") DataSource ds) { return new NamedParameterJdbcTemplate(ds); }

    @Bean(name = "directoryJdbc")
    NamedParameterJdbcTemplate directoryJdbc(@Qualifier("directoryDataSource") DataSource ds) { return new NamedParameterJdbcTemplate(ds); }

    @Bean
    ShardExecutor shardExecutor(ShardResolver resolver, @Qualifier("shardTransactionManager") PlatformTransactionManager tm,
                                CircuitBreakerRegistry registry, ShardingProperties p) {
        return new ShardExecutor(resolver, tm, registry, p.maxPoolSizePerShard());
    }

    @Bean
    SchemaMigrator schemaMigrator(ShardingProperties p) { return new SchemaMigrator(p); }

    @Bean
    ApplicationRunner migrateOnStartup(SchemaMigrator migrator, ShardingProperties p) {
        return args -> { if (p.migrateOnStartup()) migrator.migrateAll(); };
    }
}
