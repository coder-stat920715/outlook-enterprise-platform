package com.enterprise.outlook.mail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "outlook.mail")
public record MailProperties(
        @DefaultValue("http://localhost:9200") String opensearchUrl,
        @DefaultValue("http://minio:9000") String minioEndpoint,
        @DefaultValue("http://localhost:9000") String minioPublicEndpoint,
        @DefaultValue("minioadmin") String minioAccessKey,
        @DefaultValue("minioadmin") String minioSecretKey,
        @DefaultValue("outlook-attachments") String bucket,
        @DefaultValue("http://localhost:8080") String cdnBaseUrl,
        @DefaultValue("change-me-cdn-secret") String cdnSecret,
        @DefaultValue("change-me-delta-secret") String deltaSecret,
        @DefaultValue("0.0") double smtpFailureRate,
        @DefaultValue("172800") long maxDeferralSeconds) {}
