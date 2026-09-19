package io.memoryos.usage;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.usage.persistence.AiUsageRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class AiUsageRecorderTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private AiUsageRecorder recorder;
    private UUID tenant;
    private UUID actor;
    private final Instant noon = Instant.parse("2026-09-19T12:00:00Z");

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        recorder = new AiUsageRecorder(new AiUsageRepository(jdbc));
        tenant = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Usage', 'ACTIVE', 'test')")
                .param("id", tenant).param("slug", tenant.toString()).update();
        actor = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor).update();
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void callsOfOneDayAccumulateAndUnknownCostIsCountedNotAddedAsZero() {
        UUID model = UUID.randomUUID();
        recorder.record(chat(1000, 200, 400, 0.01, noon, "EXTERNAL", model));
        recorder.record(chat(500, 50, 0, 0.002, noon.plusSeconds(3600), "EXTERNAL", model));
        recorder.record(AiUsage.tokens(tenant, actor, AiUsageFlow.CHAT, "OpenAI", "gpt-5.1", null, model, "EXTERNAL",
                300, 30, 0, null, noon));
        recorder.record(AiUsage.unknown(tenant, actor, AiUsageFlow.CHAT, "OpenAI", "gpt-5.1", null, model, "EXTERNAL", noon));
        var row = only();
        assertEquals(4L, row.get("calls"));
        assertEquals(1800L, row.get("input_tokens"));
        assertEquals(280L, row.get("output_tokens"));
        assertEquals(400L, row.get("cache_read_tokens"));
        assertEquals(0, new BigDecimal("0.012").compareTo((BigDecimal) row.get("cost_usd")));
        assertEquals(2L, row.get("unknown_cost_calls"));
        assertEquals(model, row.get("model_configuration_id"));
    }

    @Test void dayFlowModelAndBoundaryStartSeparateRows() {
        recorder.record(chat(10, 1, 0, 0.1, Instant.parse("2026-09-19T23:59:59Z"), "EXTERNAL", null));
        recorder.record(chat(10, 1, 0, 0.1, Instant.parse("2026-09-20T00:00:00Z"), "EXTERNAL", null));
        recorder.record(chat(10, 1, 0, 0.1, noon, "INTERNAL", null));
        recorder.record(AiUsage.tokens(tenant, actor, AiUsageFlow.CHAT_NAMING, "OpenAI", "gpt-5.1", null, null, "EXTERNAL", 10, 1, 0, 0.1, noon));
        assertEquals(4, jdbc.sql("SELECT count(*) FROM ai_usage").query(Integer.class).single());
        assertEquals(2, jdbc.sql("SELECT count(*) FROM ai_usage WHERE day = DATE '2026-09-19' AND flow = 'CHAT'").query(Integer.class).single());
    }

    @Test void systemWorkWithoutActorOrBoundaryMergesIntoOneRow() {
        for (int i = 0; i < 3; i++)
            recorder.record(AiUsage.tokens(tenant, null, AiUsageFlow.EMBEDDING_INDEXING, "openai", "text-embedding-3-small",
                    null, null, null, 800, 0, 0, null, noon));
        var row = only();
        assertNull(row.get("actor_id"));
        assertNull(row.get("data_boundary"));
        assertEquals(3L, row.get("calls"));
        assertEquals(2400L, row.get("input_tokens"));
    }

    @Test void catalogDeletionDoesNotTouchHistoryAndInvalidInputIsRejected() {
        recorder.record(chat(10, 1, 0, 0.1, noon, "EXTERNAL", UUID.randomUUID()));
        assertEquals("gpt-5.1", only().get("model_name"));
        assertThrows(IllegalArgumentException.class, () -> chat(10, 1, 11, 0.1, noon, "EXTERNAL", null));
        assertThrows(IllegalArgumentException.class, () -> chat(10, 1, 0, -1, noon, "EXTERNAL", null));
        assertThrows(IllegalArgumentException.class, () -> chat(10, 1, 0, 0.1, noon, "PUBLIC", null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                "UPDATE ai_usage SET unknown_cost_calls = calls + 1").update());
    }

    private AiUsage chat(long input, long output, long cacheRead, double cost, Instant at, String boundary, UUID model) {
        return AiUsage.tokens(tenant, actor, AiUsageFlow.CHAT, "OpenAI", "gpt-5.1", null, model, boundary, input, output, cacheRead, cost, at);
    }

    private Map<String, Object> only() {
        return jdbc.sql("SELECT * FROM ai_usage").query().singleRow();
    }
}
