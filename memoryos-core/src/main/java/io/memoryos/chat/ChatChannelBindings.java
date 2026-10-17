package io.memoryos.chat;

import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which assistant each bot answers with, and each group chat given one of its own (MEM-273). Chat keeps the bindings
 * because it decides, for each turn of a conversation held in a channel, whether that conversation may still use the
 * assistant its bot and group resolve to. The channel calls this once it has authorised the change; nothing here
 * checks who asks.
 */
@Service
public class ChatChannelBindings {
    private final JdbcChatRepository chats;

    public ChatChannelBindings(JdbcChatRepository chats) {
        this.chats = chats;
    }

    /** The custom assistant a bot is bound to, deleted or not; empty when it answers with the built-in one. */
    @Transactional(readOnly = true)
    public Optional<UUID> bound(TenantId tenant, UUID connection) {
        return chats.boundPersona(tenant, connection);
    }

    /** Every bound bot of the Tenant with its assistant. */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> all(TenantId tenant) {
        return chats.channelBindings(tenant);
    }

    /** Binds a bot to a custom assistant of the Tenant, or to none; returns whether the binding changed. */
    @Transactional
    public boolean bind(TenantId tenant, UUID connection, @Nullable UUID persona) {
        return chats.bindChannel(tenant, connection, persona);
    }

    /** The assistant a group chat of a bot is given, deleted or not; empty when it answers with the bot's default. */
    @Transactional(readOnly = true)
    public Optional<UUID> groupBound(TenantId tenant, UUID connection, String groupChat) {
        return chats.groupBoundPersona(tenant, connection, groupChat);
    }

    /** The group chats of a bot given an assistant of their own, with the assistant. */
    @Transactional(readOnly = true)
    public Map<String, UUID> groups(TenantId tenant, UUID connection) {
        return chats.groupBindings(tenant, connection);
    }

    /** The group chats given an assistant, by bot. */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> groupsBoundTo(TenantId tenant, UUID persona) {
        var groups = new LinkedHashMap<UUID, List<String>>();
        for (var binding : chats.groupBindingsTo(tenant, persona)) {
            groups.computeIfAbsent(binding.connection(), _ -> new ArrayList<>()).add(binding.groupChat());
        }
        return groups;
    }

    /** Gives a group chat of a bot an assistant of the Tenant, or none; returns whether it changed. */
    @Transactional
    public boolean bindGroup(TenantId tenant, UUID connection, String groupChat, @Nullable UUID persona) {
        return chats.bindChannelGroup(tenant, connection, groupChat, persona);
    }

    /** Drops what a removed bot answered with: its default and every group chat's own assistant. */
    @Transactional
    public void release(TenantId tenant, UUID connection) {
        chats.releaseChannel(tenant, connection);
    }
}
