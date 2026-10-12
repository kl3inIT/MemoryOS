package io.memoryos.meeting;

import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.library.MeetingShelf;
import io.memoryos.library.ShelfMeeting;
import io.memoryos.meeting.persistence.MeetingRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The meetings' answer to the file library: the meetings a person may read, under the same two rules every meeting
 * read applies, the Chat capability {@link MeetingService} requires and {@code MeetingAccessSql.READS}. A person
 * without the capability reads no meeting here, as the meeting page itself would refuse them.
 */
@Component
public class LibraryMeetingShelf implements MeetingShelf {
    private final IamAuthorization authorization;
    private final MeetingRepository meetings;

    public LibraryMeetingShelf(IamAuthorization authorization, MeetingRepository meetings) {
        this.authorization = authorization;
        this.meetings = meetings;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShelfMeeting> readable(TenantId tenant, ActorId actor, int limit) {
        if (!reads(actor)) return List.of();
        return meetings.shelf(tenant.value(), actor.value(), null, Math.min(limit, MeetingService.MAX_LIST));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShelfMeeting> find(TenantId tenant, ActorId actor, Collection<UUID> ids) {
        if (ids.isEmpty() || !reads(actor)) return List.of();
        return meetings.shelf(tenant.value(), actor.value(), ids, ids.size());
    }

    private boolean reads(ActorId actor) {
        return authorization.effectiveCapabilities(actor).contains(IamCapability.CHAT_WRITE);
    }
}
