package io.memoryos.iam.group;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public enum IamCapability {
    SYSTEM_ADMIN,
    SYSTEM_BASIC,
    SEARCH_READ,
    CHAT_READ,
    CHAT_WRITE,
    IMAGE_GENERATE,
    LLM_GATEWAY_USE,
    USERS_MANAGE,
    GROUPS_READ,
    GROUPS_MANAGE,
    SOURCES_READ,
    SOURCES_MANAGE,
    SOURCES_DELETE,
    MODELS_MANAGE;
    private static final IamCapability[] VALUES = values();
    private static final Set<IamCapability> ALL_CAPABILITIES =
            Collections.unmodifiableSet(EnumSet.allOf(IamCapability.class));
    private static final Set<IamCapability> SYSTEM_ADMIN_IMPLICATIONS =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(SYSTEM_ADMIN)));
    private static final Set<IamCapability> SYSTEM_BASIC_IMPLICATIONS =
            Set.of(SEARCH_READ, CHAT_READ, CHAT_WRITE, IMAGE_GENERATE, LLM_GATEWAY_USE);
    private static final Set<IamCapability> CHAT_WRITE_IMPLICATIONS = Set.of(CHAT_READ);
    private static final Set<IamCapability> GROUPS_MANAGE_IMPLICATIONS = Set.of(GROUPS_READ);
    private static final Set<IamCapability> SOURCES_MANAGE_IMPLICATIONS = Set.of(SOURCES_READ, SOURCES_DELETE);
    private static final Set<IamCapability> SOURCES_DELETE_IMPLICATIONS = Set.of(SOURCES_READ);


    public boolean isOrdinaryGrant() {
        return switch (this) {
            case USERS_MANAGE, GROUPS_MANAGE, SOURCES_MANAGE, MODELS_MANAGE -> true;
            default -> false;
        };
    }

    public Set<IamCapability> impliedCapabilities() {
        return switch (this) {
            case SYSTEM_ADMIN -> SYSTEM_ADMIN_IMPLICATIONS;
            case SYSTEM_BASIC -> SYSTEM_BASIC_IMPLICATIONS;
            case CHAT_WRITE -> CHAT_WRITE_IMPLICATIONS;
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
        if (expanded.contains(SYSTEM_ADMIN)) {
            return ALL_CAPABILITIES;
        }
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
