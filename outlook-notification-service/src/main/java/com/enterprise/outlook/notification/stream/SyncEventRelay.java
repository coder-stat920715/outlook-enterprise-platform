package com.enterprise.outlook.notification.stream;

import com.enterprise.outlook.common.model.SyncEvent;
import com.enterprise.outlook.common.model.Topics;
import com.enterprise.outlook.common.util.Json;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Kafka (sync.events) -> Redis Pub/Sub (push:{tenant}:{mailbox}) -> every notification pod -> local SSE/WebSocket connections.
 * Using Pub/Sub as the fan-out hop means any pod can serve any mailbox's connection (no sticky routing).
 * Delivery is best-effort by design; clients converge through the delta endpoint.
 */
@Component
public class SyncEventRelay implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(SyncEventRelay.class);
    private final StringRedisTemplate redis;
    private final ConnectionRegistry registry;

    public SyncEventRelay(StringRedisTemplate redis, ConnectionRegistry registry, RedisMessageListenerContainer container) {
        this.redis = redis;
        this.registry = registry;
        container.addMessageListener(this, new PatternTopic("push:*"));
    }

    @KafkaListener(topics = Topics.SYNC_EVENTS, groupId = "notification-relay", concurrency = "3")
    void onSyncEvent(ConsumerRecord<String, String> rec) {
        try {
            SyncEvent e = Json.read(rec.value(), SyncEvent.class);
            redis.convertAndSend("push:" + e.tenantId() + ":" + e.mailboxId(), rec.value());
        } catch (Exception ex) { log.warn("relay failed (best effort): {}", ex.toString()); }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            SyncEvent e = Json.read(body, SyncEvent.class);
            registry.dispatch(channel.substring("push:".length()), Long.toString(e.changeSeq()), body);
        } catch (RuntimeException ex) { log.debug("bad push payload", ex); }
    }
}
