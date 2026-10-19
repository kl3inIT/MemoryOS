package io.memoryos.iam;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Read-side affordance map for one Group, projected from the same decisions the write guards enforce. It is a
 * browser hint only; every mutation keeps its own guard. Per-target and count invariants (protected owner, own
 * manager scope, last administrator, last Group) are not encoded.
 */
public record GroupPermissions(
        boolean manage,
        boolean manageMembers,
        boolean delete,
        boolean editPermissions,
        boolean manageSources
) {
    public static GroupPermissions of(
            Set<IamCapability> effectiveCapabilities,
            @Nullable GroupSystemKey systemKey,
            boolean managedByActor
    ) {
        boolean systemAdmin = effectiveCapabilities.contains(IamCapability.SYSTEM_ADMIN);
        boolean manageable = effectiveCapabilities.contains(IamCapability.GROUPS_MANAGE) || managedByActor;
        if (systemKey != null) {
            // System Groups keep only their memberships, and only for a full administrator, who also switches Basic's
            // optional grants (MEM-281).
            return new GroupPermissions(false, systemAdmin, false, systemAdmin && systemKey == GroupSystemKey.BASIC,
                    effectiveCapabilities.contains(IamCapability.SOURCES_MANAGE));
        }
        return new GroupPermissions(
                manageable,
                manageable,
                effectiveCapabilities.contains(IamCapability.GROUPS_MANAGE),
                systemAdmin,
                effectiveCapabilities.contains(IamCapability.SOURCES_MANAGE) || managedByActor
        );
    }
}
