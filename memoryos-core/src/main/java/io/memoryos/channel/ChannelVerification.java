package io.memoryos.channel;

import java.util.Objects;
import java.util.Set;

/**
 * What the platform says of a bot's app: the credential works, it has a bot, and the scopes the bot needs that the
 * platform refuses. A refused scope does not stop the bot from being stored; the administrator is shown what is
 * missing, as for a connector's app.
 */
public record ChannelVerification(String botName, String botExternalId, Set<String> missingScopes) {
    public ChannelVerification {
        Objects.requireNonNull(botName, "botName");
        Objects.requireNonNull(botExternalId, "botExternalId");
        missingScopes = Set.copyOf(missingScopes);
    }
}
