package com.enterprise.outlook.common.model;

import java.time.Duration;

/** Sealed result of a delivery attempt – consumed with pattern-matching switches. */
public sealed interface DeliveryOutcome permits DeliveryOutcome.Delivered, DeliveryOutcome.Deferred, DeliveryOutcome.DeadLettered {
    record Delivered(int recipients) implements DeliveryOutcome {}
    record Deferred(int attempt, Duration retryIn, String reason) implements DeliveryOutcome {}
    record DeadLettered(String reason) implements DeliveryOutcome {}

    static String describe(DeliveryOutcome o) {
        return switch (o) {
            case Delivered d -> "delivered to " + d.recipients();
            case Deferred d -> "deferred attempt=" + d.attempt() + " in " + d.retryIn().toSeconds() + "s: " + d.reason();
            case DeadLettered d -> "dead-lettered: " + d.reason();
        };
    }
}
