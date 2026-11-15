package com.enterprise.outlook.common.model;

import java.util.EnumSet;
import java.util.Set;

/** Email delivery lifecycle: DRAFT -> QUEUED -> SENDING -> DELIVERED | DEFERRED_RETRY -> (SENDING | DLQ). */
public enum DeliveryState {
    DRAFT, QUEUED, SENDING, DELIVERED, DEFERRED_RETRY, DLQ;

    public Set<DeliveryState> allowedNext() {
        return switch (this) {
            case DRAFT -> EnumSet.of(QUEUED);
            case QUEUED -> EnumSet.of(SENDING, DEFERRED_RETRY);
            case SENDING -> EnumSet.of(DELIVERED, DEFERRED_RETRY);
            case DEFERRED_RETRY -> EnumSet.of(SENDING, DEFERRED_RETRY, DLQ);
            case DELIVERED, DLQ -> EnumSet.noneOf(DeliveryState.class);
        };
    }

    public boolean canTransitionTo(DeliveryState next) { return allowedNext().contains(next); }

    public DeliveryState transition(DeliveryState next) {
        if (!canTransitionTo(next)) throw new IllegalStateException("Illegal delivery transition " + this + " -> " + next);
        return next;
    }
}
