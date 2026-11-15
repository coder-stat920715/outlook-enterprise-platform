package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.model.MailEnvelope;
import com.enterprise.outlook.mail.repo.MailboxRepository.RuleRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Evaluates inbox rules in priority order. First matching rule with stopProcessing ends evaluation. */
@Component
public class RulesEngine {
    public record Result(UUID folderId, boolean markRead, boolean flag, String category) {}

    public Result apply(List<RuleRow> rules, MailEnvelope env, UUID defaultFolder) {
        UUID folder = defaultFolder;
        boolean read = false, flag = false;
        String category = null;
        for (RuleRow r : rules) {
            if (!matches(r, env)) continue;
            var a = r.action();
            if (a.moveToFolderId() != null) folder = a.moveToFolderId();
            if (Boolean.TRUE.equals(a.markRead())) read = true;
            if (Boolean.TRUE.equals(a.flag())) flag = true;
            if (a.category() != null) category = a.category();
            if (a.stopProcessing()) break;
        }
        return new Result(folder, read, flag, category);
    }

    private boolean matches(RuleRow r, MailEnvelope env) {
        var c = r.condition();
        if (c == null) return false;
        boolean any = false;
        if (c.senderContains() != null) { any = true; if (!lower(env.senderEmail()).contains(c.senderContains().toLowerCase())) return false; }
        if (c.subjectContains() != null) { any = true; if (!lower(env.subject()).contains(c.subjectContains().toLowerCase())) return false; }
        if (c.bodyContains() != null) { any = true; if (!lower(env.bodyContent()).contains(c.bodyContains().toLowerCase())) return false; }
        return any;
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(); }
}
