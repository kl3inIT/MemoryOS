package io.memoryos.iam;

import java.util.Set;

/**
 * Fresh database-backed IAM resolution. The revision is an invalidation signal only; callers must
 * use the require methods for authorization. Locking methods require an existing write transaction.
 */
public interface IamAuthorization {

    Set<IamCapability> effectiveCapabilities(ActorId actorId);

    Set<IamCapability> scopedCapabilities(ActorId actorId);

    long authorizationVersion(ActorId actorId);

    IamAccess require(ActorId actorId, IamCapability capability, boolean allowScoped);

    IamAccess lockAndRequire(ActorId actorId, IamCapability capability, boolean allowScoped);

    IamAccess lockAndRequireExclusive(ActorId actorId, IamCapability capability);

    IamAccess lockAndRequireAdministration(ActorId actorId);
}
