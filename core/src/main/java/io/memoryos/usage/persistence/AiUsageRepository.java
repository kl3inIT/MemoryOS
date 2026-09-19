package io.memoryos.usage.persistence;

import io.memoryos.usage.AiUsage;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class AiUsageRepository {
    private final JdbcClient jdbc;

    public AiUsageRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Accumulates into the row of the call's UTC day (Onyx record_user_usage). */
    public void add(AiUsage call) {
        jdbc.sql("""
                INSERT INTO ai_usage(tenant_id, actor_id, day, flow, provider_name, model_name, provider_id, model_configuration_id,
                    data_boundary, calls, input_tokens, output_tokens, cache_read_tokens, image_count, audio_seconds, cost_usd, unknown_cost_calls)
                VALUES (:tenant, :actor, :day, :flow, :provider, :model, :providerId, :modelId, :boundary, :calls, :input, :output,
                    :cacheRead, :images, :audio, :cost, :unknown)
                ON CONFLICT ON CONSTRAINT uq_ai_usage_dims DO UPDATE SET
                    provider_id = COALESCE(EXCLUDED.provider_id, ai_usage.provider_id),
                    model_configuration_id = COALESCE(EXCLUDED.model_configuration_id, ai_usage.model_configuration_id),
                    calls = ai_usage.calls + EXCLUDED.calls,
                    input_tokens = ai_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = ai_usage.output_tokens + EXCLUDED.output_tokens,
                    cache_read_tokens = ai_usage.cache_read_tokens + EXCLUDED.cache_read_tokens,
                    image_count = ai_usage.image_count + EXCLUDED.image_count,
                    audio_seconds = ai_usage.audio_seconds + EXCLUDED.audio_seconds,
                    cost_usd = ai_usage.cost_usd + EXCLUDED.cost_usd,
                    unknown_cost_calls = ai_usage.unknown_cost_calls + EXCLUDED.unknown_cost_calls
                """)
                .param("tenant", call.tenant()).param("actor", call.actor(), Types.OTHER)
                .param("day", call.at().atOffset(ZoneOffset.UTC).toLocalDate())
                .param("flow", call.flow().name()).param("provider", call.providerName()).param("model", call.modelName())
                .param("providerId", call.providerId(), Types.OTHER).param("modelId", call.modelConfigurationId(), Types.OTHER)
                .param("boundary", call.dataBoundary(), Types.VARCHAR)
                .param("calls", call.calls()).param("input", call.inputTokens()).param("output", call.outputTokens())
                .param("cacheRead", call.cacheReadTokens()).param("images", call.imageCount())
                .param("audio", BigDecimal.valueOf(call.audioSeconds()))
                .param("cost", call.cost() == null ? BigDecimal.ZERO : BigDecimal.valueOf(call.cost()))
                .param("unknown", call.cost() == null ? call.calls() : 0)
                .update();
    }
}
