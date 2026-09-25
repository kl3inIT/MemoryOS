package io.memoryos.ai;

import io.memoryos.shared.TenantId;
import java.util.Set;
import java.util.UUID;

/**
 * Published in the removing transaction just before these model configurations are deleted, alone or with their
 * provider, so whatever names one as its model lets go of it first.
 */
public record ModelsRemoved(TenantId tenantId, Set<UUID> modelConfigurationIds) {
    public ModelsRemoved {
        modelConfigurationIds = Set.copyOf(modelConfigurationIds);
    }
}
