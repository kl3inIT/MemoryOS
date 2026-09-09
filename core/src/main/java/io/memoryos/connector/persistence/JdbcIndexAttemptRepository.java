package io.memoryos.connector.persistence;

import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.IndexWork;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceIndexAttemptView;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationPage;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.document.DocumentId;
import io.memoryos.tenant.TenantId;

import java.time.Duration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.Nullable;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcIndexAttemptRepository implements ConnectorIndexingPort {


    private final JdbcClient jdbcClient;
    private final JdbcSourceRepository sources;
    private final JdbcSourceDocumentRepository sourceDocuments;
    private final io.memoryos.connector.GoogleDriveConnectionService connections;

    public JdbcIndexAttemptRepository(
            JdbcClient jdbcClient,
            JdbcSourceRepository sources,
            JdbcSourceDocumentRepository sourceDocuments,
            io.memoryos.connector.GoogleDriveConnectionService connections
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
    }

    public boolean canReplay(TenantId tenant, SourceId source, UUID version) {
        return jdbcClient.sql("""
                SELECT v.provider_file_id, v.credential_revision,
                  v.scope_revision = s.revision AND m.eligible AND NOT m.excluded AS eligible
                FROM connector_item_versions v
                JOIN connector_credential_pairs p ON p.tenant_id = v.tenant_id AND p.connector_id = v.connector_id
                LEFT JOIN google_drive_sources s ON s.tenant_id = p.tenant_id AND s.source_id = p.id
                LEFT JOIN google_drive_membership m ON m.tenant_id = s.tenant_id AND m.source_id = s.source_id
                  AND m.file_id = v.provider_file_id
                WHERE v.tenant_id = :tenant AND v.id = :version AND p.id = :source
                """).param("tenant", tenant.value()).param("version", version).param("source", source.value())
                .query((r, _) -> r.getString("provider_file_id") == null
                        || (r.getBoolean("eligible") && connections.current(tenant, source, r.getLong("credential_revision"))))
                .optional().orElse(false);
    }

    public Optional<SourceOperationView> findLive(
            TenantId tenantId,
            SourceId sourceId,
            JdbcSourceItemRepository.ItemVersion itemVersion
    ) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_version_id = :versionId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        ORDER BY pair_sequence DESC
                        FETCH FIRST 1 ROW ONLY
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("versionId", itemVersion.versionId())
                .query((resultSet, ignored) -> operation(resultSet))
                .optional();
    }

    public SourceOperationView create(
            TenantId tenantId,
            JdbcSourceRepository.SourcePair pair,
            JdbcSourceItemRepository.ItemVersion itemVersion
    ) {
        return create(tenantId, pair, itemVersion, null);
    }

    public SourceOperationView create(
            TenantId tenantId,
            JdbcSourceRepository.SourcePair pair,
            JdbcSourceItemRepository.ItemVersion itemVersion,
            @Nullable SourceOperationId sourceSyncAttemptId
    ) {
        long pairSequence = pair.pairSequence() + 1;
        Long itemSequence = jdbcClient.sql("""
                        SELECT COALESCE(MAX(item_sequence), 0) + 1
                        FROM index_attempts
                        WHERE tenant_id = :tenantId AND connector_item_id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("itemId", itemVersion.itemId().value())
                .query(Long.class)
                .single();
        SourceOperationId attemptId = new SourceOperationId(UUID.randomUUID());
        var trace = SourceOperationTraceContext.current();
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET pair_sequence = :pairSequence, status = 'INDEXING',
                            error_code = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("pairSequence", pairSequence)
                .param("tenantId", tenantId.value())
                .param("pairId", pair.sourceId().value())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("itemId", itemVersion.itemId().value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO index_attempts (
                            id, tenant_id, connector_id, connector_credential_pair_id,
                            connector_item_id, connector_item_version_id,
                            pair_sequence, item_sequence, status, origin_trace_id, origin_span_id, source_sync_attempt_id
                        ) VALUES (
                            :id, :tenantId, :connectorId, :pairId,
                            :itemId, :versionId, :pairSequence, :itemSequence, 'NOT_STARTED', :originTraceId, :originSpanId, :sourceSyncAttemptId
                        )
                        """)
                .param("id", attemptId.value())
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .param("pairId", pair.sourceId().value())
                .param("itemId", itemVersion.itemId().value())
                .param("versionId", itemVersion.versionId())
                .param("pairSequence", pairSequence)
                .param("itemSequence", itemSequence)
                .param("originTraceId", trace == null ? null : trace.traceId())
                .param("originSpanId", trace == null ? null : trace.spanId())
                .param("sourceSyncAttemptId", sourceSyncAttemptId == null ? null : sourceSyncAttemptId.value())
                .update();
        return findById(tenantId, attemptId).orElseThrow();
    }

    public Optional<SourceOperationView> findById(
            TenantId tenantId,
            SourceOperationId operationId
    ) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.value())
                .param("id", operationId.value())
                .query((resultSet, ignored) -> operation(resultSet))
                .optional();
    }

    public SourceOperationPage list(TenantId tenantId, SourceId sourceId, @Nullable String cursor, int limit) {
        String scope = tenantId.value() + "|" + sourceId.value() + "|INDEX|";
        String position = SourceHistoryCursor.decode(cursor, scope);
        long before = Long.MAX_VALUE;
        if (position != null) {
            try {
                before = Long.parseLong(position);
                if (before < 1) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw SourceHistoryCursor.invalid();
            }
        }
        record Row(long sequence, SourceIndexAttemptView operation) {}
        var rows = jdbcClient.sql("""
                SELECT attempt.id, attempt.status, attempt.created_at, attempt.started_at,
                       attempt.completed_at, attempt.error_code, attempt.pair_sequence, version.filename
                FROM index_attempts attempt
                LEFT JOIN connector_item_versions version ON version.tenant_id = attempt.tenant_id
                    AND version.id = attempt.connector_item_version_id
                WHERE attempt.tenant_id = :tenant AND attempt.connector_credential_pair_id = :source
                    AND attempt.pair_sequence < :before
                ORDER BY attempt.pair_sequence DESC LIMIT :limit
                """).param("tenant", tenantId.value()).param("source", sourceId.value())
                .param("before", before).param("limit", limit + 1)
                .query((r, _) -> new Row(r.getLong("pair_sequence"), new SourceIndexAttemptView(
                        new SourceOperationId(r.getObject("id", UUID.class)), r.getString("filename"),
                        JdbcSourceRepository.operationStatus(r.getString("status")),
                        r.getTimestamp("created_at").toInstant(), JdbcSourceRepository.instant(r, "started_at"),
                        JdbcSourceRepository.instant(r, "completed_at"), r.getString("error_code")))).list();
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        long totalItems = jdbcClient.sql("""
                SELECT count(*) FROM index_attempts
                WHERE tenant_id = :tenant AND connector_credential_pair_id = :source
                """).param("tenant", tenantId.value()).param("source", sourceId.value())
                .query(Long.class).single();
        return new SourceOperationPage(page.stream().map(Row::operation).toList(),
                more ? SourceHistoryCursor.encode(scope, Long.toString(page.getLast().sequence())) : null,
                totalItems);
    }

    public void cancelForItem(
            TenantId tenantId,
            SourceId sourceId,
            SourceItemId itemId
    ) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
    }

    public void cancelForSource(TenantId tenantId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    @Override
    @Transactional
    public Optional<IndexWork> claim(
            TenantId tenantId,
            SourceOperationId operationId,
            UUID deliveryId
    ) {
        return WorkLeases.claim(
                jdbcClient,
                "index_attempts",
                tenantId.value(),
                operationId.value(),
                deliveryId,
                this::load
        );
    }

    @Override
    @Transactional
    public boolean renew(IndexWork work) {
        return WorkLeases.renew(
                jdbcClient,
                "index_attempts",
                work.tenantId().value(),
                work.operationId().value(),
                work.claimToken()
        );
    }

    @Override
    @Transactional
    public boolean retry(IndexWork work, String errorCode, int maxAttempts, Duration backoff) {
        if (!lockSource(work)) return false;
        WorkLeases.RetryOutcome outcome = WorkLeases.retry(
                jdbcClient,
                "index_attempts",
                work.tenantId().value(),
                work.operationId().value(),
                work.claimToken(),
                errorCode,
                maxAttempts,
                backoff
        );
        if (outcome == WorkLeases.RetryOutcome.EXHAUSTED) {
            markAggregateFailed(work);
        }
        return outcome != WorkLeases.RetryOutcome.STALE;
    }

    @Override
    @Transactional
    public Optional<DocumentId> findMappedDocument(IndexWork work) {
        if (!lockSource(work)) return Optional.empty();
        return sourceDocuments.findMappedDocument(work);
    }

    @Override
    @Transactional
    public boolean complete(IndexWork work, DocumentId documentId) {
        if (!isCurrent(work, true)) {
            return false;
        }
        int updated = jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL, error_code = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
        if (updated != 1) {
            return false;
        }
        sourceDocuments.publishMapping(work, documentId);
        jdbcClient.sql("""
                        UPDATE connector_items
                        SET status = 'INDEXED', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", work.itemId().value())
                .update();
        sources.recomputeStatus(work.tenantId(), work.sourceId(), true);
        return true;
    }

    @Override
    @Transactional
    public boolean fail(IndexWork work, String errorCode) {
        if (!lockSource(work)) return false;
        String safeCode = WorkLeases.safeErrorCode(errorCode);
        int updated = jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL, error_code = :errorCode
                        WHERE tenant_id = :tenantId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("errorCode", safeCode)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
        if (updated == 1) {
            markAggregateFailed(work);
        }
        return updated == 1;
    }

    private void markAggregateFailed(IndexWork work) {
        jdbcClient.sql("""
                        UPDATE connector_items
                        SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND id = :itemId
                          AND status <> 'DELETING'
                          AND current_version_id = (
                              SELECT connector_item_version_id FROM index_attempts
                              WHERE tenant_id = :tenantId AND id = :attemptId
                          )
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", work.itemId().value())
                .param("attemptId", work.operationId().value())
                .update();
        sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
    }

    private IndexWork load(UUID attemptId, UUID token) {
        return jdbcClient.sql("""
                        SELECT attempt.id,
                               attempt.created_at, attempt.started_at, attempt.processing_attempts,
                               attempt.tenant_id,
                               attempt.connector_id,
                               attempt.connector_credential_pair_id,
                               attempt.connector_item_id,
                               object.id AS stored_object_id,
                               object.object_key,
                               object.filename,
                               object.size_bytes,
                               object.declared_media_type,
                               object.content_sha256,
                               version.input_format, version.provider_file_id, version.provider_version, version.source_url
                        FROM index_attempts attempt
                        JOIN connector_item_versions version
                          ON version.tenant_id = attempt.tenant_id
                         AND version.id = attempt.connector_item_version_id
                        JOIN stored_objects object
                          ON object.tenant_id = version.tenant_id
                         AND object.id = version.stored_object_id
                         AND object.state = 'ACTIVE'
                        WHERE attempt.id = :id AND attempt.claim_token = :token
                        """)
                .param("id", attemptId)
                .param("token", token)
                .query((resultSet, ignored) -> new IndexWork(
                        new SourceOperationId(resultSet.getObject("id", UUID.class)),
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getObject("connector_id", UUID.class),
                        new SourceId(resultSet.getObject("connector_credential_pair_id", UUID.class)),
                        new SourceItemId(resultSet.getObject("connector_item_id", UUID.class)),
                        token,
                        new StoredObjectReference(
                                new StoredObjectId(resultSet.getObject("stored_object_id", UUID.class)),
                                new ObjectKey(resultSet.getString("object_key")),
                                resultSet.getString("filename"),
                                new ObjectMetadata(
                                        resultSet.getLong("size_bytes"),
                                        resultSet.getString("declared_media_type"),
                                        new ContentSha256(resultSet.getString("content_sha256"))
                                )
                        ),
                        new io.memoryos.connector.SourceInputDescriptor(
                                io.memoryos.connector.SourceInputFormat.valueOf(resultSet.getString("input_format")),
                                resultSet.getString("provider_file_id"), resultSet.getString("provider_version"),
                                resultSet.getString("source_url")),
                        WorkLeases.initialQueueWait(resultSet)
                ))
                .single();
    }

    private boolean isCurrent(IndexWork work, boolean requireEligibility) {
        if (!lockSource(work)) return false;
        if (work.input().providerFileId() != null) {
            var revision = jdbcClient.sql("""
                    SELECT v.credential_revision FROM index_attempts a
                    JOIN connector_item_versions v ON v.tenant_id = a.tenant_id AND v.id = a.connector_item_version_id
                    JOIN google_drive_sources s ON s.tenant_id = a.tenant_id AND s.source_id = a.connector_credential_pair_id
                    JOIN google_drive_membership m ON m.tenant_id = s.tenant_id AND m.source_id = s.source_id
                      AND m.file_id = v.provider_file_id
                    WHERE a.tenant_id = :tenant AND a.id = :id AND v.scope_revision = s.revision
                      AND (:ignoreEligibility OR m.eligible) AND NOT m.excluded AND m.root_id IS NOT NULL
                    """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                    .param("ignoreEligibility", !requireEligibility)
                    .query(Long.class).optional();
            if (revision.isEmpty() || !connections.current(work.tenantId(), work.sourceId(), revision.get())) return false;
        }
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM index_attempts attempt
                        JOIN tenants tenant ON tenant.id = attempt.tenant_id
                        JOIN connector_credential_pairs pair
                          ON pair.tenant_id = attempt.tenant_id
                         AND pair.id = attempt.connector_credential_pair_id
                        JOIN connector_items item
                          ON item.tenant_id = attempt.tenant_id
                         AND item.id = attempt.connector_item_id
                        WHERE attempt.tenant_id = :tenantId
                          AND attempt.id = :attemptId
                          AND attempt.status = 'IN_PROGRESS'
                          AND attempt.claim_token = :claimToken
                          AND attempt.lease_expires_at > CURRENT_TIMESTAMP
                          AND tenant.status = 'ACTIVE'
                          AND pair.status <> 'DELETING'
                          AND NOT EXISTS (
                              SELECT 1 FROM index_attempts newer
                              WHERE newer.tenant_id = attempt.tenant_id
                                AND newer.connector_credential_pair_id = attempt.connector_credential_pair_id
                                AND newer.connector_item_id = attempt.connector_item_id
                                AND newer.pair_sequence > attempt.pair_sequence
                          )
                          AND item.status <> 'DELETING'
                          AND item.current_version_id = attempt.connector_item_version_id
                        """)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .query(Integer.class)
                .single() == 1;
    }

    private boolean lockSource(IndexWork work) {
        try {
            sources.lock(work.tenantId(), work.sourceId());
            return true;
        } catch (SourceException exception) {
            if ("SOURCE_NOT_FOUND".equals(exception.code())) return false;
            throw exception;
        }
    }

    @Override
    @Transactional
    public void supersede(IndexWork work) {
        if (work.input().providerFileId() != null && isCurrent(work, false)) {
            jdbcClient.sql("""
                    UPDATE index_attempts SET status = 'NOT_STARTED', completed_at = NULL, error_code = NULL,
                        deferred_attempts = deferred_attempts + 1,
                        claim_token = NULL, lease_expires_at = NULL,
                        delivery_id = NULL, dispatch_token = NULL, dispatch_lease_expires_at = NULL,
                        redis_message_id = NULL, dispatched_at = NULL, next_dispatch_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS' AND claim_token = :token
                    """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                    .param("token", work.claimToken()).update();
            return;
        }
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'SUPERSEDED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
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
}
