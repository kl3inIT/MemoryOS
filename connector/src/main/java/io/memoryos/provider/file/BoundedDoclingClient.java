package io.memoryos.provider.file;

import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.response.ErrorItem;
import ai.docling.serve.api.convert.response.ResponseType;
import ai.docling.serve.api.task.request.TaskStatusPollRequest;
import ai.docling.serve.api.task.response.TaskStatusPollResponse;
import ai.docling.serve.client.DoclingServeClient;
import ai.docling.serve.client.DoclingServeClientException;
import ai.docling.serve.client.operations.RequestContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Official SDK operations with a bounded response transport; no response/body logging. */
@org.jspecify.annotations.NullMarked
final class BoundedDoclingClient extends DoclingServeClient implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BoundedDoclingClient.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final JsonMapper mapper = JsonMapper.builder().addMixIn(ErrorItem.class, ErrorMapping.class).build();
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private final Duration taskTimeout;
    private final String apiKey;
    private final HttpClient transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    private BoundedDoclingClient(Builder builder) {
        super(builder);
        taskTimeout = builder.taskTimeout;
        apiKey = builder.accessKey;
    }

    static BoundedDoclingClient create(DoclingProperties properties) {
        var builder = new Builder();
        builder.taskTimeout = properties.timeout().plusMinutes(5);
        return builder.baseUrl(properties.endpoint()).apiKey(properties.apiKey()).connectTimeout(Duration.ofSeconds(5))
                .readTimeout(HTTP_TIMEOUT).logRequests(false).logResponses(false).build();
    }

    CanonicalResponse convertDocument(ConvertDocumentRequest request) {
        long started = System.nanoTime();
        deadline.set(started + taskTimeout.toNanos());
        String taskId = null;
        String stage = "SUBMISSION";
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var status = executePost(RequestContext.<ConvertDocumentRequest, TaskStatusPollResponse>builder()
                    .uri("/v1/convert/source/async").request(request)
                    .responseType(TaskStatusPollResponse.class).build());
            if (status == null || status.getTaskId() == null || status.getTaskStatus() == null
                    || !TASK_ID.matcher(status.getTaskId()).matches()) {
                throw new ResponseFailure(ExtractionFailure.INTERNAL);
            }
            taskId = status.getTaskId();
            var observedStates = EnumSet.of(status.getTaskStatus());
            LOG.atInfo().addKeyValue("event", "docling.task.submitted").addKeyValue("task_id", taskId)
                    .addKeyValue("state", status.getTaskStatus().name())
                    .addKeyValue("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("Docling task accepted");
            stage = "POLLING";
            while (true) {
                remaining();
                if (status == null || !taskId.equals(status.getTaskId()) || status.getTaskStatus() == null) {
                    throw new ResponseFailure(ExtractionFailure.INTERNAL);
                }
                if (observedStates.add(status.getTaskStatus())) {
                    LOG.atInfo().addKeyValue("event", "docling.task.state_observed").addKeyValue("task_id", taskId)
                            .addKeyValue("state", status.getTaskStatus().name())
                            .addKeyValue("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                            .log("Observed Docling task state");
                }
                switch (status.getTaskStatus()) {
                    case SUCCESS -> {
                        stage = "RESULT_READ";
                        var result = executeGet(RequestContext.<Object, CanonicalResponse>builder()
                                .uri("/v1/result/" + taskId).responseType(CanonicalResponse.class).build());
                        LOG.atInfo().addKeyValue("event", "docling.task.result_received")
                                .addKeyValue("task_id", taskId)
                                .addKeyValue("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                                .log("Received Docling result; content validation follows");
                        return result;
                    }
                    case FAILURE -> throw new ResponseFailure(ExtractionFailure.INTERNAL);
                    case PENDING, STARTED -> {
                        Thread.sleep(Duration.ofNanos(Math.min(Duration.ofSeconds(5).toNanos(), remaining())));
                        try {
                            status = pollTaskStatus(TaskStatusPollRequest.builder().taskId(taskId).build());
                        } catch (DoclingServeClientException e) {
                            int code = e.getStatusCode();
                            boolean transientRead = code == 408 || code == 429 || (code >= 500 && code <= 599)
                                    || e.getCause() instanceof HttpTimeoutException
                                    || e.getCause() instanceof java.net.ConnectException
                                    || e.getCause() instanceof java.net.SocketException;
                            if (Thread.currentThread().isInterrupted() || !transientRead) throw e;
                            // Retry only status reads for this task, never submission or single-use results.
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            LOG.atWarn().addKeyValue("event", "docling.task.interrupted").addKeyValue("task_id", taskId)
                    .addKeyValue("stage", stage)
                    .addKeyValue("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("Docling observation interrupted; remote cancellation is not confirmed");
            Thread.currentThread().interrupt();
            throw new DoclingServeClientException(e);
        } catch (RuntimeException e) {
            LOG.atWarn().addKeyValue("event", "docling.task.failed").addKeyValue("task_id", taskId)
                    .addKeyValue("stage", stage).addKeyValue("error_type", e.getClass().getName())
                    .addKeyValue("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("Docling observation failed; no replacement task was submitted");
            throw e;
        } finally {
            deadline.remove();
        }
    }

    private long remaining() {
        Long end = deadline.get();
        long nanos = end == null ? HTTP_TIMEOUT.toNanos() : end - System.nanoTime();
        if (nanos <= 0) throw new DoclingServeClientException(new HttpTimeoutException("Docling task deadline exceeded"));
        return nanos;
    }

    @Override protected <T> T readValue(String json, Class<T> type) { return mapper.readValue(json, type); }
    @Override protected <T> String writeValueAsString(T value) { return mapper.writeValueAsString(value); }

    CanonicalResponse convertFile(java.nio.file.Path file, String extension,
                                  DoclingProperties properties) throws IOException {
        String boundary = "memoryos-" + java.util.UUID.randomUUID();
        StringBuilder fields = new StringBuilder();
        // Serialize the SDK options once; multipart and JSON use the same names, values and defaults.
        mapper.valueToTree(properties.options()).properties().forEach(option -> {
            var values = option.getValue().isArray() ? option.getValue() : java.util.List.of(option.getValue());
            for (var value : values) fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                    .append(option.getKey()).append("\"\r\n\r\n").append(value.asString()).append("\r\n");
        });
        fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document")
                .append(extension).append("\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(fields.toString()),
                HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        var endpoint = java.net.URI.create(properties.endpoint().toString().replaceAll("/+$", "") + "/v1/convert/file");
        var request = HttpRequest.newBuilder(endpoint).timeout(properties.timeout().plusSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Accept", "application/json").POST(body).build();
        return execute(request, CanonicalResponse.class);
    }

    @Override
    protected <T> T execute(HttpRequest request, Class<T> type) {
        int status = -1;
        try {
            var builder = HttpRequest.newBuilder(request, (name, value) -> !name.equalsIgnoreCase("X-Api-Key"));
            if (!apiKey.isBlank()) builder.header("X-Api-Key", apiKey);
            if (deadline.get() != null) {
                builder.timeout(Duration.ofNanos(Math.min(HTTP_TIMEOUT.toNanos(), remaining())));
            }
            request = builder.build();
            var response = transport.send(request,
                    HttpResponse.BodyHandlers.limiting(info -> info.statusCode() >= 300
                            ? HttpResponse.BodySubscribers.replacing("")
                            : HttpResponse.BodyHandlers.ofString().apply(info), 67_108_864));
            status = response.statusCode();
            if (response.statusCode() >= 300) {
                throw new DoclingServeClientException("Docling HTTP request failed", response.statusCode(), null);
            }
            return getResponse(request, response, type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DoclingServeClientException(e);
        } catch (IOException e) {
            throw new DoclingServeClientException(e);
        } catch (DoclingServeClientException e) {
            throw e;
        } catch (RuntimeException e) {
            var failure = new DoclingServeClientException("Docling response failed", status, null);
            failure.initCause(e);
            throw failure;
        }
    }

    @Override public Builder toBuilder() { return new Builder(this); }
    @Override public void close() { transport.shutdownNow(); }

    static final class Builder extends DoclingServeClientBuilder<BoundedDoclingClient, Builder> {
        private Duration taskTimeout = Duration.ofMinutes(10);
        Builder() { }
        private String accessKey = "";
        Builder(BoundedDoclingClient client) {
            super(client);
            taskTimeout = client.taskTimeout;
            accessKey = client.apiKey;
        }
        @Override public Builder apiKey(@org.jspecify.annotations.Nullable String key) {
            super.apiKey(key);
            accessKey = key == null ? "" : key;
            return this;
        }
        @Override public BoundedDoclingClient build() { return new BoundedDoclingClient(this); }
    }

    static final class ResponseFailure extends RuntimeException {
        final ExtractionFailure failure;

        ResponseFailure(ExtractionFailure failure) {
            super("Docling conversion rejected: " + failure.name());
            this.failure = failure;
        }
    }

    /** Retain supported custom metadata without a lossy SDK document-model round trip. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CanonicalResponse(@Nullable JsonNode document, @Nullable List<ErrorItem> errors,
                             @Nullable String status,
                             @JsonProperty("response_type") @Nullable ResponseType responseType) {
        CanonicalResponse {
            // Serve omits this discriminator for in-body results.
            if (responseType != null && responseType != ResponseType.IN_BODY) {
                throw new ResponseFailure(ExtractionFailure.MALFORMED);
            }
        }
    }

    @JsonDeserialize(using = ErrorDeserializer.class)
    private abstract static class ErrorMapping {}

    // SDK 0.6.5 omits category. Inspect only each error object, not a second document tree.
    private static final class ErrorDeserializer extends ValueDeserializer<ErrorItem> {
        @Override
        public ErrorItem deserialize(JsonParser parser, DeserializationContext context) {
            var error = context.readTree(parser);
            throw new ResponseFailure(switch (error.path("category").asString("")) {
                case "timeout" -> ExtractionFailure.TIMEOUT;
                case "policy" -> ExtractionFailure.WRITE_LIMIT;
                default -> ExtractionFailure.MALFORMED;
            });
        }
    }
}
