/*
 * The MIT License (MIT) Copyright (c) 2020-2022 artipie.com
 * https://github.com/artipie/asto/LICENSE.txt
 */
package com.artipie.asto.s3;

import com.artipie.asto.Storage;
import com.artipie.asto.factory.ArtipieStorageFactory;
import com.artipie.asto.factory.StorageConfig;
import com.artipie.asto.factory.StorageFactory;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;

/**
 * Factory to create S3 storage.
 *
 * @since 0.1
 */
@ArtipieStorageFactory("s3")
public final class S3StorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final StorageConfig cfg) {
        return new S3Storage(
            S3StorageFactory.s3Client(cfg),
            new StorageConfig.StrictStorageConfig(cfg)
                .string("bucket"),
            !"false".equals(cfg.string("multipart"))
        );
    }

    /**
     * Creates {@link S3AsyncClient} instance based on YAML config.
     *
     * @param cfg Storage config.
     * @return Built S3 client.
     * @checkstyle MethodNameCheck (3 lines)
     */
    private static S3AsyncClient s3Client(final StorageConfig cfg) {
        final S3AsyncClientBuilder builder = S3AsyncClient.builder();
        final String region = cfg.string("region");
        if (region != null) {
            builder.region(Region.of(region));
        }
        final String endpoint = cfg.string("endpoint");
        if (endpoint != null) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder
            .credentialsProvider(
                S3StorageFactory.credentials(
                    new StorageConfig.StrictStorageConfig(cfg)
                        .config("credentials")
                )
            )
            .build();
    }

    /**
     * Creates {@link StaticCredentialsProvider} instance based on config.
     *
     * @param cred Credentials config.
     * @return Credentials provider.
     */
    private static StaticCredentialsProvider credentials(final StorageConfig cred) {
        final String type = cred.string("type");
        if ("basic".equals(type)) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    cred.string("accessKeyId"),
                    cred.string("secretAccessKey")
                )
            );
        } else {
            throw new IllegalArgumentException(
                String.format("Unsupported S3 credentials type: %s", type)
            );
        }
    }
}
