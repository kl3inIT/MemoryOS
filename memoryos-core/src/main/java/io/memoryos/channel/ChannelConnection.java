package io.memoryos.channel;

import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A bot of the Tenant, without its secret. {@code appId} is the provider's id of the bot's app and {@code settings}
 * the provider's public settings of it (Lark's region); {@code serviceAccount} is the account a group question runs
 * as; {@code noticeLanguage} ({@code vi} or {@code en}) is the language of the bot's own notices;
 * {@code organisationKey} is the provider organisation of the bot's app, null until it is known;
 * {@code botName}, {@code botExternalId} and {@code missingScopes} are what the platform said at the last check.
 */
public record ChannelConnection(UUID id, ChannelProvider provider, String name, String appId, Map<String, String> settings,
                                String noticeLanguage, boolean enabled, ActorId serviceAccount, long revision,
                                Instant createdAt, Instant updatedAt, @Nullable String organisationKey,
                                @Nullable String botName, @Nullable String botExternalId, List<String> missingScopes) {
    public ChannelConnection {
        settings = Map.copyOf(settings);
        missingScopes = List.copyOf(missingScopes);
    }
}
