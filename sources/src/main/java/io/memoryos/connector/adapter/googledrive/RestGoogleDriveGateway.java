package io.memoryos.connector.adapter.googledrive;

import static io.memoryos.connector.GoogleDriveProviderException.Failure.*;

import com.google.api.client.auth.oauth2.TokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.api.services.directory.Directory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.drive.Drive;
import com.google.api.services.sheets.v4.Sheets;
import io.memoryos.connector.GoogleDriveGateway;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.adapter.RetryAfter;
import io.memoryos.document.ExtractionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Drive through Google's API clients (MEM-226): the Drive, Sheets, Docs and Admin Directory clients build each
 * request (its address, parameters and field mask), and Google's OAuth library sends the token requests, all on
 * {@link GoogleTransport}. The answers are read as bytes and parsed by the strict reader of this class, not into the
 * clients' models: a native Docs or Sheets snapshot must stay exactly what Google sent, and a duplicated key or a
 * trailing token is refused. What stays here is what MemoryOS decides: the budget of one operation, the version and
 * revision fences, the checksum, the limits, and what a status and its reason mean.
 */
public final class RestGoogleDriveGateway implements GoogleDriveGateway, AutoCloseable {
    private static final String FILE_FIELDS = "id,name,mimeType,version,md5Checksum,modifiedTime,trashed,parents,driveId,shortcutDetails(targetId)";
    private static final String PERMISSION_FIELDS = "id,type,role,emailAddress,domain,expirationTime,allowFileDiscovery,deleted,pendingOwner,permissionDetails(permissionType,role,inheritedFrom,inherited),view,inheritedPermissionsDisabled";
    private static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    /** Google accepts assertions valid for at most one hour. */
    private static final long ASSERTION_LIFETIME_SECONDS = 3600;
    private static final int DIRECTORY_PAGE_SIZE = 200;
    private static final Pattern DOMAIN = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern DIRECTORY_EMAIL = Pattern.compile("[^@\\s/]+@[A-Za-z0-9.-]+");
    private static final String APPLICATION = "MemoryOS";
    private static final String SHEET_PAGE_FIELDS = "sheets(properties(sheetId),data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue,formattedValue,userEnteredFormat(numberFormat,textFormat(link)),effectiveFormat(numberFormat,textFormat(link)),note,hyperlink,textFormatRuns,chipRuns))))";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private final GoogleDriveProviderProperties properties;
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final GoogleTransport transport;

    public RestGoogleDriveGateway(GoogleDriveProviderProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        // Invalid Google configuration fails open() rather than preventing unrelated FILE startup.
        Duration connect = properties.connectTimeout();
        if (connect.isNegative() || connect.isZero() || connect.compareTo(Duration.ofSeconds(30)) > 0) connect = Duration.ofSeconds(3);
        Duration request = properties.requestTimeout();
        if (request.isNegative() || request.isZero() || request.compareTo(Duration.ofSeconds(120)) > 0) request = Duration.ofSeconds(30);
        transport = new GoogleTransport(connect, request);
    }

    @Override public Session open(Credential credential) {
        properties.validate();
        return switch (credential) {
            case OAuthCredential oauth -> refresh(oauth);
            case ServiceAccountCredential serviceAccount -> impersonate(serviceAccount);
        };
    }

