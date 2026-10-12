package io.memoryos.connector.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.DocumentAccess;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkGateway.Collaborator;
import io.memoryos.connector.LarkGateway.Permissions;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.SourceAclChanged;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.lark.persistence.JdbcLarkAclRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkDirectoryRepository;
import io.memoryos.connector.lark.persistence.LarkCredentialConfiguration;
import io.memoryos.connector.source.DefaultSourceDocumentAccessResolver;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A SYNC Lark Source admits a reader by what Lark shares each object with: the snapshot a run records, and the
 * credential's directory, which says who the chats, departments and spaces named there contain. The index-time
 * access, the reader's tokens and the recheck are read here from the one rule they share.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresLarkPermissionTest {
    private HikariDataSource database;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private TenantId tenant;
    private UUID source;
    private CredentialId credential;
    private Work work;
    private JdbcLarkAclRepository acls;
    private JdbcSourceDocumentRepository documents;
    private DefaultSourceDocumentAccessResolver access;
    private LarkDirectorySynchronizer directory;
    private LarkGateway.Session session;
    private final List<SourceAclChanged> published = new ArrayList<>();
    private ActorId lan, minh, owner, stranger, unverified;
    private List<ActorId> readers;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        var manager = new DataSourceTransactionManager(database);
        tx = new TransactionTemplate(manager);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'lark','Lark','ACTIVE','TEST')")
                .param("id", tenant.value()).update();
        owner = member("owner@example.test", true);
        lan = member("Lan@Example.test", true);
        minh = member("minh@example.test", true);
        stranger = member("stranger@example.test", true);
        unverified = member("lan@example.test", false);
        readers = List.of(owner, lan, minh, stranger, unverified);
        source = UUID.randomUUID();
        credential = new CredentialId(source);
        jdbc.sql("""
                INSERT INTO credentials(id,tenant_id,name,credential_kind,status,owner_actor_id)
                VALUES(:id,:tenant,'Lark app','LARK_APP','ACTIVE',:owner)
                """).param("id", source).param("tenant", tenant.value()).param("owner", owner.value()).update();
        jdbc.sql("""
                INSERT INTO lark_credentials (tenant_id, credential_id, region, app_id, secret_ciphertext,
                    secret_nonce, secret_key_version)
                VALUES (:tenant, :credential, 'LARK', 'cli_a1b2c3d4e5f6a7b8', :ciphertext, :nonce, 'v1')
                """).param("tenant", tenant.value()).param("credential", source)
                .param("ciphertext", new byte[32]).param("nonce", new byte[12]).update();
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'Lark','LARK','ACTIVE')")
                .param("id", source).param("tenant", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                VALUES(:id,:tenant,:id,:id,'SYNC','ACTIVE')
                """).param("id", source).param("tenant", tenant.value()).update();
        jdbc.sql("WITH state AS (INSERT INTO source_sync_state (tenant_id, source_id) VALUES (:tenant, :source)) "
                        + "INSERT INTO lark_sources(tenant_id,source_id) VALUES(:tenant,:source)")
                .param("tenant", tenant.value()).param("source", source).update();
        work = new Work(tenant, new SourceId(source), new SourceOperationId(UUID.randomUUID()), UUID.randomUUID(),
                1L, 1L, 0L, null);

        acls = new JdbcLarkAclRepository(jdbc, event -> published.add((SourceAclChanged) event));
        documents = new JdbcSourceDocumentRepository(jdbc);
        var tenants = mock(TenantAccessResolver.class);
        when(tenants.findActiveTenant(any())).thenReturn(Optional.of(tenant));
        access = new DefaultSourceDocumentAccessResolver(tenants, documents);
        var credentials = new JdbcLarkCredentialRepository(jdbc, new JdbcSourceRepository(jdbc, _ -> { }),
                new LarkCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test"));
        directory = new LarkDirectorySynchronizer(new JdbcLarkDirectoryRepository(jdbc), credentials, manager,
                Duration.ofHours(1));
        session = mock(LarkGateway.Session.class);
        // Lark's directory as the test tenant has it: Lan in a department below Sales, Minh a member of the space
        // through a chat, the owner named directly.
        when(session.departmentChildren(eq("od-sales"), isNull()))
                .thenReturn(new LarkGateway.DepartmentPage(List.of("od-north"), null));
        when(session.departmentMembers(eq("od-sales"), isNull())).thenReturn(new LarkGateway.MemberPage(List.of(), null));
        when(session.departmentMembers(eq("od-north"), isNull()))
                .thenReturn(new LarkGateway.MemberPage(List.of("ou_lan"), null));
        when(session.spaceMembers(eq("7000"), isNull())).thenReturn(new LarkGateway.CollaboratorPage(List.of(
                new Collaborator("openid", "ou_owner", "admin"), new Collaborator("openchat", "oc_team", "member")),
                null));
        when(session.chatMembers(eq("oc_team"), isNull()))
                .thenReturn(new LarkGateway.MemberPage(List.of("ou_minh"), null));
        when(session.chatMembers(eq("oc_private"), isNull()))
                .thenThrow(new LarkProviderException(LarkProviderException.Failure.AUTHORIZATION));
        when(session.users(anyList())).thenAnswer(call -> call.<List<String>>getArgument(0).stream().map(id ->
                switch (id) {
                    case "ou_lan" -> new LarkGateway.User(id, "LAN@example.test", true);
                    case "ou_minh" -> new LarkGateway.User(id, "minh@example.test", true);
                    case "ou_owner" -> new LarkGateway.User(id, "owner@example.test", true);
                    // Someone who left keeps their email in Lark and reads nothing here.
                    default -> new LarkGateway.User(id, "stranger@example.test", false);
                }).toList());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void aReaderIsAdmittedByWhatLarkSharesTheObjectWith() {
        UUID named = document("named", share(null, "openid:ou_lan", "appid:cli_a1b2c3d4e5f6a7b8"));
        UUID department = document("department", share("tenant_readable", "opendepartmentid:od-sales"));
        UUID space = document("space", share(null, "wikispaceid:7000", "wikispaceid:7000"));
        UUID chatWithoutBot = document("chat", share(null, "openchat:oc_private"));
        UUID organizationLink = document("link", share("tenant_editable"));
        UUID anyone = document("anyone", share("anyone_readable"));
        UUID left = document("left", share(null, "openid:ou_left"));
        UUID unread = document("unread", null);
        synchronizeDirectory();

        assertEquals(Set.of(lan), readersOf(named));
        assertEquals(Set.of(lan), readersOf(department), "a department counts the people of the departments below it");
        assertEquals(Set.of(owner, minh), readersOf(space), "a space counts its people and those of its member chats");
        assertEquals(Set.of(), readersOf(chatWithoutBot), "a chat the bot is not in admits nobody");
        assertEquals(Set.of(), readersOf(organizationLink), "a link open to the organization grants nothing");
        assertEquals(Set.copyOf(readers), readersOf(anyone));
        assertEquals(Set.of(), readersOf(left), "a person who left Lark matches no reader");
        assertEquals(Set.of(), readersOf(unread), "before the first complete read nobody reads");

        // What is indexed is the same decision: an identity is qualified by its credential.
        String prefix = credential.value() + ":";
        assertEquals(new DocumentAccess(false, Set.of("lark_user:" + prefix + "ou_lan")),
                documents.documentAccess(tenant, named));
        assertEquals(new DocumentAccess(false, Set.of("lark_department:" + prefix + "od-sales")),
                documents.documentAccess(tenant, department));
        assertEquals(new DocumentAccess(false, Set.of("lark_space:" + prefix + "7000")),
                documents.documentAccess(tenant, space));
        assertEquals(new DocumentAccess(true, Set.of()), documents.documentAccess(tenant, anyone));
        assertEquals(Set.of("lark_user:" + prefix + "ou_lan", "lark_department:" + prefix + "od-sales"),
                larkTokens(lan));
        assertEquals(Set.of("lark_user:" + prefix + "ou_minh", "lark_space:" + prefix + "7000"),
                larkTokens(minh));
        assertEquals(Set.of(), documents.actorAccessTokens(tenant, unverified), "an unverified email grants nothing");
        for (UUID document : List.of(named, department, space, chatWithoutBot, organizationLink, anyone, left, unread)) {
            var indexed = documents.documentAccess(tenant, document);
            for (ActorId reader : readers)
                assertEquals(indexed.everyone() || indexed.tokens().stream()
                                .anyMatch(documents.actorAccessTokens(tenant, reader)::contains),
                        access.canRead(reader, new DocumentId(document)), "index and recheck agree");
        }
    }

    @Test
    void aChangedAnswerIsPublishedOnceAndAFailedReadKeepsTheLastOne() {
        UUID document = document("doc", share(null, "openid:ou_lan"));
        synchronizeDirectory();
        assertEquals(1, published.size());
        assertEquals(List.of(new DocumentId(document)), published.getFirst().documentIds());

        // The same answer read again changes nothing.
        record(share(null, "openid:ou_lan"), "doc");
        assertEquals(1, published.size());
        tx.executeWithoutResult(_ -> acls.recordFailure(work, "doc", "SOURCE_LARK_AUTHORIZATION"));
        assertEquals(1, published.size());
        assertEquals(Set.of(lan), readersOf(document), "a failed read removes nobody");

        record(share("anyone_editable", "openid:ou_lan"), "doc");
        assertEquals(2, published.size());
        assertEquals(Set.copyOf(readers), readersOf(document));
        // Lan is removed from the object in Lark: the next read takes her access away at once.
        record(share("closed"), "doc");
        assertEquals(3, published.size());
        assertEquals(Set.of(), readersOf(document));
    }

    @Test
    void theDirectoryIsReadInGenerationsAndANewNameIsAskedAboutSoon() {
        UUID document = document("doc", share(null, "opendepartmentid:od-sales"));
        assertEquals(Set.of(), readersOf(document), "no generation is active before the first run completes");
        synchronizeDirectory();
        assertEquals(Set.of(lan), readersOf(document));
        assertFalse(directory.advance(tenant, credential, 1L, session), "nothing is due within the interval");

        // A run that Lark stops answering leaves the active generation in force.
        jdbc.sql("UPDATE lark_directory_runs SET started_at = started_at - INTERVAL '2 hours'").update();
        when(session.departmentChildren(eq("od-sales"), isNull()))
                .thenThrow(new LarkProviderException(LarkProviderException.Failure.UNAVAILABLE));
        assertFalse(directory.advance(tenant, credential, 1L, session));
        assertEquals(Set.of(lan), readersOf(document));
        assertEquals("SOURCE_LARK_UNAVAILABLE", jdbc.sql("SELECT error_code FROM lark_directory_runs WHERE status = 'FAILED'")
                .query(String.class).single());

        // A chat named for the first time is asked about a minute after the last run, not an interval later.
        record(share(null, "openchat:oc_team"), "doc");
        assertFalse(directory.advance(tenant, credential, 1L, session), "the failed run started a moment ago");
        jdbc.sql("UPDATE lark_directory_runs SET started_at = started_at - INTERVAL '2 minutes'").update();
        synchronizeDirectory();
        assertEquals(Set.of(minh), readersOf(document));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM lark_directory_runs").query(Integer.class).single(),
                "older generations are dropped");

        // A credential whose revision moved on applies nothing.
        jdbc.sql("UPDATE lark_directory_runs SET started_at = started_at - INTERVAL '2 hours'").update();
        assertFalse(directory.advance(tenant, credential, 2L, session));
    }

    private void synchronizeDirectory() {
        int steps = 0;
        while (directory.advance(tenant, credential, 1L, session))
            assertTrue(++steps < 50, "the directory run ends");
    }

    /** The reader's Lark identities; the same email also yields the Google ones, which no Lark object names. */
    private Set<String> larkTokens(ActorId reader) {
        return documents.actorAccessTokens(tenant, reader).stream().filter(token -> token.startsWith("lark_"))
                .collect(Collectors.toSet());
    }

    private Set<ActorId> readersOf(UUID document) {
        return readers.stream().filter(reader -> access.canRead(reader, new DocumentId(document)))
                .collect(Collectors.toSet());
    }

    /** {@code members} are Lark's {@code member_type:member_id} pairs. */
    private static Permissions share(@Nullable String linkShare, String... members) {
        return new Permissions(List.of(members).stream().map(member -> member.split(":", 2))
                .map(parts -> new Collaborator(parts[0], parts[1], "view")).toList(), linkShare);
    }

    private void record(Permissions permissions, String fileId) {
        tx.executeWithoutResult(_ -> acls.recordSuccess(work, fileId, permissions));
    }

    /** A document mapped through an item for {@code fileId}; {@code permissions} null means no complete read. */
    private UUID document(String fileId, @Nullable Permissions permissions) {
        UUID document = UUID.randomUUID();
        jdbc.sql("INSERT INTO documents(id,tenant_id,status,title,content_generation) VALUES(:id,:tenant,'ELIGIBLE',:title,:generation)")
                .param("id", document).param("tenant", tenant.value()).param("title", fileId)
                .param("generation", UUID.randomUUID()).update();
        UUID item = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO connector_items(id,tenant_id,connector_id,content_sha256,status,provider_file_id)
                VALUES(:id,:tenant,:connector,REPEAT('a',64),'INDEXED',:file)
                """).param("id", item).param("tenant", tenant.value()).param("connector", source).param("file", fileId).update();
        jdbc.sql("""
                INSERT INTO documents_by_connector_credential_pair(tenant_id,connector_id,connector_credential_pair_id,document_id,
                    connector_item_id,retrieval_eligible)
                VALUES(:tenant,:source,:source,:document,:item,TRUE)
                """).param("tenant", tenant.value()).param("source", source).param("document", document).param("item", item).update();
        if (permissions != null) record(permissions, fileId);
        return document;
    }

    private ActorId member(@Nullable String email, boolean verified) {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer,subject,actor_id) VALUES('https://idp.test',CAST(:actor AS TEXT),:actor)")
                .param("actor", actor.value()).update();
        jdbc.sql("""
                INSERT INTO actor_profiles(actor_id,issuer,subject,email,email_verified,observed_at)
                VALUES(:actor,'https://idp.test',CAST(:actor AS TEXT),:email,:verified,CURRENT_TIMESTAMP)
                """).param("actor", actor.value()).param("email", email).param("verified", verified).update();
        return actor;
    }
}
