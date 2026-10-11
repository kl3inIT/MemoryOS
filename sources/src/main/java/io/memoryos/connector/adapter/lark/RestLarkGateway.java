package io.memoryos.connector.adapter.lark;

import static io.memoryos.connector.LarkProviderException.Failure.AUTHENTICATION;
import static io.memoryos.connector.LarkProviderException.Failure.AUTHORIZATION;
import static io.memoryos.connector.LarkProviderException.Failure.LIMIT_EXCEEDED;
import static io.memoryos.connector.LarkProviderException.Failure.MALFORMED;
import static io.memoryos.connector.LarkProviderException.Failure.NOT_FOUND;
import static io.memoryos.connector.LarkProviderException.Failure.QUOTA;
import static io.memoryos.connector.LarkProviderException.Failure.UNAVAILABLE;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lark.oapi.Client;
import com.lark.oapi.core.exception.ObtainAccessTokenException;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.core.response.BaseResponse;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.response.error.Error;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenReq;
import com.lark.oapi.service.auth.v3.model.InternalTenantAccessTokenReqBody;
import com.lark.oapi.service.contact.v3.model.BatchUserReq;
import com.lark.oapi.service.contact.v3.model.FindByDepartmentUserReq;
import com.lark.oapi.service.docx.v1.model.ListDocumentBlockReq;
import com.lark.oapi.service.drive.v1.model.BatchQueryMetaReq;
import com.lark.oapi.service.drive.v1.model.DownloadFileReq;
import com.lark.oapi.service.drive.v1.model.GetPermissionPublicReq;
import com.lark.oapi.service.drive.v1.model.ListFileReq;
import com.lark.oapi.service.drive.v1.model.ListPermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.MetaRequest;
import com.lark.oapi.service.drive.v1.model.RequestDoc;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.sheets.v3.model.QuerySpreadsheetSheetReq;
import com.lark.oapi.service.sheets.v3.model.Sheet;
import com.lark.oapi.service.wiki.v2.model.GetSpaceReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceNodeReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceReq;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.adapter.RetryAfter;
import io.memoryos.connector.adapter.lark.LarkProviderMetrics.Operation;
import io.memoryos.connector.adapter.lark.LarkTransport.Exchange;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * Lark Suite through Lark's SDK (MEM-245): the SDK builds the requests and reads the answers into its models, on the
 * transport of {@link LarkTransport}. What stays here is what MemoryOS decides: the budget of each exchange, what a
 * status and a Lark {@code code} mean, how far a listing is followed, and the shape of the snapshots the readers
 * consume.
 *
 * <p>The SDK keeps tokens in a cache shared by every client of the process and keyed by the App ID alone, so its token
 * management is switched off: a session fetches its own tenant token and sends it with each request. A wrong secret is
 * therefore never hidden by a token an earlier secret obtained.
 */
public final class RestLarkGateway implements LarkGateway, AutoCloseable {
    static final String DOCX_SCHEMA = "memoryos-lark-docx-v1";
    static final String SHEET_SCHEMA = "memoryos-lark-sheet-v1";
    private static final int NODE_PAGE = 50;
    private static final int FILE_PAGE = 200;
    private static final int BLOCK_PAGE = 500;
    private static final int MEMBER_PAGE = 100;
    /** Lark's block type of a spreadsheet embedded in a document. */
    private static final int SHEET_BLOCK = 30;
    /** The most embedded spreadsheets read into one Docx snapshot. */
    private static final int MAX_EMBEDDED_SHEETS = 20;

    private final LarkProviderProperties properties;
    private final LarkTransport transport;
    private final LarkProviderMetrics metrics;
    private final Clock clock;

    public RestLarkGateway(LarkProviderProperties properties, MeterRegistry registry) {
        this(properties, registry, Clock.systemUTC());
    }

    RestLarkGateway(LarkProviderProperties properties, MeterRegistry registry, Clock clock) {
        this.properties = properties;
        // Invalid Lark configuration fails open() rather than preventing another provider's startup.
        Duration connect = properties.connectTimeout();
        if (connect.isNegative() || connect.isZero() || connect.compareTo(Duration.ofSeconds(30)) > 0)
            connect = Duration.ofSeconds(3);
        this.transport = new LarkTransport(connect, properties.userAgent());
        this.metrics = new LarkProviderMetrics(registry);
        this.clock = clock;
    }

