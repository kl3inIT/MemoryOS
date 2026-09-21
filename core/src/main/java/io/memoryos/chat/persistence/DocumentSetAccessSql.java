package io.memoryos.chat.persistence;

/** SQL predicates over a {@code document_set d} row. Callers bind {@code :actor} and {@code :agentsManage}. */
public final class DocumentSetAccessSql {
    private DocumentSetAccessSql() {}

    /** Public sets, owners, direct/Group viewers, and agent managers can use a non-deleted Document Set. */
    public static final String USES = """
            (:agentsManage OR d.is_public OR d.owner_actor_id = :actor
             OR EXISTS (SELECT 1 FROM document_set_user_share user_share
                        WHERE user_share.tenant_id = d.tenant_id AND user_share.document_set_id = d.id
                          AND user_share.actor_id = :actor)
             OR EXISTS (SELECT 1 FROM document_set_group_share group_share
                        JOIN iam_group_memberships share_member
                          ON share_member.tenant_id = group_share.tenant_id AND share_member.group_id = group_share.group_id
                         AND share_member.actor_id = :actor
                        WHERE group_share.tenant_id = d.tenant_id AND group_share.document_set_id = d.id))
            """;

    /** Sets have no editor share role: only their owner or a global agent manager can change them. */
    public static final String EDITS = "(:agentsManage OR d.owner_actor_id = :actor)";
}
