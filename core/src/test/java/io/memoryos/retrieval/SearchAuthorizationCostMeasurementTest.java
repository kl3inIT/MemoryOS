package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.source.DefaultSourceDocumentAccessResolver;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.document.persistence.JdbcDocumentChunkRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in measurement of the database authorization work on the Search and Chat retrieval paths, at a realistic
 * Tenant size on production migrations. It measures only PostgreSQL authorization; OpenSearch time comes from
 * the recorded search stage receipts.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@EnabledIfEnvironmentVariable(named = "MEMORYOS_SEARCH_AUTHZ_MEASURE", matches = "true")
class SearchAuthorizationCostMeasurementTest {
    private static final int SOURCES = 200;
    private static final int GROUPS = 50;
    private static final int DOCUMENTS = 50_000;
    private static final int WARMUP = 50;
    private static final int ITERATIONS = 300;
    private static final String IDENTITY = "space";

    @Test
    void measuresAuthorizationStagesAgainstRealPostgres() throws Exception {
        try (var database = TestDatabase.freshPostgres()) {
            var jdbc = JdbcClient.create(database);
            var jpa = TestDatabase.jpa(database);
            var tenant = new TenantId(UUID.randomUUID());
            var actor = new ActorId(UUID.randomUUID());
            seed(jdbc, tenant, actor);

            var locks = new IamLockRepository(jdbc);
            var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(new JpaTenantRepository(jpa.entityManager()), locks),
                    TenantAccessResolver.class, jpa.transactionManager());
            var authorization = TestDatabase.transactionalProxy(new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks),
                    IamAuthorization.class, jpa.transactionManager());
            var sourceDocuments = new JdbcSourceDocumentRepository(jdbc);
            var sources = new SourceSearchService(tenants, sourceDocuments);
            var access = new DefaultSourceDocumentAccessResolver(tenants, sourceDocuments);
            var chunks = new JdbcDocumentChunkRepository(jdbc, new ObjectMapper());

            var scope = sources.scope(actor);
            var candidates100 = candidates(100);
            var candidates400 = candidates(400);
            assertFalse(scope.sources().isEmpty());
            var readable = sources.readableMetadata(scope, candidates400).size();
            assertTrue(readable > 0 && readable < candidates400.size(), "candidate mix must include denied documents");
            UUID expansionDocument = sources.readableMetadata(scope, candidates100).keySet().iterator().next();
            UUID expansionGeneration = chunks.currentGenerations(tenant, List.of(expansionDocument), IDENTITY).get(expansionDocument);

            var rows = new ArrayList<String>();
            // Calibration: connection checkout and container round trip without authorization work.
            rows.add(measure("Calibration SELECT 1 round trip", () -> jdbc.sql("SELECT 1").query(Integer.class).single()));
            rows.add(measure("Capability check (SEARCH_READ / CHAT_READ)", () -> authorization.require(actor, IamCapability.SEARCH_READ, false)));
            rows.add(measure("Active Tenant lookup", () -> tenants.findActiveTenant(actor)));
            rows.add(measure("PREFETCH readable Source scope", () -> sources.scope(actor)));
            rows.add(measure("AUTHORIZATION Chat recheck, 100 candidate documents", () -> {
                chunks.currentGenerations(tenant, candidates100, IDENTITY);
                return sources.readableMetadata(scope, candidates100);
            }));
            rows.add(measure("AUTHORIZATION Chat recheck, 400 candidate documents", () -> {
                chunks.currentGenerations(tenant, candidates400, IDENTITY);
                return sources.readableMetadata(scope, candidates400);
            }));
            rows.add(measure("Direct Search recheck, 100 candidate documents", () -> {
                chunks.currentGenerations(tenant, candidates100, IDENTITY);
                return access.readableDocuments(actor, candidates100);
            }));
            rows.add(measure("EXPANSION recheck, one passage window", () -> {
                tenants.findActiveTenant(actor);
                chunks.isCurrent(tenant, new DocumentId(expansionDocument), expansionGeneration, IDENTITY);
                return sources.readableMetadata(scope, List.of(expansionDocument));
            }));

