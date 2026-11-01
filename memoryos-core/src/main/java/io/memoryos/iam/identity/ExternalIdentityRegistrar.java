package io.memoryos.iam.identity;

import io.memoryos.iam.ExternalIdentity;
import io.memoryos.shared.ActorId;

public interface ExternalIdentityRegistrar {

    ActorId resolveOrCreate(ExternalIdentity identity);

    ActorId resolveOrCreateLocked(ExternalIdentity identity);

    /** A new service account bound to an identity that has no Actor yet. */
    ActorId createService(ExternalIdentity identity, String uiLanguage);
}
