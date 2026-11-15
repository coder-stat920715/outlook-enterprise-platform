package com.enterprise.outlook.mail.config;

import com.enterprise.outlook.common.model.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Durable, partitioned topics. Key = tenantId:mailboxId keeps per-mailbox ordering. replicas=1 for dev; use 3 + min.insync=2 in prod. */
@Configuration
public class KafkaTopicsConfig {
    private NewTopic t(String name, int partitions) { return TopicBuilder.name(name).partitions(partitions).replicas(1).build(); }

    @Bean NewTopic outbound() { return t(Topics.OUTBOUND, 12); }
    @Bean NewTopic inbound() { return t(Topics.INBOUND, 12); }
    @Bean NewTopic retryTier1() { return t(Topics.RETRY_TIER1, 6); }
    @Bean NewTopic retryTier2() { return t(Topics.RETRY_TIER2, 6); }
    @Bean NewTopic dlq() { return t(Topics.DLQ, 3); }
    @Bean NewTopic syncEvents() { return t(Topics.SYNC_EVENTS, 12); }
    @Bean NewTopic searchIndexer() { return t(Topics.SEARCH_INDEXER, 6); }
}
