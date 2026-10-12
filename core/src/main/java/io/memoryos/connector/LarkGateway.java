package io.memoryos.connector;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Lark Suite through one custom app of a Lark tenant, authenticated with its App ID and secret. Every call carries the
 * app's tenant token, so Lark answers with what is shared with the app and nothing else. Failures are
 * {@link LarkProviderException}s.
 */
public interface LarkGateway {
    Session open(Credential credential);

    /** The most users one {@link Session#users} call names. */
    int USER_BATCH = 50;

    interface Session extends AutoCloseable {
        /** {@code bot/v3/info}: proves the credential works and names the bot people add to a space or a folder. */
        Bot bot();

        /**
         * The read scopes the connector needs that Lark refuses to the tenant token, by name; empty when all are
         * granted. A scope granted only for the user token type is refused here.
         */
        Set<String> missingScopes();

        /** One page of the Wiki spaces the app is a member of. */
        SpacePage spaces(@Nullable String pageToken);

        Space space(String spaceId);

        /** One page of a Wiki space's nodes below {@code parentNodeToken}, or its top level when null. */
        NodePage nodes(String spaceId, @Nullable String parentNodeToken, @Nullable String pageToken);

        /** The name of a Drive folder shared with the app. */
        Folder folder(String folderToken);

        /** One page of a Drive folder's direct children. */
        FilePage files(String folderToken, @Nullable String pageToken);

        /** A Docx document's blocks, as the snapshot the Docx reader consumes. */
        Snapshot docx(String documentId);

        /** A spreadsheet's sheets and their values within the bounds, as the snapshot the Sheet reader consumes. */
        Snapshot sheet(String spreadsheetToken);

        /** Downloads an uploaded file, refusing one larger than {@code maxBytes}. */
        Content download(String fileToken, int maxBytes);

        /**
         * The object's collaborators and link setting, read completely or not at all: a list that cannot be read to
         * its end raises rather than returning part of it.
         */
        Permissions permissions(String token, ObjectType type);

        /**
         * The named users with their emails, at most {@link #USER_BATCH} at a time. A user the app's contact range
         * does not cover is left out of the answer.
         */
        List<User> users(List<String> openIds);

        /** One page of a group chat's members. */
        MemberPage chatMembers(String chatId, @Nullable String pageToken);

        /** One page of a department's direct members. */
        MemberPage departmentMembers(String departmentId, @Nullable String pageToken);

        /** One page of every department below a department, at any depth. */
        DepartmentPage departmentChildren(String departmentId, @Nullable String pageToken);

        /** One page of a Wiki space's members. */
        CollaboratorPage spaceMembers(String spaceId, @Nullable String pageToken);

        @Override void close();
    }

    /** Lark international or Feishu: an app exists on one of them only. */
    enum Region {
        LARK("https://open.larksuite.com"),
        FEISHU("https://open.feishu.cn");

        private final String baseUrl;

        Region(String baseUrl) { this.baseUrl = baseUrl; }

        public String baseUrl() { return baseUrl; }
    }

    record Credential(Region region, String appId, byte[] appSecret) implements AutoCloseable {
        public Credential {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(appId, "appId");
            appSecret = Objects.requireNonNull(appSecret, "appSecret").clone();
        }

        @Override public byte[] appSecret() { return appSecret.clone(); }

        @Override public void close() { Arrays.fill(appSecret, (byte) 0); }

        @Override public @NonNull String toString() { return "LarkCredential[" + appId + "]"; }
    }

    /** The bot identity of the app; {@code openId} is what a space or a collaborator list names it by. */
    record Bot(String appName, String openId) {
        public Bot {
            Objects.requireNonNull(appName, "appName");
            Objects.requireNonNull(openId, "openId");
        }
    }

    /**
     * The kinds of object Lark stores. Only {@link #DOCX}, {@link #SHEET}, {@link #FILE} and {@link #FOLDER} are read;
     * the others are skipped. {@code wire} is Lark's name in node and file listings and in permission calls.
     */
    enum ObjectType {
        DOCX("docx"), SHEET("sheet"), FILE("file"), FOLDER("folder"), DOC("doc"), BITABLE("bitable"),
        MINDNOTE("mindnote"), SLIDES("slides"), SHORTCUT("shortcut"), OTHER("");

