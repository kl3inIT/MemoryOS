package io.memoryos.chat.persistence;

/**
 * Onyx-equivalent agent authority as SQL predicates over a {@code persona p} row. Every query binds {@code :actor}
 * (the acting Actor) and {@code :agentsManage} (the actor's global {@code AGENTS_MANAGE}); callers have already
 * verified the actor's active Tenant membership. Onyx references: {@code db/persona.py} {@code _add_user_filters},
 * {@code user_can_edit_persona} and the owner checks in {@code persona_sharing.py}.
 */
public final class AgentAccessSql {
    private AgentAccessSql() {}

    private static final String OWNER_GROUP_MEMBER = """
            EXISTS (SELECT 1 FROM iam_group_memberships owner_member
                    WHERE owner_member.tenant_id = p.tenant_id AND owner_member.group_id = p.owner_group_id
                      AND owner_member.actor_id = :actor)""";

    /** Owner Actor, a member of the owner Group, or a global agent manager. Never true for the builtin agent. */
    public static final String OWNS = """
            (p.builtin_key IS NULL AND (:agentsManage OR p.owner_actor_id = :actor OR %s))""".formatted(OWNER_GROUP_MEMBER);

    /** Use: list, snapshot, select, admit a turn. */
    public static final String USES = """
            (p.builtin_key IS NOT NULL OR :agentsManage OR p.owner_actor_id = :actor OR p.is_public OR %s
             OR EXISTS (SELECT 1 FROM persona_user_share user_share
                        WHERE user_share.tenant_id = p.tenant_id AND user_share.persona_id = p.id
                          AND user_share.actor_id = :actor)
             OR EXISTS (SELECT 1 FROM persona_group_share group_share
                        JOIN iam_group_memberships share_member
                          ON share_member.tenant_id = group_share.tenant_id AND share_member.group_id = group_share.group_id
                         AND share_member.actor_id = :actor
                        WHERE group_share.tenant_id = p.tenant_id AND group_share.persona_id = p.id))""".formatted(OWNER_GROUP_MEMBER);

    /**
     * Edit fields, shares and labels. A Group manager edits a private agent only when every share Group is managed
     * by them, as Onyx curators do.
     */
    public static final String EDITS = """
            (:agentsManage OR (p.builtin_key IS NULL AND (
                p.owner_actor_id = :actor OR %s
                OR (p.is_public AND p.public_permission = 'EDITOR')
                OR EXISTS (SELECT 1 FROM persona_user_share user_share
                           WHERE user_share.tenant_id = p.tenant_id AND user_share.persona_id = p.id
                             AND user_share.actor_id = :actor AND user_share.permission = 'EDITOR')
                OR EXISTS (SELECT 1 FROM persona_group_share group_share
                           JOIN iam_group_memberships share_member
                             ON share_member.tenant_id = group_share.tenant_id AND share_member.group_id = group_share.group_id
                            AND share_member.actor_id = :actor
                           WHERE group_share.tenant_id = p.tenant_id AND group_share.persona_id = p.id
                             AND group_share.permission = 'EDITOR')
                OR (NOT p.is_public
                    AND EXISTS (SELECT 1 FROM persona_group_share any_share
                                WHERE any_share.tenant_id = p.tenant_id AND any_share.persona_id = p.id)
                    AND NOT EXISTS (SELECT 1 FROM persona_group_share unmanaged
                                    WHERE unmanaged.tenant_id = p.tenant_id AND unmanaged.persona_id = p.id
                                      AND NOT EXISTS (SELECT 1 FROM iam_group_memberships manager
                                                      WHERE manager.tenant_id = unmanaged.tenant_id
                                                        AND manager.group_id = unmanaged.group_id
                                                        AND manager.actor_id = :actor AND manager.is_manager)))
            )))""".formatted(OWNER_GROUP_MEMBER);

    /** A custom agent without an owner Group whose owner Actor is absent or no longer an active member. */
    public static final String VACANT = """
            (p.builtin_key IS NULL AND p.owner_group_id IS NULL
             AND NOT EXISTS (SELECT 1 FROM tenant_memberships owner_membership
                             WHERE owner_membership.tenant_id = p.tenant_id AND owner_membership.actor_id = p.owner_actor_id
                               AND owner_membership.status = 'ACTIVE'))""";
}
