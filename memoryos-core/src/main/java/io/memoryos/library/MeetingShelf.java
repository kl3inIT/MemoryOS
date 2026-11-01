package io.memoryos.library;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The meetings a person may read, as the library lists them. Meetings implement it, as Chat implements
 * {@link FileAttachments}: the library never reads a meeting's rows, so who may read one stays the meetings'
 * own rule, applied at every read.
 */
public interface MeetingShelf {
    /** The meetings {@code actor} owns or may read through a share, newest first, at most {@code limit}. */
    List<ShelfMeeting> readable(TenantId tenant, ActorId actor, int limit);

    /** The meetings among {@code ids} that {@code actor} may read now, in any order; the others are absent. */
    List<ShelfMeeting> find(TenantId tenant, ActorId actor, Collection<UUID> ids);
}
