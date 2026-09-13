package io.memoryos.connector;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Index-time access of one document, stored on every chunk. {@code everyone} is true when any eligible mapped
 * Source is PUBLIC; tokens name the Groups granted to its restricted Sources. A reader's tokens are resolved from
 * current memberships per request, so membership changes never require an index write.
 */
public record DocumentAccess(boolean everyone, Set<String> tokens) {
    public DocumentAccess {
        tokens = Set.copyOf(tokens);
    }

    public static String group(UUID groupId) {
        return "group:" + groupId;
    }

    public List<String> sortedTokens() {
        return tokens.stream().sorted().toList();
    }
}
