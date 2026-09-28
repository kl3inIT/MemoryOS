package io.memoryos.iam;

import io.memoryos.shared.ActorId;
import java.util.Objects;

/** How a browser sign-in was decided; every refusal has already been recorded in the audit stream. */
public sealed interface SignInOutcome {

    /** The Actor is an active member and its profile observation is recorded. */
    record Admitted(ActorId actorId) implements SignInOutcome {
        public Admitted {
            Objects.requireNonNull(actorId, "actorId must not be null");
        }
    }

    /** Not a member and not admitted by trusted JIT or an eligible invitation. */
    record NotAdmitted() implements SignInOutcome {}

    /** The invitation the browser was following, or its activation, could not be used. */
    record InvitationRefused(InvitationFailureReason reason) implements SignInOutcome {
        public InvitationRefused {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
