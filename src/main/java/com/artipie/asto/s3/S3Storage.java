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
package com.artipie.asto.s3;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.Transaction;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Storage that holds data in S3 storage.
 *
 * @since 0.15
 * @todo #87:60min Do not await abort to complete if save() failed.
 *  In case uploading content fails inside {@link S3Storage#save(Key, Content)} method
 *  we are doing abort() for multipart upload.
 *  Also whole operation does not complete until abort() is complete.
 *  It would be better to finish save() operation right away and do abort() in background,
 *  but it makes testing the method difficult.
 */
public final class S3Storage implements Storage {

    /**
     * Minimum content size to consider uploading it as multipart.
     */
    private static final long MIN_MULTIPART = 10 * 1024 * 1024;

    /**
     * S3 client.
     */
    private final S3AsyncClient client;

    /**
     * Bucket name.
     */
    private final String bucket;

    /**
     * Ctor.
     *
     * @param client S3 client.
     * @param bucket Bucket name.
     */
    public S3Storage(final S3AsyncClient client, final String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        final CompletableFuture<Boolean> exists = new CompletableFuture<>();
        this.client.headObject(
            HeadObjectRequest.builder()
                .bucket(this.bucket)
                .key(key.string())
                .build()
        ).handle(
            (response, throwable) -> {
                if (throwable == null) {
                    exists.complete(true);
                } else if (throwable.getCause() instanceof NoSuchKeyException) {
                    exists.complete(false);
                } else {
                    exists.completeExceptionally(throwable);
                }
                return response;
            }
        );
        return exists;
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        return this.client.listObjects(
            ListObjectsRequest.builder()
                .bucket(this.bucket)
                .prefix(prefix.string())
                .build()
        ).thenApply(
            response -> response.contents()
                .stream()
                .map(S3Object::key)
                .map(Key.From::new)
                .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final CompletableFuture<Void> future;
        final Optional<Long> size = content.size();
        if (size.isPresent() && size.get() < S3Storage.MIN_MULTIPART) {
            future = this.client.putObject(
                PutObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(key.string())
                    .build(),
                new ContentBody(content)
            ).thenApply(ignored -> null);
        } else {
            future = this.client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                    .bucket(this.bucket)
                    .key(key.string())
                    .build()
            ).thenApply(
                created -> new MultipartUpload(
                    new Bucket(this.client, this.bucket),
                    key,
                    created.uploadId()
                )
            ).thenCompose(
                upload -> upload.upload(content).handle(
                    (ignored, throwable) -> {
                        final CompletionStage<Void> finished;
                        if (throwable == null) {
                            finished = upload.complete();
                        } else {
                            final CompletableFuture<Void> promise = new CompletableFuture<>();
                            finished = promise;
                            upload.abort().whenComplete(
                                (result, ex) -> promise.completeExceptionally(throwable)
                            );
                        }
                        return finished;
                    }
                ).thenCompose(self -> self)
            );
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return this.client.copyObject(
            CopyObjectRequest.builder()
                .copySource(String.format("%s/%s", this.bucket, source.string()))
                .bucket(this.bucket)
                .key(destination.string())
                .build()
        ).thenCompose(
            copied -> this.client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(source.string())
                    .build()
            ).thenCompose(
                deleted -> CompletableFuture.allOf()
            )
        );
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final CompletableFuture<Content> promise = new CompletableFuture<>();
        this.client.getObject(
            GetObjectRequest.builder()
                .bucket(this.bucket)
                .key(key.string())
                .build(),
            new ResponseAdapter(promise)
        );
        return promise;
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return this.exists(key).thenCompose(
            exists -> {
                final CompletableFuture<Void> deleted;
                if (exists) {
                    deleted = this.client.deleteObject(
                        DeleteObjectRequest.builder()
                            .bucket(this.bucket)
                            .key(key.string())
                            .build()
                    ).thenCompose(
                        response -> CompletableFuture.allOf()
                    );
                } else {
                    deleted = new CompletableFuture<>();
                    deleted.completeExceptionally(
                        new IllegalArgumentException(String.format("Key does not exist: %s", key))
                    );
                }
                return deleted;
            }
        );
    }

    @Override
    public CompletableFuture<Transaction> transaction(final List<Key> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@link AsyncRequestBody} created from {@link Content}.
     *
     * @since 0.16
     */
    private static class ContentBody implements AsyncRequestBody {

        /**
         * Data source for request body.
         */
        private final Content source;

        /**
         * Ctor.
         *
         * @param source Data source for request body.
         */
        ContentBody(final Content source) {
            this.source = source;
        }

        @Override
        public Optional<Long> contentLength() {
            return this.source.size();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            this.source.subscribe(subscriber);
        }
    }

    /**
     * Adapts {@link AsyncResponseTransformer} to {@link CompletableFuture}.
     *
     * @since 0.15
     */
    private static class ResponseAdapter
        implements AsyncResponseTransformer<GetObjectResponse, Content> {

        /**
         * Promise of response body.
         */
        private final CompletableFuture<Content> promise;

        /**
         * Content length received in response.
         */
        private Long length;

        /**
         * Ctor.
         *
         * @param promise Promise of response body.
         */
        ResponseAdapter(final CompletableFuture<Content> promise) {
            this.promise = promise;
        }

        @Override
        public CompletableFuture<Content> prepare() {
            return this.promise;
        }

        @Override
        public void onResponse(final GetObjectResponse response) {
            this.length = response.contentLength();
        }

        @Override
        public void onStream(final SdkPublisher<ByteBuffer> publisher) {
            this.promise.complete(new Content.From(Optional.ofNullable(this.length), publisher));
        }

        @Override
        public void exceptionOccurred(final Throwable throwable) {
            this.promise.completeExceptionally(throwable);
        }
    }
}
