package io.memoryos.chat.persistence;

import io.memoryos.chat.preferences.VoiceSettings;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVoiceSettingsRepository {
    private final JdbcClient jdbc;

    public JdbcVoiceSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VoiceSettings> find(UUID tenant, UUID actor) {
        return jdbc.sql("""
                        SELECT auto_send, auto_playback, playback_speed FROM chat_voice_settings
                        WHERE tenant_id = :tenant AND actor_id = :actor""")
                .param("tenant", tenant).param("actor", actor)
                .query((row, index) -> new VoiceSettings(row.getBoolean(1), row.getBoolean(2), row.getDouble(3)))
                .optional();
    }

    /** Atomic partial update: absent values keep the stored value, or the default for a first write. */
    public VoiceSettings update(UUID tenant, UUID actor, @Nullable Boolean autoSend, @Nullable Boolean autoPlayback,
                                @Nullable Double playbackSpeed) {
        return jdbc.sql("""
                        INSERT INTO chat_voice_settings (tenant_id, actor_id, auto_send, auto_playback, playback_speed)
                        VALUES (:tenant, :actor, COALESCE(:autoSend, FALSE), COALESCE(:autoPlayback, FALSE),
                                COALESCE(:playbackSpeed, 1.0))
                        ON CONFLICT (tenant_id, actor_id) DO UPDATE SET
                            auto_send = COALESCE(:autoSend, chat_voice_settings.auto_send),
                            auto_playback = COALESCE(:autoPlayback, chat_voice_settings.auto_playback),
                            playback_speed = COALESCE(:playbackSpeed, chat_voice_settings.playback_speed)
                        RETURNING auto_send, auto_playback, playback_speed""")
                .param("tenant", tenant).param("actor", actor)
                .param("autoSend", autoSend, Types.BOOLEAN)
                .param("autoPlayback", autoPlayback, Types.BOOLEAN)
                .param("playbackSpeed", playbackSpeed, Types.DOUBLE)
                .query((row, index) -> new VoiceSettings(row.getBoolean(1), row.getBoolean(2), row.getDouble(3)))
                .single();
    }
}
