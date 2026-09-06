package io.memoryos.objectstorage.s3;

import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.UploadAuthorization;
import io.memoryos.objectstorage.UploadConstraints;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public final class S3ObjectStorage implements ObjectStorage, AutoCloseable {
    private final String bucket;
    private final S3Client client;
    private final S3Presigner presigner;
    private final S3ObjectStorageProperties properties;
    private final Clock clock;

    S3ObjectStorage(
            String bucket,
            S3Client client,
            S3Presigner presigner,
            S3ObjectStorageProperties properties,
            Clock clock
    ) {
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.presigner = Objects.requireNonNull(presigner, "presigner must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    static S3ObjectStorage create(S3ObjectStorageProperties properties, Clock clock) {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.accessKey(),
                properties.secretKey()
        ));
        var serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .build();
        var httpClient = UrlConnectionHttpClient.builder()
                .connectionTimeout(properties.connectTimeout())
                .socketTimeout(properties.readTimeout())
                .build();
        var client = S3Client.builder()
                .endpointOverride(properties.serviceEndpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration)
                .httpClient(httpClient)
                .build();
        var presigner = S3Presigner.builder()
                .endpointOverride(properties.uploadEndpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration)
                .build();
        return new S3ObjectStorage(properties.bucket(), client, presigner, properties, clock);
    }

    @Override
    public void write(ObjectKey key, byte[] content, String mediaType) {
        Objects.requireNonNull(content, "content must not be null");
        if (content.length == 0 || content.length > 33_554_432) {
            throw new IllegalArgumentException("object write exceeds bounds");
        }
        try {
            var checksum = java.util.Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key.value())
                            .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofMinutes(2)))
                            .contentType(mediaType).contentLength((long) content.length)
                            .checksumSHA256(checksum).ifNoneMatch("*").build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } catch (S3Exception | SdkClientException exception) {
            throw translate(exception);
        }
    }

    @Override
    public UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(constraints, "constraints must not be null");
        try {
            var objectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .contentType(constraints.mediaType())
                    .contentLength(constraints.sizeBytes())
                    .checksumSHA256(constraints.checksum().base64())
                    .build();
            var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(properties.authorizationLifetime())
                    .putObjectRequest(objectRequest)
                    .build());
            Map<String, String> headers = new LinkedHashMap<>();
            signed.signedHeaders().forEach((name, values) -> {
                if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                    headers.put(name, String.join(",", values));
                }
            });
            return new UploadAuthorization(
                    signed.httpRequest().method().name(),
                    URI.create(signed.url().toString()),
                    headers,
                    Instant.now(clock).plus(properties.authorizationLifetime())
            );
        } catch (SdkClientException exception) {
            throw translate(exception);
        }
    }

    @Override
    public ObjectMetadata inspect(ObjectKey key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            var response = client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            return metadata(response.contentLength(), response.contentType(), response.checksumSHA256());
        } catch (NoSuchKeyException exception) {
            throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, exception);
        } catch (S3Exception | SdkClientException exception) {
            throw translate(exception);
        }
    }

    @Override
    public ObjectContent open(ObjectKey key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            ObjectMetadata metadata = metadata(
                    response.response().contentLength(),
                    response.response().contentType(),
                    response.response().checksumSHA256()
            );
            return new S3ObjectContent(response, metadata);
        } catch (NoSuchKeyException exception) {
            throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, exception);
        } catch (S3Exception | SdkClientException exception) {
            throw translate(exception);
        }
    }

    void verifyReadable(ObjectKey key) {
        Objects.requireNonNull(key, "key must not be null");
        try (var response = client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .range("bytes=0-0")
                .build())) {
            if (response.read() < 0) {
                throw new ObjectStorageException(ObjectStorageFailureCode.PRECONDITION_FAILED, false, null);
            }
        } catch (NoSuchKeyException exception) {
            throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, exception);
        } catch (S3Exception | SdkClientException exception) {
            throw translate(exception);
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true, exception);
        }
    }

    @Override
    public void delete(ObjectKey key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.value()).build());
        } catch (S3Exception | SdkClientException exception) {
            throw translate(exception);
        }
    }

    private static ObjectMetadata metadata(long sizeBytes, String mediaType, String checksum) {
        if (checksum == null || checksum.isBlank()) {
            throw new ObjectStorageException(ObjectStorageFailureCode.PRECONDITION_FAILED, false, null);
        }
        return new ObjectMetadata(sizeBytes, mediaType, ContentSha256.fromBase64(checksum));
    }

    private static ObjectStorageException translate(RuntimeException exception) {
        if (exception instanceof S3Exception s3Exception) {
            int status = s3Exception.statusCode();
            if (status == 404) {
                return new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, exception);
            }
            if (status == 401 || status == 403) {
                return new ObjectStorageException(ObjectStorageFailureCode.ACCESS_DENIED, false, exception);
            }
            if (status == 412) {
                return new ObjectStorageException(ObjectStorageFailureCode.PRECONDITION_FAILED, false, exception);
            }
            if (status == 429 || status == 503) {
                return new ObjectStorageException(ObjectStorageFailureCode.THROTTLED, true, exception);
            }
        }
        return new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true, exception);
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }

    private record S3ObjectContent(
            ResponseInputStream<GetObjectResponse> response,
            ObjectMetadata metadata
    ) implements ObjectContent {
        @Override
        public InputStream inputStream() {
            return response;
        }

        @Override
        public void close() {
            try {
                response.close();
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not close object content", exception);
            }
        }
    }
}