    @Override public Session open(Credential credential) {
        properties.validate();
        var base = properties.baseUrl() == null ? credential.region().baseUrl()
                : properties.baseUrl().toString().replaceAll("/+$", "");
        var client = Client.newBuilder(credential.appId(), new String(credential.appSecret(), StandardCharsets.UTF_8))
                .openBaseUrl(base).disableTokenCache().logReqAtDebug(false).httpTransport(transport).build();
        return new LarkSession(client, metrics.record(Operation.TOKEN, () -> token(client, credential)));
    }

    @Override public void close() {
        transport.close();
    }

    private String token(Client client, Credential credential) {
        var body = InternalTenantAccessTokenReqBody.newBuilder().appId(credential.appId())
                .appSecret(new String(credential.appSecret(), StandardCharsets.UTF_8)).build();
        var answer = exchange(properties.requestTimeout(), properties.maxResponseBytes(), () ->
                client.auth().v3().tenantAccessToken().internal(
                        InternalTenantAccessTokenReq.newBuilder().internalTenantAccessTokenReqBody(body).build()));
        // The token is a top-level member of the answer, which the SDK's model of it does not carry.
        if (answer == null || answer.getRawResponse() == null) throw new LarkProviderException(MALFORMED);
        var token = text(json(answer.getRawResponse(), true), "tenant_access_token");
        if (token == null || token.isBlank()) throw new LarkProviderException(AUTHENTICATION);
        return token;
    }

    private final class LarkSession implements Session {
        private final Client client;
        private final RequestOptions tenant;

        private LarkSession(Client client, String token) {
            this.client = client;
            this.tenant = RequestOptions.newBuilder().tenantAccessToken(token).build();
        }

        @Override public Bot bot() {
            return metrics.record(Operation.BOT, () -> {
                JsonObject bot = object(raw("/open-apis/bot/v3/info"), "bot");
                var name = bot == null ? null : text(bot, "app_name");
                var openId = bot == null ? null : text(bot, "open_id");
                if (name == null || openId == null) throw new LarkProviderException(MALFORMED);
                return new Bot(name, openId);
            });
        }

        @Override public Set<String> missingScopes() {
            return metrics.record(Operation.SCOPES, () -> {
                var missing = new LinkedHashSet<String>();
                // A call whose scope is granted fails for another reason (the tokens name nothing) or succeeds.
                probe(missing, "wiki:wiki:readonly", () -> client.wiki().v2().space()
                        .list(ListSpaceReq.newBuilder().pageSize(1).build(), tenant));
                probe(missing, "drive:drive:readonly", () -> client.drive().v1().file()
                        .list(ListFileReq.newBuilder().pageSize(1).build(), tenant));
                probe(missing, "sheets:spreadsheet:readonly", () -> client.sheets().v3().spreadsheetSheet()
                        .query(QuerySpreadsheetSheetReq.newBuilder().spreadsheetToken("shtusMemoryOSProbe").build(), tenant));
                probe(missing, "docs:permission.member:retrieve", () -> client.drive().v1().permissionMember()
                        .list(ListPermissionMemberReq.newBuilder().token("doxusMemoryOSProbe").type("docx").build(), tenant));
                probe(missing, "im:chat:readonly", () -> client.im().v1().chatMembers().get(GetChatMembersReq.newBuilder()
                        .chatId("oc_00000000000000000000000000000000").memberIdType("open_id").pageSize(1).build(), tenant));
                return Set.copyOf(missing);
            });
        }

        private void probe(Set<String> missing, String scope, Callable<? extends BaseResponse<?>> call) {
            try {
                typed(call);
            } catch (LarkProviderException refused) {
                if (refused.failure() == AUTHORIZATION && !refused.missingScopes().isEmpty()) missing.add(scope);
                else if (refused.failure() == AUTHENTICATION || refused.failure() == QUOTA
                        || refused.failure() == UNAVAILABLE) throw refused;
            }
        }

        @Override public SpacePage spaces(@Nullable String pageToken) {
            return metrics.record(Operation.SPACES, () -> {
                var data = typed(() -> client.wiki().v2().space().list(
                        ListSpaceReq.newBuilder().pageSize(NODE_PAGE).pageToken(pageToken).build(), tenant)).getData();
                var spaces = new ArrayList<Space>();
                if (data.getItems() != null)
                    for (var space : data.getItems()) spaces.add(space(space));
                return new SpacePage(spaces, more(data.getHasMore(), data.getPageToken()));
            });
        }

