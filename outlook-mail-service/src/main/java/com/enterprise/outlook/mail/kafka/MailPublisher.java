package com.enterprise.outlook.mail.kafka;

import com.enterprise.outlook.common.model.InboundDelivery;
import com.enterprise.outlook.common.model.MailEnvelope;
import com.enterprise.outlook.common.model.RetryEnvelope;
import com.enterprise.outlook.common.model.SyncEvent;
import com.enterprise.outlook.common.model.Topics;
import com.enterprise.outlook.common.resilience.DependencyUnavailableException;
import com.enterprise.outlook.common.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** All Kafka production. Durable topics are published synchronously (acks=all, idempotent producer): a 202 means the mail is safe. */
@Component
public class MailPublisher {
    private static final Logger log = LoggerFactory.getLogger(MailPublisher.class);
    private final KafkaTemplate<String, String> kafka;

    public MailPublisher(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    public static String key(UUID tenant, UUID mailbox) { return tenant + ":" + mailbox; }

    private void sendDurable(String topic, String key, String value) {
        try {
            kafka.send(topic, key, value).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException("kafka", e);
        } catch (Exception e) {
            throw new DependencyUnavailableException("kafka", e);
        }
    }

    public void outbound(MailEnvelope env) { sendDurable(Topics.OUTBOUND, key(env.tenantId(), env.senderMailboxId()), Json.write(env)); }

    public void inbound(InboundDelivery d) { sendDurable(Topics.INBOUND, key(d.envelope().tenantId(), d.recipientMailboxId()), Json.write(d)); }

    public void retry(String topic, String key, RetryEnvelope env) { sendDurable(topic, key, Json.write(env)); }

    public void dlq(String key, RetryEnvelope env) { sendDurable(Topics.DLQ, key, Json.write(env)); }

    /** Push / search events are best-effort: they are derivable from the delta log, so never block mail flow on them. */
    public void syncEvent(SyncEvent e) {
        kafka.send(Topics.SYNC_EVENTS, key(e.tenantId(), e.mailboxId()), Json.write(e))
                .whenComplete((r, ex) -> { if (ex != null) log.warn("sync.events publish failed: {}", ex.toString()); });
    }

    public void searchDoc(UUID tenant, UUID mailbox, String json) {
        kafka.send(Topics.SEARCH_INDEXER, key(tenant, mailbox), json)
                .whenComplete((r, ex) -> { if (ex != null) log.warn("search.indexer publish failed: {}", ex.toString()); });
    }
}
