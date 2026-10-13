package io.memoryos.channel;

import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A bot of the Tenant, without its secret. {@code serviceAccount} is the account a group question runs as;
 * {@code noticeLanguage} ({@code vi} or {@code en}) is the language of the bot's own notices;
 * {@code organisationKey} is the provider organisation of the bot's app, null until the app is verified.
 */
public record ChannelConnection(UUID id, ChannelProvider provider, String name, ChannelRegion region, String appId,
                                String noticeLanguage, boolean enabled, ActorId serviceAccount, long revision,
                                Instant createdAt, Instant updatedAt, @Nullable String organisationKey) {
}
