package io.memoryos.chat;

import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.persona.persistence.JdbcPromptShortcutRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prompt shortcuts inserted from the composer with "/" (Onyx {@code InputPrompt}). Actors own private shortcuts;
 * {@code AGENTS_MANAGE} administers public ones, which every member can hide for themselves.
 */
@Service
public class ChatPromptShortcutService {
    public static final int MAX_PRIVATE = 200;

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final JdbcChatRepository chats;
    private final JdbcPromptShortcutRepository shortcuts;

    public ChatPromptShortcutService(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                                     JdbcPromptShortcutRepository shortcuts) {
        this.tenants = tenants; this.authorization = authorization; this.chats = chats; this.shortcuts = shortcuts;
    }

    public record ShortcutInput(String name, String content, @Nullable Boolean active) {}
    public record PromptShortcutPreferences(boolean enabled) {}

    @Transactional(readOnly = true)
    public List<PromptShortcut> list(ActorId actor, boolean includeHidden) {
        var tenant = tenant(actor);
        authorization.require(actor, IamCapability.CHAT_READ, false);
        return shortcuts.visible(tenant.value(), actor.value(), includeHidden);
    }

    @Transactional(readOnly = true)
    public List<PromptShortcut> publicShortcuts(ActorId actor) {
        var tenant = tenant(actor);
        requireManage(actor);
        return shortcuts.publicShortcuts(tenant.value());
    }

    @Transactional
    public PromptShortcut create(ActorId actor, ShortcutInput input, boolean isPublic) {
        var tenant = write(actor);
        if (isPublic) requireManage(actor); else authorization.require(actor, IamCapability.CHAT_WRITE, false);
        UUID owner = isPublic ? null : actor.value();
        var name = name(input.name());
        var content = content(input.content());
        if (owner != null && shortcuts.countOwned(tenant.value(), owner) >= MAX_PRIVATE)
            throw ChatException.invalid("Use at most " + MAX_PRIVATE + " prompt shortcuts.");
        if (shortcuts.nameTaken(tenant.value(), owner, name, null)) throw ChatException.conflict();
        var id = UUID.randomUUID();
        if (!shortcuts.insert(tenant.value(), owner, id, name, content, input.active() == null || input.active()))
            throw ChatException.conflict();
        return shortcuts.locked(tenant.value(), owner, id).orElseThrow();
    }

    @Transactional
    public PromptShortcut update(ActorId actor, UUID id, long revision, ShortcutInput input, boolean isPublic) {
        var tenant = write(actor);
        if (isPublic) requireManage(actor); else authorization.require(actor, IamCapability.CHAT_WRITE, false);
        UUID owner = isPublic ? null : actor.value();
        var current = shortcuts.locked(tenant.value(), owner, id).orElseThrow(ChatException::unavailable);
        if (current.revision() != revision) throw ChatException.conflict();
        var name = name(input.name());
        if (shortcuts.nameTaken(tenant.value(), owner, name, id)) throw ChatException.conflict();
        shortcuts.update(tenant.value(), id, name, content(input.content()), input.active() == null ? current.active() : input.active());
        return shortcuts.locked(tenant.value(), owner, id).orElseThrow();
    }

    @Transactional
    public void delete(ActorId actor, UUID id, boolean isPublic) {
        var tenant = write(actor);
        if (isPublic) requireManage(actor); else authorization.require(actor, IamCapability.CHAT_WRITE, false);
        shortcuts.locked(tenant.value(), isPublic ? null : actor.value(), id).orElseThrow(ChatException::unavailable);
        shortcuts.delete(tenant.value(), id);
    }

    @Transactional
    public void hide(ActorId actor, UUID id, boolean hidden) {
        var tenant = write(actor);
        authorization.require(actor, IamCapability.CHAT_WRITE, false);
        if (!shortcuts.publicExists(tenant.value(), id)) throw ChatException.unavailable();
        shortcuts.hide(tenant.value(), id, actor.value(), hidden);
    }

    @Transactional(readOnly = true)
    public PromptShortcutPreferences preferences(ActorId actor) {
        var tenant = tenant(actor);
        return new PromptShortcutPreferences(shortcuts.shortcutsEnabled(tenant.value(), actor.value()));
    }

    @Transactional
    public PromptShortcutPreferences preferences(ActorId actor, boolean enabled) {
        var tenant = write(actor);
        authorization.require(actor, IamCapability.CHAT_WRITE, false);
        shortcuts.shortcutsEnabled(tenant.value(), actor.value(), enabled);
        return new PromptShortcutPreferences(enabled);
    }

    private static String name(@Nullable String value) {
        if (value == null || value.isBlank() || value.strip().length() > 100 || value.chars().anyMatch(Character::isISOControl))
            throw ChatException.invalid("Shortcut names have 1 to 100 characters on one line.");
        return value.strip();
    }

    private static String content(@Nullable String value) {
        if (value == null || value.isBlank() || value.length() > 8000) throw ChatException.invalid("Shortcut content has 1 to 8000 characters.");
        return value;
    }

    private void requireManage(ActorId actor) {
        if (!authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_MANAGE)) throw ChatException.unavailable();
    }

    private TenantId tenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
    }

    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        return tenant;
    }
}
