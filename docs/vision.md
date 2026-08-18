# MemoryOS vision

MemoryOS is a durable personal knowledge system whose data remains owned by a stable internal actor even when authentication providers or external account identifiers change.

## Outcomes

- Ingest knowledge into an actor-owned store with explicit provenance.
- Retrieve relevant knowledge under authorization boundaries.
- Provide assistant behavior grounded in owned knowledge rather than provider identity.
- Preserve an auditable trail for security-sensitive and ownership-changing operations.
- Keep data and processing recoverable across restart, deploy, and provider changes.

## Product principles

1. **Stable ownership before features.** Authentication establishes an internal actor; provider claims never become the long-term ownership key.
2. **Production path first.** A capability is not complete until its real storage, authorization, operations, and recovery path exist. Temporary application modes do not substitute for the product flow.
3. **Capability-owned vertical slices.** Add infrastructure only when a concrete capability needs it and owns its lifecycle.
4. **Fail closed.** Missing configuration, invalid tokens, unknown identity bindings, and ambiguous ownership fail explicitly.
5. **Repository continuity.** Architecture, decisions, active plans, and verification evidence are versioned with the code.

## Current horizon

The foundation establishes module boundaries, a dedicated OIDC realm, and persistent actor resolution. Knowledge ingestion, retrieval, model integration, authorization policy integration, and durable worker processing remain future vertical slices.
