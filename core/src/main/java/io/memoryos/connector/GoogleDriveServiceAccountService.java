package io.memoryos.connector;

import io.memoryos.shared.ActorId;

/**
 * Service-account credentials for Google Drive. Both commands validate the key against Google, acting as the primary
 * admin, before anything is stored; only global Source managers hold a service account.
 */
public interface GoogleDriveServiceAccountService {
    CredentialId create(ActorId actorId, String name, String keyJson, String adminEmail);

    /** Replaces the key and acting admin of the same service account; returns the new credential revision. */
    long replace(ActorId actorId, CredentialId credentialId, long expectedRevision, String name, String keyJson, String adminEmail);
}