        @Override public Space space(String spaceId) {
            return metrics.record(Operation.SPACE, () -> {
                var data = typed(() -> client.wiki().v2().space().get(
                        GetSpaceReq.newBuilder().spaceId(spaceId).build(), tenant)).getData();
                if (data.getSpace() == null) throw new LarkProviderException(MALFORMED);
                return space(data.getSpace());
            });
        }

        private Space space(com.lark.oapi.service.wiki.v2.model.Space space) {
            if (space.getSpaceId() == null) throw new LarkProviderException(MALFORMED);
            return new Space(space.getSpaceId(), space.getName() == null ? "" : space.getName(), space.getVisibility());
        }

        @Override public NodePage nodes(String spaceId, @Nullable String parentNodeToken, @Nullable String pageToken) {
            return metrics.record(Operation.NODES, () -> {
                var data = typed(() -> client.wiki().v2().spaceNode().list(ListSpaceNodeReq.newBuilder()
                        .spaceId(spaceId).parentNodeToken(parentNodeToken).pageSize(NODE_PAGE).pageToken(pageToken)
                        .build(), tenant)).getData();
                var nodes = new ArrayList<Node>();
                if (data.getItems() != null)
                    for (var node : data.getItems()) {
                        if (node.getNodeToken() == null || node.getObjToken() == null)
                            throw new LarkProviderException(MALFORMED);
                        nodes.add(new Node(node.getNodeToken(), spaceId, blankToNull(node.getParentNodeToken()),
                                ObjectType.of(node.getObjType()), node.getObjToken(),
                                node.getTitle() == null ? "" : node.getTitle(), Boolean.TRUE.equals(node.getHasChild()),
                                seconds(node.getObjEditTime()), seconds(node.getObjCreateTime()), blankToNull(node.getUrl())));
                    }
                return new NodePage(nodes, more(data.getHasMore(), data.getPageToken()));
            });
        }

        @Override public Folder folder(String folderToken) {
            return metrics.record(Operation.FOLDER, () -> {
                var request = MetaRequest.newBuilder().requestDocs(new RequestDoc[] {
                        RequestDoc.newBuilder().docToken(folderToken).docType(ObjectType.FOLDER.wire()).build()}).build();
                var data = typed(() -> client.drive().v1().meta().batchQuery(
                        BatchQueryMetaReq.newBuilder().metaRequest(request).build(), tenant)).getData();
                if (data.getMetas() == null || data.getMetas().length == 0) {
                    // Lark answers 0 and lists the refused token: 970003 is "no permission", anything else "not found".
                    boolean refused = data.getFailedList() != null && Arrays.stream(data.getFailedList())
                            .anyMatch(failed -> Integer.valueOf(970003).equals(failed.getCode()));
                    throw new LarkProviderException(refused ? AUTHORIZATION : NOT_FOUND);
                }
                var title = data.getMetas()[0].getTitle();
                return new Folder(folderToken, title == null ? "" : title);
            });
        }

        @Override public FilePage files(String folderToken, @Nullable String pageToken) {
            return metrics.record(Operation.FILES, () -> {
                var data = typed(() -> client.drive().v1().file().list(ListFileReq.newBuilder()
                        .folderToken(folderToken).pageSize(FILE_PAGE).pageToken(pageToken).userIdType("open_id")
                        .build(), tenant)).getData();
                var files = new ArrayList<File>();
                if (data.getFiles() != null)
                    for (var file : data.getFiles()) {
                        if (file.getToken() == null) throw new LarkProviderException(MALFORMED);
                        files.add(new File(file.getToken(), file.getName() == null ? "" : file.getName(),
                                ObjectType.of(file.getType()), blankToNull(file.getParentToken()),
                                blankToNull(file.getUrl()), seconds(file.getCreatedTime()),
                                seconds(file.getModifiedTime()), blankToNull(file.getOwnerId())));
                    }
                return new FilePage(files, more(data.getHasMore(), data.getNextPageToken()));
            });
        }

