package io.memoryos.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.files.ChatFileAttachments;
import io.memoryos.chat.files.ChatLibraryArtifacts;
import io.memoryos.chat.files.persistence.JdbcChatArtifactRepository;
import io.memoryos.chat.files.persistence.JdbcChatFileAttachmentRepository;
import io.memoryos.chat.image.persistence.JdbcImageArtifactRepository;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.ActorProfileReader;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.library.persistence.JdbcLibraryMarkRepository;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.meeting.LibraryMeetingShelf;
import io.memoryos.meeting.Meeting;
import io.memoryos.meeting.persistence.MeetingRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.retrieval.DocumentShelfService;
import io.memoryos.retrieval.ShelfDocument;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The library's reachable views over real PostgreSQL: a meeting share, a Group, an agent's audience and Search each
 * show the viewer a row while they grant it and stop at the next read once revoked, and the viewer's marks never
 * grant anything. Search's shelf is a stand-in here; its own authority is tested with the connector browse.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class LibraryShelfIntegrationTest {
    private static final LibraryShelfService.StarredQuery EVERY_STAR = new LibraryShelfService.StarredQuery("", Set.of(), 0, 100);

    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private final DocumentShelfService documents = mock(DocumentShelfService.class);
    private LibraryShelfService shelf;
    private UserFileService userFiles;
    private MeetingRepository meetings;
    private JdbcUserFileRepository files;
    private TenantId tenant;
    private ActorId owner;
    private ActorId viewer;
    private ActorId stranger;

    @BeforeEach
    void setup() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        jpa = TestDatabase.jpa(database);
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var library = new JdbcLibraryRepository(jdbc);
        files = new JdbcUserFileRepository(jdbc);
        var attachments = new ChatFileAttachments(new JdbcChatFileAttachmentRepository(jdbc));
        var quotas = new StorageQuotaService(tenants, new LibraryStorageProperties(0), library);
        var owned = new LibraryService(tenants, library, files, attachments,
                new ChatLibraryArtifacts(new JdbcChatArtifactRepository(jdbc), new JdbcInterpreterRepository(jdbc),
                        new JdbcImageArtifactRepository(jdbc)),
                mock(ObjectStorage.class), mock(ObjectWriteService.class), new UserFileProperties(104857600, 262144000),
                quotas, jpa.transactionManager(), mock(UserFileSearchService.class));
        var authorization = mock(IamAuthorization.class);
        when(authorization.effectiveCapabilities(any())).thenReturn(Set.of(IamCapability.CHAT_WRITE));
        meetings = new MeetingRepository(jdbc);
        var profiles = mock(ActorProfileReader.class);
        when(profiles.read(any())).thenReturn(new ActorProfileReader.Profile("Chị Lan", null));
        shelf = new LibraryShelfService(tenants, library, files, new JdbcLibraryMarkRepository(jdbc), attachments,
                new LibraryMeetingShelf(authorization, meetings), documents, owned, profiles, jpa.transactionManager());
        userFiles = new UserFileService(tenants, files, attachments, mock(ObjectUploadService.class),
                new UserFileProperties(104857600, 262144000), quotas, new LibraryTrashProperties(Duration.ZERO),
                jpa.transactionManager());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Shelf','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = member();
        viewer = member();
        stranger = member();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void aMeetingSharedWithTheViewerOrTheirGroupIsListedUntilTheShareEnds() {
        var direct = meeting(owner, "Họp giao ban");
        var viaGroup = meeting(owner, "Họp kế toán");
        var own = meeting(viewer, "Họp của tôi");
        meeting(owner, "Họp riêng");
        var accounting = group("Kế toán", viewer);
        var board = group("Ban giám đốc", owner);
        meetings.share(tenant.value(), direct, List.of(viewer.value()), List.of());
        meetings.share(tenant.value(), viaGroup, List.of(), List.of(accounting, board));
        meetings.insertUtterance(tenant.value(), direct, new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "0",
                0, 1200, "Chào mọi người", 0.9, List.of(), null));

        var shared = shelf.shared(viewer, sharedMeetings());
        assertEquals(List.of(direct, viaGroup), ids(shared.items()), "never their own nor one not shared with them");
        var rows = byId(shared.items());
        assertEquals(new LibraryEntry.Reason(LibraryEntry.ReasonKind.MEMBER_SHARE, List.of()), rows.get(direct).reason());
        assertEquals(new LibraryEntry.Reason(LibraryEntry.ReasonKind.GROUP_SHARE, List.of("Kế toán")),
                rows.get(viaGroup).reason(), "only the viewer's own Groups are named");
        assertFalse(rows.get(direct).owned());
        assertEquals(new LibraryEntry.MeetingState("RECORDING", false, true, 1200), rows.get(direct).meeting());
        assertEquals(new LibraryEntry.MeetingState("RECORDING", false, false, 0), rows.get(viaGroup).meeting());
        assertEquals(0, shelf.shared(stranger, sharedMeetings()).totalCount());

        // The meetings view holds every meeting they may read, and tells theirs from the shared ones.
        assertEquals(Set.of(direct, viaGroup, own), Set.copyOf(ids(meetingsOf(LibraryShelfService.Owner.ALL))));
        assertEquals(List.of(own), ids(meetingsOf(LibraryShelfService.Owner.MINE)));
        assertEquals(Set.of(direct, viaGroup), Set.copyOf(ids(meetingsOf(LibraryShelfService.Owner.SHARED))));

        // Unsharing, or leaving the Group, removes the meeting at the next read.
        meetings.share(tenant.value(), direct, List.of(), List.of());
        jdbc.sql("DELETE FROM iam_group_memberships WHERE tenant_id = :tenant AND group_id = :group")
                .param("tenant", tenant.value()).param("group", accounting).update();
        assertEquals(List.of(), ids(shelf.shared(viewer, sharedMeetings()).items()));
        assertEquals(List.of(own), ids(meetingsOf(LibraryShelfService.Owner.ALL)));
    }

    @Test
    void anAgentsKnowledgeFilesAreSharedOnlyWhileTheViewerUsesTheAgent() {
        var knowledge = readyFile(owner, "Quy chế.pdf", "application/pdf");
        var avatar = readyFile(owner, "logo.png", "image/png");
        readyFile(owner, "Riêng.txt", "text/plain");
        var mine = readyFile(viewer, "Của tôi.txt", "text/plain");
        var agent = agent(owner, "Trợ lý nhân sự", List.of(knowledge, mine));
        jdbc.sql("UPDATE persona SET avatar_file_id = :file WHERE id = :id").param("file", avatar).param("id", agent).update();
        // Managing agents is not using them: a manager the agent is not shared with reads none of its files.
        var managers = group("Quản trị trợ lý", stranger);
        jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id, group_id, capability) VALUES (:tenant, :group, 'AGENTS_MANAGE')")
                .param("tenant", tenant.value()).param("group", managers).update();

        assertEquals(List.of(), ids(shelf.shared(viewer, sharedAgentFiles()).items()));
        assertUnavailable(() -> userFiles.servable(viewer, knowledge));

        jdbc.sql("INSERT INTO persona_user_share(tenant_id, persona_id, actor_id, permission) VALUES (:tenant, :agent, :actor, 'VIEWER')")
                .param("tenant", tenant.value()).param("agent", agent).param("actor", viewer.value()).update();
        var shared = shelf.shared(viewer, sharedAgentFiles());
        assertEquals(List.of(knowledge), ids(shared.items()), "never the avatar, another upload or their own file");
        var file = shared.items().getFirst();
        assertEquals(LibraryEntry.Kind.AGENT_FILE, file.kind());
        assertFalse(file.owned());
        assertEquals(new LibraryEntry.Reason(LibraryEntry.ReasonKind.AGENT, List.of("Trợ lý nhân sự")), file.reason());
        assertEquals(List.of(new LibraryEntry.Agent(agent, "Trợ lý nhân sự")), file.agents());
        assertEquals("Chị Lan", file.ownerName());
        assertEquals(LibraryFile.Category.DOCUMENT, file.category());
        // Owner decision 2: whoever reads a file through an agent may download it; nobody else may.
        assertEquals(knowledge, userFiles.servable(viewer, knowledge).id());
        assertUnavailable(() -> userFiles.servable(stranger, knowledge));
        assertEquals(List.of(), ids(shelf.shared(stranger, sharedAgentFiles()).items()));

        jdbc.sql("DELETE FROM persona_user_share WHERE persona_id = :agent").param("agent", agent).update();
        assertEquals(List.of(), ids(shelf.shared(viewer, sharedAgentFiles()).items()));
        assertUnavailable(() -> userFiles.servable(viewer, knowledge));

        // A public agent grants its files to every member but the uploader, whose file stays an owned row.
        jdbc.sql("UPDATE persona SET is_public = TRUE WHERE id = :agent").param("agent", agent).update();
        assertEquals(List.of(knowledge), ids(shelf.shared(viewer, sharedAgentFiles()).items()));
        assertEquals(List.of(mine), ids(shelf.shared(owner, sharedAgentFiles()).items()));

        jdbc.sql("UPDATE persona SET deleted_at = CURRENT_TIMESTAMP WHERE id = :agent").param("agent", agent).update();
        assertEquals(List.of(), ids(shelf.shared(viewer, sharedAgentFiles()).items()));
    }

    @Test
    void recentListsWhatTheViewerOpenedNewestFirstWhileTheyMayStillReadIt() {
        var meeting = meeting(owner, "Họp giao ban");
        meetings.share(tenant.value(), meeting, List.of(viewer.value()), List.of());
        var knowledge = readyFile(owner, "Quy chế.pdf", "application/pdf");
        var agent = agent(owner, "Trợ lý nhân sự", List.of(knowledge));
        jdbc.sql("UPDATE persona SET is_public = TRUE WHERE id = :agent").param("agent", agent).update();
        var mine = readyFile(viewer, "Ghi chú.txt", "text/plain");
        var report = UUID.randomUUID();
        when(documents.find(any(), any())).thenAnswer(call -> readable(call.getArgument(1), Set.of(report)));

        shelf.opened(viewer, LibraryEntry.Kind.MEETING, meeting);
        shelf.opened(viewer, LibraryEntry.Kind.AGENT_FILE, knowledge);
        shelf.opened(viewer, LibraryEntry.Kind.UPLOAD, mine);
        shelf.opened(viewer, LibraryEntry.Kind.DOCUMENT, report);
        var recent = shelf.recent(viewer, 100);
        assertEquals(List.of(report, mine, knowledge, meeting), ids(recent));
        assertTrue(recent.stream().allMatch(entry -> entry.openedAt() != null));
        assertEquals(List.of(), shelf.recent(stranger, 100), "opens are the viewer's own");

        // Opening again moves a row to the front.
        shelf.opened(viewer, LibraryEntry.Kind.MEETING, meeting);
        assertEquals(List.of(meeting, report, mine, knowledge), ids(shelf.recent(viewer, 100)));
        assertEquals(List.of(meeting, report), ids(shelf.recent(viewer, 2)));

        // A revoked share, and Search withheld, drop their rows at the next read.
        meetings.share(tenant.value(), meeting, List.of(), List.of());
        doReturn(List.of()).when(documents).find(any(), any());
        assertEquals(List.of(mine, knowledge), ids(shelf.recent(viewer, 100)));
        assertThrows(LibraryException.class, () -> shelf.recent(viewer, 101));
    }

    @Test
    void onlyTheNewestTwoHundredOpensAreKeptAndAStarOutlivesItsOpen() {
        var mine = readyFile(viewer, "Ghi chú.txt", "text/plain");
        jdbc.sql("""
                INSERT INTO library_mark(tenant_id, actor_id, kind, item_id, opened_at)
                SELECT :tenant, :actor, 'DOCUMENT', gen_random_uuid(), CURRENT_TIMESTAMP - make_interval(mins => n)
                FROM generate_series(1, 200) AS n
                """).param("tenant", tenant.value()).param("actor", viewer.value()).update();
        var starred = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO library_mark(tenant_id, actor_id, kind, item_id, starred_at, opened_at)
                VALUES (:tenant, :actor, 'MEETING', :item, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '1 day')
                """).param("tenant", tenant.value()).param("actor", viewer.value()).param("item", starred).update();
        var oldestDocument = jdbc.sql("SELECT item_id FROM library_mark WHERE kind = 'DOCUMENT' ORDER BY opened_at LIMIT 1")
                .query(UUID.class).single();

        shelf.opened(viewer, LibraryEntry.Kind.UPLOAD, mine);

        assertEquals(200, count("library_mark WHERE opened_at IS NOT NULL"));
        assertEquals(0, count("library_mark WHERE item_id = '" + oldestDocument + "'"), "an old open alone is forgotten");
        assertEquals(1, count("library_mark WHERE item_id = '" + starred + "' AND starred_at IS NOT NULL AND opened_at IS NULL"),
                "a star is kept when its open is forgotten");
        assertEquals(1, count("library_mark WHERE item_id = '" + mine + "' AND opened_at IS NOT NULL"));
    }

    @Test
    void aStarOnAReachableRowShowsOnlyWhileTheViewerMayReadIt() {
        var meeting = meeting(owner, "Họp kế toán");
        var accounting = group("Kế toán", viewer);
        meetings.share(tenant.value(), meeting, List.of(), List.of(accounting));
        var mine = readyFile(viewer, "Ghi chú.txt", "text/plain");

        shelf.star(viewer, LibraryEntry.Kind.MEETING, meeting);
        shelf.star(viewer, LibraryEntry.Kind.MEETING, meeting);
        shelf.star(viewer, LibraryEntry.Kind.UPLOAD, mine);
        var starred = shelf.starred(viewer, EVERY_STAR);
        assertEquals(List.of(mine, meeting), ids(starred.items()), "most recently starred first, owned and shared");
        assertTrue(starred.items().stream().allMatch(LibraryEntry::starred));
        assertTrue(byId(starred.items()).get(mine).owned());
        assertTrue(shelf.shared(viewer, sharedMeetings()).items().getFirst().starred());
        assertEquals(List.of(meeting), ids(shelf.starred(viewer,
                new LibraryShelfService.StarredQuery("kế", Set.of(), 0, 100)).items()));
        assertEquals(List.of(), ids(shelf.starred(stranger, EVERY_STAR).items()));

        // Leaving the Group takes the meeting out of Starred at once; the star grants nothing.
        jdbc.sql("DELETE FROM iam_group_memberships WHERE tenant_id = :tenant AND group_id = :group")
                .param("tenant", tenant.value()).param("group", accounting).update();
        assertEquals(List.of(mine), ids(shelf.starred(viewer, EVERY_STAR).items()));
        assertUnavailable(() -> shelf.unstar(viewer, LibraryEntry.Kind.MEETING, meeting));

        shelf.unstar(viewer, LibraryEntry.Kind.UPLOAD, mine);
        assertEquals(List.of(), ids(shelf.starred(viewer, EVERY_STAR).items()));
    }

    @Test
    void starringMoreThanFiveHundredSharedRowsIsRefusedUntilSomeAreNoLongerReadable() {
        var seeded = jdbc.sql("""
                INSERT INTO library_mark(tenant_id, actor_id, kind, item_id, starred_at)
                SELECT :tenant, :actor, 'DOCUMENT', gen_random_uuid(), CURRENT_TIMESTAMP - make_interval(mins => n)
                FROM generate_series(1, 500) AS n RETURNING item_id
                """).param("tenant", tenant.value()).param("actor", viewer.value()).query(UUID.class).list();
        var extra = UUID.randomUUID();
        var everything = new HashSet<>(seeded);
        everything.add(extra);
        when(documents.find(any(), any())).thenAnswer(call -> readable(call.getArgument(1), everything));

        var refused = assertThrows(LibraryException.class, () -> shelf.star(viewer, LibraryEntry.Kind.DOCUMENT, extra));
        assertEquals("CHAT_INVALID_REQUEST", refused.code());
        assertEquals(500, count("library_mark WHERE starred_at IS NOT NULL"));

        // A star on a document the viewer can no longer read is shown nowhere, so it gives way to a new one.
        var lost = seeded.getFirst();
        everything.remove(lost);
        shelf.star(viewer, LibraryEntry.Kind.DOCUMENT, extra);
        assertEquals(500, count("library_mark WHERE starred_at IS NOT NULL"));
        assertEquals(0, count("library_mark WHERE item_id = '" + lost + "'"));
        assertEquals(extra, shelf.starred(viewer, EVERY_STAR).items().getFirst().id());
    }

    @Test
    void whatTheViewerCannotReadNowCannotBeStarredOrOpened() {
        var unshared = meeting(owner, "Họp riêng");
        var foreign = readyFile(owner, "Riêng.txt", "text/plain");
        var gone = UUID.randomUUID();

        assertUnavailable(() -> shelf.star(viewer, LibraryEntry.Kind.MEETING, unshared));
        assertUnavailable(() -> shelf.opened(viewer, LibraryEntry.Kind.MEETING, unshared));
        assertUnavailable(() -> shelf.star(viewer, LibraryEntry.Kind.AGENT_FILE, foreign));
        assertUnavailable(() -> shelf.opened(viewer, LibraryEntry.Kind.UPLOAD, foreign));
        assertUnavailable(() -> shelf.star(viewer, LibraryEntry.Kind.UPLOAD, foreign));
        assertUnavailable(() -> shelf.star(viewer, LibraryEntry.Kind.DOCUMENT, gone));
        assertUnavailable(() -> shelf.opened(viewer, LibraryEntry.Kind.DOCUMENT, gone));
        assertEquals(0, count("library_mark"));
        assertTrue(jdbc.sql("SELECT favorite_at IS NULL FROM chat_user_file WHERE id = :id").param("id", foreign)
                .query(Boolean.class).single());
    }

    private LibraryShelfService.SharedQuery sharedMeetings() {
        return new LibraryShelfService.SharedQuery("", Set.of(LibraryEntry.Kind.MEETING), Set.of(),
                LibraryShelfService.Sort.NAME, 0, 50);
    }

    private LibraryShelfService.SharedQuery sharedAgentFiles() {
        return new LibraryShelfService.SharedQuery("", Set.of(LibraryEntry.Kind.AGENT_FILE), Set.of(),
                LibraryShelfService.Sort.NAME, 0, 50);
    }

    private List<LibraryEntry> meetingsOf(LibraryShelfService.Owner whose) {
        return shelf.meetings(viewer, new LibraryShelfService.MeetingQuery("", whose, LibraryShelfService.Sort.NAME, 0, 50))
                .items();
    }

    /** The Source documents among {@code ids} the stand-in for Search lets the viewer read. */
    private static List<ShelfDocument> readable(Collection<UUID> ids, Set<UUID> readable) {
        return ids.stream().filter(readable::contains).map(id -> new ShelfDocument(id, null, "Báo cáo.pdf", null,
                "application/pdf", 10, "DOCUMENT", Instant.parse("2026-09-01T00:00:00Z"), UUID.randomUUID(),
                "Tài liệu chung", SourceType.FILE, null, ShelfDocument.Access.PUBLIC, List.of())).toList();
    }

    private static void assertUnavailable(Executable call) {
        var failure = assertThrows(LibraryException.class, call);
        assertEquals("CHAT_UNAVAILABLE", failure.code());
    }

    private static List<UUID> ids(List<LibraryEntry> entries) {
        return entries.stream().map(LibraryEntry::id).toList();
    }

    private static Map<UUID, LibraryEntry> byId(List<LibraryEntry> entries) {
        return entries.stream().collect(Collectors.toMap(LibraryEntry::id, Function.identity()));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private ActorId member() {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
        return actor;
    }

    private UUID group(String name, ActorId member) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:tenant, :id, :name)")
                .param("tenant", tenant.value()).param("id", id).param("name", name).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id, group_id, actor_id) VALUES (:tenant, :group, :actor)")
                .param("tenant", tenant.value()).param("group", id).param("actor", member.value()).update();
        return id;
    }

    private UUID meeting(ActorId by, String title) {
        var id = UUID.randomUUID();
        meetings.insert(tenant.value(), id, by.value(), new Meeting.Draft(title, Meeting.Kind.IN_PERSON, "vi", List.of(), List.of()));
        return id;
    }

    /** A custom agent the creator owns, private until shared, holding {@code knowledge} as its files. */
    private UUID agent(ActorId creator, String name, List<UUID> knowledge) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO persona(id, tenant_id, owner_actor_id, name, instructions, model, file_ids)
                VALUES (:id, :tenant, :owner, :name, '', 'gpt', CAST(:files AS jsonb))
                """).param("id", id).param("tenant", tenant.value()).param("owner", creator.value()).param("name", name)
                .param("files", knowledge.stream().map(file -> "\"" + file + "\"").collect(Collectors.joining(",", "[", "]")))
                .update();
        return id;
    }

    /** A READY upload, as the file worker leaves one. */
    private UUID readyFile(ActorId by, String filename, String mediaType) {
        var objectId = new StoredObjectId(UUID.randomUUID());
        var uploadId = new ObjectUploadId(UUID.randomUUID());
        var spec = new ObjectUploadSpecification(filename, mediaType, 4, new ContentSha256("a".repeat(64)),
                ObjectUploadPurpose.CHAT_FILE);
        new JdbcStoredObjectRepository(jdbc).create(tenant, objectId,
                new ObjectKey("raw/" + tenant.value() + "/" + objectId.value()), spec, Instant.now().plusSeconds(600));
        new JdbcObjectUploadRepository(jdbc).create(tenant, uploadId, objectId, spec.purpose());
        var id = files.create(tenant, by, UUID.randomUUID(), uploadId, spec);
        jdbc.sql("UPDATE chat_user_file SET status='READY', plaintext='Test', stored_object_id=:object WHERE id=:id")
                .param("object", objectId.value()).param("id", id).update();
        assertNotNull(id);
        return id;
    }
}
