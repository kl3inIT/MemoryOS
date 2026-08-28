package io.memoryos.connector.persistence;

import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.IndexWork;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.document.DocumentId;
import io.memoryos.organization.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcIndexAttemptRepository implements ConnectorIndexingPort {

    private static final int MAX_BATCH = 32;

    private final JdbcClient jdbcClient;
    private final JdbcSourceDocumentRepository documents;

    public JdbcIndexAttemptRepository(
            JdbcClient jdbcClient,
            JdbcSourceDocumentRepository documents
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
    }

    public Optional<SourceOperationView> findLive(
            OrganizationId organizationId,
            SourceId sourceId,
            JdbcSourceItemRepository.ItemVersion itemVersion
    ) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_version_id = :versionId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        ORDER BY pair_sequence DESC
                        FETCH FIRST 1 ROW ONLY
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .param("versionId", itemVersion.versionId())
                .query((resultSet, ignored) -> operation(resultSet))
                .optional();
    }

    public SourceOperationView create(
            OrganizationId organizationId,
            JdbcSourceRepository.SourcePair pair,
            JdbcSourceItemRepository.ItemVersion itemVersion
    ) {
        long pairSequence = pair.pairSequence() + 1;
        Long itemSequence = jdbcClient.sql("""
                        SELECT COALESCE(MAX(item_sequence), 0) + 1
                        FROM index_attempts
                        WHERE organization_id = :organizationId AND connector_item_id = :itemId
                        """)
                .param("organizationId", organizationId.value())
                .param("itemId", itemVersion.itemId().value())
                .query(Long.class)
                .single();
        SourceOperationId attemptId = new SourceOperationId(UUID.randomUUID());
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET pair_sequence = :pairSequence, status = 'INDEXING',
                            error_code = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId AND id = :pairId
                        """)
                .param("pairSequence", pairSequence)
                .param("organizationId", organizationId.value())
                .param("pairId", pair.sourceId().value())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId AND id = :itemId
                        """)
                .param("organizationId", organizationId.value())
                .param("itemId", itemVersion.itemId().value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO index_attempts (
                            id, organization_id, connector_id, connector_credential_pair_id,
                            connector_item_id, connector_item_version_id,
                            pair_sequence, item_sequence, status
                        ) VALUES (
                            :id, :organizationId, :connectorId, :pairId,
                            :itemId, :versionId, :pairSequence, :itemSequence, 'NOT_STARTED'
                        )
                        """)
                .param("id", attemptId.value())
                .param("organizationId", organizationId.value())
                .param("connectorId", pair.connectorId())
                .param("pairId", pair.sourceId().value())
                .param("itemId", itemVersion.itemId().value())
                .param("versionId", itemVersion.versionId())
                .param("pairSequence", pairSequence)
                .param("itemSequence", itemSequence)
                .update();
        return findById(organizationId, attemptId).orElseThrow();
    }

    public Optional<SourceOperationView> findById(
            OrganizationId organizationId,
            SourceOperationId operationId
    ) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE organization_id = :organizationId AND id = :id
                        """)
                .param("organizationId", organizationId.value())
                .param("id", operationId.value())
                .query((resultSet, ignored) -> operation(resultSet))
                .optional();
    }

    public List<SourceOperationView> list(OrganizationId organizationId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                        ORDER BY pair_sequence DESC
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .query((resultSet, ignored) -> operation(resultSet))
                .list();
    }

    public void cancelForItem(
            OrganizationId organizationId,
            SourceId sourceId,
            SourceItemId itemId
    ) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
    }

    public void cancelForSource(OrganizationId organizationId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    @Override
    @Transactional
    public List<IndexWork> claim(int batchSize) {
        int limit = Math.clamp(batchSize, 1, MAX_BATCH);
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'CANCELLED',
                            claim_token = NULL,
                            lease_expires_at = NULL,
                            error_code = 'SOURCE_ORGANIZATION_INACTIVE',
                            completed_at = CURRENT_TIMESTAMP
                        WHERE status IN ('NOT_STARTED', 'IN_PROGRESS')
                          AND organization_id IN (
                              SELECT id FROM organizations WHERE status <> 'ACTIVE'
                          )
                        """)
                .update();
        Instant now = Instant.now();
        List<UUID> candidates = jdbcClient.sql("""
                        SELECT attempt.id
                        FROM index_attempts attempt
                        JOIN organizations organization ON organization.id = attempt.organization_id
                        JOIN connector_credential_pairs pair
                          ON pair.organization_id = attempt.organization_id
                         AND pair.id = attempt.connector_credential_pair_id
                        JOIN connector_items item
                          ON item.organization_id = attempt.organization_id
                         AND item.id = attempt.connector_item_id
                        WHERE organization.status = 'ACTIVE'
                          AND pair.status <> 'DELETING'
                          AND item.status <> 'DELETING'
                          AND (
                              attempt.status = 'NOT_STARTED'
                              OR (attempt.status = 'IN_PROGRESS' AND attempt.lease_expires_at < :now)
                          )
                        ORDER BY attempt.created_at, attempt.id
                        LIMIT :limit
                        FOR UPDATE OF attempt SKIP LOCKED
                        """)
                .param("now", sqlTime(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
        List<IndexWork> claimed = new ArrayList<>(Math.min(limit, candidates.size()));
        for (UUID candidate : candidates) {
            if (claimed.size() == limit) {
                break;
            }
            UUID token = UUID.randomUUID();
            int updated = jdbcClient.sql("""
                            UPDATE index_attempts
                            SET status = 'IN_PROGRESS',
                                claim_token = :token,
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
    @Transactional(readOnly = true)
    public Optional<DocumentId> findMappedDocument(IndexWork work) {
        return documents.findMappedDocument(work);
    }

    @Override
    @Transactional
    public boolean complete(IndexWork work, DocumentId documentId) {
        if (!isCurrent(work)) {
            return false;
        }
        int updated = jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL, error_code = NULL
                        WHERE organization_id = :organizationId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("organizationId", work.organizationId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
        if (updated != 1) {
            return false;
        }
        documents.publishMapping(work, documentId);
        jdbcClient.sql("""
                        UPDATE connector_items
                        SET status = 'INDEXED', updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId AND id = :itemId
                        """)
                .param("organizationId", work.organizationId().value())
                .param("itemId", work.itemId().value())
                .update();
        recomputePair(work.organizationId(), work.sourceId());
        return true;
    }

    @Override
    @Transactional
    public boolean fail(IndexWork work, String errorCode) {
        String safeCode = safeErrorCode(errorCode);
        int updated = jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL, error_code = :errorCode
                        WHERE organization_id = :organizationId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("errorCode", safeCode)
                .param("organizationId", work.organizationId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
        if (updated == 1) {
            jdbcClient.sql("""
                            UPDATE connector_items
                            SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                            WHERE organization_id = :organizationId
                              AND id = :itemId
                              AND status <> 'DELETING'
                            """)
                    .param("organizationId", work.organizationId().value())
                    .param("itemId", work.itemId().value())
                    .update();
            recomputePair(work.organizationId(), work.sourceId());
        }
        return updated == 1;
    }

    private IndexWork load(UUID attemptId, UUID token) {
        return jdbcClient.sql("""
                        SELECT attempt.id,
                               attempt.organization_id,
                               attempt.connector_id,
                               attempt.connector_credential_pair_id,
                               attempt.connector_item_id,
                               attempt.connector_item_version_id,
                               version.filename,
                               version.content_bytes,
                               version.content_sha256
                        FROM index_attempts attempt
                        JOIN connector_item_versions version
                          ON version.organization_id = attempt.organization_id
                         AND version.id = attempt.connector_item_version_id
                        WHERE attempt.id = :id AND attempt.claim_token = :token
                        """)
                .param("id", attemptId)
                .param("token", token)
                .query((resultSet, ignored) -> new IndexWork(
                        new SourceOperationId(resultSet.getObject("id", UUID.class)),
                        new OrganizationId(resultSet.getObject("organization_id", UUID.class)),
                        resultSet.getObject("connector_id", UUID.class),
                        new SourceId(resultSet.getObject("connector_credential_pair_id", UUID.class)),
                        new SourceItemId(resultSet.getObject("connector_item_id", UUID.class)),
                        resultSet.getObject("connector_item_version_id", UUID.class),
                        token,
                        resultSet.getString("filename"),
                        resultSet.getBytes("content_bytes"),
                        resultSet.getString("content_sha256")
                ))
                .single();
    }

    private boolean isCurrent(IndexWork work) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM index_attempts attempt
                        JOIN organizations organization ON organization.id = attempt.organization_id
                        JOIN connector_credential_pairs pair
                          ON pair.organization_id = attempt.organization_id
                         AND pair.id = attempt.connector_credential_pair_id
                        JOIN connector_items item
                          ON item.organization_id = attempt.organization_id
                         AND item.id = attempt.connector_item_id
                        WHERE attempt.organization_id = :organizationId
                          AND attempt.id = :attemptId
                          AND attempt.status = 'IN_PROGRESS'
                          AND attempt.claim_token = :claimToken
                          AND organization.status = 'ACTIVE'
                          AND pair.status <> 'DELETING'
                          AND NOT EXISTS (
                              SELECT 1 FROM index_attempts newer
                              WHERE newer.organization_id = attempt.organization_id
                                AND newer.connector_credential_pair_id = attempt.connector_credential_pair_id
                                AND newer.connector_item_id = attempt.connector_item_id
                                AND newer.pair_sequence > attempt.pair_sequence
                          )
                          AND item.status <> 'DELETING'
                          AND item.current_version_id = attempt.connector_item_version_id
                        """)
                .param("organizationId", work.organizationId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .query(Integer.class)
                .single() == 1;
    }

    @Override
    @Transactional
    public void supersede(IndexWork work) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'SUPERSEDED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL
                        WHERE organization_id = :organizationId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("organizationId", work.organizationId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
    }

    private void recomputePair(OrganizationId organizationId, SourceId sourceId) {
        int nonterminal = jdbcClient.sql("""
                        SELECT COUNT(*) FROM index_attempts
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .query(Integer.class)
                .single();
        long documentCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM documents_by_connector_credential_pair
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND retrieval_eligible = TRUE
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .query(Long.class)
                .single();
        String latestError = jdbcClient.sql("""
                        SELECT error_code FROM index_attempts
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND status = 'FAILED'
                        ORDER BY pair_sequence DESC
                        FETCH FIRST 1 ROW ONLY
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .query(String.class)
                .optional()
                .orElse(null);
        String status = nonterminal > 0
                ? "INDEXING"
                : documentCount > 0 ? "ACTIVE" : latestError == null ? "NOT_STARTED" : "FAILED";
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET status = CASE WHEN status = 'DELETING' THEN 'DELETING' ELSE :status END,
                            document_count = :documentCount,
                            last_succeeded_at = CASE
                                WHEN :documentCount > 0 THEN CURRENT_TIMESTAMP
                                ELSE last_succeeded_at
                            END,
                            error_code = :errorCode,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId AND id = :pairId
                        """)
                .param("status", status)
                .param("documentCount", documentCount)
                .param("errorCode", latestError)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    private static SourceOperationView operation(ResultSet resultSet) throws SQLException {
        return new SourceOperationView(
                new SourceOperationId(resultSet.getObject("id", UUID.class)),
                SourceOperationType.INDEX,
                JdbcSourceRepository.operationStatus(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(resultSet, "completed_at"),
                resultSet.getString("error_code")
        );
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
