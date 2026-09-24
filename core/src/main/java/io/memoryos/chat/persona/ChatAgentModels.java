package io.memoryos.chat.persona;

import io.memoryos.ai.AgentDirectory;
import io.memoryos.ai.ModelsRemoved;
import io.memoryos.chat.persona.persistence.JdbcAgentModelRepository;
import io.memoryos.shared.TenantId;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Chat's agents as the model catalog sees them: restriction targets, and holders of a model that is being removed. */
@Component
class ChatAgentModels implements AgentDirectory {
    private final JdbcAgentModelRepository agents;

    ChatAgentModels(JdbcAgentModelRepository agents) {
        this.agents = agents;
    }

    @Override
    public boolean exist(TenantId tenant, Set<UUID> agentIds) {
        return agents.exist(tenant.value(), agentIds);
    }

    /** Runs in the removing transaction, before the models go, so no agent is left naming one. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void removed(ModelsRemoved event) {
        agents.clearModels(event.tenantId().value(), event.modelConfigurationIds());
    }
}
