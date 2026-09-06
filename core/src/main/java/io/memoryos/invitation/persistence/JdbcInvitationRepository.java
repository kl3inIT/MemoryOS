package io.memoryos.invitation.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.invitation.InvitationQuery;
import io.memoryos.invitation.InvitationSort;
import io.memoryos.invitation.InvitationStatus;
import io.memoryos.invitation.InvitationView;
import io.memoryos.tenant.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcInvitationRepository {

    private static final String EXPIRE_PENDING_EMAIL = """
            UPDATE tenant_invitations
            SET status = 'EXPIRED',
                open_email_key = NULL,
                updated_at = :now
            WHERE tenant_id = :tenantId
              AND open_email_key = :email
              AND status = 'PENDING'
              AND expires_at <= :now
            """;

    private static final String EXPIRE_PENDING_TENANT = """
            UPDATE tenant_invitations
            SET status = 'EXPIRED',
                open_email_key = NULL,
                updated_at = :now
            WHERE tenant_id = :tenantId
              AND status = 'PENDING'
              AND expires_at <= :now
            """;

    private static final String INSERT_INVITATION = """
            INSERT INTO tenant_invitations (
                id,
                tenant_id,
                normalized_email,
                open_email_key,
                secret_digest,
                status,
                created_by_actor_id,
                created_at,
                updated_at,
                expires_at
            )
            VALUES (
                :id,
                :tenantId,
                :email,
                :email,
                :digest,
                'PENDING',
                :actorId,
                :now,
                :now,
                :expiresAt
            )
            """;

    private static final String COUNT_INVITATIONS = """
            SELECT count(*)
            FROM tenant_invitations invitation
            WHERE invitation.tenant_id = :tenantId
            """;

    private static final String SELECT_INVITATION_PAGE = """
            SELECT invitation.*
            FROM tenant_invitations invitation
            WHERE invitation.tenant_id = :tenantId
            """;

    private static final String SELECT_INVITATION = """
            SELECT invitation.*
            FROM tenant_invitations invitation
            WHERE invitation.tenant_id = :tenantId
              AND invitation.id = :invitationId
            """;

    private static final String LOCK_INVITATION = """
            SELECT invitation.*
            FROM tenant_invitations invitation
            WHERE invitation.tenant_id = :tenantId
              AND invitation.id = :invitationId
            FOR UPDATE
            """;

    private static final String SELECT_INVITATION_BY_DIGEST = """
            SELECT invitation.*
            FROM tenant_invitations invitation
            WHERE invitation.secret_digest = :digest
            """;

    private static final String LOCK_PENDING_INVITATION_BY_EMAIL = """
            SELECT invitation.*
            FROM tenant_invitations invitation
            WHERE invitation.open_email_key = :email
              AND invitation.status = 'PENDING'
              AND invitation.expires_at > :now
            FOR UPDATE
            """;

    private static final String ROTATE_INVITATION = """
            UPDATE tenant_invitations
            SET secret_digest = :digest,
                expires_at = :expiresAt,
                updated_at = :now
            WHERE tenant_id = :tenantId
              AND id = :invitationId
              AND status = 'PENDING'
            """;

    private static final String REVOKE_INVITATION = """
            UPDATE tenant_invitations
            SET status = 'REVOKED',
                open_email_key = NULL,
                revoked_by_actor_id = :actorId,
                revoked_at = :now,
                updated_at = :now
            WHERE tenant_id = :tenantId
              AND id = :invitationId
              AND status = 'PENDING'
            """;

    private static final String ACCEPT_INVITATION = """
            UPDATE tenant_invitations
            SET status = 'ACCEPTED',
                open_email_key = NULL,
                accepted_by_actor_id = :actorId,
                accepted_at = :now,
                updated_at = :now
            WHERE tenant_id = :tenantId
              AND id = :invitationId
              AND status = 'PENDING'
            """;

    private final JdbcClient jdbcClient;

    public JdbcInvitationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void expirePending(TenantId tenantId, String email, Instant now) {
        jdbcClient.sql(EXPIRE_PENDING_EMAIL)
                .param("tenantId", tenantId.value())
                .param("email", email)
                .param("now", sqlTime(now))
                .update();
    }

    public void expirePending(TenantId tenantId, Instant now) {
        jdbcClient.sql(EXPIRE_PENDING_TENANT)
                .param("tenantId", tenantId.value())
                .param("now", sqlTime(now))
                .update();
    }

    public int insert(
            UUID invitationId,
            TenantId tenantId,
            String email,
            String digest,
            ActorId creatorActorId,
            Instant now,
            Instant expiresAt
    ) {
        return jdbcClient.sql(INSERT_INVITATION)
                .param("id", invitationId)
                .param("tenantId", tenantId.value())
                .param("email", email)
                .param("digest", digest)
                .param("actorId", creatorActorId.value())
                .param("now", sqlTime(now))
                .param("expiresAt", sqlTime(expiresAt))
                .update();
    }

    public long count(TenantId tenantId, InvitationQuery query) {
        var statement = jdbcClient.sql(COUNT_INVITATIONS + filterClause(query))
                .param("tenantId", tenantId.value());
        if (query.status() != null) {
            statement = statement.param("status", query.status().name());
        }
        if (query.email() != null) {
            statement = statement.param("email", query.email());
        }
        return statement.query(Long.class).single();
    }

    public List<InvitationView> findPage(TenantId tenantId, InvitationQuery query) {
        var statement = jdbcClient.sql(
                        SELECT_INVITATION_PAGE
                                + filterClause(query)
                                + orderClause(query.sort())
                                + " LIMIT :size OFFSET :offset"
                )
                .param("tenantId", tenantId.value())
                .param("size", query.size())
                .param("offset", query.offset());
        if (query.status() != null) {
            statement = statement.param("status", query.status().name());
        }
        if (query.email() != null) {
            statement = statement.param("email", query.email());
        }
        return statement.query((resultSet, ignored) -> invitation(resultSet)).list();
    }

    public Optional<InvitationView> find(TenantId tenantId, UUID invitationId) {
        return jdbcClient.sql(SELECT_INVITATION)
                .param("tenantId", tenantId.value())
                .param("invitationId", invitationId)
                .query((resultSet, ignored) -> invitation(resultSet))
                .optional();
    }

    public Optional<InvitationView> findLocked(TenantId tenantId, UUID invitationId) {
        return jdbcClient.sql(LOCK_INVITATION)
                .param("tenantId", tenantId.value())
                .param("invitationId", invitationId)
                .query((resultSet, ignored) -> invitation(resultSet))
                .optional();
    }

    public Optional<InvitationView> findByDigest(String digest) {
        return jdbcClient.sql(SELECT_INVITATION_BY_DIGEST)
                .param("digest", digest)
                .query((resultSet, ignored) -> invitation(resultSet))
                .optional();
    }

    public List<InvitationView> findLockedPendingByEmail(String email, Instant now) {
        return jdbcClient.sql(LOCK_PENDING_INVITATION_BY_EMAIL)
                .param("email", email)
                .param("now", sqlTime(now))
                .query((resultSet, ignored) -> invitation(resultSet))
                .list();
    }

    public int rotate(InvitationView invitation, String digest, Instant expiresAt, Instant now) {
        return jdbcClient.sql(ROTATE_INVITATION)
                .param("tenantId", invitation.tenantId().value())
                .param("invitationId", invitation.id())
                .param("digest", digest)
                .param("expiresAt", sqlTime(expiresAt))
                .param("now", sqlTime(now))
                .update();
    }

    public int revoke(InvitationView invitation, ActorId actorId, Instant now) {
        return jdbcClient.sql(REVOKE_INVITATION)
                .param("tenantId", invitation.tenantId().value())
                .param("invitationId", invitation.id())
                .param("actorId", actorId.value())
                .param("now", sqlTime(now))
                .update();
    }

    public int accept(InvitationView invitation, ActorId actorId, Instant now) {
        return jdbcClient.sql(ACCEPT_INVITATION)
                .param("tenantId", invitation.tenantId().value())
                .param("invitationId", invitation.id())
                .param("actorId", actorId.value())
                .param("now", sqlTime(now))
                .update();
    }

    private static String filterClause(InvitationQuery query) {
        StringBuilder filters = new StringBuilder();
        if (query.status() != null) {
            filters.append(" AND invitation.status = :status");
        }
        if (query.email() != null) {
            filters.append(" AND POSITION(:email IN invitation.normalized_email) > 0");
        }
        return filters.toString();
    }

    private static String orderClause(InvitationSort sort) {
        return switch (sort) {
            case CREATED_AT_DESC -> " ORDER BY invitation.created_at DESC, invitation.id ASC";
            case CREATED_AT_ASC -> " ORDER BY invitation.created_at ASC, invitation.id ASC";
            case EMAIL_ASC -> " ORDER BY invitation.normalized_email ASC, invitation.id ASC";
            case EMAIL_DESC -> " ORDER BY invitation.normalized_email DESC, invitation.id ASC";
        };
    }

    private static InvitationView invitation(ResultSet resultSet) throws SQLException {
        UUID acceptedActorId = resultSet.getObject("accepted_by_actor_id", UUID.class);
        return new InvitationView(
                resultSet.getObject("id", UUID.class),
                new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                resultSet.getString("normalized_email"),
                InvitationStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                acceptedActorId == null ? null : new ActorId(acceptedActorId),
                instant(resultSet, "accepted_at"),
                instant(resultSet, "revoked_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime sqlTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
