package com.enterprise.outlook.calendar.service;

import com.enterprise.outlook.calendar.domain.IcsBuilder;
import com.enterprise.outlook.calendar.domain.Models.*;
import com.enterprise.outlook.common.model.MailEnvelope;
import com.enterprise.outlook.common.model.Region;
import com.enterprise.outlook.common.model.Topics;
import com.enterprise.outlook.common.util.Json;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Publishes meeting invitations / cancellations / replies as e-mails (iCalendar body) on mail.outbound. */
@Component
public class MeetingMailer {
    private final KafkaTemplate<String, String> kafka;

    public MeetingMailer(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    public void send(UUID tenant, UUID senderMailbox, String senderEmail, String senderName, CalendarEvent e, String method,
                     List<MailEnvelope.Recipient> to, Response replyResponse) {
        if (to.isEmpty()) return;
        String ics = IcsBuilder.build(method, e, senderEmail, replyResponse);
        UUID id = UUID.randomUUID();
        String prefix = switch (method) { case "CANCEL" -> "Canceled: "; case "REPLY" -> "Response: "; default -> e.sequenceNo() > 0 ? "Updated: " : "Invitation: "; };
        MailEnvelope env = new MailEnvelope(id, tenant, senderMailbox, senderMailbox, senderEmail, senderName, null, to, List.of(), List.of(),
                prefix + e.title(), "Meeting " + method.toLowerCase() + " for " + e.title() + " starting " + e.startAt(), "text",
                "<" + id + "@" + senderEmail.substring(senderEmail.indexOf('@') + 1) + ">", null,
                UUID.nameUUIDFromBytes(("conv|" + e.icsUid()).getBytes()), (short) 1, List.of(), Instant.now(), Region.AMER, false, ics);
        try {
            kafka.send(Topics.OUTBOUND, tenant + ":" + senderMailbox, Json.write(env)).get(5, TimeUnit.SECONDS);
        } catch (Exception ex) { throw new IllegalStateException("could not queue meeting mail", ex); }
    }
}
