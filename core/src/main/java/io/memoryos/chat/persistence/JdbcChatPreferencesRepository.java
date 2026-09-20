package io.memoryos.chat.persistence;

import io.memoryos.chat.preferences.ChatPreferences;
import io.memoryos.chat.preferences.ReasoningEffort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcChatPreferencesRepository {
    private static final String COLUMNS =
            "work_role, personal_preferences, default_model_configuration_id, temperature_default, reasoning_effort_default, auto_scroll";
    private final JdbcClient jdbc;

    public JdbcChatPreferencesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChatPreferences> find(UUID tenant, UUID actor) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chat_preferences WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenant).param("actor", actor)
                .query((row, index) -> map(row)).optional();
    }

    /** Replaces the member's preferences in one statement. */
    public ChatPreferences save(UUID tenant, UUID actor, ChatPreferences value) {
        return jdbc.sql("""
                        INSERT INTO chat_preferences (tenant_id, actor_id, work_role, personal_preferences,
                            default_model_configuration_id, temperature_default, reasoning_effort_default, auto_scroll)
                        VALUES (:tenant, :actor, :role, :preferences, :model, :temperature, :reasoning, :autoScroll)
                        ON CONFLICT (tenant_id, actor_id) DO UPDATE SET
                            work_role = EXCLUDED.work_role,
                            personal_preferences = EXCLUDED.personal_preferences,
                            default_model_configuration_id = EXCLUDED.default_model_configuration_id,
                            temperature_default = EXCLUDED.temperature_default,
                            reasoning_effort_default = EXCLUDED.reasoning_effort_default,
                            auto_scroll = EXCLUDED.auto_scroll
                        RETURNING\s""" + COLUMNS)
                .param("tenant", tenant).param("actor", actor)
                .param("role", value.workRole()).param("preferences", value.personalPreferences())
                .param("model", value.defaultModelId()).param("temperature", value.temperatureDefault())
                .param("reasoning", value.reasoningEffortDefault() == null ? null : value.reasoningEffortDefault().name())
                .param("autoScroll", value.autoScroll())
                .query((row, index) -> map(row)).single();
    }

    private static ChatPreferences map(ResultSet row) throws SQLException {
        String reasoning = row.getString(5);
        return new ChatPreferences(row.getString(1), row.getString(2), row.getObject(3, UUID.class),
                row.getObject(4, Double.class), reasoning == null ? null : ReasoningEffort.valueOf(reasoning),
                row.getBoolean(6));
    }
}
