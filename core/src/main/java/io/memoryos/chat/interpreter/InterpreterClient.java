package io.memoryos.chat.interpreter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.InputStreamBody;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * memoryos-interpreter REST client, following the Onyx 40eb240df {@code code_interpreter_client.py} endpoints and
 * timeouts. Service response bodies and the API key never reach the model or the UI.
 */
@Component
public class InterpreterClient implements AutoCloseable {
    /** Largest generated file copied into MemoryOS object storage (MEM-110 design). */
    public static final int MAX_DOWNLOAD_BYTES = 25 * 1024 * 1024;
    private static final int JSON_LIMIT = 8 * 1024 * 1024;
    /** Guard on accumulated stdout/stderr; the service caps its own output well below this. */
    private static final int MAX_STREAM_CHARACTERS = 4 * 1024 * 1024;
    private static final long HEALTH_CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InterpreterProperties properties;
    private final LongSupplier nanos;
    private final CloseableHttpClient client;
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
        BusyException() { super("Interpreter busy"); }
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
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(20).setMaxConnPerRoute(20)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(90)).build());
        this.client = HttpClients.custom().setConnectionManager(pool.build()).disableAutomaticRetries()
                .disableCookieManagement().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(30)).build()).build();
    }

    public boolean configured() { return properties.configured(); }

    /** Uncached health, as the Onyx admin endpoint reports it; also refreshes the cache. */
    public Health health() {
        Health health;
        if (!configured()) health = new Health(false, "Code Interpreter is not configured", "0.0.0");
        else {
            try {
                health = send(request("GET", "/health", 5), response -> {
                    if (response.status() < 200 || response.status() >= 300)
                        return new Health(true, "Code Interpreter service returned HTTP " + response.status(), "0.0.0");
                    var body = JSON.readTree(response.body());
                    return "ok".equals(body.path("status").asString(""))
                            ? new Health(true, "", body.path("version").asString("0.0.0"))
                            : new Health(true, "Code Interpreter service is not healthy", body.path("version").asString("0.0.0"));
                });
            } catch (IOException | RuntimeException unreachable) {
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
        var request = request("POST", "/v1/files", 30);
        request.setEntity(MultipartEntityBuilder.create().setMode(HttpMultipartMode.EXTENDED)
                .addPart("file", new InputStreamBody(content, ContentType.parse(mediaType), filename)).build());
        return send(request, response -> {
            requireSuccess(response.status());
            return text(JSON.readTree(response.body()).path("file_id"), "file_id");
        });
    }

    /** Runs code with {@code POST /v1/execute}; the HTTP timeout is 10 seconds longer than the execution timeout. */
    public Execution execute(String code, int timeoutMs, List<StagedFile> files) throws IOException {
        var request = request("POST", "/v1/execute", timeoutMs / 1000 + 10);
        request.setEntity(new StringEntity(JSON.writeValueAsString(body(code, timeoutMs, files)), ContentType.APPLICATION_JSON));
        return send(request, response -> {
            requireSuccess(response.status());
            var json = JSON.readTree(response.body());
            var workspace = new ArrayList<WorkspaceFile>();
            for (var file : json.path("files")) {
                var id = file.path("file_id");
                workspace.add(new WorkspaceFile(file.path("path").asString(""), file.path("kind").asString(""),
                        id.isString() ? id.asString() : null));
            }
            var exit = json.path("exit_code");
            return new Execution(json.path("stdout").asString(""), json.path("stderr").asString(""),
                    exit.isNumber() ? exit.asInt() : null, json.path("timed_out").asBoolean(false), List.copyOf(workspace));
        });
    }

    /** Receives one stdout/stderr chunk while the code runs; throwing aborts the run. */
    public interface OutputListener {
        void output(String stream, String data) throws IOException;
    }

    /**
     * Runs code with {@code POST /v1/execute/stream}, reporting output as it is produced. Aborting the read (a listener
     * that throws, or an interrupted thread) closes the connection, which kills the executor container and frees the
     * service's execution slot instead of holding it until the timeout.
     */
    public Execution executeStream(String code, int timeoutMs, List<StagedFile> files, OutputListener listener) throws IOException {
        if (!configured()) throw new IOException("Interpreter not configured");
        var request = request("POST", "/v1/execute/stream", timeoutMs / 1000L + 10);
        request.setEntity(new StringEntity(JSON.writeValueAsString(body(code, timeoutMs, files)), ContentType.APPLICATION_JSON));
        return client.execute(request, response -> {
            requireSuccess(response.getCode());
            var entity = response.getEntity();
            if (entity == null) throw new IOException("Interpreter returned no stream");
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(entity.getContent(), StandardCharsets.UTF_8))) {
                return consume(reader, listener);
            } catch (IOException | RuntimeException failure) {
                request.cancel(); // Abandoning the body kills the container; never leave the slot held.
                throw failure;
            }
        });
    }

    /** Reads {@code event:}/{@code data:} frames until the terminal {@code result}, accumulating output as Onyx does. */
    private static Execution consume(java.io.BufferedReader reader, OutputListener listener) throws IOException {
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        String event = "";
        var data = new StringBuilder();
        for (String line; (line = reader.readLine()) != null; ) {
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
                case "error" -> throw new IOException("Interpreter stream failed");
                case "result" -> {
                    var workspace = new ArrayList<WorkspaceFile>();
                    for (var file : json.path("files")) {
                        var id = file.path("file_id");
                        workspace.add(new WorkspaceFile(file.path("path").asString(""), file.path("kind").asString(""),
                                id.isString() ? id.asString() : null));
                    }
                    var exit = json.path("exit_code");
                    return new Execution(stdout.toString(), stderr.toString(), exit.isNumber() ? exit.asInt() : null,
                            json.path("timed_out").asBoolean(false), List.copyOf(workspace));
                }
                default -> { } // A newer service may add events; the terminal result still decides the outcome.
            }
        }
        throw new IOException("Interpreter stream ended without a result");
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
        var request = request("GET", "/v1/files/" + pathSegment(fileId), 30);
        return client.execute(request, response -> {
            requireSuccess(response.getCode());
            var entity = response.getEntity();
            if (entity == null) return new byte[0];
            if (entity.getContentLength() > MAX_DOWNLOAD_BYTES) { request.cancel(); throw new TooLargeException(); }
            try (var stream = entity.getContent()) {
                var bytes = stream.readNBytes(MAX_DOWNLOAD_BYTES + 1);
                if (bytes.length > MAX_DOWNLOAD_BYTES) { request.cancel(); throw new TooLargeException(); }
                return bytes;
            }
        });
    }

    public void delete(String fileId) throws IOException {
        send(request("DELETE", "/v1/files/" + pathSegment(fileId), 10), response -> {
            if (response.status() != 404) requireSuccess(response.status());
            return null;
        });
    }

    private record Response(int status, byte[] body) {}
    private interface Handler<T> { T handle(Response response) throws IOException; }

    private HttpUriRequestBase request(String method, String path, long timeoutSeconds) {
        var request = new HttpUriRequestBase(method, URI.create(properties.baseUrl() + path));
        if (!properties.apiKey().isEmpty()) request.setHeader("X-Api-Key", properties.apiKey());
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(timeoutSeconds)).build());
        return request;
    }

    private <T> T send(HttpUriRequestBase request, Handler<T> handler) throws IOException {
        if (!configured()) throw new IOException("Interpreter not configured");
        return client.execute(request, response -> {
            var entity = response.getEntity();
            byte[] body = new byte[0];
            if (entity != null) {
                try (var stream = entity.getContent()) { body = stream.readNBytes(JSON_LIMIT + 1); }
                if (body.length > JSON_LIMIT) { request.cancel(); throw new IOException("Interpreter response too large"); }
            }
            return handler.handle(new Response(response.getCode(), body));
        });
    }

    private static void requireSuccess(int status) throws IOException {
        if (status == 429) throw new BusyException();
        if (status < 200 || status >= 300) throw new IOException("Interpreter returned HTTP " + status);
    }

    private static String text(JsonNode node, String name) throws IOException {
        if (!node.isString() || node.asString().isBlank()) throw new IOException("Interpreter response missing " + name);
        return node.asString();
    }

    private static String pathSegment(String fileId) throws IOException {
        if (!fileId.matches("[0-9a-fA-F-]{1,64}")) throw new IOException("Invalid interpreter file id");
        return new String(fileId.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() throws IOException { client.close(); }
}
