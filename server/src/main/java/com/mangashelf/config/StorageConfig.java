package com.mangashelf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/** S3 客户端与预签名器（MinIO 路径风格访问，DESIGN 18.1）。 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(MangaShelfProperties props) {
        var s = props.storage();
        return S3Client.builder()
                .region(Region.of(s.region()))
                .endpointOverride(URI.create(s.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s.accessKey(), s.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s.pathStyle())
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(MangaShelfProperties props) {
        var s = props.storage();
        return S3Presigner.builder()
                .region(Region.of(s.region()))
                .endpointOverride(URI.create(s.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s.accessKey(), s.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s.pathStyle())
                        .build())
                .build();
    }
}
