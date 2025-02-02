/*
 * The MIT License (MIT) Copyright (c) 2020-2022 artipie.com
 * https://github.com/artipie/asto/LICENSE.txt
 */
package com.artipie.asto.factory;

import com.amihaiemil.eoyaml.Yaml;
import com.artipie.asto.s3.S3Storage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;

/**
 * Test for Storages.
 *
 * @since 0.1
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class StoragesTest {

    /**
     * Test for S3 storage factory.
     *
     * @checkstyle MethodNameCheck (3 lines)
     */
    @Test
    void shouldCreateS3Storage() {
        MatcherAssert.assertThat(
            new Storages()
                .newStorage(
                    "s3",
                    Yaml.createYamlMappingBuilder()
                        .add("region", "us-east-1")
                        .add("bucket", "aaa")
                        .add("endpoint", "http://localhost")
                        .add(
                            "credentials",
                            Yaml.createYamlMappingBuilder()
                                .add("type", "basic")
                                .add("accessKeyId", "foo")
                                .add("secretAccessKey", "bar")
                                .build()
                        )
                        .build()
                ),
            new IsInstanceOf(S3Storage.class)
        );
    }
}
