package io.memoryos.ai;

import io.memoryos.shared.TenantId;
import java.util.Set;
import java.util.UUID;

/**
 * The agents a provider may be restricted to. Agents belong to Chat, which implements this; the catalog stores only
 * their ids.
 */
public interface AgentDirectory {
    /** Whether every one of {@code agentIds} is an agent of this Tenant. */
    boolean exist(TenantId tenant, Set<UUID> agentIds);
}
