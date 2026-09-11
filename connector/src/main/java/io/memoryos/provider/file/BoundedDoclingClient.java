package io.memoryos.provider.file;

import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import ai.docling.serve.api.convert.response.ErrorItem;
import ai.docling.serve.api.task.request.TaskStatusPollRequest;
import ai.docling.serve.api.task.response.TaskStatusPollResponse;
import ai.docling.serve.client.DoclingServeClient;
import ai.docling.serve.client.DoclingServeClientException;
import ai.docling.serve.client.operations.RequestContext;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Official SDK operations with a bounded response transport; no response/body logging. */
final class BoundedDoclingClient extends DoclingServeClient implements AutoCloseable {
    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(2);
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final JsonMapper mapper = JsonMapper.builder().addMixIn(ErrorItem.class, ErrorMapping.class).build();
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private final Duration taskTimeout;
    private final HttpClient transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    private BoundedDoclingClient(Builder builder) {
        super(builder);
        taskTimeout = builder.taskTimeout;
    }

    static BoundedDoclingClient create(DoclingProperties properties) {
        var builder = new Builder();
        builder.taskTimeout = properties.timeout().plusMinutes(5);
        return builder.baseUrl(properties.endpoint()).apiKey(properties.apiKey()).connectTimeout(Duration.ofSeconds(5))
                .readTimeout(HTTP_TIMEOUT).logRequests(false).logResponses(false).build();
    }

    @Override
    public ConvertDocumentResponse convertSource(ConvertDocumentRequest request) {
        deadline.set(System.nanoTime() + taskTimeout.toNanos());
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var status = executePost(RequestContext.<ConvertDocumentRequest, TaskStatusPollResponse>builder()
                    .uri("/v1/convert/source/async").request(request)
                    .responseType(TaskStatusPollResponse.class).build());
            if (status == null || status.getTaskId() == null || !TASK_ID.matcher(status.getTaskId()).matches()) {
                throw new ResponseFailure(ExtractionFailure.INTERNAL);
            }
            String taskId = status.getTaskId();
            while (true) {
                remaining();
                if (status == null || !taskId.equals(status.getTaskId()) || status.getTaskStatus() == null) {
                    throw new ResponseFailure(ExtractionFailure.INTERNAL);
                }
                switch (status.getTaskStatus()) {
                    case SUCCESS -> {
                        return executeGet(RequestContext.<Object, ConvertDocumentResponse>builder()
                                .uri("/v1/result/" + taskId).responseType(ConvertDocumentResponse.class).build());
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
            Thread.currentThread().interrupt();
            throw new DoclingServeClientException(e);
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

    @Override
    protected <T> T execute(HttpRequest request, Class<T> type) {
        int status = -1;
        try {
            request = HttpRequest.newBuilder(request, (name, value) -> true)
                    .timeout(Duration.ofNanos(Math.min(HTTP_TIMEOUT.toNanos(), remaining()))).build();
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
        Builder(BoundedDoclingClient client) {
            super(client);
            taskTimeout = client.taskTimeout;
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
