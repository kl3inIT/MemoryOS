package io.memoryos.chat;

import io.memoryos.shared.ActorId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic conversation creation and settings across assistant and project services. */
@Service
public class ChatWorkspaceService {
    private final ChatSessionService sessions;
    private final ChatPersonaService personas;
    private final ChatProjectService projects;

    public ChatWorkspaceService(ChatSessionService sessions, ChatPersonaService personas, ChatProjectService projects) {
        this.sessions = sessions; this.personas = personas; this.projects = projects;
    }

    @Transactional
    public ChatSession create(ActorId actor, String title, @Nullable UUID personaId, @Nullable UUID projectId) {
        return create(actor, title, personaId, projectId, false);
    }

    /** A temporary conversation (MEM-153) leaves no history, so it never belongs to a Project. */
    @Transactional
    public ChatSession create(ActorId actor, String title, @Nullable UUID personaId, @Nullable UUID projectId,
                              boolean temporary) {
        if (temporary && projectId != null)
            throw ChatException.invalid("A temporary conversation cannot belong to a Project.");
        var session = projectId == null ? sessions.create(actor, title, temporary)
                : projects.createConversation(actor, projectId, title);
        return personaId == null ? session : personas.select(actor, session.id(), personaId);
    }

    @Transactional
    public ChatSession configure(ActorId actor, UUID sessionId, UUID personaId, @Nullable UUID projectId) {
        personas.select(actor, sessionId, personaId);
        return projects.move(actor, sessionId, projectId);
    }
}
