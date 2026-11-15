package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.util.Ids;

import java.util.List;
import java.util.UUID;

/**
 * Stateless conversation threading. conversation_id = UUIDv3(tenant, rootMessageId) where the root is the first
 * entry of References, else In-Reply-To, else the message's own Message-ID. All participants therefore derive the
 * same conversation id without coordination (tree grouping by root).
 */
public final class ConversationResolver {
    private ConversationResolver() {}

    public static UUID resolve(UUID tenant, String internetMessageId, String inReplyTo, List<String> references) {
        String root = references != null && !references.isEmpty() ? references.get(0)
                : inReplyTo != null && !inReplyTo.isBlank() ? inReplyTo : internetMessageId;
        return Ids.deterministic("conv", tenant.toString(), root);
    }

    /** Normalised subject (strips Re:/Fwd: prefixes) – fallback grouping for clients that drop References. */
    public static String normalizeSubject(String subject) {
        return subject == null ? "" : subject.replaceAll("(?i)^((re|fw|fwd)\\s*:\\s*)+", "").trim();
    }
}
