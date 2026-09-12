package io.memoryos.chat;

import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JpaProjectRepository;
import io.memoryos.chat.persistence.ChatPage;
import io.memoryos.chat.persistence.ProjectEntity;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatProjectService {
    private final TenantAccessResolver tenants;
    private final JdbcChatRepository chats;
    private final JpaProjectRepository settings;
    private final ChatSessionService sessions;
    private final ChatFileService files;
    public ChatProjectService(TenantAccessResolver tenants, JdbcChatRepository chats, JpaProjectRepository settings, ChatSessionService sessions, ChatFileService files) {
        this.tenants = tenants; this.chats = chats; this.settings = settings; this.sessions = sessions;
        this.files = files;
    }
    public record ProjectInput(String name, String description, String instructions, @Nullable List<UUID> fileIds) {
        public ProjectInput(String name, String description, String instructions) { this(name, description, instructions, null); }
    }
    public record ProjectView(UUID id, String name, String description, String instructions, long revision, Instant updatedAt, List<UUID> fileIds) {}

    @Transactional(readOnly = true)
    public List<ProjectView> list(ActorId actor, int offset, int limit) {
        ChatPersonaService.page(offset, limit);
        return settings.findByTenantIdAndOwnerIdOrderByUpdatedAtDescIdAsc(tenant(actor).value(), actor.value(), new ChatPage(offset, limit))
                .stream().map(ChatProjectService::view).toList();
    }
    @Transactional
    public ProjectView create(ActorId actor, ProjectInput input) {
        var tenant = write(actor); validate(input);
        var entity = new ProjectEntity(UUID.randomUUID(), tenant.value(), actor.value(), input.name().strip(), input.description(), input.instructions());
        if (input.fileIds() != null) { files.admit(tenant, actor, input.fileIds()); entity.files(input.fileIds()); }
        return view(settings.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public ProjectView get(ActorId actor, UUID id) { return view(owned(tenant(actor), actor, id, false)); }
    @Transactional
    public ProjectView update(ActorId actor, UUID id, long revision, ProjectInput input) {
        var tenant = write(actor);
        var entity = owned(tenant, actor, id, true); validate(input);
        if (entity.revision() != revision) throw ChatException.conflict();
        if (input.fileIds() != null) { files.admit(tenant, actor, input.fileIds()); entity.files(input.fileIds()); }
        entity.update(input.name().strip(), input.description(), input.instructions()); settings.flush(); return view(entity);
    }
    @Transactional
    public void delete(ActorId actor, UUID id, long revision) {
        var tenant = write(actor); var entity = owned(tenant, actor, id, true);
        if (entity.revision() != revision) throw ChatException.conflict();
        chats.unlinkProject(tenant, actor, id);
        settings.delete(entity);
    }
    @Transactional(readOnly = true)
    public List<ChatSession> conversations(ActorId actor, UUID id, int offset, int limit) {
        ChatPersonaService.page(offset, limit); var tenant = tenant(actor); owned(tenant, actor, id, false);
        return chats.projectSessions(tenant, actor, id, offset, limit);
    }
    @Transactional
    public ChatSession createConversation(ActorId actor, UUID id, String title) {
        var tenant = write(actor); owned(tenant, actor, id, true);
        var session = sessions.create(actor, title); chats.moveProject(session.id(), id);
        return chats.findOwned(tenant, actor, session.id(), false).orElseThrow();
    }
    @Transactional
    public ChatSession move(ActorId actor, UUID session, @Nullable UUID project) {
        var tenant = write(actor);
        chats.findOwned(tenant, actor, session, true).orElseThrow(ChatException::unavailable);
        if (chats.hasActiveReply(session)) throw ChatException.conflict();
        if (project != null) owned(tenant, actor, project, true);
        chats.moveProject(session, project);
        return chats.findOwned(tenant, actor, session, false).orElseThrow();
    }
    private ProjectEntity owned(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        return (lock ? settings.locked(tenant.value(), actor.value(), id) : settings.findByTenantIdAndOwnerIdAndId(tenant.value(), actor.value(), id))
                .orElseThrow(ChatException::unavailable);
    }
    private TenantId tenant(ActorId actor) { return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable); }
    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor); return tenant;
    }
    private static void validate(ProjectInput input) {
        ChatPersonaService.text(input.name(), 200, true); ChatPersonaService.text(input.description(), 2000, false);
        ChatPersonaService.text(input.instructions(), 32000, false);
    }
    private static ProjectView view(ProjectEntity p) { return new ProjectView(p.id(), p.name(), p.description(), p.instructions(), p.revision(), p.updatedAt(), p.fileIds()); }
}
