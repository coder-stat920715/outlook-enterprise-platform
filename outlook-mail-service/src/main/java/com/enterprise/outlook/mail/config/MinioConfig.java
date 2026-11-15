package com.enterprise.outlook.mail.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {
    /** Internal client (stat / bucket admin) – talks to MinIO on the private network. */
    @Bean("minioInternal")
    MinioClient minioInternal(MailProperties p) {
        return MinioClient.builder().endpoint(p.minioEndpoint()).credentials(p.minioAccessKey(), p.minioSecretKey()).build();
    }

    /** Signing client: endpoint = public storage ingress; region pinned so no network call is made while presigning. */
    @Bean("minioSigner")
    MinioClient minioSigner(MailProperties p) {
        return MinioClient.builder().endpoint(p.minioPublicEndpoint()).credentials(p.minioAccessKey(), p.minioSecretKey())
                .region("us-east-1").build();
    }
}
