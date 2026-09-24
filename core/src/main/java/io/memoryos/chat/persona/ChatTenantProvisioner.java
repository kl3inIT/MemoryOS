package io.memoryos.chat.persona;

import io.memoryos.chat.PersonaProperties;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.iam.TenantBootstrapped;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions a Tenant's built-in default agent. It runs in the Tenant bootstrap transaction, so a Tenant never commits
 * without it, and it is insert-only: a restart re-runs it without touching what an administrator has since changed.
 * The model catalog provisions itself the same way.
 */
@Component
public class ChatTenantProvisioner {
    private final JdbcChatRepository chats;
    private final PersonaProperties persona;

    public ChatTenantProvisioner(JdbcChatRepository chats, PersonaProperties persona) {
        this.chats = Objects.requireNonNull(chats, "chats must not be null");
        this.persona = Objects.requireNonNull(persona, "persona must not be null");
    }

    /** Runs in the transaction that created or re-verified the Tenant. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void bootstrapped(TenantBootstrapped event) {
        provision(event.tenantId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void provision(TenantId tenantId) {
        chats.provisionPersona(tenantId, persona.getName(), persona.getInstructions(), persona.getModel());
    }
}