        private final String wire;

        ObjectType(String wire) { this.wire = wire; }

        public String wire() { return wire; }

        public static ObjectType of(@Nullable String wire) {
            for (var type : values())
                if (type != OTHER && type.wire.equals(wire)) return type;
            return OTHER;
        }
    }

    record Space(String spaceId, String name, @Nullable String visibility) {
        public Space {
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(name, "name");
        }
    }

    record SpacePage(List<Space> spaces, @Nullable String pageToken) {
        public SpacePage { spaces = List.copyOf(spaces); }
    }

    /**
     * A Wiki node: a place in a space's tree pointing at one object. {@code objToken} is what the object's own APIs
     * take; {@code url} is the address Lark returns, which a reader opens.
     */
    record Node(String nodeToken, String spaceId, @Nullable String parentNodeToken, ObjectType objType,
                String objToken, String title, boolean hasChild, @Nullable Instant objEditedAt,
                @Nullable Instant objCreatedAt, @Nullable String url) {
        public Node {
            Objects.requireNonNull(nodeToken, "nodeToken");
            Objects.requireNonNull(spaceId, "spaceId");
            Objects.requireNonNull(objType, "objType");
            Objects.requireNonNull(objToken, "objToken");
            Objects.requireNonNull(title, "title");
        }
    }

    record NodePage(List<Node> nodes, @Nullable String pageToken) {
        public NodePage { nodes = List.copyOf(nodes); }
    }

    record Folder(String token, String name) {
        public Folder {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(name, "name");
        }
    }

    /** A Drive entry; {@code url} is the address Lark returns, which a reader opens. */
    record File(String token, String name, ObjectType type, @Nullable String parentToken, @Nullable String url,
                @Nullable Instant createdAt, @Nullable Instant modifiedAt, @Nullable String ownerOpenId) {
        public File {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    record FilePage(List<File> files, @Nullable String pageToken) {
        public FilePage { files = List.copyOf(files); }
    }

    /** {@code bytes} is the JSON a reader of {@code schema} consumes. */
    record Snapshot(String schema, byte[] bytes) {
        public Snapshot {
            Objects.requireNonNull(schema, "schema");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }

        @Override public @NonNull String toString() { return "Snapshot[" + schema + "," + bytes.length + " bytes]"; }
    }

    record Content(String filename, String mediaType, byte[] bytes) {
        public Content {
            Objects.requireNonNull(filename, "filename");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override public @NonNull String toString() { return "Content[" + bytes.length + " bytes]"; }
    }

    /**
     * One entry of a collaborator or space member list. {@code memberType} is Lark's ({@code openid},
     * {@code openchat}, {@code opendepartmentid}, {@code wikispaceid}, {@code appid}, …), kept as Lark sends it.
     */
    record Collaborator(String memberType, String memberId, @Nullable String perm) {
        public Collaborator {
            Objects.requireNonNull(memberType, "memberType");
            Objects.requireNonNull(memberId, "memberId");
        }
    }

    record CollaboratorPage(List<Collaborator> members, @Nullable String pageToken) {
        public CollaboratorPage { members = List.copyOf(members); }
    }

    /**
     * {@code linkShare} is Lark's {@code link_share_entity}: {@code tenant_readable}, {@code tenant_editable},
     * {@code anyone_readable}, {@code anyone_editable} or {@code closed}; null when Lark returned none.
     */
    record Permissions(List<Collaborator> collaborators, @Nullable String linkShare) {
        public Permissions { collaborators = List.copyOf(collaborators); }
    }

    /** A user of the tenant; {@code email} is null when the user has none or the app may not read it. */
    record User(String openId, @Nullable String email, boolean active) {
        public User { Objects.requireNonNull(openId, "openId"); }

        @Override public @NonNull String toString() { return "User[" + openId + "]"; }
    }

    /** Departments, by {@code open_department_id}. */
    record DepartmentPage(List<String> departmentIds, @Nullable String pageToken) {
        public DepartmentPage { departmentIds = List.copyOf(departmentIds); }
    }

    /** Members of a chat or a department, by {@code open_id}. */
    record MemberPage(List<String> openIds, @Nullable String pageToken) {
        public MemberPage { openIds = List.copyOf(openIds); }
    }
}
