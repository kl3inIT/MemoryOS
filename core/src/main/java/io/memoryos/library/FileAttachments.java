package io.memoryos.library;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What outside the library attaches an upload: Chat's agents (as a knowledge file or as the avatar) and Projects.
 * Chat implements it. The library asks it who may read an upload besides its owner, and what keeps an upload from
 * being deleted; it never reads those rows itself.
 */
public interface FileAttachments {
    /**
     * The uploads among {@code files} that {@code actor} may read without owning them: those a non-deleted agent they
     * can use attaches or shows as its avatar, as Onyx re-syncs an agent's files to the people it is shared with.
     * Managing agents grants no extra file authority.
     */
    Set<UUID> readableThroughAgents(TenantId tenant, ActorId actor, Collection<UUID> files);

    /** Everything that attaches one of {@code files}, ordered by kind and name, in one query for a whole page. */
    List<Holder> holders(TenantId tenant, Collection<UUID> files);

    /** One agent or Project attaching one upload. */
    record Holder(UUID fileId, ChatLibraryFile.Usage.Kind kind, UUID id, String name) {}
}
