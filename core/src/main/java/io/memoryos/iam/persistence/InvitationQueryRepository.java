package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.InvitationQuery;
import io.memoryos.iam.InvitationSort;
import io.memoryos.iam.InvitationStatus;
import io.memoryos.iam.InvitationView;
import io.memoryos.iam.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class InvitationQueryRepository {

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

    private final JdbcClient jdbcClient;

    public InvitationQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
}
