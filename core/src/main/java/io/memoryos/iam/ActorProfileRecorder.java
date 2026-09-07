package io.memoryos.iam;

import org.jspecify.annotations.Nullable;

public interface ActorProfileRecorder {

    void record(
            ActorId actorId,
            ExternalIdentity identity,
            @Nullable String displayName,
            @Nullable String email,
            boolean emailVerified
    );
}
