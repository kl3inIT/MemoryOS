package io.memoryos.chat;

import io.memoryos.chat.preferences.persistence.JdbcChatMemoryRepository;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each active member's own memory (MEM-250). The member reads, rewrites, clears and switches it in Settings; the
 * {@code update_memory} tool edits it one passage at a time. Nobody reads or writes another member's memory.
 */
@Service
public class ChatMemoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatMemoryService.class);

    /** What the tool tells the model: the outcome, and the current memory when its edit did not apply. */
    public sealed interface Edit {
        record Applied(ChatMemory memory) implements Edit {}
        record PassageNotFound(ChatMemory memory) implements Edit {}
        record PassageNotUnique(ChatMemory memory) implements Edit {}
        record TooLong(ChatMemory memory, int length) implements Edit {}
    }

    private final JdbcChatMemoryRepository memories;
    private final TenantAccessResolver tenants;

    public ChatMemoryService(JdbcChatMemoryRepository memories, TenantAccessResolver tenants) {
        this.memories = memories;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public ChatMemory get(ActorId actor) {
        return memories.find(tenant(actor), actor.value());
    }

    /** The member's own whole-text edit; {@code revision} is the one they read, so a newer write is not lost. */
    @Transactional
    public ChatMemory replace(ActorId actor, String content, long revision) {
        var text = content == null ? "" : content.strip();
        if (text.length() > ChatMemory.MAX_CONTENT)
            throw ChatException.invalid("Memory must be at most " + ChatMemory.MAX_CONTENT + " characters.");
        var tenant = tenant(actor);
        var saved = memories.replace(tenant, actor.value(), text, revision).orElseThrow(ChatException::conflict);
        updated(tenant, "settings");
        return saved;
    }

    @Transactional
    public ChatMemory clear(ActorId actor) {
        var tenant = tenant(actor);
        var current = memories.find(tenant, actor.value());
        if (current.content().isEmpty()) return current;
        var saved = memories.replace(tenant, actor.value(), "", current.revision()).orElseThrow(ChatException::conflict);
        updated(tenant, "settings");
        return saved;
    }

    @Transactional
    public ChatMemory enabled(ActorId actor, boolean enabled) {
        return memories.enabled(tenant(actor), actor.value(), enabled);
    }

    /** Whether a turn in this conversation reads and writes memory: switched on and not a temporary conversation. */
    @Transactional(readOnly = true)
    public boolean usableIn(TenantId tenant, ActorId actor, UUID session) {
        return memories.usableIn(tenant.value(), actor.value(), session);
    }

    /**
     * Dust's {@code edit(oldStr, newStr)}: an empty {@code oldText} appends {@code newText} as a new line, an empty
     * {@code newText} removes {@code oldText}, otherwise the one occurrence of {@code oldText} is replaced. It reads
     * the stored memory, not the turn's copy, and a concurrent write makes the caller read again.
     */
    @Transactional
    public Edit edit(TenantId tenant, ActorId actor, String oldText, String newText) {
        for (int attempt = 0; attempt < 3; attempt++) {
            var edit = editOnce(tenant, actor, oldText, newText);
            if (edit != null) return edit;
        }
        throw ChatException.conflict();
    }

    /** One read and compare-and-set; null when another write came between them. */
    private Edit.@Nullable Applied saveIfUnchanged(TenantId tenant, ActorId actor, String next, long revision) {
        return memories.replace(tenant.value(), actor.value(), next, revision).map(Edit.Applied::new).orElse(null);
    }

    private @Nullable Edit editOnce(TenantId tenant, ActorId actor, String oldText, String newText) {
        var current = memories.find(tenant.value(), actor.value());
        String old = oldText == null ? "" : oldText.strip();
        String replacement = newText == null ? "" : newText.strip();
        String next;
        if (old.isEmpty()) {
            next = current.content().isEmpty() ? replacement : current.content() + "\n" + replacement;
        } else {
            int at = current.content().indexOf(old);
            if (at < 0) return new Edit.PassageNotFound(current);
            if (current.content().indexOf(old, at + 1) >= 0) return new Edit.PassageNotUnique(current);
            next = current.content().substring(0, at) + replacement + current.content().substring(at + old.length());
            next = next.replaceAll("\n{3,}", "\n\n").strip();
        }
        if (next.length() > ChatMemory.MAX_CONTENT) {
            LOGGER.atInfo().addKeyValue("event", "chat.memory.update_rejected").addKeyValue("tenant_id", tenant.value())
                    .addKeyValue("error_code", "CHAT_MEMORY_TOO_LONG").log("Memory edit exceeded the limit");
            return new Edit.TooLong(current, next.length());
        }
        var applied = saveIfUnchanged(tenant, actor, next, current.revision());
        if (applied != null) updated(tenant.value(), "tool");
        return applied;
    }

    private UUID tenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
    }

    private static void updated(UUID tenant, String by) {
        LOGGER.atInfo().addKeyValue("event", "chat.memory.updated").addKeyValue("tenant_id", tenant)
                .addKeyValue("source", by).log("Member memory updated");
    }
}
