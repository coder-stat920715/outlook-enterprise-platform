package com.enterprise.outlook.mail.kafka;

import com.enterprise.outlook.common.model.*;
import com.enterprise.outlook.common.util.Json;
import com.enterprise.outlook.mail.service.MessageService;
import com.enterprise.outlook.mail.service.SearchService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Consumers. Offsets are committed only after the record was either processed OR durably handed to a retry tier,
 * so a crash can only cause redelivery (all handlers are idempotent), never loss.
 */
@Component
public class MailKafkaListeners {
    private static final Logger log = LoggerFactory.getLogger(MailKafkaListeners.class);
    private final MessageService mail;
    private final TieredRetryRouter router;
    private final SearchService search;

    public MailKafkaListeners(MessageService mail, TieredRetryRouter router, SearchService search) {
        this.mail = mail; this.router = router; this.search = search;
    }

    @KafkaListener(topics = Topics.OUTBOUND, groupId = "mail-outbound", concurrency = "${outlook.kafka.concurrency:3}")
    void outbound(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        handleOutbound(rec.value(), rec.key(), 0, Instant.now());
        ack.acknowledge();
    }

    @KafkaListener(topics = Topics.INBOUND, groupId = "mail-inbound", concurrency = "${outlook.kafka.concurrency:3}")
    void inbound(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        handleInbound(rec.value(), rec.key(), 0, Instant.now());
        ack.acknowledge();
    }

    @KafkaListener(topics = {Topics.RETRY_TIER1, Topics.RETRY_TIER2}, groupId = "mail-retry")
    void retry(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        RetryEnvelope r = Json.read(rec.value(), RetryEnvelope.class);
        Duration wait = Duration.between(Instant.now(), r.notBefore());
        if (!wait.isNegative() && !wait.isZero()) {                       // not due yet: pause consumption, redeliver later
            ack.nack(wait.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : wait);
            return;
        }
        if (r.kind() == RetryEnvelope.Kind.OUTBOUND) handleOutbound(r.payload(), rec.key(), r.attempt(), r.firstAttemptAt());
        else handleInbound(r.payload(), rec.key(), r.attempt(), r.firstAttemptAt());
        ack.acknowledge();
    }

    @KafkaListener(topics = Topics.SEARCH_INDEXER, groupId = "mail-search-indexer")
    void index(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        try { search.index(rec.value()); } catch (Exception e) { log.warn("indexing failed (search is eventually consistent): {}", e.toString()); }
        ack.acknowledge();
    }

    private void handleOutbound(String payload, String key, int attempt, Instant firstAttempt) {
        MailEnvelope env = Json.read(payload, MailEnvelope.class);
        try {
            DeliveryOutcome o = mail.processOutbound(env);
            log.debug("outbound {} {}", env.messageId(), DeliveryOutcome.describe(o));
        } catch (Exception e) {
            mail.markDeferred(env);
            log.warn("outbound {} deferred: {}", env.messageId(), DeliveryOutcome.describe(
                    router.route(RetryEnvelope.Kind.OUTBOUND, payload, key, attempt, firstAttempt, e)));
        }
    }

    private void handleInbound(String payload, String key, int attempt, Instant firstAttempt) {
        try {
            mail.processInbound(Json.read(payload, InboundDelivery.class));
        } catch (Exception e) {
            log.warn("inbound deferred: {}", DeliveryOutcome.describe(router.route(RetryEnvelope.Kind.INBOUND, payload, key, attempt, firstAttempt, e)));
        }
    }
}