            String report = """
                    # Search authorization cost (real PostgreSQL)

                    Corpus: %d documents, %d Sources (half PUBLIC files, half PRIVATE Drive with Group grants), %d Groups, actor in 5 Groups.
                    Warm-up %d, measured %d sequential iterations per row, one connection pool of 4.

                    | Operation | p50 ms | p95 ms | mean ms |
                    |---|---:|---:|---:|
                    %s
                    """.formatted(DOCUMENTS, SOURCES, GROUPS, WARMUP, ITERATIONS, String.join("\n", rows));
            var output = Path.of("build", "reports", "search-authorization", "measurements.md");
            Files.createDirectories(output.getParent());
            Files.writeString(output, report);
            System.out.println(report);
        }
    }

    private static List<UUID> candidates(int size) {
        // Spread across Sources so the batch mixes public, granted and denied documents.
        return IntStream.range(0, size).map(i -> 1 + (i * 97) % DOCUMENTS).mapToObj(SearchAuthorizationCostMeasurementTest::document).toList();
    }

    /** Same bytes as PostgreSQL {@code md5('doc'||n)::uuid}, without UUID version bits. */
    private static UUID document(int index) {
        try {
            var hash = ByteBuffer.wrap(MessageDigest.getInstance("MD5")
                    .digest(("doc" + index).getBytes(StandardCharsets.UTF_8)));
            return new UUID(hash.getLong(), hash.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String measure(String name, Supplier<?> operation) {
        for (int i = 0; i < WARMUP; i++) operation.get();
        long[] samples = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long started = System.nanoTime();
            operation.get();
            samples[i] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        double mean = Arrays.stream(samples).average().orElse(0);
        return String.format(Locale.ROOT, "| %s | %.2f | %.2f | %.2f |", name,
                samples[ITERATIONS / 2] / 1e6, samples[(int) (ITERATIONS * 0.95)] / 1e6, mean / 1e6);
    }

    private static void seed(JdbcClient jdbc, TenantId tenant, ActorId actor) {
        UUID basic = UUID.randomUUID();
        for (String sql : List.of(
                "INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:tenant,'measure','Measure','ACTIVE','TEST')",
                "INSERT INTO actors(id) VALUES(:actor)",
                "INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')",
                "INSERT INTO iam_groups(tenant_id,id,name,system_key) VALUES(:tenant,:basic,'Basic','BASIC')",
                "INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES(:tenant,:basic,'SYSTEM_BASIC')",
                "INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES(:tenant,:basic,:actor)")) {
            jdbc.sql(sql).param("tenant", tenant.value()).param("actor", actor.value()).param("basic", basic).update();
        }
        for (String sql : List.of(
                "INSERT INTO iam_groups(tenant_id,id,name) SELECT :tenant, md5('grp'||g)::uuid, 'G'||g FROM generate_series(1,:groups) g",
                "INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) SELECT :tenant, md5('grp'||g)::uuid, :actor FROM generate_series(1,5) g",
                """
                INSERT INTO credentials(id,tenant_id,name,credential_kind,status)
                SELECT md5('file-credential')::uuid, :tenant, 'Files', 'NO_AUTH', 'ACTIVE'
                UNION ALL
                SELECT md5('src'||n)::uuid, :tenant, 'C'||n, 'GOOGLE_OAUTH', 'ACTIVE' FROM generate_series(:sources/2+1,:sources) n""",
                """
                INSERT INTO connectors(id,tenant_id,name,connector_type,status)
                SELECT md5('src'||n)::uuid, :tenant, 'S'||n, CASE WHEN n<=:sources/2 THEN 'FILE' ELSE 'GOOGLE_DRIVE' END, 'ACTIVE'
                FROM generate_series(1,:sources) n""",
                """
                INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                SELECT md5('src'||n)::uuid, :tenant, md5('src'||n)::uuid,
                    CASE WHEN n<=:sources/2 THEN md5('file-credential')::uuid ELSE md5('src'||n)::uuid END,
                    CASE WHEN n<=:sources/2 THEN 'PUBLIC' ELSE 'PRIVATE' END, 'ACTIVE'
                FROM generate_series(1,:sources) n""",
                """
                INSERT INTO source_group_grants(tenant_id,connector_credential_pair_id,group_id)
                SELECT DISTINCT :tenant, md5('src'||n)::uuid, md5('grp'||g)::uuid
                FROM generate_series(:sources/2+1,:sources) n, LATERAL (VALUES (n % :groups + 1), ((n + 7) % :groups + 1)) AS grp(g)""",
                """
                INSERT INTO connector_items(id,tenant_id,connector_id,content_sha256,status)
                SELECT md5('item'||d)::uuid, :tenant, md5('src'||(d % :sources + 1))::uuid, encode(sha256(convert_to('item'||d,'UTF8')),'hex'), 'INDEXED'
                FROM generate_series(1,:documents) d""",
                """
                INSERT INTO documents(id,tenant_id,status,title,content_generation,searchable_generation,search_index_identity)
                SELECT md5('doc'||d)::uuid, :tenant, 'ELIGIBLE', 'Document '||d, md5('gen'||d)::uuid, md5('gen'||d)::uuid, 'space'
                FROM generate_series(1,:documents) d""",
                """
                INSERT INTO document_search_projection(tenant_id,document_id,index_identity,generation)
                SELECT :tenant, md5('doc'||d)::uuid, 'space', md5('gen'||d)::uuid
                FROM generate_series(1,:documents) d""",
                """
                INSERT INTO documents_by_connector_credential_pair(tenant_id,connector_id,connector_credential_pair_id,document_id,connector_item_id,retrieval_eligible)
                SELECT :tenant, md5('src'||(d % :sources + 1))::uuid, md5('src'||(d % :sources + 1))::uuid, md5('doc'||d)::uuid, md5('item'||d)::uuid, TRUE
                FROM generate_series(1,:documents) d""",
                "ANALYZE")) {
            var statement = jdbc.sql(sql);
            if (sql.contains(":tenant")) statement = statement.param("tenant", tenant.value());
            if (sql.contains(":actor")) statement = statement.param("actor", actor.value());
            if (sql.contains(":groups")) statement = statement.param("groups", GROUPS);
            if (sql.contains(":sources")) statement = statement.param("sources", SOURCES);
            if (sql.contains(":documents")) statement = statement.param("documents", DOCUMENTS);
            statement.update();
        }
    }
}
