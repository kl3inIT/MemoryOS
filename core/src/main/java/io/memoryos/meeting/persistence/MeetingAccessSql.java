package io.memoryos.meeting.persistence;

/**
 * Who may read a meeting, as a SQL predicate over a {@code meeting m} row. Every query binds {@code :actor}; the
 * caller has already checked that the actor is an active member of the Tenant.
 *
 * <p>There are two authorities and no more. The owner recorded the meeting and may do anything with it. Everyone the
 * owner named — a person, or a Group they belong to — may read it. Nothing here grants an edit, so a reader can
 * never rename a speaker, change the notes or delete the recording, and no permission column is needed.
 */
public final class MeetingAccessSql {
    private MeetingAccessSql() {}

    /** Record, rename a speaker, write notes, end, rerun the minutes, share, delete. */
    public static final String OWNS = "m.owner_actor_id = :actor";

    /** Read the transcript, the minutes and the biên bản, while the meeting runs and after it ends. */
    public static final String READS = """
            (m.owner_actor_id = :actor
             OR EXISTS (SELECT 1 FROM meeting_user_share user_share
                        WHERE user_share.tenant_id = m.tenant_id AND user_share.meeting_id = m.id
                          AND user_share.actor_id = :actor)
             OR EXISTS (SELECT 1 FROM meeting_group_share group_share
                        JOIN iam_group_memberships share_member
                          ON share_member.tenant_id = group_share.tenant_id AND share_member.group_id = group_share.group_id
                         AND share_member.actor_id = :actor
                        WHERE group_share.tenant_id = m.tenant_id AND group_share.meeting_id = m.id))""";
}
