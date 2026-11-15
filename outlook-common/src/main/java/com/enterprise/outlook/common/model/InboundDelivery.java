package com.enterprise.outlook.common.model;

import java.util.UUID;

/** One recipient-mailbox delivery of an envelope (mail.inbound topic). */
public record InboundDelivery(MailEnvelope envelope, UUID recipientMailboxId, String recipientEmail) {}
