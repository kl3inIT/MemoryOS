package io.memoryos.connector.persistence;

/** Source scope predicates use the pair alias and the actorId parameter. */
final class SourceScopeSql {
    static final String ACTIVE_MANAGER = """
            EXISTS (SELECT 1 FROM iam_group_memberships manager
                JOIN iam_groups managed ON managed.tenant_id = manager.tenant_id
                  AND managed.id = manager.group_id AND managed.system_key IS NULL
                JOIN tenant_memberships member ON member.tenant_id = manager.tenant_id
                  AND member.actor_id = manager.actor_id AND member.status = 'ACTIVE'
                JOIN tenants tenant ON tenant.id = member.tenant_id AND tenant.status = 'ACTIVE'
                JOIN actors actor ON actor.id = member.actor_id AND actor.account_type = 'STANDARD'
                WHERE manager.tenant_id = pair.tenant_id AND manager.actor_id = :actorId
                  AND manager.is_manager = TRUE)
            """;
    static final String OWNER_GROUPLESS = """
            (pair.access_type <> 'PUBLIC' AND pair.created_by_actor_id = :actorId
             AND NOT EXISTS (SELECT 1 FROM source_group_grants owned_grant
                 WHERE owned_grant.tenant_id = pair.tenant_id
                   AND owned_grant.connector_credential_pair_id = pair.id))
            """;
    static final String READ = """
            (pair.access_type = 'PUBLIC' OR %s OR EXISTS (
                SELECT 1 FROM source_group_grants readable_grant
                JOIN iam_group_memberships readable_member
                  ON readable_member.tenant_id = readable_grant.tenant_id
                 AND readable_member.group_id = readable_grant.group_id
                 AND readable_member.actor_id = :actorId
                WHERE readable_grant.tenant_id = pair.tenant_id
                  AND readable_grant.connector_credential_pair_id = pair.id))
            """.formatted(OWNER_GROUPLESS);
    static final String WRITE = """
            (%s AND pair.access_type <> 'PUBLIC' AND (%s OR (
                EXISTS (SELECT 1 FROM source_group_grants any_grant
                    WHERE any_grant.tenant_id = pair.tenant_id
                      AND any_grant.connector_credential_pair_id = pair.id)
                AND NOT EXISTS (
                    SELECT 1 FROM source_group_grants required_grant
                    WHERE required_grant.tenant_id = pair.tenant_id
                      AND required_grant.connector_credential_pair_id = pair.id
                      AND NOT EXISTS (
                        SELECT 1 FROM iam_group_memberships manager
                        JOIN iam_groups managed ON managed.tenant_id = manager.tenant_id
                          AND managed.id = manager.group_id AND managed.system_key IS NULL
                        WHERE manager.tenant_id = required_grant.tenant_id
                          AND manager.group_id = required_grant.group_id
                          AND manager.actor_id = :actorId AND manager.is_manager = TRUE)))))
            """.formatted(ACTIVE_MANAGER, OWNER_GROUPLESS);

    private SourceScopeSql() {}
}
