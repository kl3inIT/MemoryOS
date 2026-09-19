package io.memoryos.usage;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.usage.persistence.AiCostQueries;
import io.memoryos.usage.persistence.AiCostQueries.Dimension;
import io.memoryos.usage.persistence.AiCostQueries.Scope;
import io.memoryos.usage.persistence.AiCostQueries.Split;
import io.memoryos.usage.persistence.AiUsageRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class AiCostQueriesTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private AiUsageRecorder recorder;
    private AiCostQueries queries;
    private UUID tenant, ha, quan, legal;
    private final LocalDate day = LocalDate.parse("2026-09-18");

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        recorder = new AiUsageRecorder(new AiUsageRepository(jdbc));
        queries = new AiCostQueries(jdbc);
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        tenant = tenant();
        ha = actor("Trần Thu Hà", "ha@tasco.vn");
        quan = actor(null, "quan@tasco.vn");
        legal = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:tenant, :id, 'Pháp chế')").param("tenant", tenant).param("id", legal).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id, group_id, actor_id) VALUES (:tenant, :group, :actor)")
                .param("tenant", tenant).param("group", legal).param("actor", ha).update();
        var at = day.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC);
        recorder.record(AiUsage.tokens(tenant, ha, AiUsageFlow.CHAT, "OpenAI", "gpt-5.1", null, null, "EXTERNAL", 1000, 200, 100, 0.03, at));
        recorder.record(AiUsage.tokens(tenant, ha, AiUsageFlow.CHAT, "vLLM nội bộ", "qwen3-8b", null, null, "INTERNAL", 4000, 500, 0, 0.0, at));
        recorder.record(AiUsage.tokens(tenant, quan, AiUsageFlow.CHAT_NAMING, "OpenAI", "gpt-5-mini", null, null, "EXTERNAL", 300, 10, 0, null, at));
        recorder.record(AiUsage.tokens(tenant, null, AiUsageFlow.EMBEDDING_INDEXING, "api.openai.com", "text-embedding-3-large",
                null, null, null, 9000, 0, 0, 0.01, at.plusSeconds(86_400)));
        // Another Tenant's spend never appears.
        UUID other = tenant();
        recorder.record(AiUsage.tokens(other, null, AiUsageFlow.CHAT, "OpenAI", "gpt-5.1", null, null, "EXTERNAL", 1, 1, 0, 99.0, at));
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void totalsSeparateKnownExternalAndUnknownCostAndCountPeople() {
        var totals = queries.totals(scope(null, false, null, null));
        assertEquals(0, new BigDecimal("0.04").compareTo(totals.cost()));
        assertEquals(0, new BigDecimal("0.03").compareTo(totals.externalCost()));
        assertEquals(4, totals.calls());
        assertEquals(1, totals.unknownCostCalls());
        assertEquals(14300, totals.inputTokens());
        assertEquals(100, totals.cacheReadTokens());
        assertEquals(2, totals.activePeople());
        assertEquals(0, new BigDecimal("0.03").compareTo(queries.totals(scope(ha, false, "gpt-5.1", null)).cost()));
        assertEquals(1, queries.totals(scope(null, true, null, null)).calls());
        assertEquals(0, queries.totals(new Scope(tenant, day.minusDays(10), day.minusDays(1), null, null, null, false)).calls());
    }

    @Test void dailySeriesSplitByBoundaryOrModel() {
        var byBoundary = queries.daily(scope(null, false, null, null), Split.BOUNDARY);
        assertEquals(2, byBoundary.stream().filter(point -> point.day().equals(day)).count());
        assertTrue(byBoundary.stream().anyMatch(point -> point.day().equals(day.plusDays(1)) && point.key().equals("NONE")));
        var byModel = queries.daily(scope(null, false, null, AiUsageFlow.CHAT), Split.MODEL);
        assertEquals(2, byModel.size());
    }

    @Test void breakdownsRankByCostAndLabelPeopleGroupsModelsFlowsAndProviders() {
        var people = queries.breakdown(scope(null, false, null, null), Dimension.ACTOR, 10);
        assertEquals("Trần Thu Hà", people.getFirst().label());
        assertEquals("ha@tasco.vn", people.getFirst().detail());
        assertTrue(people.stream().anyMatch(row -> row.label().equals("quan@tasco.vn") && row.unknownCostCalls() == 1));
        assertTrue(people.stream().anyMatch(row -> row.key().equals("SYSTEM")));
        var groups = queries.breakdown(scope(null, false, null, null), Dimension.GROUP, 10);
        assertEquals(1, groups.size());
        assertEquals("Pháp chế", groups.getFirst().label());
        assertEquals(2, groups.getFirst().calls());
        var models = queries.breakdown(scope(null, false, null, null), Dimension.MODEL, 2);
        assertEquals(2, models.size());
        assertEquals("gpt-5.1", models.getFirst().label());
        assertEquals("OpenAI", models.getFirst().detail());
        var flows = queries.breakdown(scope(null, false, null, null), Dimension.FLOW, 10);
        assertEquals(3, flows.size());
        var providers = queries.breakdown(scope(null, false, null, null), Dimension.PROVIDER, 10);
        assertTrue(providers.stream().anyMatch(row -> row.label().equals("vLLM nội bộ") && "INTERNAL".equals(row.detail())));
        // A provider relabelled mid-period keeps its External spend visible in its own row.
        recorder.record(AiUsage.tokens(tenant, ha, AiUsageFlow.CHAT, "OpenAI", "gpt-5.1", null, null, "INTERNAL", 10, 10, 0, 0.5,
                day.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC)));
        var relabelled = queries.breakdown(scope(null, false, null, null), Dimension.PROVIDER, 10).stream()
                .filter(row -> row.label().equals("OpenAI")).toList();
        assertEquals(2, relabelled.size());
        assertTrue(relabelled.stream().anyMatch(row -> "EXTERNAL".equals(row.detail())
                && new BigDecimal("0.03").compareTo(row.cost()) == 0));
    }

    private Scope scope(UUID actor, boolean system, String model, AiUsageFlow flow) {
        return new Scope(tenant, day, day.plusDays(1), actor, model, flow, system);
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Usage', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return id;
    }

    private UUID actor(String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", id).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer, subject, actor_id) VALUES ('https://id.test', :subject, :id)")
                .param("subject", id.toString()).param("id", id).update();
        jdbc.sql("""
                INSERT INTO actor_profiles(actor_id, issuer, subject, display_name, email, email_verified, observed_at)
                VALUES (:id, 'https://id.test', :subject, :name, :email, true, :at)""")
                .param("id", id).param("subject", id.toString()).param("name", name, java.sql.Types.VARCHAR).param("email", email)
                .param("at", java.sql.Timestamp.from(Instant.now())).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant).param("actor", id).update();
        return id;
    }
}
