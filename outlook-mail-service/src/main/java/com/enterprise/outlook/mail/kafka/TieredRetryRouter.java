package com.enterprise.outlook.mail.kafka;

import com.enterprise.outlook.common.model.DeliveryOutcome;
import com.enterprise.outlook.common.model.RetryEnvelope;
import com.enterprise.outlook.common.model.Topics;
import com.enterprise.outlook.common.resilience.JitterBackoff;
import com.enterprise.outlook.mail.config.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Store-and-forward policy (simulates Exchange's 48-hour retry queue):
 *   attempts 1-3   -> mail.retry.tier1  (full-jitter delay, base 15s, cap 2m)
 *   attempts 4..   -> mail.retry.tier2  (full-jitter delay, base 5m,  cap 30m)
 *   age > 48h      -> mail.dlq
 * Messages never leave Kafka until delivered, so a degraded shard / region means delay, never loss.
 */
@Component
public class TieredRetryRouter {
    private static final Logger log = LoggerFactory.getLogger(TieredRetryRouter.class);
    private final MailPublisher publisher;
    private final Duration maxAge;

    public TieredRetryRouter(MailPublisher publisher, MailProperties props) {
        this.publisher = publisher;
        this.maxAge = Duration.ofSeconds(props.maxDeferralSeconds());
    }

    public DeliveryOutcome route(RetryEnvelope.Kind kind, String payload, String key, int previousAttempt,
                                 Instant firstAttemptAt, Throwable error) {
        Instant now = Instant.now();
        int attempt = previousAttempt + 1;
        String reason = error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage();
        if (Duration.between(firstAttemptAt, now).compareTo(maxAge) > 0) {
            publisher.dlq(key, new RetryEnvelope(kind, payload, attempt, firstAttemptAt, now, reason));
            log.error("DLQ after {} attempts: {}", attempt, reason);
            return new DeliveryOutcome.DeadLettered(reason);
        }
        boolean tier1 = attempt <= 3;
        Duration delay = tier1 ? JitterBackoff.fullJitter(attempt, Duration.ofSeconds(15), Duration.ofMinutes(2))
                               : JitterBackoff.fullJitter(attempt - 3, Duration.ofMinutes(5), Duration.ofMinutes(30));
        publisher.retry(tier1 ? Topics.RETRY_TIER1 : Topics.RETRY_TIER2, key,
                new RetryEnvelope(kind, payload, attempt, firstAttemptAt, now.plus(delay), reason));
        return new DeliveryOutcome.Deferred(attempt, delay, reason);
    }
}