        @Override public Snapshot docx(String documentId) {
            return metrics.record(Operation.DOCX, () -> {
                var blocks = new JsonArray();
                var embedded = new LinkedHashSet<String>();
                String pageToken = null;
                do {
                    String page = pageToken;
                    var data = typed(() -> client.docx().v1().documentBlock().list(ListDocumentBlockReq.newBuilder()
                            .documentId(documentId).pageSize(BLOCK_PAGE).pageToken(page).documentRevisionId(-1)
                            .build(), tenant)).getData();
                    if (data.getItems() != null)
                        for (var block : data.getItems()) {
                            if (blocks.size() >= properties.maxBlocks()) throw new LarkProviderException(LIMIT_EXCEEDED);
                            blocks.add(Jsons.DEFAULT.toJsonTree(block));
                            if (Integer.valueOf(SHEET_BLOCK).equals(block.getBlockType()) && block.getSheet() != null
                                    && block.getSheet().getToken() != null && embedded.size() < MAX_EMBEDDED_SHEETS)
                                embedded.add(block.getSheet().getToken());
                        }
                    pageToken = more(data.getHasMore(), data.getPageToken());
                } while (pageToken != null);
                var snapshot = new JsonObject();
                snapshot.addProperty("schema", DOCX_SCHEMA);
                snapshot.addProperty("documentId", documentId);
                snapshot.add("blocks", blocks);
                var sheets = new JsonObject();
                // An embedded spreadsheet is named "<spreadsheet token>_<sheet id>"; one that cannot be read stays a gap.
                for (String token : embedded) {
                    int split = token.lastIndexOf('_');
                    if (split < 1) continue;
                    try {
                        sheets.add(token, values(token.substring(0, split), token.substring(split + 1), null, null));
                    } catch (LarkProviderException unreadable) {
                        if (unreadable.failure() != AUTHORIZATION && unreadable.failure() != NOT_FOUND
                                && unreadable.failure() != MALFORMED && unreadable.failure() != LIMIT_EXCEEDED)
                            throw unreadable;
                    }
                }
                snapshot.add("sheets", sheets);
                return snapshot(DOCX_SCHEMA, snapshot);
            });
        }

        @Override public Snapshot sheet(String spreadsheetToken) {
            return metrics.record(Operation.SHEET, () -> {
                var data = typed(() -> client.sheets().v3().spreadsheetSheet().query(
                        QuerySpreadsheetSheetReq.newBuilder().spreadsheetToken(spreadsheetToken).build(), tenant)).getData();
                var sheets = new JsonArray();
                if (data.getSheets() != null)
                    for (Sheet sheet : data.getSheets()) {
                        // A sheet tab that holds a Base table has no cells to read.
                        if (sheet.getSheetId() == null || Boolean.TRUE.equals(sheet.getHidden())
                                || (sheet.getResourceType() != null && !"sheet".equals(sheet.getResourceType()))) continue;
                        if (sheets.size() >= properties.maxSheets()) break;
                        var grid = sheet.getGridProperties();
                        var entry = values(spreadsheetToken, sheet.getSheetId(),
                                grid == null ? null : grid.getRowCount(), grid == null ? null : grid.getColumnCount());
                        entry.addProperty("sheetId", sheet.getSheetId());
                        entry.addProperty("title", sheet.getTitle() == null ? "" : sheet.getTitle());
                        sheets.add(entry);
                    }
                var snapshot = new JsonObject();
                snapshot.addProperty("schema", SHEET_SCHEMA);
                snapshot.addProperty("spreadsheetToken", spreadsheetToken);
                snapshot.add("sheets", sheets);
                return snapshot(SHEET_SCHEMA, snapshot);
            });
        }

        /**
         * The values of one sheet as {@code {"values": [[…]], "truncated": bool}}. With the grid size the read stops
         * at the cell bound; without it the whole sheet is asked for and the answer bound applies.
         */
        private JsonObject values(String spreadsheetToken, String sheetId, @Nullable Integer rows,
                @Nullable Integer columns) {
            String range = sheetId;
            boolean truncated = false;
            if (rows != null && columns != null && rows > 0 && columns > 0) {
                int width = Math.min(columns, 702);
                int height = Math.min(rows, Math.max(1, properties.maxCells() / width));
                truncated = width < columns || height < rows;
                range = sheetId + "!A1:" + column(width) + height;
            }
            JsonObject data = object(raw("/open-apis/sheets/v2/spreadsheets/" + path(spreadsheetToken) + "/values/"
                    + path(range) + "?valueRenderOption=ToString&dateTimeRenderOption=FormattedString"), "data");
            JsonObject valueRange = data == null ? null : object(data, "valueRange");
            JsonElement values = valueRange == null ? null : valueRange.get("values");
            var entry = new JsonObject();
            entry.add("values", values != null && values.isJsonArray() ? trimmed(values.getAsJsonArray()) : new JsonArray());
            entry.addProperty("truncated", truncated);
            return entry;
        }

