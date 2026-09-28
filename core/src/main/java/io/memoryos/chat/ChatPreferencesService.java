package io.memoryos.chat;

import io.memoryos.chat.preferences.persistence.JdbcChatPreferencesRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.ActorProfileReader;
import io.memoryos.iam.TenantAccessResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each active member's own Chat preferences; nobody reads or writes another member's. */
@Service
public class ChatPreferencesService {
    public record View(ChatPreferences preferences, ActorProfileReader.Profile profile) {}

    private final JdbcChatPreferencesRepository preferences;
    private final TenantAccessResolver tenants;
    private final ChatModelAccess catalog;
    private final ActorProfileReader profiles;

    public ChatPreferencesService(JdbcChatPreferencesRepository preferences, TenantAccessResolver tenants,
                                  ChatModelAccess catalog, ActorProfileReader profiles) {
        this.preferences = preferences;
        this.tenants = tenants;
        this.catalog = catalog;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public View get(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return new View(preferences.find(tenant, actor.value()).orElse(ChatPreferences.DEFAULT), profiles.read(actor));
    }

    /** Replaces the preferences; a default model must be one the member may pick for a new conversation now. */
    @Transactional
    public View save(ActorId actor, ChatPreferences value) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        if (value.workRole().length() > ChatPreferences.MAX_WORK_ROLE)
            throw ChatException.invalid("Work role must be at most 200 characters.");
        if (value.personalPreferences().length() > ChatPreferences.MAX_PERSONAL_PREFERENCES)
            throw ChatException.invalid("Personal preferences must be at most 2000 characters.");
        Double temperature = value.temperatureDefault();
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
            throw ChatException.invalid("Creativity must be between 0 and 2.");
        if (value.defaultModelId() != null && catalog.availableModels(actor, null).stream()
                .noneMatch(model -> model.id().equals(value.defaultModelId())))
            throw ChatException.invalid("Choose a model you can use.");
        return new View(preferences.save(tenant, actor.value(), value), profiles.read(actor));
    }
}
