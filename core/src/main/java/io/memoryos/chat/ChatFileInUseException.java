package io.memoryos.chat;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import java.util.List;
import java.util.UUID;

/**
 * An upload cannot be deleted while a Project or an Agent attaches it. Onyx answers the same situation with
 * 200 and {@code has_associations}; MemoryOS keeps the refused command a conflict and names what holds the
 * file, so the library can say why instead of showing a bare error.
 */
public final class ChatFileInUseException extends BusinessException {
    /** {@code kind} is AGENT or PROJECT. */
    public record Usage(String kind, UUID id, String name) {}

    private final List<Usage> usedBy;

    public ChatFileInUseException(List<Usage> usedBy) {
        super("CHAT_FILE_IN_USE", FailureCategory.CONFLICT,
                "This file is attached to a project or an assistant.", "chat file is attached");
        this.usedBy = List.copyOf(usedBy);
    }

    public List<Usage> usedBy() { return usedBy; }
}