        @Override public Content download(String fileToken, int maxBytes) {
            return metrics.record(Operation.CONTENT, () -> {
                var answer = check(exchange(properties.contentTimeout(), Math.min(maxBytes, properties.maxContentBytes()),
                        () -> client.drive().v1().file().download(
                                DownloadFileReq.newBuilder().fileToken(fileToken).build(), tenant)));
                RawResponse raw = answer.getRawResponse();
                var types = raw.getHeaders().get("Content-Type");
                var name = answer.getFileName();
                return new Content(name == null || name.isBlank() ? fileToken : name,
                        types == null || types.isEmpty() ? "application/octet-stream" : types.getFirst(), raw.getBody());
            });
        }

        @Override public Permissions permissions(String token, ObjectType type) {
            return metrics.record(Operation.PERMISSIONS, () -> {
                var members = typed(() -> client.drive().v1().permissionMember().list(ListPermissionMemberReq.newBuilder()
                        .token(token).type(type.wire()).fields("*").build(), tenant)).getData();
                var collaborators = new ArrayList<Collaborator>();
                if (members.getItems() != null)
                    for (var member : members.getItems()) {
                        if (member.getMemberType() == null || member.getMemberId() == null)
                            throw new LarkProviderException(MALFORMED);
                        collaborators.add(new Collaborator(member.getMemberType(), member.getMemberId(), member.getPerm()));
                    }
                var setting = typed(() -> client.drive().v1().permissionPublic().get(
                        GetPermissionPublicReq.newBuilder().token(token).type(type.wire()).build(), tenant)).getData();
                var link = setting.getPermissionPublic() == null ? null : setting.getPermissionPublic().getLinkShareEntity();
                return new Permissions(collaborators, blankToNull(link));
            });
        }

        @Override public List<User> users(List<String> openIds) {
            if (openIds.isEmpty()) return List.of();
            if (openIds.size() > USER_BATCH) throw new IllegalArgumentException("too many users in one call");
            return metrics.record(Operation.USERS, () -> {
                var data = typed(() -> client.contact().v3().user().batch(BatchUserReq.newBuilder()
                        .userIds(openIds.toArray(String[]::new)).userIdType("open_id").build(), tenant)).getData();
                var users = new ArrayList<User>();
                if (data.getItems() != null)
                    for (var user : data.getItems()) {
                        if (user.getOpenId() == null) continue;
                        var status = user.getStatus();
                        boolean active = status == null || (!Boolean.TRUE.equals(status.getIsResigned())
                                && !Boolean.TRUE.equals(status.getIsFrozen()) && !Boolean.TRUE.equals(status.getIsExited()));
                        var email = blankToNull(user.getEnterpriseEmail());
                        users.add(new User(user.getOpenId(), email == null ? blankToNull(user.getEmail()) : email, active));
                    }
                return List.copyOf(users);
            });
        }

        @Override public MemberPage chatMembers(String chatId, @Nullable String pageToken) {
            return metrics.record(Operation.CHAT_MEMBERS, () -> {
                var data = typed(() -> client.im().v1().chatMembers().get(GetChatMembersReq.newBuilder().chatId(chatId)
                        .memberIdType("open_id").pageSize(MEMBER_PAGE).pageToken(pageToken).build(), tenant)).getData();
                var members = new ArrayList<String>();
                if (data.getItems() != null)
                    for (var member : data.getItems())
                        if (member.getMemberId() != null) members.add(member.getMemberId());
                return new MemberPage(members, more(data.getHasMore(), data.getPageToken()));
            });
        }

