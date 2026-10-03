package io.memoryos.chat.interpreter;

import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import io.memoryos.shared.OutboundHttp.ResponseTooLargeException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * memoryos-interpreter REST client, following the Onyx 40eb240df {@code code_interpreter_client.py} endpoints and
 * timeouts. As in Onyx, failure text (including a bounded error response body) reaches the model; the API key never does.
 */
@Component
@NamedInterface("interpreter")
public class InterpreterClient {
    /** Largest generated file copied into MemoryOS object storage (MEM-110 design). */
    public static final int MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024;
    private static final int JSON_LIMIT = 8 * 1024 * 1024;
    /** Guard on accumulated stdout/stderr; the service caps its own output well below this. */
    private static final int MAX_STREAM_CHARACTERS = 4 * 1024 * 1024;
    private static final int ERROR_BODY_BYTES = 2048;
    private static final long HEALTH_CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InterpreterProperties properties;
    private final LongSupplier nanos;
    private volatile @Nullable CachedHealth cached;

    public record Health(boolean connected, String error, String version) {
        public boolean healthy() { return connected && error.isEmpty(); }
    }
    public record StagedFile(String path, String fileId) {}
    public record WorkspaceFile(String path, String kind, @Nullable String fileId) {}
    public record Execution(String stdout, String stderr, @Nullable Integer exitCode, boolean timedOut, List<WorkspaceFile> files) {}
    private record CachedHealth(Health health, long at) {}

    /** All execution slots are in use (HTTP 429). */
    public static final class BusyException extends IOException {
        BusyException(String detail) { super(detail); }
    }
    /** A generated file is larger than {@link #MAX_DOWNLOAD_BYTES}. */
    public static final class TooLargeException extends IOException {
        TooLargeException() { super("Interpreter file too large"); }
    }

    @Autowired
    public InterpreterClient(InterpreterProperties properties) {
        this(properties, System::nanoTime);
    }

    InterpreterClient(InterpreterProperties properties, LongSupplier nanos) {
        this.properties = properties;
        this.nanos = nanos;
    }

    public boolean configured() { return properties.configured(); }

