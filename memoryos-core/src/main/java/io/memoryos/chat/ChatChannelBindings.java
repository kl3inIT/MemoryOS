package io.memoryos.chat;

import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.shared.TenantId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which assistant each bot answers with (MEM-273). Chat keeps the binding because it decides, for each turn of a
 * conversation held in a channel, whether that conversation may still use the bot's assistant. The channel calls this
 * once it has authorised the change; nothing here checks who asks.
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
}
