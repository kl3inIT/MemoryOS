package io.memoryos.connector.persistence;

import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.tenant.TenantId;
import io.memoryos.document.DocumentCommandService;
import io.memoryos.document.DocumentId;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcConnectorCleanupPort implements ConnectorCleanupPort {

    private static final int MAX_BATCH = 32;

    private final JdbcClient jdbcClient;
    private final JdbcSourceDocumentRepository sourceDocuments;
    private final DocumentCommandService documents;

    public JdbcConnectorCleanupPort(
            JdbcClient jdbcClient,
            JdbcSourceDocumentRepository sourceDocuments,
            DocumentCommandService documents
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
    }

    @Override
    @Transactional
    public List<CleanupWork> claim(int batchSize) {
        int limit = Math.clamp(batchSize, 1, MAX_BATCH);
        Instant now = Instant.now();
        List<UUID> candidates = jdbcClient.sql("""
                        SELECT id FROM connector_cleanup_attempts
                        WHERE status = 'NOT_STARTED'
                           OR (status = 'IN_PROGRESS' AND lease_expires_at < :now)
                        ORDER BY created_at, id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("now", sqlTime(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
        List<CleanupWork> claimed = new ArrayList<>(Math.min(limit, candidates.size()));
        for (UUID candidate : candidates) {
            if (claimed.size() == limit) {
                break;
            }
            UUID token = UUID.randomUUID();
            int updated = jdbcClient.sql("""
                            UPDATE connector_cleanup_attempts
                            SET status = 'IN_PROGRESS', claim_token = :token,
                                lease_expires_at = :leaseExpiresAt,
                                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                                error_code = NULL
                            WHERE id = :id
                              AND (
                                  status = 'NOT_STARTED'
                                  OR (status = 'IN_PROGRESS' AND lease_expires_at < :now)
                              )
                            """)
                    .param("token", token)
                    .param("leaseExpiresAt", sqlTime(now.plusSeconds(120)))
                    .param("id", candidate)
                    .param("now", sqlTime(now))
                    .update();
            if (updated == 1) {
                claimed.add(load(candidate, token));
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    @Transactional
    public boolean execute(CleanupWork work) {
        if (!ownsClaim(work)) {
            return false;
        }
        if (work.type() == SourceOperationType.REMOVE_ITEM) {
            removeItem(work);
        } else if (work.type() == SourceOperationType.DELETE_SOURCE) {
            deleteSource(work);
        } else {
            throw new IllegalStateException("unsupported cleanup operation: " + work.type());
        }
        return complete(work, "SUCCEEDED", null);
    }

    @Override
    @Transactional
    public boolean fail(CleanupWork work, String errorCode) {
        return complete(work, "FAILED", safeErrorCode(errorCode));
    }

    private CleanupWork load(UUID operationId, UUID token) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, operation, target_pair_id, target_item_id
                        FROM connector_cleanup_attempts
                        WHERE id = :id AND claim_token = :token
                        """)
                .param("id", operationId)
                .param("token", token)
                .query((resultSet, ignored) -> {
                    UUID itemId = resultSet.getObject("target_item_id", UUID.class);
                    return new CleanupWork(
                            new SourceOperationId(resultSet.getObject("id", UUID.class)),
                            new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                            SourceOperationType.valueOf(resultSet.getString("operation")),
                            resultSet.getObject("target_pair_id", UUID.class),
                            itemId == null ? null : new SourceItemId(itemId),
                            token
                    );
                })
                .single();
    }

    private boolean ownsClaim(CleanupWork work) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM connector_cleanup_attempts
                        WHERE tenant_id = :tenantId
                          AND id = :id
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :token
                        """)
                .param("tenantId", work.tenantId().value())
                .param("id", work.operationId().value())
                .param("token", work.claimToken())
                .query(Integer.class)
                .single() == 1;
    }

    private void removeItem(CleanupWork work) {
        SourceItemId itemId = Objects.requireNonNull(work.itemId(), "REMOVE_ITEM requires itemId");
        List<UUID> documentIds = sourceDocuments.removeItemMappings(
                work.tenantId(),
                new SourceId(work.sourceId()),
                itemId
        );
        jdbcClient.sql("""
                        DELETE FROM index_attempts
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId())
                .param("itemId", itemId.value())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET current_version_id = NULL
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", itemId.value())
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_item_versions
                        WHERE tenant_id = :tenantId AND connector_item_id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", itemId.value())
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_items
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", itemId.value())
                .update();
        documents.removeUnreferenced(
                work.tenantId(),
                documentIds.stream().map(DocumentId::new).toList()
        );
        recomputePair(work.tenantId(), work.sourceId());
    }

    private void deleteSource(CleanupWork work) {
        UUID connectorId = jdbcClient.sql("""
                        SELECT connector_id FROM connector_credential_pairs
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId())
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (connectorId == null) {
            complete(work, "SUPERSEDED", null);
            return;
        }
        List<UUID> documentIds = sourceDocuments.removeSourceMappings(
                work.tenantId(),
                new SourceId(work.sourceId())
        );
        jdbcClient.sql("""
                        DELETE FROM index_attempts
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET current_version_id = NULL
                        WHERE tenant_id = :tenantId AND connector_id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_item_versions
                        WHERE tenant_id = :tenantId AND connector_id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_items
                        WHERE tenant_id = :tenantId AND connector_id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_credential_pairs
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId())
                .update();
        jdbcClient.sql("""
                        DELETE FROM connectors
                        WHERE tenant_id = :tenantId AND id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        documents.removeUnreferenced(
                work.tenantId(),
                documentIds.stream().map(DocumentId::new).toList()
        );
    }


    private void recomputePair(TenantId tenantId, UUID sourceId) {
        long documents = jdbcClient.sql("""
                        SELECT COUNT(*) FROM documents_by_connector_credential_pair
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND retrieval_eligible = TRUE
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET document_count = :documentCount,
                            status = CASE WHEN :documentCount > 0 THEN 'ACTIVE' ELSE 'NOT_STARTED' END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND id = :pairId
                          AND status <> 'DELETING'
                        """)
                .param("documentCount", documents)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId)
                .update();
    }

    private boolean complete(CleanupWork work, String status, String errorCode) {
        int updated = jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET status = :status, error_code = :errorCode,
                            completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :id
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :token
                        """)
                .param("status", status)
                .param("errorCode", errorCode)
                .param("tenantId", work.tenantId().value())
                .param("id", work.operationId().value())
                .param("token", work.claimToken())
                .update();
        return updated == 1;
    }

    private static String safeErrorCode(String value) {
        Objects.requireNonNull(value, "errorCode must not be null");
        if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase token");
        }
        return value;
    }

    private static OffsetDateTime sqlTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
