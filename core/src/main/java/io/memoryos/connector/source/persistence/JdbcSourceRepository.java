package io.memoryos.connector.source.persistence;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceAccessChanged;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceType;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceRepository {

    private final JdbcClient jdbcClient;
    private final ApplicationEventPublisher events;

    public JdbcSourceRepository(JdbcClient jdbcClient, ApplicationEventPublisher events) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    public SourcePair createFileSource(TenantId tenantId, ActorId actorId, String name, SourceAccess access,
            @Nullable ActorId managerActorId) {
        UUID credentialId = ensureNoAuthCredential(tenantId);
        UUID connectorId = UUID.randomUUID();
        SourceId sourceId = new SourceId(UUID.randomUUID());
        jdbcClient.sql("""
                        INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                        VALUES (:id, :tenantId, :name, 'FILE', 'ACTIVE')
                        """)
                .param("id", connectorId)
                .param("tenantId", tenantId.value())
                .param("name", name)
                .update();
        jdbcClient.sql("""
                        INSERT INTO connector_credential_pairs (
                            id, tenant_id, connector_id, credential_id, access_type, status,
                            created_by_actor_id, manager_actor_id
                        ) VALUES (
                            :id, :tenantId, :connectorId, :credentialId, :access, 'NOT_STARTED',
                            :actorId, :managerActorId
                        )
                        """)
                .param("id", sourceId.value())
                .param("tenantId", tenantId.value())
                .param("connectorId", connectorId)
                .param("credentialId", credentialId)
                .param("access", access.name())
                .param("actorId", actorId.value())
                .param("managerActorId", managerActorId == null ? null : managerActorId.value())
                .update();
        return new SourcePair(connectorId, sourceId, SourceStatus.NOT_STARTED, 0);
    }

    /** The connector kind behind a Source, which decides whose synchronization runs it. */
    public SourceType type(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT c.connector_type FROM connector_credential_pairs p
                        JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
                        WHERE p.tenant_id = :tenantId AND p.id = :sourceId
                        """)
                .param("tenantId", tenantId.value()).param("sourceId", sourceId.value())
                .query(String.class).optional().map(SourceType::valueOf)
                .orElseThrow(SourceException::notFound);
    }

    public SourcePair lock(TenantId tenantId, SourceId sourceId) {
        jdbcClient.sql("SELECT id FROM tenants WHERE id = :tenant FOR SHARE")
                .param("tenant", tenantId.value()).query(UUID.class).optional();
        jdbcClient.sql("""
                SELECT credential.id FROM credentials credential
                WHERE credential.tenant_id = :tenant
                  AND credential.id = (SELECT credential_id FROM connector_credential_pairs
                    WHERE tenant_id = :tenant AND id = :source)
                FOR UPDATE
                """).param("tenant", tenantId.value()).param("source", sourceId.value())
                .query(UUID.class).optional();
        return jdbcClient.sql("""
                        SELECT pair.connector_id, pair.status, pair.pair_sequence
                        FROM connector_credential_pairs pair
                        JOIN connectors connector ON connector.tenant_id = pair.tenant_id AND connector.id = pair.connector_id
                        WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.value()).param("pairId", sourceId.value())
                .query((row, _) -> new SourcePair(row.getObject("connector_id", UUID.class), sourceId,
                        SourceStatus.valueOf(row.getString("status")), row.getLong("pair_sequence")))
                .optional().orElseThrow(SourceException::notFound);
    }

    public SourcePair lockAuthorized(
            TenantId tenantId,
            ActorId actorId,
            SourceId sourceId,
            boolean globalAccess
    ) {
        jdbcClient.sql("SELECT id FROM tenants WHERE id = :tenant FOR SHARE")
                .param("tenant", tenantId.value()).query(UUID.class).optional();
        jdbcClient.sql("""
                SELECT credential.id FROM credentials credential
                WHERE credential.tenant_id = :tenant
                  AND credential.id = (SELECT credential_id FROM connector_credential_pairs
                    WHERE tenant_id = :tenant AND id = :source)
                FOR UPDATE
                """).param("tenant", tenantId.value()).param("source", sourceId.value())
                .query(UUID.class).optional();
        return jdbcClient.sql("""
                        SELECT pair.connector_id, pair.status, pair.pair_sequence
                        FROM connector_credential_pairs pair
                        JOIN connectors connector
                          ON connector.tenant_id = pair.tenant_id
                         AND connector.id = pair.connector_id
                        WHERE pair.tenant_id = :tenantId
                          AND pair.id = :pairId
                          AND (:globalAccess OR %s)
                        FOR UPDATE
                        """.formatted(SourceScopeSql.WRITE))
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("pairId", sourceId.value())
                .param("globalAccess", globalAccess)
                .query((resultSet, ignored) -> new SourcePair(
                        resultSet.getObject("connector_id", UUID.class),
                        sourceId,
                        SourceStatus.valueOf(resultSet.getString("status")),
                        resultSet.getLong("pair_sequence")
                ))
                .optional()
                .orElseThrow(SourceException::notFound);
    }

    public void requireAuthorized(TenantId tenantId, ActorId actorId, SourceId sourceId,
            boolean globalAccess, boolean write) {
        boolean found = jdbcClient.sql("""
                SELECT EXISTS (SELECT 1 FROM connector_credential_pairs pair
                    WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
                      AND (:globalAccess OR %s))
                """.formatted(write ? SourceScopeSql.WRITE : SourceScopeSql.READ))
                .param("tenantId", tenantId.value()).param("pairId", sourceId.value())
                .param("actorId", actorId.value()).param("globalAccess", globalAccess)
                .query(Boolean.class).single();
        if (!found) throw SourceException.notFound();
    }

    /** Records the Actor who may attach this Source to Groups, or clears it so only global authority remains. */
    public void assignManager(TenantId tenantId, SourceId sourceId, @Nullable ActorId managerActorId) {
        int updated = jdbcClient.sql("""
                UPDATE connector_credential_pairs
                SET manager_actor_id = :managerActorId, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenantId AND id = :pairId
                """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("managerActorId", managerActorId == null ? null : managerActorId.value())
                .update();
        if (updated != 1) throw SourceException.notFound();
    }

    public void requireCreatorGroupless(TenantId tenantId, ActorId actorId, SourceId sourceId) {
        boolean found = jdbcClient.sql("""
                SELECT EXISTS (SELECT 1 FROM connector_credential_pairs pair
                    WHERE pair.tenant_id = :tenantId AND pair.id = :pairId AND %s)
                """.formatted(SourceScopeSql.OWNER_GROUPLESS))
                .param("tenantId", tenantId.value()).param("pairId", sourceId.value())
                .param("actorId", actorId.value()).query(Boolean.class).single();
        if (!found) throw SourceException.notFound();
    }

    public void rename(TenantId tenantId, SourcePair pair, String name) {
        jdbcClient.sql("""
                UPDATE connectors SET name = :name, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenantId AND id = :connectorId
                """).param("tenantId", tenantId.value()).param("connectorId", pair.connectorId())
                .param("name", name).update();
    }

    /** FILE and Google Drive Sources change access; only Google Drive has provider permissions for SYNC. */
    public void updateAccess(TenantId tenantId, SourceId sourceId, SourceAccess access) {
        int updated = jdbcClient.sql("""
                UPDATE connector_credential_pairs pair
                SET access_type = :access, updated_at = CURRENT_TIMESTAMP
                WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
                  AND EXISTS (SELECT 1 FROM connectors connector
                    WHERE connector.tenant_id = pair.tenant_id AND connector.id = pair.connector_id
                      AND (connector.connector_type = 'GOOGLE_DRIVE'
                        OR (connector.connector_type = 'FILE' AND :access <> 'SYNC')))
                """).param("tenantId", tenantId.value()).param("pairId", sourceId.value())
                .param("access", access.name()).update();
        if (updated != 1) throw SourceException.conflict("Auto Sync requires a Google Drive source");
        events.publishEvent(new SourceAccessChanged(tenantId, sourceId));
    }

    public boolean ownsCleanup(TenantId tenantId, ActorId actorId, SourceOperationId operationId) {
        return jdbcClient.sql("""
                SELECT EXISTS (SELECT 1 FROM connector_cleanup_attempts
                    WHERE tenant_id = :tenantId AND id = :operationId AND scope_owner_actor_id = :actorId)
                """).param("tenantId", tenantId.value()).param("actorId", actorId.value())
                .param("operationId", operationId.value()).query(Boolean.class).single();
    }

    public void markDeleting(TenantId tenantId, SourcePair pair) {
        jdbcClient.sql("""
                        UPDATE connectors SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :connectorId
                        """)
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", pair.sourceId().value())
                .update();
    }

    public Optional<SourceOperationView> findCleanup(
            TenantId tenantId,
            SourceOperationType type,
            String targetKey
    ) {
        return jdbcClient.sql("""
                        SELECT id, operation, status, created_at, completed_at, error_code
                        FROM connector_cleanup_attempts
                        WHERE tenant_id = :tenantId
                          AND operation = :operation AND target_key = :targetKey
                        """)
                .param("tenantId", tenantId.value())
                .param("operation", type.name())
                .param("targetKey", targetKey)
                .query(JdbcSourceRepository::cleanupOperation)
                .optional();
    }

    public SourceOperationView createCleanup(
            SourceOperationId operationId,
            TenantId tenantId,
            SourceOperationType type,
            String targetKey,
            SourceId sourceId,
            @Nullable SourceItemId itemId,
            @Nullable ActorId scopeOwner
    ) {
        var trace = SourceOperationTraceContext.current();
        jdbcClient.sql("""
                        INSERT INTO connector_cleanup_attempts (
                            id, tenant_id, operation, target_key,
                            target_pair_id, target_item_id, status, origin_trace_id, origin_span_id, scope_owner_actor_id
                        ) VALUES (
                            :id, :tenantId, :operation, :targetKey,
                            :pairId, :itemId, 'NOT_STARTED', :originTraceId, :originSpanId, :scopeOwner
                        )
                        """)
                .param("id", operationId.value())
                .param("tenantId", tenantId.value())
                .param("operation", type.name())
                .param("targetKey", targetKey)
                .param("pairId", sourceId.value())
                .param("itemId", itemId == null ? null : itemId.value())
                .param("scopeOwner", scopeOwner == null ? null : scopeOwner.value())
                .param("originTraceId", trace == null ? null : trace.traceId())
                .param("originSpanId", trace == null ? null : trace.spanId())
                .update();
        return findCleanupById(tenantId, operationId).orElseThrow();
    }

    private Optional<SourceOperationView> findCleanupById(
            TenantId tenantId,
            SourceOperationId operationId
    ) {
        return jdbcClient.sql("""
                        SELECT id, operation, status, created_at, completed_at, error_code
                        FROM connector_cleanup_attempts
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.value())
                .param("id", operationId.value())
                .query(JdbcSourceRepository::cleanupOperation)
                .optional();
    }

    public void supersedeItemCleanups(TenantId tenantId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET status = 'SUPERSEDED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND target_pair_id = :pairId
                          AND operation = 'REMOVE_ITEM'
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    /**
     * Re-derives the pair's state from each item's latest attempt and current retrieval mappings.
     * A pair that is DELETING keeps that status; {@code indexSucceeded} also stamps {@code last_succeeded_at}.
     */
    public void recomputeStatus(TenantId tenantId, SourceId sourceId, boolean indexSucceeded) {
        jdbcClient.sql("""
                        WITH current_attempts AS (
                            SELECT DISTINCT ON (attempt.connector_item_id) attempt.status, attempt.error_code, attempt.pair_sequence
                            FROM index_attempts attempt
                            JOIN connector_items item
                              ON item.tenant_id = attempt.tenant_id AND item.id = attempt.connector_item_id
                             AND item.current_version_id = attempt.connector_item_version_id
                             AND item.status <> 'DELETING'
                            WHERE attempt.tenant_id = :tenantId
                              AND attempt.connector_credential_pair_id = :pairId
                            ORDER BY attempt.connector_item_id, attempt.pair_sequence DESC
                        )
                        UPDATE connector_credential_pairs
                        SET document_count = (
                                SELECT COUNT(*) FROM documents_by_connector_credential_pair mapping
                                WHERE mapping.tenant_id = :tenantId
                                  AND mapping.connector_credential_pair_id = :pairId
                                  AND mapping.retrieval_eligible = TRUE
                            ),
                            error_code = (
                                SELECT error_code FROM current_attempts
                                WHERE status = 'FAILED'
                                ORDER BY pair_sequence DESC
                                LIMIT 1
                            ),
                            status = CASE
                                WHEN status IN ('DELETING', 'PAUSED') THEN status
                                WHEN EXISTS (
                                    SELECT 1 FROM current_attempts
                                    WHERE status IN ('NOT_STARTED', 'IN_PROGRESS')
                                ) THEN 'INDEXING'
                                WHEN EXISTS (
                                    SELECT 1 FROM documents_by_connector_credential_pair mapping
                                    WHERE mapping.tenant_id = :tenantId
                                      AND mapping.connector_credential_pair_id = :pairId
                                      AND mapping.retrieval_eligible = TRUE
                                ) THEN 'ACTIVE'
                                WHEN EXISTS (
                                    SELECT 1 FROM current_attempts WHERE status = 'FAILED'
                                ) THEN 'FAILED'
                                ELSE 'NOT_STARTED'
                            END,
                            last_succeeded_at = CASE
                                WHEN :indexSucceeded THEN CURRENT_TIMESTAMP
                                ELSE last_succeeded_at
                            END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("indexSucceeded", indexSucceeded)
                .update();
    }

    /** Records durable pause intent; returns false when the pair is not in a pausable state. */
    public boolean setPaused(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId AND status <> 'DELETING'
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update() == 1;
    }

    /** Clears pause intent so {@link #recomputeStatus} can re-derive the operational status. */
    public boolean clearPaused(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET status = 'NOT_STARTED', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId AND status = 'PAUSED'
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update() == 1;
    }

    public boolean lockActiveTenant(TenantId tenantId) {
        return jdbcClient.sql("SELECT id FROM tenants WHERE id = :tenant AND status = 'ACTIVE' FOR SHARE")
                .param("tenant", tenantId.value()).query(UUID.class).optional().isPresent();
    }
    private UUID ensureNoAuthCredential(TenantId tenantId) {
        Optional<UUID> existing = jdbcClient.sql("""
                        SELECT id FROM credentials
                        WHERE tenant_id = :tenantId AND credential_kind = 'NO_AUTH'
                        """)
                .param("tenantId", tenantId.value())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID credentialId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO credentials (id, tenant_id, name, credential_kind, status)
                        VALUES (:id, :tenantId, 'No authentication', 'NO_AUTH', 'ACTIVE')
                        """)
                .param("id", credentialId)
                .param("tenantId", tenantId.value())
                .update();
        return credentialId;
    }

    private static SourceOperationView cleanupOperation(ResultSet resultSet, int ignored) throws SQLException {
        return new SourceOperationView(
                new SourceOperationId(resultSet.getObject("id", UUID.class)),
                SourceOperationType.valueOf(resultSet.getString("operation")),
                operationStatus(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                instant(resultSet, "completed_at"),
                resultSet.getString("error_code")
        );
    }

    public static SourceOperationStatus operationStatus(String value) {
        return switch (value) {
            case "NOT_STARTED" -> SourceOperationStatus.NOT_STARTED;
            case "IN_PROGRESS" -> SourceOperationStatus.IN_PROGRESS;
            // A run whose failures stayed isolated to single items still completed; its errors are in run history.
            case "SUCCEEDED", "COMPLETED_WITH_ERRORS" -> SourceOperationStatus.SUCCEEDED;
            case "FAILED" -> SourceOperationStatus.FAILED;
            case "SUPERSEDED" -> SourceOperationStatus.SUPERSEDED;
            case "CANCELLED" -> SourceOperationStatus.CANCELLED;
            default -> throw new IllegalStateException("unsupported source operation status: " + value);
        };
    }

    public static @Nullable Instant instant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record SourcePair(UUID connectorId, SourceId sourceId, SourceStatus status, long pairSequence) {
    }

    /** What an audit record names a Source by: its name, provider and access at this moment. */
    public record AuditView(String name, String provider, String access, @Nullable UUID manager) {}

    public Optional<AuditView> auditView(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                SELECT connector.name, connector.connector_type, pair.access_type, pair.manager_actor_id
                FROM connector_credential_pairs pair
                JOIN connectors connector ON connector.tenant_id = pair.tenant_id AND connector.id = pair.connector_id
                WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
                """).param("tenantId", tenantId.value()).param("pairId", sourceId.value())
                .query((r, ignored) -> new AuditView(r.getString("name"), r.getString("connector_type"),
                        r.getString("access_type"), r.getObject("manager_actor_id", UUID.class)))
                .optional();
    }

    /** Whether the Source exists at all, whoever may reach it: tells a scoped manager's refusal from a wrong id. */
    public boolean exists(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM connector_credential_pairs WHERE tenant_id = :tenantId AND id = :pairId)")
                .param("tenantId", tenantId.value()).param("pairId", sourceId.value()).query(Boolean.class).single();
    }
}
