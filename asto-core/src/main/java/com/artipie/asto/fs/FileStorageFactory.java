/*
 * The MIT License (MIT) Copyright (c) 2020-2022 artipie.com
 * https://github.com/artipie/asto/LICENSE.txt
 */
package com.artipie.asto.fs;

import com.artipie.asto.Storage;
import com.artipie.asto.factory.ArtipieStorageFactory;
import com.artipie.asto.factory.StorageConfig;
import com.artipie.asto.factory.StorageFactory;
import java.nio.file.Paths;

/**
 * File storage factory.
 *
 * @since 1.13.0
 */
@ArtipieStorageFactory("fs")
public final class FileStorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final StorageConfig cfg) {
        return new FileStorage(
            Paths.get(new StorageConfig.StrictStorageConfig(cfg).string("path"))
        );
    }
}
