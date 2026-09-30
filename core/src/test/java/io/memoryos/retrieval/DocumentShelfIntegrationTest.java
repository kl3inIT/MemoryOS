package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.retrieval.ShelfDocument.Access;
import io.memoryos.retrieval.ShelfQuery.Sort;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The Source document shelf over real PostgreSQL: what a reader may open now, and nothing else. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DocumentShelfIntegrationTest {
    private static final String LIVE_INDEX = "live-index";
    private static final Instant BASE = Instant.parse("2026-09-01T08:00:00Z");

    private HikariDataSource database;
    private JdbcClient jdbc;
    private TenantId tenant;
    private DocumentShelfService shelf;
    private ActorId alice, bob, inactive, withoutSearch, homeless;
    private UUID privateSource, publicSource, driveSource, finance, audit;
    private int sequence;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'shelf','Shelf','ACTIVE','TEST')")
                .param("id", tenant.value()).update();
        alice = member("alice@example.test", "ACTIVE");
        bob = member("bob@other.test", "ACTIVE");
        inactive = member("alice@example.test", "INACTIVE");
        withoutSearch = member("alice@example.test", "ACTIVE");
        homeless = member("alice@example.test", "ACTIVE");
        // Source ids are ordered so that the PRIVATE Source is a document's first mapping.
        privateSource = source(1, "Finance files", "FILE", "PRIVATE");
        publicSource = source(2, "Handbooks", "FILE", "PUBLIC");
        driveSource = source(3, "Drive", "GOOGLE_DRIVE", "SYNC");
        jdbc.sql("WITH state AS (INSERT INTO source_sync_state (tenant_id, source_id) VALUES (:tenant, :source)) "
                        + "INSERT INTO google_drive_sources(tenant_id,source_id) VALUES(:tenant,:source)")
                .param("tenant", tenant.value()).param("source", driveSource).update();
        finance = group("Finance", privateSource, alice, inactive, withoutSearch);
        audit = group("Audit", privateSource, alice);
        group("Zeta", privateSource);

        var tenants = mock(TenantAccessResolver.class);
        when(tenants.findActiveTenant(any())).thenReturn(Optional.of(tenant));
        when(tenants.findActiveTenant(homeless)).thenReturn(Optional.empty());
        var authorization = mock(IamAuthorization.class);
        when(authorization.effectiveCapabilities(any())).thenReturn(Set.of(IamCapability.SEARCH_READ));
        when(authorization.effectiveCapabilities(withoutSearch)).thenReturn(Set.of());
        when(authorization.require(eq(withoutSearch), eq(IamCapability.SEARCH_READ), eq(false)))
                .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "no search"));
        var index = mock(OpenSearchIndexService.class);
        when(index.identity()).thenReturn(LIVE_INDEX);
        shelf = new DocumentShelfService(tenants, authorization,
                new SourceSearchService(tenants, new JdbcSourceDocumentRepository(jdbc)), index);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void listsEachReadableDocumentOnceUnderItsFirstReadableMappingWithTheAuthorityThatAdmittedIt() {
        UUID handbook = document(publicSource, "handbook.pdf", "application/pdf", BASE, "Employee handbook");
        UUID budget = document(privateSource, "budget.xlsx", "application/vnd.ms-excel", BASE.plusSeconds(1), null);
        UUID shared = document(privateSource, "shared.docx", "application/msword", BASE.plusSeconds(2), null);
        map(publicSource, shared, "shared.docx", BASE.plusSeconds(2));
        UUID granted = document(driveSource, "roadmap.pdf", "application/pdf", BASE.plusSeconds(3), null);
        grantDrive("roadmap.pdf", "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"Alice@Example.test\"}");
        UUID hiddenDrive = document(driveSource, "salaries.pdf", "application/pdf", BASE.plusSeconds(4), null);
        grantDrive("salaries.pdf", "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"ceo@example.test\"}");

        var forAlice = all(alice, Sort.NEWEST);
        assertEquals(List.of(granted, shared, budget, handbook), ids(forAlice));
        assertEquals(Access.PROVIDER, forAlice.get(0).access());
        assertEquals(List.of(), forAlice.get(0).groups());
        // A document mapped by two Sources is listed once, under the first one the reader may read.
        assertEquals(privateSource, forAlice.get(1).sourceId());
        assertEquals(Access.GROUP, forAlice.get(1).access());
        assertEquals(List.of("Audit", "Finance"), forAlice.get(1).groups(), "only the reader's own granted Groups");
        var first = forAlice.get(3);
        assertEquals(Access.PUBLIC, first.access());
        assertEquals("handbook.pdf", first.filename());
        assertEquals("Employee handbook", first.title());
        assertEquals("application/pdf", first.mediaType());
        assertEquals("DOCUMENT", first.category());
        assertEquals("Handbooks", first.sourceName());
        assertEquals(SourceType.FILE, first.sourceType());
        assertEquals("https://files.test/handbook.pdf", first.providerUrl());
        assertEquals(64L, first.sizeBytes());
        assertEquals(BASE, first.updatedAt());
        assertEquals("SPREADSHEET", forAlice.get(2).category());

        var forBob = all(bob, Sort.NEWEST);
        assertEquals(List.of(shared, handbook), ids(forBob));
        assertEquals(publicSource, forBob.get(0).sourceId());
        assertEquals(Access.PUBLIC, forBob.get(0).access());
        assertEquals(List.of(), forBob.get(0).groups());

        assertEquals(List.of(), all(inactive, Sort.NEWEST), "an inactive membership reads nothing");
        assertTrue(ids(all(alice, Sort.NAME)).containsAll(List.of(granted, shared, budget, handbook)));
        assertFalse(ids(all(alice, Sort.NAME)).contains(hiddenDrive));

        assertEquals(Set.of("Finance files", "Handbooks", "Drive"),
                Set.copyOf(shelf.sources(alice).stream().map(SourceSearchService.SourceOption::name).toList()));
        assertEquals(Set.of("Handbooks", "Drive"),
                Set.copyOf(shelf.sources(bob).stream().map(SourceSearchService.SourceOption::name).toList()));
    }

    @Test
    void revokedGroupsAndUnavailableSourcesHideDocumentsAtTheNextRead() {
        UUID budget = document(privateSource, "budget.xlsx", "application/vnd.ms-excel", BASE, null);
        UUID handbook = document(publicSource, "handbook.pdf", "application/pdf", BASE, null);

        leave(finance, alice);
        var still = shelf.find(alice, List.of(budget));
        assertEquals(List.of(budget), ids(still));
        assertEquals(List.of("Audit"), still.getFirst().groups());
        leave(audit, alice);
        assertEquals(List.of(), shelf.find(alice, List.of(budget)));
        assertEquals(List.of(handbook), ids(all(alice, Sort.NEWEST)));

        jdbc.sql("UPDATE connector_credential_pairs SET status='PAUSED' WHERE id=:id").param("id", publicSource).update();
        assertEquals(List.of(), all(bob, Sort.NEWEST));
        jdbc.sql("UPDATE connector_credential_pairs SET status='DELETING' WHERE id=:id").param("id", publicSource).update();
        assertEquals(List.of(), shelf.find(bob, List.of(handbook)));
        jdbc.sql("UPDATE connector_credential_pairs SET status='ACTIVE' WHERE id=:id").param("id", publicSource).update();
        jdbc.sql("UPDATE documents_by_connector_credential_pair SET retrieval_eligible=FALSE WHERE document_id=:id")
                .param("id", handbook).update();
        assertEquals(List.of(), all(bob, Sort.NEWEST), "a withdrawn mapping is not listed");
    }

    @Test
    void keysetCursorWalksEveryReadableDocumentExactlyOnceInBothSorts() {
        var expected = new HashSet<UUID>();
        for (int i = 0; i < 9; i++) {
            // Equal times and equal names force the Document id tie-break across page boundaries.
            expected.add(document(publicSource, (i % 3 == 0 ? "Same" : "file-" + (char) ('a' + 8 - i)) + ".pdf",
                    "application/pdf", BASE.plusSeconds(i / 2), null));
        }
        expected.add(document(privateSource, "private.pdf", "application/pdf", BASE, null));
        expected.add(document(privateSource, "zzz-hidden-from-bob.pdf", "application/pdf", BASE, null));

        for (var sort : Sort.values()) {
            var walked = new ArrayList<ShelfDocument>();
            String cursor = null;
            int pages = 0;
            do {
                var page = shelf.page(alice, new ShelfQuery("", Set.of(), Set.of(), sort, cursor, 2));
                walked.addAll(page.items());
                cursor = page.nextCursor();
                pages++;
            } while (cursor != null && pages < 20);
            assertEquals(ids(all(alice, sort)), ids(walked), sort + " pages concatenate to the single page");
            assertEquals(walked.size(), new HashSet<>(ids(walked)).size(), "no document repeats");
            assertEquals(expected, Set.copyOf(ids(walked)));
            if (sort == Sort.NEWEST) {
                // PostgreSQL orders UUIDs by their bytes, which is the order of their lowercase text.
                var newest = Comparator.comparing(ShelfDocument::updatedAt).reversed()
                        .thenComparing(document -> document.documentId().toString(), Comparator.reverseOrder());
                assertEquals(walked.stream().sorted(newest).toList(), walked);
            }
        }

        var first = shelf.page(bob, new ShelfQuery("", Set.of(), Set.of(), Sort.NEWEST, null, 3));
        assertEquals(3, first.items().size());
        assertNotNull(first.nextCursor());
        String cursor = first.nextCursor();
        assertThrows(SearchRequestException.class,
                () -> shelf.page(bob, new ShelfQuery("file", Set.of(), Set.of(), Sort.NEWEST, cursor, 3)));
        assertThrows(SearchRequestException.class,
                () -> shelf.page(bob, new ShelfQuery("", Set.of(), Set.of("DOCUMENT"), Sort.NEWEST, cursor, 3)));
        assertThrows(SearchRequestException.class,
                () -> shelf.page(bob, new ShelfQuery("", Set.of(publicSource), Set.of(), Sort.NEWEST, cursor, 3)));
        assertThrows(SearchRequestException.class,
                () -> shelf.page(bob, new ShelfQuery("", Set.of(), Set.of(), Sort.NAME, cursor, 3)));
        assertThrows(SearchRequestException.class,
                () -> shelf.page(alice, new ShelfQuery("", Set.of(), Set.of(), Sort.NEWEST, cursor, 3)), "another reader's cursor");
        assertThrows(SearchRequestException.class,
                () -> shelf.page(bob, new ShelfQuery("", Set.of(), Set.of(), Sort.NEWEST, "not-a-cursor", 3)));
        assertEquals(3, shelf.page(bob, new ShelfQuery("", Set.of(), Set.of(), Sort.NEWEST, cursor, 3)).items().size());
    }

    @Test
    void filtersByNameCategoryAndSourceWithinTheReadableSet() {
        UUID sheet = document(publicSource, "Q3 budget.xlsx", "application/octet-stream", BASE, null);
        UUID titled = document(publicSource, "scan-001.pdf", "application/pdf", BASE, "Budget review");
        UUID percent = document(publicSource, "100% plan.pptx", "application/octet-stream", BASE, null);
        UUID image = document(publicSource, "logo.png", "image/png", BASE, null);
        UUID privateSheet = document(privateSource, "private budget.csv", "text/csv", BASE, null);

        assertEquals(Set.of(sheet, titled, privateSheet), Set.copyOf(ids(query(alice, "BUDGET", Set.of(), Set.of()))));
        assertEquals(Set.of(sheet, titled), Set.copyOf(ids(query(bob, "budget", Set.of(), Set.of()))));
        assertEquals(List.of(percent), ids(query(alice, "100%", Set.of(), Set.of())), "wildcards are literal");
        assertEquals(Set.of(sheet, privateSheet), Set.copyOf(ids(query(alice, "", Set.of(), Set.of("SPREADSHEET")))));
        assertEquals(Set.of(image, percent), Set.copyOf(ids(query(alice, "", Set.of(), Set.of("IMAGE", "PRESENTATION")))));
        assertEquals(List.of(privateSheet), ids(query(alice, "", Set.of(privateSource), Set.of())));
        assertEquals(List.of(), ids(query(bob, "", Set.of(privateSource), Set.of())), "a Source filter never widens access");

        assertThrows(SearchRequestException.class, () -> query(alice, "", Set.of(), Set.of("VIDEO")));
        assertThrows(SearchRequestException.class, () -> query(alice, "x".repeat(201), Set.of(), Set.of()));
        assertThrows(SearchRequestException.class,
                () -> shelf.page(alice, new ShelfQuery("", Set.of(), Set.of(), Sort.NEWEST, null, 0)));
        assertThrows(SearchRequestException.class,
                () -> shelf.page(alice, new ShelfQuery("", Set.of(), Set.of(), Sort.NEWEST, null, 101)));
    }

    @Test
    void readsRequireSearchAuthorityAndAnActiveTenant() {
        UUID handbook = document(publicSource, "handbook.pdf", "application/pdf", BASE, null);
        UUID budget = document(privateSource, "budget.xlsx", "application/vnd.ms-excel", BASE, null);

        assertEquals(List.of(handbook), ids(shelf.find(bob, List.of(handbook, budget, UUID.randomUUID()))));
        assertEquals(List.of(), shelf.find(withoutSearch, List.of(handbook, budget)));
        assertEquals(List.of(), shelf.find(homeless, List.of(handbook)));
        assertThrows(IllegalArgumentException.class,
                () -> shelf.find(alice, IntStream.range(0, 501).mapToObj(i -> UUID.randomUUID()).toList()));

        assertThrows(IamException.class, () -> all(withoutSearch, Sort.NEWEST));
        assertThrows(IamException.class, () -> shelf.sources(withoutSearch));
        assertThrows(SearchDocumentUnavailableException.class, () -> all(homeless, Sort.NEWEST));
    }

    @Test
    void reportsOnlyTheGenerationTheLiveIndexServes() {
        UUID served = document(publicSource, "served.pdf", "application/pdf", BASE, null);
        UUID elsewhere = document(publicSource, "elsewhere.pdf", "application/pdf", BASE, null);
        UUID pending = document(publicSource, "pending.pdf", "application/pdf", BASE, null);
        jdbc.sql("UPDATE documents SET search_index_identity='previous-index' WHERE id=:id").param("id", elsewhere).update();
        jdbc.sql("UPDATE documents SET searchable_generation=NULL, search_index_identity=NULL WHERE id=:id")
                .param("id", pending).update();

        var found = shelf.find(bob, List.of(served, elsewhere, pending));
        assertEquals(Set.of(served, elsewhere, pending), Set.copyOf(ids(found)));
        for (var document : found) {
            UUID generation = jdbc.sql("SELECT searchable_generation FROM documents WHERE id=:id")
                    .param("id", document.documentId()).query(UUID.class).optional().orElse(null);
            if (document.documentId().equals(served)) assertEquals(generation, document.generation());
            else assertNull(document.generation(), document.filename());
        }
    }

    @Test
    void catalogListsEverySourceTheReaderMayReadFromWhateverItsStateWithWhatTheyReadThere() {
        document(publicSource, "handbook.pdf", "application/pdf", BASE, null);
        document(publicSource, "policy.pdf", "application/pdf", BASE, null);
        UUID withdrawn = document(publicSource, "withdrawn.pdf", "application/pdf", BASE, null);
        jdbc.sql("UPDATE documents_by_connector_credential_pair SET retrieval_eligible=FALSE WHERE document_id=:id")
                .param("id", withdrawn).update();
        document(privateSource, "budget.xlsx", "application/vnd.ms-excel", BASE, null);
        document(driveSource, "roadmap.pdf", "application/pdf", BASE, null);
        grantDrive("roadmap.pdf", "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"alice@example.test\"}");
        document(driveSource, "salaries.pdf", "application/pdf", BASE, null);
        grantDrive("salaries.pdf", "{\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"ceo@example.test\"}");
        UUID fresh = source(4, "Brand new", "FILE", "PUBLIC");
        jdbc.sql("UPDATE connector_credential_pairs SET status='NOT_STARTED' WHERE id=:id").param("id", fresh).update();
        UUID unshared = source(5, "Legal", "FILE", "PRIVATE");
        group("Legal team", unshared, bob);
        jdbc.sql("UPDATE actor_profiles SET display_name='Alice Nguyen' WHERE actor_id=:actor").param("actor", alice.value()).update();
        jdbc.sql("UPDATE connector_credential_pairs SET manager_actor_id=:manager, last_succeeded_at=:at WHERE id=:id")
                .param("manager", alice.value()).param("at", BASE.atOffset(ZoneOffset.UTC)).param("id", privateSource).update();

        var forAlice = shelf.catalog(alice);
        assertEquals(List.of("Brand new", "Drive", "Finance files", "Handbooks"), names(forAlice), "by name");
        var brandNew = forAlice.get(0);
        assertEquals(ShelfSource.Status.NOT_STARTED, brandNew.status());
        assertEquals(0L, brandNew.readableDocuments(), "a new PUBLIC Source is listed before it holds a Document");
        var drive = forAlice.get(1);
        assertEquals(SourceAccess.SYNC, drive.access());
        assertEquals(SourceType.GOOGLE_DRIVE, drive.type());
        assertEquals(1L, drive.readableDocuments(), "only the Drive files that grant the reader count");
        var finance = forAlice.get(2);
        assertEquals(privateSource, finance.id());
        assertEquals(SourceAccess.PRIVATE, finance.access());
        assertEquals(ShelfSource.Status.ACTIVE, finance.status());
        assertEquals(1L, finance.readableDocuments());
        assertEquals(List.of("Audit", "Finance"), finance.groups(), "only the reader's own granted Groups");
        assertEquals("Alice Nguyen", finance.managerName());
        assertEquals(BASE, finance.lastSucceededAt());
        var handbooks = forAlice.get(3);
        assertEquals(SourceAccess.PUBLIC, handbooks.access());
        assertEquals(2L, handbooks.readableDocuments(), "a withdrawn mapping is not counted");
        assertEquals(List.of(), handbooks.groups());
        assertNull(handbooks.managerName());
        assertNull(handbooks.lastSucceededAt());

        // Bob has no granted Group of Finance files and no Drive grant; Legal admits him through his Group.
        var forBob = shelf.catalog(bob);
        assertEquals(List.of("Brand new", "Handbooks", "Legal"), names(forBob));
        assertEquals(List.of("Legal team"), forBob.get(2).groups());
        assertEquals(List.of(), shelf.catalog(inactive), "an inactive membership reads nothing");

        // A paused, failed or indexing Source stays listed with its count; a deleting Source or connector does not.
        jdbc.sql("UPDATE connector_credential_pairs SET status='PAUSED' WHERE id=:id").param("id", publicSource).update();
        jdbc.sql("UPDATE connector_credential_pairs SET sync_error_code='SOURCE_SYNC_FAILED' WHERE id=:id")
                .param("id", privateSource).update();
        jdbc.sql("UPDATE connector_credential_pairs SET status='INDEXING' WHERE id=:id").param("id", fresh).update();
        var changed = shelf.catalog(alice);
        assertEquals(List.of("Brand new", "Drive", "Finance files", "Handbooks"), names(changed));
        assertEquals(ShelfSource.Status.INDEXING, changed.get(0).status());
        assertEquals(ShelfSource.Status.FAILED, changed.get(2).status());
        assertEquals(1L, changed.get(2).readableDocuments());
        assertEquals(ShelfSource.Status.PAUSED, changed.get(3).status());
        assertEquals(2L, changed.get(3).readableDocuments(), "a paused Source keeps its readable Documents");
        assertEquals(List.of(), all(bob, Sort.NEWEST), "while Search leaves the paused Source's Documents out");

        jdbc.sql("UPDATE connector_credential_pairs SET status='DELETING' WHERE id=:id").param("id", publicSource).update();
        jdbc.sql("UPDATE connectors SET status='DELETING' WHERE id=:id").param("id", fresh).update();
        assertEquals(List.of("Drive", "Finance files"), names(shelf.catalog(alice)));
        // The Drive grant is the only thing that admits Alice to the SYNC Source.
        jdbc.sql("DELETE FROM google_drive_acl_snapshots WHERE tenant_id=:tenant").param("tenant", tenant.value()).update();
        assertEquals(List.of("Finance files"), names(shelf.catalog(alice)));

        assertThrows(IamException.class, () -> shelf.catalog(withoutSearch));
        assertThrows(SearchDocumentUnavailableException.class, () -> shelf.catalog(homeless));
    }

    private static List<String> names(List<ShelfSource> sources) {
        return sources.stream().map(ShelfSource::name).toList();
    }

    private List<ShelfDocument> all(ActorId actor, Sort sort) {
        return shelf.page(actor, new ShelfQuery("", Set.of(), Set.of(), sort, null, 100)).items();
    }

    private List<ShelfDocument> query(ActorId actor, String text, Set<UUID> sources, Set<String> categories) {
        return shelf.page(actor, new ShelfQuery(text, sources, categories, Sort.NAME, null, 100)).items();
    }

    private static List<UUID> ids(List<ShelfDocument> documents) {
        return documents.stream().map(ShelfDocument::documentId).toList();
    }

    private ActorId member(String email, String status) {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER',:status)")
                .param("tenant", tenant.value()).param("actor", actor.value()).param("status", status).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer,subject,actor_id) VALUES('https://idp.test',CAST(:actor AS TEXT),:actor)")
                .param("actor", actor.value()).update();
        jdbc.sql("""
                INSERT INTO actor_profiles(actor_id,issuer,subject,email,email_verified,observed_at)
                VALUES(:actor,'https://idp.test',CAST(:actor AS TEXT),:email,TRUE,CURRENT_TIMESTAMP)
                """).param("actor", actor.value()).param("email", email).update();
        return actor;
    }

    /** A Source whose id sorts by {@code order}; FILE Sources share the Tenant's one NO_AUTH credential. */
    private UUID source(int order, String name, String type, String access) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(order));
        String kind = "FILE".equals(type) ? "NO_AUTH" : "GOOGLE_OAUTH";
        UUID credential = jdbc.sql("SELECT id FROM credentials WHERE tenant_id=:tenant AND credential_kind='NO_AUTH'")
                .param("tenant", tenant.value()).query(UUID.class).optional()
                .filter(_ -> "NO_AUTH".equals(kind)).orElse(id);
        if (credential.equals(id)) {
            jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test',:kind,'ACTIVE')")
                    .param("id", id).param("tenant", tenant.value()).param("kind", kind).update();
        }
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,:name,:type,'ACTIVE')")
                .param("id", id).param("tenant", tenant.value()).param("name", name).param("type", type).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                VALUES(:id,:tenant,:id,:credential,:access,'ACTIVE')
                """).param("id", id).param("tenant", tenant.value()).param("credential", credential).param("access", access).update();
        return id;
    }

    private UUID group(String name, UUID source, ActorId... members) {
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES(:tenant,:id,:name)")
                .param("tenant", tenant.value()).param("id", group).param("name", name).update();
        for (var member : members) {
            jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES(:tenant,:group,:actor)")
                    .param("tenant", tenant.value()).param("group", group).param("actor", member.value()).update();
        }
        jdbc.sql("INSERT INTO source_group_grants(tenant_id,connector_credential_pair_id,group_id) VALUES(:tenant,:source,:group)")
                .param("tenant", tenant.value()).param("source", source).param("group", group).update();
        return group;
    }

    private void leave(UUID group, ActorId member) {
        jdbc.sql("DELETE FROM iam_group_memberships WHERE tenant_id=:tenant AND group_id=:group AND actor_id=:actor")
                .param("tenant", tenant.value()).param("group", group).param("actor", member.value()).update();
    }

    /** An eligible Document served by the live index, mapped through a new item of {@code source}. */
    private UUID document(UUID source, String filename, String mediaType, Instant updatedAt, @Nullable String title) {
        UUID document = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO documents(id,tenant_id,status,title,media_type,content_generation,searchable_generation,search_index_identity)
                VALUES(:id,:tenant,'ELIGIBLE',:title,:media,:generation,:generation,:identity)
                """).param("id", document).param("tenant", tenant.value()).param("title", title).param("media", mediaType)
                .param("generation", generation).param("identity", LIVE_INDEX).update();
        map(source, document, filename, updatedAt);
        return document;
    }

    private void map(UUID source, UUID document, String filename, Instant updatedAt) {
        UUID item = UUID.randomUUID();
        String sha = "%064x".formatted(++sequence);
        String providerFile = source.equals(driveSource) ? filename : null;
        jdbc.sql("""
                INSERT INTO stored_objects(id,tenant_id,object_key,filename,declared_media_type,size_bytes,content_sha256,state,expires_at)
                VALUES(:id,:tenant,'raw/' || CAST(:id AS TEXT),:filename,'application/octet-stream',64,:sha,'ACTIVE',CURRENT_TIMESTAMP)
                """).param("id", item).param("tenant", tenant.value()).param("filename", filename).param("sha", sha).update();
        jdbc.sql("""
                INSERT INTO connector_items(id,tenant_id,connector_id,content_sha256,status,provider_file_id,updated_at)
                VALUES(:id,:tenant,:connector,:sha,'INDEXED',:file,:updated)
                """).param("id", item).param("tenant", tenant.value()).param("connector", source).param("sha", sha)
                .param("file", providerFile).param("updated", updatedAt.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO connector_item_versions(id,tenant_id,connector_id,connector_item_id,revision_number,filename,content_sha256,
                    size_bytes,stored_object_id,source_url)
                VALUES(:id,:tenant,:connector,:id,1,:filename,:sha,64,:id,:url)
                """).param("id", item).param("tenant", tenant.value()).param("connector", source).param("filename", filename)
                .param("sha", sha).param("url", "https://files.test/" + filename).update();
        jdbc.sql("UPDATE connector_items SET current_version_id=:id WHERE tenant_id=:tenant AND id=:id")
                .param("id", item).param("tenant", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO documents_by_connector_credential_pair(tenant_id,connector_id,connector_credential_pair_id,document_id,
                    connector_item_id,retrieval_eligible)
                VALUES(:tenant,:source,:source,:document,:item,TRUE)
                """).param("tenant", tenant.value()).param("source", source).param("document", document).param("item", item).update();
    }

    /** A successful permission snapshot of the Drive file, as the ACL sync records it. */
    private void grantDrive(String fileId, String permission) {
        UUID operation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO google_drive_acl_snapshots(tenant_id,source_id,file_id,observation_revision,permissions_json,status,
                    last_attempt_at,attempt_operation_id,attempt_credential_id,attempt_credential_revision,attempt_scope_revision,
                    attempt_generation,last_success_at,success_operation_id,success_credential_id,success_credential_revision,
                    success_scope_revision,success_generation)
                VALUES(:tenant,:source,:file,1,CAST(:permissions AS jsonb),'SUCCEEDED',CURRENT_TIMESTAMP,:operation,:source,1,1,0,
                    CURRENT_TIMESTAMP,:operation,:source,1,1,0)
                """).param("tenant", tenant.value()).param("source", driveSource).param("file", fileId)
                .param("permissions", "[" + permission + "]").param("operation", operation).update();
    }
}
