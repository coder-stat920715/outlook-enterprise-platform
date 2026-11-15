package com.enterprise.outlook.mail.dto;

import com.enterprise.outlook.common.model.MailEnvelope.Recipient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    public record SendRequest(List<Recipient> to, List<Recipient> cc, List<Recipient> bcc, String subject, String body,
                              String bodyType, Short importance, List<UUID> attachmentIds, UUID inReplyToMessageId) {}

    public record SendAccepted(UUID messageId, UUID conversationId, String state, boolean replayed) {}

    /** Exactly the columns of the covering index => listing is an Index Only Scan. */
    public record MessageSummary(UUID messageId, UUID conversationId, String subject, String senderEmail, String senderName,
                                 String snippet, boolean isRead, boolean isFlagged, boolean hasAttachments, short importance,
                                 Instant receivedAt) {}

    public record MessageDetail(MessageSummary summary, UUID folderId, String bodyContent, String bodyType, String toRecipients,
                                String ccRecipients, boolean isPinned, String categories, String deliveryState, long version) {}

    public record PageResult<T>(List<T> items, String nextCursor) {}

    public record PatchRequest(Boolean isRead, Boolean isFlagged, Boolean isPinned, List<String> categories, UUID folderId) {}

    public record FolderView(UUID folderId, UUID parentFolderId, String displayName, String wellKnown, int totalCount, int unreadCount) {}

    public record CreateFolderRequest(String displayName, UUID parentFolderId) {}

    public record DeltaChange(long changeSeq, String entityType, UUID entityId, String changeType, UUID folderId) {}

    public record DeltaResponse(List<DeltaChange> changes, String nextDeltaToken, boolean hasMore, boolean fullSyncRequired) {}

    public record ProvisionRequest(String email, String displayName, String mailboxType, String region) {}

    public record ProvisionResponse(UUID tenantId, UUID mailboxId, String email) {}

    public record UploadUrlRequest(String fileName, String contentType, long sizeBytes, boolean inline, String contentId) {}

    public record UploadUrlResponse(UUID attachmentId, String uploadUrl, String method, Instant expiresAt) {}

    public record DownloadUrlResponse(String url, Instant expiresAt) {}

    public record GrantRequest(UUID granteeMailboxId, String permission) {}

    public record RuleRequest(String name, int priority, RuleCondition condition, RuleAction action, boolean enabled) {}

    public record RuleCondition(String senderContains, String subjectContains, String bodyContains) {}

    public record RuleAction(UUID moveToFolderId, Boolean markRead, Boolean flag, String category, boolean stopProcessing) {}

    public record OofRequest(boolean enabled, Instant startAt, Instant endAt, String subject, String replyText) {}

    public record ContactRequest(String displayName, String email, String phone, String company) {}

    public record TaskView(UUID taskId, UUID sourceMessageId, String title, Instant dueAt, short priority, String status) {}

    public record SearchHit(UUID messageId, String subject, String sender, String snippet, Instant receivedAt) {}
}
