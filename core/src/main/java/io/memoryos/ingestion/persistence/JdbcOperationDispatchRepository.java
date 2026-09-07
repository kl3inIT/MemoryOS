package io.memoryos.ingestion.persistence;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.ingestion.DispatchClaim;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.iam.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcOperationDispatchRepository implements OperationDispatchPort {

    private static final int MAX_BATCH = 32;
    private static final Duration DISPATCH_LEASE = Duration.ofSeconds(30);
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final String INDEX_CANDIDATES = """
            SELECT attempt.id, attempt.tenant_id, attempt.origin_trace_id, attempt.origin_span_id
            FROM index_attempts attempt
            JOIN tenants tenant ON tenant.id = attempt.tenant_id
            JOIN connector_credential_pairs pair
              ON pair.tenant_id = attempt.tenant_id
             AND pair.id = attempt.connector_credential_pair_id
            JOIN connector_items item
              ON item.tenant_id = attempt.tenant_id
             AND item.id = attempt.connector_item_id
            WHERE tenant.status = 'ACTIVE'
              AND pair.status <> 'DELETING'
              AND item.status <> 'DELETING'
              AND attempt.next_dispatch_at <= :now
              AND (attempt.dispatch_token IS NULL OR attempt.dispatch_lease_expires_at < :now)
              AND (
                  attempt.status = 'NOT_STARTED'
                  OR (attempt.status = 'IN_PROGRESS' AND attempt.lease_expires_at < :now)
              )
            ORDER BY attempt.created_at, attempt.id
            LIMIT :limit
            FOR UPDATE OF attempt SKIP LOCKED
            """;

    private static final String CLEANUP_CANDIDATES = """
            SELECT id, tenant_id, origin_trace_id, origin_span_id
            FROM connector_cleanup_attempts
            WHERE next_dispatch_at <= :now
              AND (dispatch_token IS NULL OR dispatch_lease_expires_at < :now)
              AND (
                  status = 'NOT_STARTED'
                  OR (status = 'IN_PROGRESS' AND lease_expires_at < :now)
              )
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcClient jdbcClient;

    public JdbcOperationDispatchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    @Transactional
    public List<DispatchClaim> claim(OperationWorkload workload, int batchSize) {
        Objects.requireNonNull(workload, "workload must not be null");
        Instant now = Instant.now();
        int limit = Math.clamp(batchSize, 1, MAX_BATCH);
        List<DispatchCandidate> candidates = jdbcClient.sql(candidateSql(workload))
                .param("now", sqlTime(now))
                .param("limit", limit)
                .query((resultSet, _) -> new DispatchCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        SourceOperationTraceContext.from(resultSet.getString("origin_trace_id"), resultSet.getString("origin_span_id"))
                ))
                .list();
        List<DispatchClaim> claims = new ArrayList<>(candidates.size());
        for (DispatchCandidate candidate : candidates) {
            UUID token = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            int updated = jdbcClient.sql("UPDATE " + table(workload) + """
                            
                            SET dispatch_token = :token,
                                dispatch_lease_expires_at = :leaseExpiresAt,
                                delivery_id = :deliveryId,
                                dispatch_attempts = dispatch_attempts + 1,
                                last_transport_error = NULL
                            WHERE tenant_id = :tenantId
                              AND id = :operationId
                              AND (dispatch_token IS NULL OR dispatch_lease_expires_at < :now)
                            """)
                    .param("token", token)
                    .param("leaseExpiresAt", sqlTime(now.plus(DISPATCH_LEASE)))
                    .param("deliveryId", deliveryId)
                    .param("tenantId", candidate.tenantId())
                    .param("operationId", candidate.operationId())
                    .param("now", sqlTime(now))
                    .update();
            if (updated == 1) {
                claims.add(new DispatchClaim(
                        new OperationDelivery(
                                new TenantId(candidate.tenantId()),
                                workload,
                                new SourceOperationId(candidate.operationId()),
                                deliveryId,
                                candidate.origin()
                        ),
                        token
                ));
            }
        }
        return List.copyOf(claims);
    }

    @Override
    @Transactional
    public boolean recordPublished(DispatchClaim claim, String redisMessageId, Duration rediscoveryDelay) {
        requireClaim(claim);
        requireText(redisMessageId, "redisMessageId");
        requirePositive(rediscoveryDelay, "rediscoveryDelay");
        int updated = jdbcClient.sql("UPDATE " + table(claim.delivery().workload()) + """
                        
                        SET redis_message_id = :redisMessageId,
                            dispatched_at = CURRENT_TIMESTAMP,
                            next_dispatch_at = :nextDispatchAt,
                            dispatch_token = NULL,
                            dispatch_lease_expires_at = NULL,
                            last_transport_error = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND delivery_id = :deliveryId
                          AND dispatch_token = :token
                        """)
                .param("redisMessageId", redisMessageId)
                .param("nextDispatchAt", sqlTime(Instant.now().plus(rediscoveryDelay)))
                .param("tenantId", claim.delivery().tenantId().value())
                .param("operationId", claim.delivery().operationId().value())
                .param("deliveryId", claim.delivery().deliveryId())
                .param("token", claim.token())
                .update();
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean defer(DispatchClaim claim, String errorCode, Duration backoff) {
        requireClaim(claim);
        requirePositive(backoff, "backoff");
        int updated = jdbcClient.sql("UPDATE " + table(claim.delivery().workload()) + """
                        
                        SET next_dispatch_at = :nextDispatchAt,
                            dispatch_token = NULL,
                            dispatch_lease_expires_at = NULL,
                            redis_message_id = NULL,
                            dispatched_at = NULL,
                            last_transport_error = :errorCode
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND delivery_id = :deliveryId
                          AND dispatch_token = :token
                        """)
                .param("nextDispatchAt", sqlTime(Instant.now().plus(backoff)))
                .param("errorCode", safeErrorCode(errorCode))
                .param("tenantId", claim.delivery().tenantId().value())
                .param("operationId", claim.delivery().operationId().value())
                .param("deliveryId", claim.delivery().deliveryId())
                .param("token", claim.token())
                .update();
        return updated == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean reclaimable(OperationDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery must not be null");
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table(delivery.workload()) + """
                        
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND delivery_id = :deliveryId
                          AND status = 'IN_PROGRESS'
                          AND lease_expires_at >= :now
                        """)
                .param("tenantId", delivery.tenantId().value())
                .param("operationId", delivery.operationId().value())
                .param("deliveryId", delivery.deliveryId())
                .param("now", sqlTime(Instant.now()))
                .query(Integer.class)
                .single() == 0;
    }


    @Override
    @Transactional
    public int cancelInactiveTenantIndexing(int batchSize) {
        return jdbcClient.sql("""
                        WITH cancellation_candidates AS (
                            SELECT attempt.id
                            FROM index_attempts attempt
                            JOIN tenants tenant ON tenant.id = attempt.tenant_id
                            WHERE attempt.status IN ('NOT_STARTED', 'IN_PROGRESS')
                              AND tenant.status <> 'ACTIVE'
                            ORDER BY attempt.created_at, attempt.id
                            LIMIT :limit
                            FOR UPDATE OF attempt SKIP LOCKED
                        )
                        UPDATE index_attempts attempt
                        SET status = 'CANCELLED',
                            claim_token = NULL,
                            lease_expires_at = NULL,
                            dispatch_token = NULL,
                            dispatch_lease_expires_at = NULL,
                            error_code = 'SOURCE_TENANT_INACTIVE',
                            completed_at = CURRENT_TIMESTAMP
                        FROM cancellation_candidates candidate
                        WHERE attempt.id = candidate.id
                        """)
                .param("limit", Math.clamp(batchSize, 1, MAX_BATCH))
                .update();
    }

    private record DispatchCandidate(UUID operationId, UUID tenantId, @Nullable SourceOperationTraceContext origin) {
    }

    private static String table(OperationWorkload workload) {
        return switch (workload) {
            case INGESTION -> "index_attempts";
            case CLEANUP -> "connector_cleanup_attempts";
        };
    }

    private static String candidateSql(OperationWorkload workload) {
        return switch (workload) {
            case INGESTION -> INDEX_CANDIDATES;
            case CLEANUP -> CLEANUP_CANDIDATES;
        };
    }

    private static void requireClaim(DispatchClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String safeErrorCode(String value) {
        requireText(value, "errorCode");
        if (!ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase token");
        }
        return value;
    }

    private static OffsetDateTime sqlTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