        @Override public MemberPage departmentMembers(String departmentId, @Nullable String pageToken) {
            return metrics.record(Operation.DEPARTMENT_MEMBERS, () -> {
                var data = typed(() -> client.contact().v3().user().findByDepartment(FindByDepartmentUserReq.newBuilder()
                        .departmentId(departmentId).departmentIdType("open_department_id").userIdType("open_id")
                        .pageSize(NODE_PAGE).pageToken(pageToken).build(), tenant)).getData();
                var members = new ArrayList<String>();
                if (data.getItems() != null)
                    for (var user : data.getItems())
                        if (user.getOpenId() != null) members.add(user.getOpenId());
                return new MemberPage(members, more(data.getHasMore(), data.getPageToken()));
            });
        }

        @Override public CollaboratorPage spaceMembers(String spaceId, @Nullable String pageToken) {
            return metrics.record(Operation.SPACE_MEMBERS, () -> {
                var data = typed(() -> client.wiki().v2().spaceMember().list(ListSpaceMemberReq.newBuilder()
                        .spaceId(spaceId).pageSize(NODE_PAGE).pageToken(pageToken).build(), tenant)).getData();
                var members = new ArrayList<Collaborator>();
                if (data.getMembers() != null)
                    for (var member : data.getMembers())
                        if (member.getMemberType() != null && member.getMemberId() != null)
                            members.add(new Collaborator(member.getMemberType(), member.getMemberId(), member.getMemberRole()));
                return new CollaboratorPage(members, more(data.getHasMore(), data.getPageToken()));
            });
        }

        @Override public void close() {
        }

        /** One SDK call of a JSON answer, whose data is present when it returns. */
        private <R extends BaseResponse<?>> R typed(Callable<R> call) {
            R answer = check(exchange(properties.requestTimeout(), properties.maxResponseBytes(), call));
            if (answer.getData() == null) throw new LarkProviderException(MALFORMED);
            return answer;
        }

        /** One request the SDK has no model for, sent with the tenant token. */
        private JsonObject raw(String path) {
            return json(exchange(properties.requestTimeout(), properties.maxResponseBytes(), () ->
                    client.get(path, null, AccessTokenType.Tenant, tenant)), false);
        }
    }

    /** Runs one SDK call within its budget and turns a transport failure into the provider's. */
    private <R> R exchange(Duration timeout, int maxBytes, Callable<R> call) {
        try {
            return ScopedValue.where(LarkTransport.EXCHANGE, new Exchange(timeout, maxBytes)).call(call::call);
        } catch (LarkProviderException classified) {
            throw classified;
        } catch (LarkTransport.Status status) {
            if (status.code != 429) throw new LarkProviderException(UNAVAILABLE);
            Duration wait = RetryAfter.parse(status.retryAfter, clock);
            throw new LarkProviderException(QUOTA,
                    wait == null ? RetryAfter.parse(status.rateLimitReset, clock) : wait, Set.of());
        } catch (LarkTransport.TooLarge tooLarge) {
            throw new LarkProviderException(LIMIT_EXCEEDED);
        } catch (IOException unreachable) {
            throw new LarkProviderException(UNAVAILABLE);
        } catch (ObtainAccessTokenException refused) {
            throw new LarkProviderException(AUTHENTICATION);
        } catch (Exception unreadable) {
            // The SDK could not read the answer into its model.
            throw new LarkProviderException(MALFORMED);
        }
    }

    private <R extends BaseResponse<?>> R check(@Nullable R answer) {
        if (answer == null) throw new LarkProviderException(MALFORMED);
        if (answer.success()) return answer;
        RawResponse raw = answer.getRawResponse();
        throw failure(raw == null ? 0 : raw.getStatusCode(), answer.getCode(), scopes(answer.getError()));
    }

