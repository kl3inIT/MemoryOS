package io.memoryos.iam;

import io.memoryos.shared.ActorId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** An active Tenant member as a sharing picker shows them; the profile may not exist yet. */
public record PrincipalPerson(ActorId actorId, @Nullable String name, @Nullable String email) {
    public PrincipalPerson {
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
