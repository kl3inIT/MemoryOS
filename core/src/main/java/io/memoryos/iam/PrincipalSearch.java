package io.memoryos.iam;

import io.memoryos.shared.ActorId;

/**
 * Finds the people and Groups a member may name when sharing something: active members and ordinary Groups of the
 * searcher's own Tenant. The searcher needs {@link IamCapability#CHAT_WRITE}, the authority agent sharing has always
 * required for this search; every member holding the Basic Group's {@code SYSTEM_BASIC} has it.
 * Each consumer rechecks the principals it is finally given.
 */
public interface PrincipalSearch {

    PrincipalMatches search(ActorId searcher, PrincipalQuery query);
}
