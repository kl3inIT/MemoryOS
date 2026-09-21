package io.memoryos.usage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAccess;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.usage.persistence.AiUsageLimitRepository;
import io.memoryos.usage.persistence.AiUsageRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What a Tenant, a Group and a person may spend, weighed against the settled ledger (MEM-123). */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class AiUsageLimitServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private AiUsageRecorder recorder;
    private AiUsageLimitService limits;
    private AiUsageLimitRepository repository;
    private UUID tenant;
    private UUID actor;
    private UUID other;
    private UUID group;

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        recorder = new AiUsageRecorder(new AiUsageRepository(jdbc));
        repository = new AiUsageLimitRepository(jdbc);
        tenant = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Limits', 'ACTIVE', 'test')")
                .param("id", tenant).param("slug", tenant.toString()).update();
        actor = person();
        other = person();
        group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:tenant, :id, 'Pháp chế')")
                .param("tenant", tenant).param("id", group).update();
        member(actor);
        member(other);
        limits = new AiUsageLimitService(authorization(), repository, TestDatabase.noAudit(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void aPersonIsRefusedOnceTheirOwnBudgetIsSpentAndNotBefore() {
        limit(AiUsageLimitScope.PERSON, null, 1_000L, null, 7);
        spend(actor, 600, 300, 0.5, NOW);
        assertTrue(limits.check(new ActorId(actor)).isEmpty());
        spend(actor, 100, 0, 0.1, NOW);
        var breach = limits.check(new ActorId(actor)).orElseThrow();
        assertEquals(AiUsageLimitScope.PERSON, breach.scope());
        assertEquals(AiUsageLimitService.Breach.Budget.TOKENS, breach.budget());
        // Another person has their own budget of the same size.
        assertTrue(limits.check(new ActorId(other)).isEmpty());
    }

    @Test void systemWorkIsNeitherCountedNorRefused() {
        limit(AiUsageLimitScope.TENANT, null, 1_000L, null, 7);
        // Indexing a Source carries no person; it must not exhaust the Tenant's budget.
        recorder.record(AiUsage.tokens(tenant, null, AiUsageFlow.EMBEDDING_INDEXING, "OpenAI", "text-embedding-3", null,
                null, "INTERNAL", 900_000, 0, 0, 12.0, NOW));
        assertTrue(limits.check(new ActorId(actor)).isEmpty());
    }

    @Test void aGroupBudgetCountsEveryMemberAndBindsEveryMember() {
        limit(AiUsageLimitScope.GROUP, group, 1_000L, null, 7);
        spend(actor, 600, 0, 0.1, NOW);
        spend(other, 500, 0, 0.1, NOW);
        // Neither person spent the budget alone; together they did, so both are refused.
        assertEquals(AiUsageLimitScope.GROUP, limits.check(new ActorId(actor)).orElseThrow().scope());
        assertEquals("Pháp chế", limits.check(new ActorId(other)).orElseThrow().groupName());
    }

    @Test void aGroupLimitDoesNotBindSomeoneOutsideThatGroup() {
        UUID outsider = person();
        limit(AiUsageLimitScope.GROUP, group, 10L, null, 7);
        spend(actor, 600, 0, 0.1, NOW);
        assertTrue(limits.check(new ActorId(outsider)).isEmpty());
    }

    @Test void spendOlderThanThePeriodNoLongerCounts() {
        limit(AiUsageLimitScope.PERSON, null, 1_000L, null, 3);
        spend(actor, 1_500, 0, 0.1, NOW.minus(java.time.Duration.ofDays(5)));
        assertTrue(limits.check(new ActorId(actor)).isEmpty());
        spend(actor, 1_500, 0, 0.1, NOW.minus(java.time.Duration.ofDays(1)));
        var breach = limits.check(new ActorId(actor)).orElseThrow();
        // The window keeps three days, so yesterday's spend leaves it two days from now.
        assertEquals(Instant.parse("2026-09-23T00:00:00Z"), breach.resetsAt());
    }

    @Test void aCostBudgetBindsOnPriceAndAnUnpricedCallOnlyAddsTokens() {
        limit(AiUsageLimitScope.TENANT, null, null, new BigDecimal("1.00"), 7);
        recorder.record(AiUsage.unknown(tenant, actor, AiUsageFlow.CHAT, "vLLM", "qwen3", null, null, "INTERNAL", NOW));
        assertTrue(limits.check(new ActorId(actor)).isEmpty(), "an unpriced call carries no cost to weigh");
        spend(actor, 10, 10, 1.20, NOW);
        assertEquals(AiUsageLimitService.Breach.Budget.COST, limits.check(new ActorId(actor)).orElseThrow().budget());
    }

    @Test void aDisabledLimitKeepsItsBudgetButRefusesNothing() {
        UUID id = limit(AiUsageLimitScope.PERSON, null, 10L, null, 7);
        spend(actor, 600, 0, 0.1, NOW);
        assertFalse(limits.check(new ActorId(actor)).isEmpty());
        var stored = repository.find(tenant, id).orElseThrow();
        repository.update(tenant, new AiUsageLimit(id, stored.scope(), stored.groupId(), stored.groupName(),
                stored.tokenBudget(), stored.costBudgetUsd(), stored.periodDays(), false));
        // The cache would otherwise hold the previous answer for a minute.
        limits = new AiUsageLimitService(authorization(), repository, TestDatabase.noAudit(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertTrue(limits.check(new ActorId(actor)).isEmpty());
    }

    @Test void theTightestBudgetIsTheOneAPersonSees() {
        limit(AiUsageLimitScope.TENANT, null, 10_000L, null, 7);
        limit(AiUsageLimitScope.PERSON, null, 1_000L, null, 7);
        spend(actor, 800, 0, 0.1, NOW);
        var standing = limits.standing(new ActorId(actor)).orElseThrow();
        assertEquals(AiUsageLimitScope.PERSON, standing.scope());
        assertEquals(800L, standing.tokensUsed());
        assertEquals(1_000L, standing.tokenBudget());
    }

    @Test void nothingIsWeighedWhenTheTenantSetsNoLimit() {
        spend(actor, 10_000_000, 0, 500.0, NOW);
        assertTrue(limits.check(new ActorId(actor)).isEmpty());
        assertTrue(limits.standing(new ActorId(actor)).isEmpty());
    }

    private IamAuthorization authorization() {
        var authorization = mock(IamAuthorization.class);
        when(authorization.require(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new IamAccess(new TenantId(tenant), Authority.GLOBAL));
        return authorization;
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", id).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant).param("actor", id).update();
        return id;
    }

    private void member(UUID who) {
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id, group_id, actor_id) VALUES (:tenant, :group, :actor)")
                .param("tenant", tenant).param("group", group).param("actor", who).update();
    }

    private UUID limit(AiUsageLimitScope scope, @Nullable UUID groupId, @Nullable Long tokens,
                       @Nullable BigDecimal cost, int days) {
        UUID id = UUID.randomUUID();
        repository.insert(tenant, new AiUsageLimit(id, scope, groupId, null, tokens, cost, days, true));
        return id;
    }

    private void spend(UUID who, long input, long output, double cost, Instant at) {
        recorder.record(AiUsage.tokens(tenant, who, AiUsageFlow.CHAT, "OpenAI", "gpt-5.1", null, null, "EXTERNAL",
                input, output, 0, cost, at));
    }
}
