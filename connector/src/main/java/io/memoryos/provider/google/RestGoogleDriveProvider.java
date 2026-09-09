package io.memoryos.provider.google;

import static io.memoryos.connector.GoogleDriveProviderException.Failure.*;

import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.ingestion.ExtractionException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class RestGoogleDriveProvider implements GoogleDriveProvider, AutoCloseable {
    private static final String FILE_FIELDS = "id,name,mimeType,version,md5Checksum,modifiedTime,trashed,parents,driveId,shortcutDetails(targetId)";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private final GoogleDriveProviderProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public RestGoogleDriveProvider(GoogleDriveProviderProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        // Invalid Google configuration fails open() rather than preventing unrelated FILE startup.
        Duration connect = properties.connectTimeout();
        if (connect.isNegative() || connect.isZero() || connect.compareTo(Duration.ofSeconds(30)) > 0) connect = Duration.ofSeconds(3);
        client = HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public Session open(Credential credential) {
        properties.validate();
        byte[] refresh = credential.refreshToken();
        byte[] secret = credential.clientSecret();
        try {
            String form = "grant_type=refresh_token&client_id=" + encode(credential.clientId())
                    + "&client_secret=" + encode(new String(secret, StandardCharsets.UTF_8))
                    + "&refresh_token=" + encode(new String(refresh, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(properties.tokenUri())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            JsonNode response = json(exchange(request, new Budget(), 65_536, true));
            String bearer = required(response, "access_token");
            if (!"Bearer".equalsIgnoreCase(required(response, "token_type"))) throw failure(MALFORMED);
            String rotated = optional(response, "refresh_token");
            return new DriveSession(bearer, rotated == null ? null : rotated.getBytes(StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(refresh, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override public void close() { client.close(); }

    private final class DriveSession implements Session {
        private @Nullable String bearer;
        private final byte @Nullable [] rotated;

        private DriveSession(String bearer, byte @Nullable [] rotated) { this.bearer = bearer; this.rotated = rotated; }


        @Override public FilePage listFiles(String parentId, @Nullable String pageToken) {
            if ("root".equals(parentId)) throw failure(MALFORMED);
            String query = "'" + fileId(parentId) + "' in parents";
            String path = "/files?q=" + encode("trashed = false and (" + query + ")")
                    + "&spaces=drive&corpora=user&orderBy=folder,name&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + "&pageSize=" + properties.pageSize() + "&fields=" + encode("nextPageToken,incompleteSearch,files(" + FILE_FIELDS + ")")
                    + (pageToken == null ? "" : "&pageToken=" + encode(token(pageToken)));
            JsonNode response = get(properties.driveApiBaseUrl(), path, new Budget());
            if (response.path("incompleteSearch").asBoolean(false)) throw failure(INCONSISTENT);
            JsonNode entries = array(response, "files");
            if (entries.size() > properties.pageSize()) throw failure(LIMIT_EXCEEDED);
            List<FileMetadata> files = new ArrayList<>(entries.size());
            for (JsonNode entry : entries) files.add(parseFile(entry));
            String next = optional(response, "nextPageToken");
            if (next != null && next.equals(pageToken)) throw failure(MALFORMED);
            return new FilePage(files, next);
        }

        @Override public FileMetadata metadata(String id) { return metadata(id, new Budget()); }

        private FileMetadata metadata(String id, Budget budget) {
            return parseFile(get(properties.driveApiBaseUrl(), "/files/" + fileId(id)
                    + "?supportsAllDrives=true&fields=" + encode(FILE_FIELDS), budget));
        }

        @Override public AcquiredContent acquire(FileMetadata file) {
            supported(file);
            if (file.folder()) throw failure(UNSUPPORTED);
            Budget budget = new Budget();
            sameVersion(file, metadata(file.id(), budget));
            String mime = file.mimeType();
            SourceInputFormat format = SourceInputFormat.BINARY;
            String filename = file.name();
            byte[] bytes;
            if ("application/vnd.google-apps.spreadsheet".equals(mime)) {
                bytes = sheets(file, budget);
                format = SourceInputFormat.GOOGLE_SHEETS;
                mime = "application/json";
            } else if ("application/vnd.google-apps.document".equals(mime)) {
                bytes = docs(file, budget);
                format = SourceInputFormat.GOOGLE_DOCS;
                mime = "application/json";
            } else if ("application/vnd.google-apps.presentation".equals(mime)) {
                mime = PPTX;
                filename = filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pptx") ? filename : filename + ".pptx";
                bytes = request(properties.driveApiBaseUrl(), "/files/" + fileId(file.id())
                        + "/export?mimeType=" + encode(PPTX), budget, properties.maxBinaryBytes());
            } else {
                if (mime.startsWith("application/vnd.google-apps.")) throw failure(UNSUPPORTED);
                bytes = request(properties.driveApiBaseUrl(), "/files/" + fileId(file.id())
                        + "?alt=media&supportsAllDrives=true", budget, properties.maxBinaryBytes());
                verifyChecksum(file, bytes);
            }
            if (bytes.length == 0) throw failure(MALFORMED);
            sameVersion(file, metadata(file.id(), budget));
            budget.check();
            return new AcquiredContent(filename, mime, bytes, new SourceInputDescriptor(format,
                    file.id(), file.version(), "https://drive.google.com/file/d/" + file.id() + "/view"));
        }

        private byte[] sheets(FileMetadata file, Budget budget) {
            String path = "/spreadsheets/" + fileId(file.id());
            JsonNode structure = get(properties.sheetsApiBaseUrl(), path + "?fields="
                    + encode("spreadsheetId,properties(title,locale,timeZone),namedRanges,sheets(properties,merges)"), budget);
            if (!file.id().equals(required(structure, "spreadsheetId"))) throw failure(MALFORMED);
            JsonNode sheetNodes = array(structure, "sheets");
            if (sheetNodes.size() < 1 || sheetNodes.size() > properties.maxTabs()) throw failure(LIMIT_EXCEEDED);
            long cells = 0;
            long requests = budget.requests + 1; // Leave room for the final Drive version fence.
            for (JsonNode sheet : sheetNodes) {
                JsonNode props = sheet.path("properties");
                if (!"GRID".equals(props.path("sheetType").asString("GRID"))) throw failure(UNSUPPORTED);
                int rows = props.path("gridProperties").path("rowCount").asInt(-1);
                int columns = props.path("gridProperties").path("columnCount").asInt(-1);
                if (rows < 1 || columns < 1 || (cells += (long) rows * columns) > properties.maxCells()) throw failure(LIMIT_EXCEEDED);
                requests += (rows + 499L) / 500;
                if (requests > properties.maxRequests()) throw failure(LIMIT_EXCEEDED);
            }
            for (JsonNode node : sheetNodes) {
                ObjectNode sheet = (ObjectNode) node;
                JsonNode props = sheet.path("properties");
                String title = required(props, "title");
                int rows = props.path("gridProperties").path("rowCount").asInt();
                int columns = props.path("gridProperties").path("columnCount").asInt();
                ArrayNode pages = sheet.putArray("pages");
                for (int row = 0; row < rows; row += 500) {
                    String range = "'" + title.replace("'", "''") + "'!A" + (row + 1) + ":" + columnName(columns) + Math.min(rows, row + 500);
                    JsonNode response = get(properties.sheetsApiBaseUrl(), path + "?ranges=" + encode(range)
                            + "&includeGridData=true&fields=" + encode("sheets(properties(sheetId),data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue,formattedValue,userEnteredFormat(numberFormat,textFormat(link)),effectiveFormat(numberFormat,textFormat(link)),note,hyperlink,textFormatRuns,chipRuns))))"), budget);
                    JsonNode responseSheets = array(response, "sheets");
                    if (responseSheets.size() != 1 || responseSheets.get(0).path("properties").path("sheetId").asInt(0)
                            != props.path("sheetId").asInt(0)) throw failure(INCONSISTENT);
                    ObjectNode page = pages.addObject();
                    page.put("startRow", row);
                    page.put("endRow", Math.min(rows, row + 500));
                    page.put("range", range);
                    JsonNode data = responseSheets.get(0).path("data");
                    if (!data.isMissingNode() && !data.isArray()) throw failure(MALFORMED);
                    page.set("data", data.isMissingNode() ? mapper.createArrayNode() : data);
                }
            }
            return snapshot(file, "GOOGLE_SHEETS", structure, budget);
        }

        private byte[] docs(FileMetadata file, Budget budget) {
            String path = "/documents/" + fileId(file.id());
            JsonNode document = get(properties.docsApiBaseUrl(), path
                    + "?includeTabsContent=true&suggestionsViewMode=PREVIEW_WITHOUT_SUGGESTIONS", budget);
            if (!file.id().equals(required(document, "documentId"))) throw failure(MALFORMED);
            String revision = optional(document, "revisionId");
            int tabs = countTabs(array(document, "tabs"), 0);
            if (tabs == 0 || tabs > properties.maxTabs()) throw failure(LIMIT_EXCEEDED);
            // Docs omits revisionId for read-only collaborators; the surrounding Drive version fence still applies.
            if (revision != null) {
                JsonNode fence = get(properties.docsApiBaseUrl(), path + "?fields=documentId,revisionId", budget);
                if (!file.id().equals(required(fence, "documentId")) || !revision.equals(required(fence, "revisionId"))) throw failure(INCONSISTENT);
            }
            return snapshot(file, "GOOGLE_DOCS", document, budget);
        }

        private int countTabs(JsonNode tabs, int depth) {
            if (depth > 100) throw failure(LIMIT_EXCEEDED);
            if (!tabs.isMissingNode() && !tabs.isArray()) throw failure(MALFORMED);
            int count = 0;
            for (JsonNode tab : tabs) {
                if (!tab.path("documentTab").isObject()) throw failure(UNSUPPORTED);
                count += 1 + countTabs(tab.path("childTabs"), depth + 1);
                if (count > properties.maxTabs()) throw failure(LIMIT_EXCEEDED);
            }
            return count;
        }

        private byte[] snapshot(FileMetadata file, String kind, JsonNode content, Budget budget) {
            ObjectNode snapshot = NativeSnapshot.envelope(mapper, file, kind, content);
            long[] counts = new long[3];
            try { NativeSnapshot.checkTree(snapshot, 0, counts); }
            catch (ExtractionException exception) { throw failure(LIMIT_EXCEEDED); }
            if (counts[2] > properties.maxCells()) throw failure(LIMIT_EXCEEDED);
            byte[] bytes = mapper.writeValueAsBytes(snapshot);
            if (bytes.length > properties.maxSnapshotBytes()) throw failure(LIMIT_EXCEEDED);
            budget.check();
            return bytes;
        }

        private JsonNode get(URI base, String path, Budget budget) {
            byte[] bytes = request(base, path, budget, properties.maxSnapshotBytes());
            budget.nativeBytes += bytes.length;
            if (budget.nativeBytes > properties.maxSnapshotBytes()) throw failure(LIMIT_EXCEEDED);
            return json(bytes);
        }

        private byte[] request(URI base, String path, Budget budget, int limit) {
            if (bearer == null) throw failure(AUTHENTICATION);
            URI uri = URI.create(base.toString().replaceAll("/+$", "") + path);
            HttpRequest request = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + bearer)
                    .header("Accept", "application/json").GET().build();
            return exchange(request, budget, limit, false);
        }

        @Override public byte @Nullable [] rotatedRefreshToken() { return rotated == null ? null : rotated.clone(); }
        @Override public void close() { bearer = null; if (rotated != null) Arrays.fill(rotated, (byte) 0); }
    }

    private byte[] exchange(HttpRequest request, Budget budget, int limit, boolean oauth) {
        budget.request();
        long timeout = Math.min(properties.requestTimeout().toNanos(), budget.remaining());
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info ->
                new LimitedBody(info.statusCode() >= 200 && info.statusCode() < 300 ? limit : 8_192));
        try {
            HttpResponse<byte[]> response = future.get(timeout, TimeUnit.NANOSECONDS);
            budget.check();
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw httpFailure(status, response.body(), oauth);
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw failure(UNAVAILABLE);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw failure(UNAVAILABLE);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof GoogleDriveProviderException provider) throw provider;
            throw failure(UNAVAILABLE);
        }
    }

    private GoogleDriveProviderException httpFailure(int status, byte[] body, boolean oauth) {
        String reason = "";
        try {
            JsonNode error = mapper.readTree(body).path("error");
            reason = oauth ? error.asString("") : error.path("errors").path(0).path("reason").asString("");
        } catch (RuntimeException ignored) { /* Only status classification is available for invalid error bodies. */ }
        if (oauth && List.of("invalid_grant", "invalid_client", "unauthorized_client").contains(reason)) return failure(AUTHENTICATION);
        if (status == 401) return failure(AUTHENTICATION);
        if (status == 429 || List.of("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "quotaExceeded").contains(reason)) return failure(QUOTA);
        if (status == 403 || status == 404 || status == 410) return failure(NOT_FOUND);
        return failure(status >= 500 || status == 408 ? UNAVAILABLE : MALFORMED);
    }

    private FileMetadata parseFile(JsonNode node) {
        try {
            List<String> parents = new ArrayList<>();
            JsonNode parentNodes = node.path("parents");
            if (!parentNodes.isMissingNode() && !parentNodes.isArray()) throw failure(MALFORMED);
            for (JsonNode parent : parentNodes) parents.add(fileId(parent.asString()));
            String modified = optional(node, "modifiedTime");
            FileMetadata file = new FileMetadata(fileId(required(node, "id")), required(node, "name"),
                    required(node, "mimeType"), required(node, "version"), optional(node, "md5Checksum"),
                    modified == null ? null : Instant.parse(modified), node.path("trashed").asBoolean(false),
                    parents, optional(node, "driveId"), optional(node.path("shortcutDetails"), "targetId"));
            return file;
        } catch (java.time.DateTimeException exception) { throw failure(MALFORMED); }
    }

    private static void supported(FileMetadata file) {
        if (file.shortcutTargetId() != null || "application/vnd.google-apps.shortcut".equals(file.mimeType())) throw failure(UNSUPPORTED);
        if (file.trashed()) throw failure(NOT_FOUND);
    }

    private static void verifyChecksum(FileMetadata file, byte[] bytes) {
        if (file.checksum() == null) return;
        try {
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
            if (!actual.equalsIgnoreCase(file.checksum())) throw failure(INCONSISTENT);
        } catch (java.security.NoSuchAlgorithmException exception) { throw failure(UNAVAILABLE); }
    }

    private static void sameVersion(FileMetadata expected, FileMetadata actual) {
        supported(actual);
        if (!expected.id().equals(actual.id()) || !expected.version().equals(actual.version())
                || !expected.name().equals(actual.name()) || !expected.mimeType().equals(actual.mimeType())
                || !Objects.equals(expected.modifiedAt(), actual.modifiedAt()) || !Objects.equals(expected.checksum(), actual.checksum())) throw failure(INCONSISTENT);
    }

    private JsonNode json(byte[] bytes) {
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node == null || !node.isObject()) throw failure(MALFORMED);
            return node;
        } catch (tools.jackson.core.JacksonException exception) { throw failure(MALFORMED); }
    }

    private JsonNode array(JsonNode node, String field) {
        JsonNode result = node.path(field);
        if (result.isMissingNode()) return mapper.createArrayNode();
        if (!result.isArray()) throw failure(MALFORMED);
        return result;
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null) throw failure(MALFORMED);
        return value;
    }

    private static @Nullable String optional(JsonNode node, String field) {
        String value = node.path(field).asString("");
        if (value.length() > 16_384) throw failure(LIMIT_EXCEEDED);
        return value.isBlank() ? null : value;
    }

    private static String fileId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,256}")) throw failure(MALFORMED);
        return value;
    }

    private static String token(String value) {
        if (value == null || value.isBlank() || value.length() > 16_384) throw failure(MALFORMED);
        return value;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static String columnName(int number) {
        StringBuilder name = new StringBuilder();
        while (number > 0) { name.append((char) ('A' + (number - 1) % 26)); number = (number - 1) / 26; }
        return name.reverse().toString();
    }

    private static GoogleDriveProviderException failure(GoogleDriveProviderException.Failure failure) { return new GoogleDriveProviderException(failure); }

    private final class Budget {
        private final long deadline = System.nanoTime() + properties.acquisitionTimeout().toNanos();
        private int requests;
        private long nativeBytes;
        void request() { check(); if (++requests > properties.maxRequests()) throw failure(LIMIT_EXCEEDED); }
        long remaining() { return Math.max(1, deadline - System.nanoTime()); }
        void check() { if (System.nanoTime() - deadline >= 0) throw failure(LIMIT_EXCEEDED); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) output.size() + buffer.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(failure(LIMIT_EXCEEDED)); return;
                }
                if (buffer.hasArray()) {
                    output.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
                    buffer.position(buffer.limit());
                } else {
                    while (buffer.hasRemaining()) output.write(buffer.get());
                }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
