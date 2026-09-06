package io.memoryos.connector.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared token-fenced processing lease mechanics for the attempt tables.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
final class WorkLeases {

    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private WorkLeases() {
    }

    static <W> Optional<W> claim(
            JdbcClient jdbcClient,
            String table,
            UUID tenantId,
            UUID operationId,
            UUID deliveryId,
            BiFunction<UUID, UUID, W> load
    ) {
        Instant now = Instant.now();
        UUID token = UUID.randomUUID();
        int updated = jdbcClient.sql("UPDATE " + table + """

                        SET status = 'IN_PROGRESS',
                            claim_token = :token,
                            lease_expires_at = :leaseExpiresAt,
                            started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                            processing_attempts = processing_attempts + 1,
                            error_code = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND delivery_id = :deliveryId
                          AND (
                              status = 'NOT_STARTED'
                              OR (status = 'IN_PROGRESS' AND lease_expires_at < :now)
                          )
                        """)
                .param("token", token)
                .param("leaseExpiresAt", sqlTime(now.plus(LEASE)))
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("deliveryId", deliveryId)
                .param("now", sqlTime(now))
                .update();
        return updated == 1 ? Optional.of(load.apply(operationId, token)) : Optional.empty();
    }

    static @org.jspecify.annotations.Nullable Duration initialQueueWait(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return row.getInt("processing_attempts") == 1
                ? Duration.between(row.getTimestamp("created_at").toInstant(), row.getTimestamp("started_at").toInstant())
                : null;
    }

    static boolean renew(
            JdbcClient jdbcClient,
            String table,
            UUID tenantId,
            UUID operationId,
            UUID claimToken
    ) {
        return jdbcClient.sql("UPDATE " + table + """

                        SET lease_expires_at = :leaseExpiresAt
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("leaseExpiresAt", sqlTime(Instant.now().plus(LEASE)))
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    static RetryOutcome retry(
            JdbcClient jdbcClient,
            String table,
            UUID tenantId,
            UUID operationId,
            UUID claimToken,
            String errorCode,
            int maxAttempts,
            Duration backoff
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Objects.requireNonNull(backoff, "backoff must not be null");
        if (backoff.isNegative() || backoff.isZero()) {
            throw new IllegalArgumentException("backoff must be positive");
        }
        Integer attempts = jdbcClient.sql("SELECT processing_attempts FROM " + table + """

                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("claimToken", claimToken)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (attempts == null) {
            return RetryOutcome.STALE;
        }
        String safeCode = safeErrorCode(errorCode);
        if (attempts >= maxAttempts) {
            jdbcClient.sql("UPDATE " + table + """

                            SET status = 'FAILED',
                                claim_token = NULL,
                                lease_expires_at = NULL,
                                completed_at = CURRENT_TIMESTAMP,
                                error_code = :errorCode
                            WHERE tenant_id = :tenantId
                              AND id = :operationId
                              AND claim_token = :claimToken
                            """)
                    .param("errorCode", safeCode)
                    .param("tenantId", tenantId)
                    .param("operationId", operationId)
                    .param("claimToken", claimToken)
                    .update();
            return RetryOutcome.EXHAUSTED;
        }
        jdbcClient.sql("UPDATE " + table + """

                        SET status = 'NOT_STARTED',
                            claim_token = NULL,
                            lease_expires_at = NULL,
                            delivery_id = NULL,
                            redis_message_id = NULL,
                            dispatched_at = NULL,
                            dispatch_token = NULL,
                            dispatch_lease_expires_at = NULL,
                            next_dispatch_at = :nextDispatchAt,
                            error_code = :errorCode
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND claim_token = :claimToken
                        """)
                .param("nextDispatchAt", sqlTime(Instant.now().plus(backoff)))
                .param("errorCode", safeCode)
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("claimToken", claimToken)
                .update();
        return RetryOutcome.RETRY_SCHEDULED;
    }

    static String safeErrorCode(String value) {
        Objects.requireNonNull(value, "errorCode must not be null");
        if (!ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase token");
        }
        return value;
    }

    static OffsetDateTime sqlTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    enum RetryOutcome {
        STALE,
        RETRY_SCHEDULED,
        EXHAUSTED
    }
}
