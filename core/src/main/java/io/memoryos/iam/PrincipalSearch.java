package io.memoryos.iam;

import io.memoryos.shared.ActorId;

/**
 * Finds the people and Groups a member may name when sharing something: active members and ordinary Groups of the
 * searcher's own Tenant. Any active member of that Tenant may search, whatever their capabilities, because agents,
 * conversations and meetings all share through the same picker. Each consumer rechecks the principals it is finally
 * given against its own authority.
 */
public interface PrincipalSearch {

    PrincipalMatches search(ActorId searcher, PrincipalQuery query);
}
