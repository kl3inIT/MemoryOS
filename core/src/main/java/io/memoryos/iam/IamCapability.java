package io.memoryos.iam;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public enum IamCapability {
    IAM_ADMIN,
    USERS_MANAGE,
    GROUPS_READ,
    GROUPS_MANAGE,
    SOURCES_READ,
    SOURCES_MANAGE,
    SOURCES_DELETE;
    private static final IamCapability[] VALUES = values();
    private static final Set<IamCapability> IAM_ADMIN_IMPLICATIONS = Set.of(
            USERS_MANAGE,
            GROUPS_READ,
            GROUPS_MANAGE,
            SOURCES_READ,
            SOURCES_MANAGE,
            SOURCES_DELETE
    );
    private static final Set<IamCapability> GROUPS_MANAGE_IMPLICATIONS = Set.of(GROUPS_READ);
    private static final Set<IamCapability> SOURCES_MANAGE_IMPLICATIONS = Set.of(SOURCES_READ);
    private static final Set<IamCapability> SOURCES_DELETE_IMPLICATIONS = Set.of(SOURCES_READ);


    public Set<IamCapability> impliedCapabilities() {
        return switch (this) {
            case IAM_ADMIN -> IAM_ADMIN_IMPLICATIONS;
            case GROUPS_MANAGE -> GROUPS_MANAGE_IMPLICATIONS;
            case SOURCES_MANAGE -> SOURCES_MANAGE_IMPLICATIONS;
            case SOURCES_DELETE -> SOURCES_DELETE_IMPLICATIONS;
            default -> Set.of();
        };
    }

    public static Set<IamCapability> expand(Collection<IamCapability> explicitCapabilities) {
        Objects.requireNonNull(explicitCapabilities, "explicitCapabilities must not be null");
        if (explicitCapabilities.isEmpty()) {
            return Set.of();
        }

        EnumSet<IamCapability> expanded = EnumSet.noneOf(IamCapability.class);
        explicitCapabilities.forEach(capability -> expanded.add(
                Objects.requireNonNull(capability, "capability must not be null")
        ));
        boolean changed;
        do {
            changed = false;
            for (IamCapability capability : VALUES) {
                if (expanded.contains(capability)) {
                    changed |= expanded.addAll(capability.impliedCapabilities());
                }
            }
        } while (changed);
        return Collections.unmodifiableSet(expanded);
    }
}
