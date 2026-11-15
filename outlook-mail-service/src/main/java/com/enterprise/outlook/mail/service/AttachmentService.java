package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.mail.config.MailProperties;
import com.enterprise.outlook.mail.dto.Dtos.*;
import com.enterprise.outlook.mail.repo.AttachmentRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Attachments never pass through the API: clients upload with presigned PUT URLs and download via signed CDN URLs. */
@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final long MAX_BYTES = 150L * 1024 * 1024;

    private final MinioClient internal, signer;
    private final MailProperties props;
    private final ShardExecutor shards;
    private final AttachmentRepository repo;
    private final CdnUrlSigner cdn;

    public AttachmentService(@Qualifier("minioInternal") MinioClient internal, @Qualifier("minioSigner") MinioClient signer,
                             MailProperties props, ShardExecutor shards, AttachmentRepository repo, CdnUrlSigner cdn) {
        this.internal = internal; this.signer = signer; this.props = props; this.shards = shards; this.repo = repo; this.cdn = cdn;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensureBucket() {
        try {
            if (!internal.bucketExists(BucketExistsArgs.builder().bucket(props.bucket()).build()))
                internal.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
        } catch (Exception e) { log.warn("bucket check failed (will be created by minio-init in compose): {}", e.toString()); }
    }

    public UploadUrlResponse createUploadUrl(UUID tenant, UUID mailbox, UploadUrlRequest r) {
        if (r.sizeBytes() <= 0 || r.sizeBytes() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "attachment limit is 150 MB");
        UUID id = UUID.randomUUID();
        String key = tenant + "/" + mailbox + "/" + id;
        shards.transact(tenant, mailbox, () -> {
            repo.insertPending(tenant, mailbox, id, r.fileName(), r.contentType(), r.sizeBytes(), key, r.inline(), r.contentId());
            return null;
        });
        try {
            String url = signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT)
                    .bucket(props.bucket()).object(key).expiry(15, TimeUnit.MINUTES).build());
            return new UploadUrlResponse(id, url, "PUT", Instant.now().plus(Duration.ofMinutes(15)));
        } catch (Exception e) { throw new IllegalStateException("could not presign upload url", e); }
    }

    public void complete(UUID tenant, UUID mailbox, UUID attachmentId) {
        var att = shards.read(tenant, mailbox, () -> repo.find(tenant, mailbox, attachmentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment"));
        try {
            long size = internal.statObject(StatObjectArgs.builder().bucket(props.bucket()).object(att.objectKey()).build()).size();
            shards.transact(tenant, mailbox, () -> { repo.markUploaded(tenant, mailbox, attachmentId, size); return null; });
        } catch (ResponseStatusException e) { throw e;
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "object not uploaded yet"); }
    }

    public DownloadUrlResponse downloadUrl(UUID tenant, UUID mailbox, UUID attachmentId) {
        var att = shards.read(tenant, mailbox, () -> repo.find(tenant, mailbox, attachmentId))
                .filter(a -> "UPLOADED".equals(a.status())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment"));
        var s = cdn.sign(att.objectKey(), Duration.ofMinutes(10));
        return new DownloadUrlResponse(s.url(), s.expiresAt());
    }
}
