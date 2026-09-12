package io.memoryos.iam.persistence;

import java.util.UUID;

/** Refresh an already-managed Actor before mutation, within the application's transaction. */
public interface ActorRefresh {
    ActorEntity refreshForUpdate(UUID id);
}