    /** Uncached health, as the Onyx admin endpoint reports it; also refreshes the cache. */
    public Health health() {
        Health health;
        if (!configured()) health = new Health(false, "Code Interpreter is not configured", "0.0.0");
        else {
            try {
                // Read with exchange: a failed status is a health answer here, not a failure.
                health = client(5, JSON_LIMIT).get().uri("/health").exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status < 200 || status >= 300)
                        return new Health(true, "Code Interpreter service returned HTTP " + status, "0.0.0");
                    var body = JSON.readTree(response.getBody().readAllBytes());
                    return "ok".equals(body.path("status").asString(""))
                            ? new Health(true, "", body.path("version").asString("0.0.0"))
                            : new Health(true, "Code Interpreter service is not healthy", body.path("version").asString("0.0.0"));
                });
            } catch (RuntimeException unreachable) {
                health = new Health(false, "Unable to reach the Code Interpreter service", "0.0.0");
            }
        }
        cached = new CachedHealth(health, nanos.getAsLong());
        return health;
    }

    /** Health for tool registration, cached for 30 seconds as in Onyx. */
    public boolean healthy() {
        var current = cached;
        if (current != null && nanos.getAsLong() - current.at() < HEALTH_CACHE_NANOS) return current.health().healthy();
        return health().healthy();
    }

    /** Streams one file to {@code POST /v1/files}; returns the service file id. */
    public String upload(String filename, String mediaType, InputStream content) throws IOException {
        var part = new HttpHeaders();
        part.setContentType(MediaType.parseMediaType(mediaType));
        var file = new AbstractResource() {
            @Override public InputStream getInputStream() { return content; }
            @Override public long contentLength() { return -1; }
            @Override public String getFilename() { return filename; }
            @Override public String getDescription() { return "interpreter upload"; }
        };
        JsonNode stored = call(() -> api(30, JSON_LIMIT).upload(new HttpEntity<>(file, part)));
        if (stored == null) throw new IOException("Interpreter response missing file_id");
        return text(stored.path("file_id"), "file_id");
    }

    /** Runs code with {@code POST /v1/execute}; the HTTP timeout is 10 seconds longer than the execution timeout. */
    public Execution execute(String code, int timeoutMs, List<StagedFile> files) throws IOException {
        JsonNode result = call(() -> api(timeoutMs / 1000 + 10, JSON_LIMIT).execute(body(code, timeoutMs, files)));
        if (result == null) throw new IOException("Interpreter returned no result");
        return execution(result, result.path("stdout").asString(""), result.path("stderr").asString(""));
    }

    /** Receives one stdout/stderr chunk while the code runs; throwing aborts the run. */
    public interface OutputListener {
        void output(String stream, String data) throws IOException;
    }

    /**
     * Runs code with {@code POST /v1/execute/stream}, reporting output as it is produced. Aborting the read (a listener
     * that throws, or an interrupted thread) closes the response without reading the rest of it, which closes the
     * connection, kills the executor container and frees the service's execution slot instead of holding it until the
     * timeout.
     */
    public Execution executeStream(String code, int timeoutMs, List<StagedFile> files, OutputListener listener) throws IOException {
        // The stream has its own limits on a frame and on the output it accumulates.
        return Objects.requireNonNull(call(() -> client(timeoutMs / 1000L + 10, Integer.MAX_VALUE).post().uri("/v1/execute/stream")
                .contentType(MediaType.APPLICATION_JSON).body(body(code, timeoutMs, files)).exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) failed(request, response);
                    try (var reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        return consume(reader, listener);
                    }
                })));
    }

    /** Reads {@code event:}/{@code data:} frames until the terminal {@code result}, accumulating output as Onyx does. */
    private static Execution consume(BufferedReader reader, OutputListener listener) throws IOException {
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        String event = "";
        var data = new StringBuilder();
        for (String line; (line = readLine(reader)) != null; ) {
            if (!line.isEmpty()) {
                if (line.startsWith("event:")) event = line.substring(6).strip();
                else if (line.startsWith("data:")) data.append(line.substring(5).strip());
                if (data.length() > JSON_LIMIT) throw new IOException("Interpreter stream frame too large");
                continue;
            }
            if (data.isEmpty()) continue;
            var json = JSON.readTree(data.toString());
            data.setLength(0);
            switch (event) {
                case "output" -> {
                    String stream = json.path("stream").asString("stdout");
                    String chunk = json.path("data").asString("");
                    var target = "stderr".equals(stream) ? stderr : stdout;
                    if (target.length() + chunk.length() > MAX_STREAM_CHARACTERS)
                        throw new IOException("Interpreter stream exceeded its output limit");
                    target.append(chunk);
                    listener.output(stream, chunk);
                }
                case "error" -> throw new IOException("Code interpreter error: " + json.path("message").asString(""));
                case "result" -> { return execution(json, stdout.toString(), stderr.toString()); }
                default -> { } // A newer service may add events; the terminal result still decides the outcome.
            }
        }
        throw new IOException("Code interpreter stream ended without a result event");
    }

    /** The outcome and workspace files of a run, as the plain call and the stream's result event both report them. */
    private static Execution execution(JsonNode json, String stdout, String stderr) {
        var workspace = new ArrayList<WorkspaceFile>();
        for (var file : json.path("files")) {
            var id = file.path("file_id");
            workspace.add(new WorkspaceFile(file.path("path").asString(""), file.path("kind").asString(""),
                    id.isString() ? id.asString() : null));
        }
        var exit = json.path("exit_code");
        return new Execution(stdout, stderr, exit.isNumber() ? exit.asInt() : null,
                json.path("timed_out").asBoolean(false), List.copyOf(workspace));
    }

    /**
     * One SSE line, refusing one longer than {@link #JSON_LIMIT}. {@code BufferedReader.readLine} would buffer an
     * unterminated line of any length first, so the limit has to be enforced while reading.
     */
    private static @Nullable String readLine(Reader reader) throws IOException {
        var line = new StringBuilder();
        for (int character; (character = reader.read()) != -1; ) {
            if (character == '\n') return line.toString();
            if (character == '\r') continue; // CRLF frames; a lone CR cannot end a line here.
            if (line.length() >= JSON_LIMIT) throw new IOException("Interpreter stream line too long");
            line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static Map<String, Object> body(String code, int timeoutMs, List<StagedFile> files) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("timeout_ms", timeoutMs);
        if (!files.isEmpty()) body.put("files", files.stream().map(f -> Map.of("path", f.path(), "file_id", f.fileId())).toList());
        return body;
    }

    /** Downloads a generated file of at most {@link #MAX_DOWNLOAD_BYTES}. */
    public byte[] download(String fileId) throws IOException {
        String id = pathSegment(fileId);
        try {
            byte[] bytes = call(() -> api(30, MAX_DOWNLOAD_BYTES).download(id));
            return bytes == null ? new byte[0] : bytes;
        } catch (ResponseTooLargeException large) {
            throw new TooLargeException();
        }
    }

    public void delete(String fileId) throws IOException {
        String id = pathSegment(fileId);
        call(() -> {
            api(10, JSON_LIMIT).delete(id);
            return null;
        });
    }

    /** A client for one call: the service's address and key, the call's deadline and the largest answer it may read. */
    private RestClient client(long timeoutSeconds, int maxResponseBytes) {
        return OutboundHttp.builder(new Limits(Duration.ofSeconds(timeoutSeconds), maxResponseBytes), InterpreterClient::failed)
                .baseUrl(properties.baseUrl())
                .defaultHeaders(headers -> { if (!properties.apiKey().isEmpty()) headers.set("X-Api-Key", properties.apiKey()); })
                .build();
    }

    private InterpreterApi api(long timeoutSeconds, int maxResponseBytes) {
        return OutboundHttp.service(InterpreterApi.class, client(timeoutSeconds, maxResponseBytes));
    }

    /** Onyx surfaces the HTTP error text as it is; the service's response body is kept, bounded. */
    private static void failed(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        // A file that is already gone is deleted.
        if (status == 404 && request.getMethod() == HttpMethod.DELETE) return;
        byte[] body = response.getBody().readNBytes(ERROR_BODY_BYTES);
        String detail = "Code interpreter returned HTTP " + status;
        String text = new String(body, StandardCharsets.UTF_8).strip();
        if (!text.isEmpty()) detail += ": " + text;
        if (status == 429) throw new BusyException(detail);
        throw new IOException(detail);
    }

    @FunctionalInterface private interface Call<T> { @Nullable T run(); }

    /** {@code RestClient} throws unchecked; callers of this class catch {@code IOException} and its two subclasses. */
    private <T> @Nullable T call(Call<T> call) throws IOException {
        if (!configured()) throw new IOException("Interpreter not configured");
        try {
            return call.run();
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        } catch (RestClientException failed) {
            for (Throwable cause = failed.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseTooLargeException large) throw large;
                if (cause instanceof IOException transport) throw transport;
            }
            throw new IOException("Code interpreter request failed");
        }
    }

    private static String text(JsonNode node, String name) throws IOException {
        if (!node.isString() || node.asString().isBlank()) throw new IOException("Interpreter response missing " + name);
        return node.asString();
    }

    private static String pathSegment(String fileId) throws IOException {
        if (!fileId.matches("[0-9a-fA-F-]{1,64}")) throw new IOException("Invalid interpreter file id");
        return new String(fileId.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