    /** The refresh grant through Google's OAuth library; a refresh token Google rotated is kept for the caller. */
    private Session refresh(OAuthCredential credential) {
        byte[] refresh = credential.refreshToken();
        byte[] secret = credential.clientSecret();
        try {
            var request = new GoogleRefreshTokenRequest(transport.transport(), GoogleTransport.JSON,
                    new String(refresh, StandardCharsets.UTF_8), credential.clientId(), new String(secret, StandardCharsets.UTF_8));
            JsonNode response = exchangeToken(request);
            String rotated = optional(response, "refresh_token");
            return new DriveSession(bearer(response), rotated == null ? null : rotated.getBytes(StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(refresh, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * RFC 7523 JWT bearer grant: the service account signs an assertion naming the user it acts as. Google's library
     * signs it and sends the grant. Its {@code ServiceAccountCredentials} is not used: it builds its own request,
     * which follows a redirect with the assertion, retries and reads the answer whole, and offers no place to stop
     * that.
     */
    private Session impersonate(ServiceAccountCredential credential) {
        var key = credential.key();
        long issuedAt = Instant.now().getEpochSecond();
        var header = new JsonWebSignature.Header().setAlgorithm("RS256").setType("JWT").setKeyId(key.privateKeyId());
        var claims = new JsonWebToken.Payload().setIssuer(key.clientEmail()).setSubject(credential.subject())
                .setAudience(properties.tokenUri().toString()).setIssuedAtTimeSeconds(issuedAt)
                .setExpirationTimeSeconds(issuedAt + ASSERTION_LIFETIME_SECONDS);
        claims.set("scope", String.join(" ", SERVICE_ACCOUNT_SCOPES));
        String assertion;
        try {
            assertion = JsonWebSignature.signUsingRsaSha256(key.privateKey(), GoogleTransport.JSON, header, claims);
        } catch (GeneralSecurityException | IOException exception) {
            throw failure(AUTHENTICATION);
        }
        var request = new TokenRequest(transport.transport(), GoogleTransport.JSON,
                new GenericUrl(properties.tokenUri().toString()), JWT_BEARER_GRANT);
        request.set("assertion", assertion);
        return new DriveSession(bearer(exchangeToken(request)), null);
    }

    private JsonNode exchangeToken(TokenRequest request) {
        request.setTokenServerUrl(new GenericUrl(properties.tokenUri().toString()))
                .setRequestInitializer(transport.initializer(null));
        return json(exchange(request::executeUnparsed, new Budget(), 65_536, true, NOT_FOUND));
    }

    private static String bearer(JsonNode response) {
        String bearer = required(response, "access_token");
        if (!"Bearer".equalsIgnoreCase(required(response, "token_type"))) throw failure(MALFORMED);
        return bearer;
    }

    @Override public void close() { transport.close(); }

    private final class DriveSession implements Session {
        private @Nullable String bearer;
        private final byte @Nullable [] rotated;

        private final Drive drive;
        private final Sheets sheets;
        private final Docs docs;
        private final Directory directory;

        private DriveSession(String bearer, byte @Nullable [] rotated) {
            this.bearer = bearer;
            this.rotated = rotated;
            var initializer = transport.initializer(bearer);
            var http = transport.transport();
            drive = new Drive.Builder(http, GoogleTransport.JSON, initializer)
                    .setRootUrl(GoogleTransport.root(properties.driveApiBaseUrl()))
                    .setServicePath(GoogleTransport.servicePath(properties.driveApiBaseUrl()))
                    .setApplicationName(APPLICATION).build();
            // These three clients carry the API version in each request path, so their root is the base without it.
            sheets = new Sheets.Builder(http, GoogleTransport.JSON, initializer)
                    .setRootUrl(GoogleTransport.rootBefore(properties.sheetsApiBaseUrl(), "v4")).setServicePath("")
                    .setApplicationName(APPLICATION).build();
            docs = new Docs.Builder(http, GoogleTransport.JSON, initializer)
                    .setRootUrl(GoogleTransport.rootBefore(properties.docsApiBaseUrl(), "v1")).setServicePath("")
                    .setApplicationName(APPLICATION).build();
            directory = new Directory.Builder(http, GoogleTransport.JSON, initializer)
                    .setRootUrl(GoogleTransport.rootBefore(properties.adminApiBaseUrl(), "admin/directory/v1"))
                    .setServicePath("").setApplicationName(APPLICATION).build();
        }


        @Override public FilePage listFiles(String parentId, @Nullable String pageToken) {
            if ("root".equals(parentId)) throw failure(MALFORMED);
            String query = "'" + fileId(parentId) + "' in parents";
            String page = pageToken == null ? null : token(pageToken);
            JsonNode response = get(prepared(() -> drive.files().list().setQ("trashed = false and (" + query + ")")
                    .setSpaces("drive").setCorpora("user").setOrderBy("folder,name").setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true).setPageSize(properties.pageSize())
                    .setFields("nextPageToken,incompleteSearch,files(" + FILE_FIELDS + ")").setPageToken(page)), new Budget());
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
            String file = fileId(id);
            return parseFile(get(prepared(() -> drive.files().get(file).setSupportsAllDrives(true).setFields(FILE_FIELDS)), budget));
        }

        @Override public List<Permission> permissions(String id) {
            String file = fileId(id);
            int pageSize = Math.min(properties.pageSize(), 100);
            Budget budget = new Budget();
            List<Permission> permissions = new ArrayList<>();
            var permissionIds = new HashSet<String>();
            var pageTokens = new HashSet<String>();
            String next = null;
            do {
                String page = next;
                JsonNode response = get(prepared(() -> drive.permissions().list(file).setSupportsAllDrives(true)
                        .setPageSize(pageSize).setFields("nextPageToken,permissions(" + PERMISSION_FIELDS + ")")
                        .setPageToken(page)), budget, ACCESS_DENIED);
                JsonNode entries = response.path("permissions");
                if (!entries.isArray()) throw failure(MALFORMED);
                if (entries.size() > pageSize) throw failure(LIMIT_EXCEEDED);
                for (JsonNode entry : entries) {
                    budget.check();
                    Permission permission = parsePermission(entry);
                    if (!permissionIds.add(permission.id())) throw failure(INCONSISTENT);
                    permissions.add(permission);
                }
                next = optionalText(response, "nextPageToken");
                if (next != null && !pageTokens.add(token(next))) throw failure(INCONSISTENT);
            } while (next != null);
            budget.check();
            return List.copyOf(permissions);
        }

        @Override public DirectoryUser directoryUser(String email) {
            String key = directoryEmail(email);
            JsonNode user = get(prepared(() -> directory.users().get(key).setFields("primaryEmail,isAdmin,suspended")),
                    new Budget(), ACCESS_DENIED);
            return new DirectoryUser(directoryEmail(required(user, "primaryEmail")),
                    user.path("isAdmin").asBoolean(false), user.path("suspended").asBoolean(false));
        }

        @Override public DirectoryPage groups(String domain, @Nullable String pageToken) {
            if (domain == null || !DOMAIN.matcher(domain).matches()) throw failure(MALFORMED);
            String page = pageToken == null ? null : token(pageToken);
            JsonNode response = get(prepared(() -> directory.groups().list().setDomain(domain)
                    .setMaxResults(DIRECTORY_PAGE_SIZE).setFields("nextPageToken,groups(email)").setPageToken(page)),
                    new Budget(), ACCESS_DENIED);
            List<String> emails = new ArrayList<>();
            for (JsonNode group : directoryEntries(response, "groups")) emails.add(directoryEmail(required(group, "email")));
            return new DirectoryPage(emails, nextDirectoryPage(response, pageToken));
        }

        @Override public MemberPage groupMembers(String groupEmail, @Nullable String pageToken) {
            String group = directoryEmail(groupEmail);
            String page = pageToken == null ? null : token(pageToken);
            JsonNode response = get(prepared(() -> directory.members().list(group).setIncludeDerivedMembership(true)
                    .setMaxResults(DIRECTORY_PAGE_SIZE).setFields("nextPageToken,members(email,type,status)")
                    .setPageToken(page)), new Budget(), ACCESS_DENIED);
            List<String> emails = new ArrayList<>();
            boolean wholeDomain = false;
            for (JsonNode member : directoryEntries(response, "members")) {
                switch (member.path("type").asString("")) {
                    // Nested groups are already expanded into their users by includeDerivedMembership.
                    case "USER" -> {
                        if ("ACTIVE".equals(member.path("status").asString("ACTIVE"))) emails.add(directoryEmail(required(member, "email")));
                    }
                    case "CUSTOMER" -> wholeDomain = true;
                    default -> { }
                }
            }
            return new MemberPage(emails, wholeDomain, nextDirectoryPage(response, pageToken));
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
                filename = filename.toLowerCase(Locale.ROOT).endsWith(".pptx") ? filename : filename + ".pptx";
                String id = fileId(file.id());
                bytes = request(prepared(() -> drive.files().export(id, PPTX)), budget, properties.maxBinaryBytes());
            } else {
                if (mime.startsWith("application/vnd.google-apps.")) throw failure(UNSUPPORTED);
                String id = fileId(file.id());
                // The client addresses a download at its own path under the root (…/download/drive/v3/files/{id}).
                bytes = request(prepared(() -> drive.files().get(id).setSupportsAllDrives(true).setAlt("media")),
                        budget, properties.maxBinaryBytes());
                verifyChecksum(file, bytes);
            }
            if (bytes.length == 0) throw failure(MALFORMED);
            sameVersion(file, metadata(file.id(), budget));
            budget.check();
            return new AcquiredContent(filename, mime, bytes, new SourceInputDescriptor(format,
                    file.id(), file.version(), "https://drive.google.com/file/d/" + file.id() + "/view"));
        }

        private byte[] sheets(FileMetadata file, Budget budget) {
            String id = fileId(file.id());
            JsonNode structure = get(prepared(() -> sheets.spreadsheets().get(id)
                    .setFields("spreadsheetId,properties(title,locale,timeZone),namedRanges,sheets(properties,merges)")), budget);
            if (!file.id().equals(required(structure, "spreadsheetId"))) throw failure(MALFORMED);
            JsonNode sheetNodes = array(structure, "sheets");
            if (sheetNodes.isEmpty() || sheetNodes.size() > properties.maxTabs()) throw failure(LIMIT_EXCEEDED);
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
                    JsonNode response = get(prepared(() -> sheets.spreadsheets().get(id).setRanges(List.of(range))
                            .setIncludeGridData(true).setFields(SHEET_PAGE_FIELDS)), budget);
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
            String id = fileId(file.id());
            JsonNode document = get(prepared(() -> docs.documents().get(id).setIncludeTabsContent(true)
                    .setSuggestionsViewMode("PREVIEW_WITHOUT_SUGGESTIONS")), budget);
            if (!file.id().equals(required(document, "documentId"))) throw failure(MALFORMED);
            String revision = optional(document, "revisionId");
            int tabs = countTabs(array(document, "tabs"), 0);
            if (tabs == 0 || tabs > properties.maxTabs()) throw failure(LIMIT_EXCEEDED);
            // Docs omits revisionId for read-only collaborators; the surrounding Drive version fence still applies.
            if (revision != null) {
                JsonNode fence = get(prepared(() -> docs.documents().get(id).setFields("documentId,revisionId")), budget);
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

        private JsonNode get(AbstractGoogleClientRequest<?> request, Budget budget) { return get(request, budget, NOT_FOUND); }

        private JsonNode get(AbstractGoogleClientRequest<?> request, Budget budget, GoogleDriveProviderException.Failure forbidden) {
            byte[] bytes = request(request, budget, properties.maxSnapshotBytes(), forbidden);
            budget.nativeBytes += bytes.length;
            if (budget.nativeBytes > properties.maxSnapshotBytes()) throw failure(LIMIT_EXCEEDED);
            return json(bytes);
        }

        private byte[] request(AbstractGoogleClientRequest<?> request, Budget budget, int limit) {
            return request(request, budget, limit, NOT_FOUND);
        }

        private byte[] request(AbstractGoogleClientRequest<?> request, Budget budget, int limit,
                GoogleDriveProviderException.Failure forbidden) {
            if (bearer == null) throw failure(AUTHENTICATION);
            // A Google client request sets these itself after the initializer ran: its own headers ask for gzip and
            // its answer is decoded. The bound is on the bytes Google sends, so neither may stand.
            request.getRequestHeaders().setAcceptEncoding(null);
            request.setReturnRawInputStream(true);
            return exchange(request::executeUnparsed, budget, limit, false, forbidden);
        }

        @Override public byte @Nullable [] rotatedRefreshToken() { return rotated == null ? null : rotated.clone(); }
        @Override public void close() { bearer = null; if (rotated != null) Arrays.fill(rotated, (byte) 0); }
    }

    /** A request of a Google client as it is built; building one fails only on a value no request can carry. */
    @FunctionalInterface
    private interface Prepared<T> {
        T build() throws IOException;
    }

    private static <T> T prepared(Prepared<T> request) {
        try {
            return request.build();
        } catch (IOException | IllegalArgumentException exception) {
            throw failure(MALFORMED);
        }
    }

    /**
     * One request of an operation: it counts against the budget and runs until the shorter of the request timeout and
     * what the budget has left. Whatever fails is a provider failure that carries none of Google's text.
     */
    private byte[] exchange(GoogleTransport.Call call, Budget budget, int limit, boolean oauth,
            GoogleDriveProviderException.Failure forbidden) {
        budget.request();
        long timeout = Math.min(properties.requestTimeout().toNanos(), budget.remaining());
        try {
            byte[] body = transport.exchange(call, limit, timeout);
            budget.check();
            return body;
        } catch (GoogleTransport.Status status) {
            budget.check();
            throw httpFailure(status.code, status.body, oauth, forbidden, RetryAfter.parse(status.retryAfter, Clock.systemUTC()));
        } catch (GoogleTransport.TooLarge exception) {
            throw failure(LIMIT_EXCEEDED);
        } catch (IOException exception) {
            throw failure(UNAVAILABLE);
        }
    }

    /**
     * {@code forbidden} is what a 403 without a scope or quota reason means for the calling request. A throttled
     * or unavailable response carries the wait Google asked for in {@code Retry-After}.
     */
    private GoogleDriveProviderException httpFailure(int status, byte[] body, boolean oauth,
            GoogleDriveProviderException.Failure forbidden, @Nullable Duration retryAfter) {
        String reason = "";
        boolean missingScope = false;
        try {
            JsonNode error = mapper.readTree(body).path("error");
            reason = oauth ? error.asString("") : error.path("errors").path(0).path("reason").asString("");
            for (JsonNode detail : error.path("details"))
                missingScope |= "ACCESS_TOKEN_SCOPE_INSUFFICIENT".equals(detail.path("reason").asString(""));
        } catch (RuntimeException ignored) { /* Only status classification is available for invalid error bodies. */ }
        if (oauth && List.of("invalid_grant", "invalid_client", "unauthorized_client").contains(reason)) return failure(AUTHENTICATION);
        if (status == 401) return failure(AUTHENTICATION);
        if (status == 429 || List.of("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "quotaExceeded").contains(reason))
            return new GoogleDriveProviderException(QUOTA, retryAfter);
        if (status == 403 && (missingScope || "insufficientPermissions".equals(reason))) return failure(SCOPE_INSUFFICIENT);
        if (status == 403) return failure(forbidden);
        if (status == 404 || status == 410) return failure(NOT_FOUND);
        if (status == 503) return new GoogleDriveProviderException(UNAVAILABLE, retryAfter);
        return failure(status >= 500 || status == 408 ? UNAVAILABLE : MALFORMED);
    }

    private FileMetadata parseFile(JsonNode node) {
        try {
            List<String> parents = new ArrayList<>();
            JsonNode parentNodes = node.path("parents");
            if (!parentNodes.isMissingNode() && !parentNodes.isArray()) throw failure(MALFORMED);
            for (JsonNode parent : parentNodes) parents.add(fileId(parent.asString()));
            String modified = optional(node, "modifiedTime");
            return new FileMetadata(fileId(required(node, "id")), required(node, "name"),
                    required(node, "mimeType"), required(node, "version"), optional(node, "md5Checksum"),
                    modified == null ? null : Instant.parse(modified), node.path("trashed").asBoolean(false),
                    parents, optional(node, "driveId"), optional(node.path("shortcutDetails"), "targetId"));
        } catch (DateTimeException exception) { throw failure(MALFORMED); }
    }

    private Permission parsePermission(JsonNode node) {
        if (!node.isObject()) throw failure(MALFORMED);
        List<PermissionDetail> details = new ArrayList<>();
        for (JsonNode detail : array(node, "permissionDetails")) {
            if (!detail.isObject()) throw failure(MALFORMED);
            details.add(new PermissionDetail(optionalText(detail, "permissionType"), optionalText(detail, "role"),
                    optionalText(detail, "inheritedFrom"), optionalBoolean(detail, "inherited")));
        }
        String expiration = optionalText(node, "expirationTime");
        try {
            return new Permission(requiredText(node, "id"), requiredText(node, "type"), requiredText(node, "role"),
                    optionalText(node, "emailAddress"), optionalText(node, "domain"),
                    expiration == null ? null : Instant.parse(expiration), optionalBoolean(node, "allowFileDiscovery"),
                    optionalBoolean(node, "deleted"), optionalBoolean(node, "pendingOwner"), details,
                    optionalText(node, "view"), optionalBoolean(node, "inheritedPermissionsDisabled"));
        } catch (DateTimeException exception) { throw failure(MALFORMED); }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) throw failure(MALFORMED);
        return value;
    }

    private static @Nullable String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) throw failure(MALFORMED);
        String text = value.asString();
        if (text.length() > 16_384) throw failure(LIMIT_EXCEEDED);
        return text;
    }

    private static @Nullable Boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isBoolean()) throw failure(MALFORMED);
        return value.asBoolean();
    }

    private static void supported(FileMetadata file) {
        if (file.shortcutTargetId() != null || "application/vnd.google-apps.shortcut".equals(file.mimeType())) throw failure(UNSUPPORTED);
        if (file.trashed()) throw failure(NOT_FOUND);
    }

    private static void verifyChecksum(FileMetadata file, byte[] bytes) {
        if (file.checksum() == null) return;
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            if (!actual.equalsIgnoreCase(file.checksum())) throw failure(INCONSISTENT);
        } catch (NoSuchAlgorithmException exception) { throw failure(UNAVAILABLE); }
    }

    private static void sameVersion(FileMetadata expected, FileMetadata actual) {
        supported(actual);
        if (!expected.id().equals(actual.id()) || !expected.version().equals(actual.version())
                || !expected.name().equals(actual.name()) || !expected.mimeType().equals(actual.mimeType())
                || !Objects.equals(expected.modifiedAt(), actual.modifiedAt()) || !Objects.equals(expected.checksum(), actual.checksum())) throw failure(INCONSISTENT);
    }

    private JsonNode json(byte[] bytes) {
        try {
            JsonNode node = reader.readTree(bytes);
            if (node == null || !node.isObject()) throw failure(MALFORMED);
            return node;
        } catch (JacksonException exception) { throw failure(MALFORMED); }
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

    private static JsonNode directoryEntries(JsonNode response, String field) {
        JsonNode entries = response.path(field);
        if (entries.isMissingNode()) return entries;
        if (!entries.isArray()) throw failure(MALFORMED);
        if (entries.size() > DIRECTORY_PAGE_SIZE) throw failure(LIMIT_EXCEEDED);
        return entries;
    }

    private static @Nullable String nextDirectoryPage(JsonNode response, @Nullable String current) {
        String next = optional(response, "nextPageToken");
        if (next != null && next.equals(current)) throw failure(INCONSISTENT);
        return next;
    }

    private static String directoryEmail(String value) {
        if (value == null || value.length() > 320 || !DIRECTORY_EMAIL.matcher(value).matches()) throw failure(MALFORMED);
        return value.toLowerCase(Locale.ROOT);
    }

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
}
