package io.memoryos.chat.preferences;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.persistence.JdbcVoiceSettingsRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each active member's own voice preferences; nobody reads or writes another member's settings. */
@Service
public class VoiceSettingsService {
    public static final double MIN_PLAYBACK_SPEED = 0.5;
    public static final double MAX_PLAYBACK_SPEED = 2.0;
    private final JdbcVoiceSettingsRepository settings;
    private final TenantAccessResolver tenants;

    public VoiceSettingsService(JdbcVoiceSettingsRepository settings, TenantAccessResolver tenants) {
        this.settings = settings;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public VoiceSettings get(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return settings.find(tenant, actor.value()).orElse(VoiceSettings.DEFAULT);
    }

    /** Partial update; the playback speed is stored in tenths, as the settings slider offers it. */
    @Transactional
    public VoiceSettings update(ActorId actor, @Nullable Boolean autoSend, @Nullable Boolean autoPlayback,
                                @Nullable Double playbackSpeed) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        Double speed = null;
        if (playbackSpeed != null) {
            if (!Double.isFinite(playbackSpeed) || playbackSpeed < MIN_PLAYBACK_SPEED || playbackSpeed > MAX_PLAYBACK_SPEED)
                throw ChatException.invalid("Playback speed must be between 0.5 and 2.0.");
            speed = Math.round(playbackSpeed * 10) / 10.0;
        }
        return settings.update(tenant, actor.value(), autoSend, autoPlayback, speed);
    }
}
