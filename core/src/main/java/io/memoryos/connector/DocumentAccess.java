package io.memoryos.connector;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Index-time access of one document, stored on every chunk. {@code everyone} is true when any eligible mapped
 * Source is PUBLIC or an Auto Sync file is shared with anyone; tokens name the Groups of its PRIVATE Sources and the
 * Google users and domains granted by its SYNC Sources. A reader's tokens are resolved from current memberships and
 * the verified login email per request, so membership changes never require an index write.
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
