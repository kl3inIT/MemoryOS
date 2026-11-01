package io.memoryos.iam;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Optional;

/** Finds the person of a Tenant another system names by an e-mail address, as a channel names its sender. */
public interface MemberLookup {

    /**
     * The one active person of the Tenant whose e-mail, verified by their identity provider, equals the given one
     * without regard to case or surrounding blanks. Empty when nobody matches and when several do: an address two
     * accounts hold names neither.
     */
    Optional<ActorId> activeMemberByVerifiedEmail(TenantId tenantId, String email);
}
