package io.memoryos.channel;

import io.memoryos.channel.persistence.JdbcChannelGroupRepository;
import io.memoryos.chat.ChatChannelBindings;
import io.memoryos.shared.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group chats a bot is in as its provider tells them (MEM-273 step 5d): a group the bot is added to is on the bot's
 * page at once, and the provider's whole list, read when the bot connects and every {@link ChannelSupervisor#LISTING}
 * after, adds the groups it joined unseen and removes those it left. A group the bot left loses its own assistant with
 * it: the bot cannot answer there, and coming back starts on the bot's default, as a group never seen does.
 */
@Service
public class ChannelGroupRoster {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelGroupRoster.class);

    private final JdbcChannelGroupRepository groups;
    private final ChatChannelBindings bindings;
    private final Clock clock;

    public ChannelGroupRoster(JdbcChannelGroupRepository groups, ChatChannelBindings bindings) {
        this(groups, bindings, Clock.systemUTC());
    }

    ChannelGroupRoster(JdbcChannelGroupRepository groups, ChatChannelBindings bindings, Clock clock) {
        this.groups = groups;
        this.bindings = bindings;
        this.clock = clock;
    }

    /** The bot was added to a group. */
    public void joined(TenantId tenant, UUID connection, String chatId, @Nullable String name) {
        groups.present(tenant, connection, chatId, name, clock.instant());
        LOG.atInfo().addKeyValue("event", "channel.group.joined").log("Channel bot added to a group chat");
    }

    /** The provider's whole list of the bot's groups, read from {@code readAt}. */
    @Transactional
    public void listed(TenantId tenant, UUID connection, List<ChannelGroupPresence> present, Instant readAt) {
        var left = groups.replace(tenant, connection, present, readAt, clock.instant());
        for (String chatId : left) bindings.bindGroup(tenant, connection, chatId, null);
        if (!left.isEmpty()) {
            LOG.atInfo().addKeyValue("event", "channel.group.left").addKeyValue("count", left.size())
                    .log("Channel bot no longer in group chats");
        }
    }
}
