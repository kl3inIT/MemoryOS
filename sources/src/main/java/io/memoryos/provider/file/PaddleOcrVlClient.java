package io.memoryos.provider.file;

import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One bounded {@code POST /layout-parsing} to the PaddleX serving API of PaddleOCR-VL.
 *
 * <p>The document travels as base64 inside JSON, encoded while it is sent: a 250 MiB Chat file never
 * becomes a String or a second byte array on the heap. The answer is read up to a fixed size. Each
 * request is sent once; nothing here retries, because a failed extraction is terminal for the
 * attempt and the Source manager decides when to read it again (MEM-192).
 *
 * <p>A worker reads several operations at once and the GPU serves them all, so at most
 * {@code max-concurrent-requests} are at the service at a time; the rest wait in line, fairly. The
 * deadline counts from when a request is sent, so a document is never timed out for waiting.
 */
final class PaddleOcrVlClient implements AutoCloseable {
    /**
     * With {@code visualize=false} the spike's answers ran about 15 KB a page (29.7 KB for two), so
     * 200 pages is some 3 MB. Dense tables can be several times that; 64 MiB is the headroom Docling's
     * transport already has, and stays above the 32 MiB artifact the answer becomes.
     */
    static final int MAX_RESPONSE_BYTES = 67_108_864;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PaddleOcrVlClient.class);
    private static final Pattern LOG_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final int maxResponseBytes;
    private final Semaphore permits;
    private final HttpClient transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    enum FileType {
        PDF(0), IMAGE(1);
        final int code;
        FileType(int code) { this.code = code; }
    }

    /** The bytes of the document, opened once for the one request. */
    interface Input {
        long size() throws IOException;
        InputStream open() throws IOException;

        static Input of(byte[] bytes) {
            return new Input() {
                @Override public long size() { return bytes.length; }
                @Override public InputStream open() { return new ByteArrayInputStream(bytes); }
            };
        }

        static Input of(java.nio.file.Path file) {
            return new Input() {
                @Override public long size() throws IOException { return java.nio.file.Files.size(file); }
                @Override public InputStream open() throws IOException { return java.nio.file.Files.newInputStream(file); }
            };
        }
    }

    PaddleOcrVlClient(PaddleOcrVlProperties properties, ObjectMapper mapper) {
        this(properties, mapper, MAX_RESPONSE_BYTES);
    }

    PaddleOcrVlClient(PaddleOcrVlProperties properties, ObjectMapper mapper, int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
        this.endpoint = URI.create(java.util.Objects.requireNonNull(properties.endpoint(), "endpoint").toString()
                .replaceAll("/+$", "") + "/layout-parsing");
        this.timeout = properties.timeout();
        this.mapper = mapper;
        this.permits = new Semaphore(java.util.Objects.requireNonNull(properties.maxConcurrentRequests()), true);
    }

    /** @return the envelope's {@code result.layoutParsingResults}, one element per page */
    JsonNode parse(Input input, FileType type) throws ExtractionException {
        long queued = System.nanoTime();
        long started = queued;
        int status = -1;
        long waited = 0;
        boolean permitted = false;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            permits.acquire();
            permitted = true;
            started = System.nanoTime();
            waited = started - queued;
            byte[] prefix = ("{\"fileType\":" + type.code + ",\"visualize\":false,\"mergeTables\":false,"
                    + "\"useDocOrientationClassify\":false,\"useDocUnwarping\":false,\"file\":\"")
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] suffix = "\"}".getBytes(StandardCharsets.US_ASCII);
            long length = prefix.length + Base64Stream.encodedLength(input.size()) + suffix.length;
            var body = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return new SequenceInputStream(java.util.Collections.enumeration(List.of(
                            new ByteArrayInputStream(prefix), new Base64Stream(input.open()), new ByteArrayInputStream(suffix))));
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
            }), length);
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(body).build();
            var response = transport.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            byte[] answer;
            // The request timeout ends with the response headers. A server that then stalls mid-body
            // would hold the worker for ever, so the same deadline, counted from sending, closes
            // the body; a read that fails after it has passed is a timeout, not a transport fault.
            var expired = new java.util.concurrent.atomic.AtomicBoolean();
            try (var stream = response.body()) {
                if (status >= 300) throw new StatusFailure(status);
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                java.util.concurrent.CompletableFuture.delayedExecutor(Math.max(0, remaining), TimeUnit.NANOSECONDS)
                        .execute(() -> {
                            expired.set(true);
                            try {
                                stream.close();
                            } catch (IOException ignored) {
                                // Closing is how the stalled read is released; nothing else to do.
                            }
                        });
                try {
                    answer = stream.readNBytes(maxResponseBytes + 1);
                } catch (IOException read) {
                    if (expired.get()) throw failed(ExtractionFailure.TIMEOUT, status, "response_deadline", waited);
                    throw read;
                }
                if (expired.get()) throw failed(ExtractionFailure.TIMEOUT, status, "response_deadline", waited);
            }
            if (answer.length > maxResponseBytes) throw failed(ExtractionFailure.WRITE_LIMIT, status, "response_limit", waited);
            JsonNode envelope;
            try {
                envelope = mapper.readTree(answer);
            } catch (RuntimeException unparseable) {
                throw failed(ExtractionFailure.MALFORMED, status, unparseable.getClass().getName(), waited);
            }
            JsonNode results = envelope == null ? null : envelope.path("result").path("layoutParsingResults");
            if (results == null || !envelope.path("errorCode").isIntegralNumber() || envelope.path("errorCode").asInt() != 0
                    || !results.isArray() || results.isEmpty()) {
                throw failed(ExtractionFailure.MALFORMED, status, "envelope", waited);
            }
            String logId = envelope.path("logId").asString("");
            LOG.atInfo().addKeyValue("event", "paddleocr_vl.request.completed")
                    .addKeyValue("log_id", LOG_ID.matcher(logId).matches() ? logId : "invalid")
                    .addKeyValue("page_count", results.size())
                    .addKeyValue("queued_ms", TimeUnit.NANOSECONDS.toMillis(waited))
                    .addKeyValue("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("PaddleOCR-VL returned a layout; content validation follows");
            return results;
        } catch (ExtractionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!permitted) waited = System.nanoTime() - queued;
            throw failed(ExtractionFailure.TIMEOUT, status, e.getClass().getName(), waited);
        } catch (StatusFailure e) {
            throw failed(switch (e.status) {
                case 413 -> ExtractionFailure.WRITE_LIMIT;
                case 408, 504 -> ExtractionFailure.TIMEOUT;
                default -> ExtractionFailure.INTERNAL;
            }, e.status, e.getClass().getName(), waited);
        } catch (IOException | RuntimeException e) {
            throw failed(transportFailure(e), status, e.getClass().getName(), waited);
        } finally {
            if (permitted) permits.release();
        }
    }

    /** Connection establishment is checked first: an unreachable service is not a slow document. */
    private static ExtractionFailure transportFailure(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpConnectTimeoutException || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException || cause instanceof java.net.NoRouteToHostException) {
                return ExtractionFailure.CONNECTION_FAILED;
            }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof InterruptedException
                    || cause instanceof java.io.InterruptedIOException) {
                return ExtractionFailure.TIMEOUT;
            }
        }
        return ExtractionFailure.INTERNAL;
    }

    private ExtractionException failed(ExtractionFailure failure, int status, String errorType, long waitedNanos) {
        LOG.atWarn().addKeyValue("event", "paddleocr_vl.extraction.failed")
                .addKeyValue("error_code", failure.name()).addKeyValue("http_status", status)
                .addKeyValue("error_type", errorType)
                .addKeyValue("queued_ms", TimeUnit.NANOSECONDS.toMillis(waitedNanos))
                .log("PaddleOCR-VL extraction failed");
        return DocumentAssembly.failure(failure);
    }

    @Override public void close() { transport.shutdownNow(); }

    private static final class StatusFailure extends IOException {
        final int status;

        StatusFailure(int status) {
            super("PaddleOCR-VL HTTP status " + status);
            this.status = status;
        }
    }

    /** Standard base64 of the wrapped stream, produced a block at a time and never held whole. */
    static final class Base64Stream extends InputStream {
        private static final int CHUNK = 3 * 16_384;
        private final InputStream source;
        private final Base64.Encoder encoder = Base64.getEncoder();
        private byte[] encoded = new byte[0];
        private int position;
        private boolean finished;

        Base64Stream(InputStream source) {
            this.source = source;
        }

        static long encodedLength(long size) {
            return 4 * ((size + 2) / 3);
        }

        @Override public int read() throws IOException {
            if (!fill()) return -1;
            return encoded[position++] & 0xff;
        }

        @Override public int read(byte[] target, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            if (!fill()) return -1;
            int count = Math.min(length, encoded.length - position);
            System.arraycopy(encoded, position, target, offset, count);
            position += count;
            return count;
        }

        /** Reads whole three-byte groups so that only the final block carries padding. */
        private boolean fill() throws IOException {
            if (position < encoded.length) return true;
            if (finished) return false;
            byte[] chunk = source.readNBytes(CHUNK);
            if (chunk.length < CHUNK) finished = true;
            if (chunk.length == 0) return false;
            encoded = encoder.encode(chunk);
            position = 0;
            return true;
        }

        @Override public void close() throws IOException { source.close(); }
    }
}
