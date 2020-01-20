/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 artipie.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.artipie.asto;

import com.jcabi.log.Logger;
import hu.akarnokd.rxjava3.jdk8interop.CompletableInterop;
import hu.akarnokd.rxjava3.jdk8interop.SingleInterop;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import org.reactivestreams.FlowAdapters;

/**
 * Simple storage, in files.
 *
 * @since 0.1
 */
public final class Simple implements Storage {

    /**
     * Where we keep the data.
     */
    private final Path dir;

    /**
     * Ctor.
     *
     * @throws IOException If fails
     */
    public Simple() throws IOException {
        this(Files.createTempDirectory("asto"));
    }

    /**
     * Ctor.
     *
     * @param path The path to the dir
     */
    public Simple(final Path path) {
        this.dir = path;
    }

    @Override
    public CompletableFuture<Boolean> exists(final String key) {
        return (CompletableFuture<Boolean>) Single.fromCallable(
            () -> {
                final Path path = Paths.get(this.dir.toString(), key);
                return Files.exists(path);
            }).to(SingleInterop.get());
    }

    @Override
    public CompletableFuture<Collection<String>> list(final String prefix) {
        return (CompletableFuture<Collection<String>>) Single.fromCallable(
            () -> {
                if (!prefix.endsWith("/")) {
                    throw new IllegalArgumentException(
                        String.format(
                            "The prefix must end with a slash: \"%s\"",
                            prefix
                        )
                    );
                }
                final Path path = Paths.get(this.dir.toString(), prefix);
                final Collection<String> keys = Files.walk(path)
                    .filter(Files::isRegularFile)
                    .map(Path::toString)
                    .map(
                        p -> p.substring(
                            path.toString().length() - prefix.length() + 1
                        )
                    )
                    .collect(Collectors.toList());
                Logger.info(
                    this,
                    "Found %d objects by the prefix \"%s\" in %s by %s: %s",
                    keys.size(), prefix, this.dir, path, keys
                );
                return keys;
            }).to(SingleInterop.get());
    }

    @Override
    public CompletableFuture<Void> save(final String key, final Flow.Publisher<Byte> content) {
        final Completable result = Flowable.fromPublisher(FlowAdapters.toPublisher(content))
            .toList()
            .map(bytes -> bytes.toArray(new Byte[0]))
            .map(ByteArray::new)
            .flatMapCompletable(
                byteArray ->
                    Completable.fromAction(
                        () -> {
                            final Path target = Paths.get(this.dir.toString(), key);
                            target.getParent().toFile().mkdirs();
                            final byte[] bytes = byteArray.primitiveBytes();
                            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                            Logger.info(
                                this,
                                "Saved %d bytes to %s: %s",
                                bytes, key, target
                            );
                        }));
        return ((CompletableFuture<Object>) result.to(CompletableInterop.await()))
            .thenApply(o -> null);
    }

    @Override
    public CompletableFuture<Flow.Publisher<Byte>> value(final String key) {
        final Path source = Paths.get(this.dir.toString(), key);
        final Flowable<Byte> result =
            Single.fromCallable(
                () -> {
                    final byte[] bytes = Files.readAllBytes(source);
                    Logger.info(
                        this,
                        "Loaded %d bytes of %s: %s",
                        bytes.length, key, source
                    );
                    return bytes;
                }
            ).flatMapPublisher(
                bytes -> Flowable.fromIterable(Arrays.asList(new ByteArray(bytes).boxedBytes()))
            );
        return CompletableFuture.supplyAsync(() -> FlowAdapters.toFlowPublisher(result));
    }
}
