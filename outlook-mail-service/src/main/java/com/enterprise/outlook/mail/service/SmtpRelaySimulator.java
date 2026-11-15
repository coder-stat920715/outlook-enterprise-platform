package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.model.MailEnvelope;
import com.enterprise.outlook.mail.config.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Async SMTP relay simulation for external recipients. outlook.mail.smtp-failure-rate injects 4xx-style transient failures. */
@Component
public class SmtpRelaySimulator {
    private static final Logger log = LoggerFactory.getLogger(SmtpRelaySimulator.class);
    private final double failureRate;

    public SmtpRelaySimulator(MailProperties props) { this.failureRate = props.smtpFailureRate(); }

    public void relay(MailEnvelope env, List<MailEnvelope.Recipient> externals) {
        if (ThreadLocalRandom.current().nextDouble() < failureRate) throw new IllegalStateException("simulated SMTP 451 4.7.1 try again later");
        log.info("SMTP relay accepted messageId={} externalRecipients={}", env.messageId(), externals.size());
    }
}