    private JsonObject json(RawResponse raw, boolean tokenRequest) {
        JsonObject answer;
        try {
            var parsed = JsonParser.parseString(new String(raw.getBody(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new LarkProviderException(MALFORMED);
            answer = parsed.getAsJsonObject();
        } catch (JsonParseException unreadable) {
            throw raw.getStatusCode() >= 400 ? failure(raw.getStatusCode(), -1, Set.of())
                    : new LarkProviderException(MALFORMED);
        }
        var code = answer.get("code");
        int value = code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber() ? code.getAsInt() : -1;
        if (value == 0 && raw.getStatusCode() < 400) return answer;
        // Lark refuses a wrong App ID or secret on the token request with a code of its own; a limit or an outage
        // keeps its own meaning, so the credential is not marked for an update it does not need.
        if (tokenRequest) {
            var refused = failure(raw.getStatusCode(), value, Set.of());
            throw refused.failure() == QUOTA || refused.failure() == UNAVAILABLE ? refused
                    : new LarkProviderException(AUTHENTICATION);
        }
        Set<String> scopes = Set.of();
        try {
            if (answer.has("error")) scopes = scopes(Jsons.DEFAULT.fromJson(answer.get("error"), Error.class));
        } catch (JsonParseException ignored) {
            // The scopes are an explanation; the failure stands without them.
        }
        throw failure(raw.getStatusCode(), value, scopes);
    }

    private static Set<String> scopes(@Nullable Error error) {
        if (error == null || error.getPermissionViolations() == null) return Set.of();
        var scopes = new LinkedHashSet<String>();
        for (var violation : error.getPermissionViolations())
            if (violation.getSubject() != null && !violation.getSubject().isBlank()) scopes.add(violation.getSubject());
        return scopes;
    }

    /**
     * What a refusal means. Lark's gateway codes say it exactly; for the product codes, which differ per API, the HTTP
     * status decides.
     */
    static LarkProviderException failure(int status, int code, Set<String> scopes) {
        return switch (code) {
            // Invalid, expired or missing access token; unknown app or wrong secret.
            case 99991661, 99991663, 99991664, 99991665, 99991668, 10003, 10014 -> new LarkProviderException(AUTHENTICATION);
            // The scope is not granted to this token type, or not at all.
            case 99991672, 99991679 -> new LarkProviderException(AUTHORIZATION, null, scopes);
            // Frequency limit.
            case 99991400 -> new LarkProviderException(QUOTA);
            // A document, file or Wiki node that does not exist or was deleted.
            case 1770002, 1770003, 1061003, 1061007, 131005 -> new LarkProviderException(NOT_FOUND);
            default -> new LarkProviderException(switch (status) {
                case 401 -> AUTHENTICATION;
                case 403 -> AUTHORIZATION;
                case 404 -> NOT_FOUND;
                case 429 -> QUOTA;
                default -> status >= 500 ? UNAVAILABLE : MALFORMED;
            });
        };
    }

    /**
     * The rows without the empty cells that end them and without the empty rows that end the sheet: Lark answers the
     * whole grid, which for a new sheet is 200 rows of 20 empty cells.
     */
    private static JsonArray trimmed(JsonArray rows) {
        var kept = new JsonArray();
        int last = -1;
        for (int index = 0; index < rows.size(); index++) {
            var row = new JsonArray();
            if (rows.get(index).isJsonArray()) {
                var cells = rows.get(index).getAsJsonArray();
                int end = cells.size();
                while (end > 0 && blank(cells.get(end - 1))) end--;
                for (int cell = 0; cell < end; cell++) row.add(cells.get(cell));
            }
            if (!row.isEmpty()) last = index;
            kept.add(row);
        }
        while (kept.size() > last + 1) kept.remove(kept.size() - 1);
        return kept;
    }

    private static boolean blank(JsonElement cell) {
        return cell.isJsonNull() || (cell.isJsonPrimitive() && cell.getAsString().isBlank());
    }

    private Snapshot snapshot(String schema, JsonObject content) {
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxSnapshotBytes()) throw new LarkProviderException(LIMIT_EXCEEDED);
        return new Snapshot(schema, bytes);
    }

    private static @Nullable JsonObject object(JsonObject parent, String name) {
        var value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static @Nullable String text(JsonObject parent, String name) {
        var value = parent.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static @Nullable String more(@Nullable Boolean hasMore, @Nullable String pageToken) {
        return Boolean.TRUE.equals(hasMore) && pageToken != null && !pageToken.isBlank() ? pageToken : null;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Lark sends times as epoch seconds in a string. */
    private static @Nullable Instant seconds(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long epoch = Long.parseLong(value);
            return epoch <= 0 ? null : Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException | DateTimeException malformed) {
            return null;
        }
    }

    /** The spreadsheet column letters of a one-based index: 1 is A, 27 is AA. */
    static String column(int index) {
        var letters = new StringBuilder();
        for (int rest = index; rest > 0; rest = (rest - 1) / 26) letters.insert(0, (char) ('A' + (rest - 1) % 26));
        return letters.toString();
    }

    private static String path(String segment) {
        return URLEncoder.encode(Objects.requireNonNull(segment), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
