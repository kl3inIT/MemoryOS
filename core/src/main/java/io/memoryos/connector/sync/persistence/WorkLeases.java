package io.memoryos.connector.sync.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Token-fenced processing leases shared by every attempt table: claim, renew, hand back and retry. Lease and
 * dispatch times come from the database clock, so workers whose clocks drift still agree on who holds a lease.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class WorkLeases {

    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /**
     * Releases the claim and every dispatch marker, so the dispatcher can deliver the row again. Every attempt
     * table carries these columns.
     */
    public static final String RELEASE = """
            claim_token = NULL, lease_expires_at = NULL, delivery_id = NULL, redis_message_id = NULL,
            dispatched_at = NULL, dispatch_token = NULL, dispatch_lease_expires_at = NULL""";

    private WorkLeases() {
    }

    /** How an attempt table counts the attempts its retry budget spends. */
    public record AttemptCount(String expression, @Nullable String incrementedColumn) {
        /** Every claim spends one attempt. */
        public static final AttemptCount PROCESSING = new AttemptCount("processing_attempts", null);

        public AttemptCount {
            Objects.requireNonNull(expression, "expression");
            if (incrementedColumn != null) identifier(incrementedColumn);
        }

        /** Claims minus those handed back without spending budget, for example a reconciliation deferral. */
        public static AttemptCount processingExcept(String deferredColumn) {
            return new AttemptCount("processing_attempts - " + identifier(deferredColumn), null);
        }

        /**
         * Only failures spend the budget, for work that claims itself again for every slice of a long run. The
         * column is incremented by the retry that counts it.
         */
        public static AttemptCount failures(String column) {
            return new AttemptCount(identifier(column) + " + 1", column);
        }
    }

    public enum RetryOutcome {
        STALE,
        RETRY_SCHEDULED,
        EXHAUSTED
    }

    public static <W> Optional<W> claim(
            JdbcClient jdbcClient,
            String table,
            UUID tenantId,
            UUID operationId,
            UUID deliveryId,
            BiFunction<UUID, UUID, W> load
    ) {
        UUID token = UUID.randomUUID();
        int updated = jdbcClient.sql("UPDATE " + identifier(table) + """

                        SET status = 'IN_PROGRESS',
                            claim_token = :token,
                            lease_expires_at = CURRENT_TIMESTAMP + :leaseSeconds * INTERVAL '1 second',
                            started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                            processing_attempts = processing_attempts + 1,
                            error_code = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND delivery_id = :deliveryId
                          AND (
                              status = 'NOT_STARTED'
                              OR (status = 'IN_PROGRESS' AND lease_expires_at < CURRENT_TIMESTAMP)
                          )
                        """)
                .param("token", token)
                .param("leaseSeconds", LEASE.toSeconds())
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("deliveryId", deliveryId)
                .update();
        return updated == 1 ? Optional.of(load.apply(operationId, token)) : Optional.empty();
    }

    public static @Nullable Duration initialQueueWait(java.sql.ResultSet row) throws java.sql.SQLException {
        return row.getInt("processing_attempts") == 1
                ? Duration.between(row.getTimestamp("created_at").toInstant(), row.getTimestamp("started_at").toInstant())
                : null;
    }

    public static boolean renew(
            JdbcClient jdbcClient,
            String table,
            UUID tenantId,
            UUID operationId,
            UUID claimToken
    ) {
        return jdbcClient.sql("UPDATE " + identifier(table) + """

                        SET lease_expires_at = CURRENT_TIMESTAMP + :leaseSeconds * INTERVAL '1 second'
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("leaseSeconds", LEASE.toSeconds())
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    /**
     * Hands a claimed row back to the dispatcher without spending retry budget, for work that continues in a
     * later slice. Returns false when the claim was already lost.
     */
    public static boolean handBack(
            JdbcClient jdbcClient,
            String table,
            UUID tenantId,
            UUID operationId,
            UUID claimToken,
            Duration delay
    ) {
        return jdbcClient.sql("UPDATE " + identifier(table) + " SET status = 'NOT_STARTED', " + RELEASE + """
                        ,
                            next_dispatch_at = CURRENT_TIMESTAMP + :delayMillis * INTERVAL '1 millisecond'
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND claim_token = :claimToken
                          AND status = 'IN_PROGRESS'
                        """)
                .param("delayMillis", delay.toMillis())
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    public static RetryOutcome retry(
            JdbcClient jdbcClient,
            String table,
            UUID tenantId,
            UUID operationId,
            UUID claimToken,
            String errorCode,
            @Nullable String errorMessage,
            @Nullable String errorDetail,
            int maxAttempts,
            Duration backoff
    ) {
        return retry(jdbcClient, table, AttemptCount.PROCESSING, tenantId, operationId, claimToken, errorCode,
                errorMessage, errorDetail, maxAttempts, backoff);
    }

    /**
     * Schedules the claimed row again after {@code backoff}, or fails it once {@code count} reaches
     * {@code maxAttempts}. The error code, message and detail stay on the row either way, so a scheduled retry
     * still explains what went wrong.
     */
    public static RetryOutcome retry(
            JdbcClient jdbcClient,
            String table,
            AttemptCount count,
            UUID tenantId,
            UUID operationId,
            UUID claimToken,
            String errorCode,
            @Nullable String errorMessage,
            @Nullable String errorDetail,
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
        String safeTable = identifier(table);
        Integer attempts = jdbcClient.sql("SELECT " + count.expression() + " FROM " + safeTable + """

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
        String increment = count.incrementedColumn() == null ? ""
                : count.incrementedColumn() + " = " + count.incrementedColumn() + " + 1, ";
        String safeCode = safeErrorCode(errorCode);
        String safeMessage = safeErrorMessage(errorMessage);
        String safeDetail = safeErrorDetail(errorDetail);
        if (attempts >= maxAttempts) {
            jdbcClient.sql("UPDATE " + safeTable + " SET " + increment + """
                            status = 'FAILED',
                                claim_token = NULL,
                                lease_expires_at = NULL,
                                completed_at = CURRENT_TIMESTAMP,
                                error_code = :errorCode,
                                error_message = :errorMessage,
                                error_detail = :errorDetail
                            WHERE tenant_id = :tenantId
                              AND id = :operationId
                              AND claim_token = :claimToken
                            """)
                    .param("errorCode", safeCode)
                    .param("errorMessage", safeMessage)
                    .param("errorDetail", safeDetail)
                    .param("tenantId", tenantId)
                    .param("operationId", operationId)
                    .param("claimToken", claimToken)
                    .update();
            return RetryOutcome.EXHAUSTED;
        }
        jdbcClient.sql("UPDATE " + safeTable + " SET " + increment + "status = 'NOT_STARTED', " + RELEASE + """
                        ,
                            next_dispatch_at = CURRENT_TIMESTAMP + :backoffMillis * INTERVAL '1 millisecond',
                            error_code = :errorCode,
                            error_message = :errorMessage,
                            error_detail = :errorDetail
                        WHERE tenant_id = :tenantId
                          AND id = :operationId
                          AND claim_token = :claimToken
                        """)
                .param("backoffMillis", backoff.toMillis())
                .param("errorCode", safeCode)
                .param("errorMessage", safeMessage)
                .param("errorDetail", safeDetail)
                .param("tenantId", tenantId)
                .param("operationId", operationId)
                .param("claimToken", claimToken)
                .update();
        return RetryOutcome.RETRY_SCHEDULED;
    }

    public static String safeErrorCode(String value) {
        Objects.requireNonNull(value, "errorCode must not be null");
        if (!ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase token");
        }
        return value;
    }

    public static @Nullable String safeErrorMessage(@Nullable String value) {
        return io.memoryos.FailureEvidence.safeErrorMessage(value);
    }

    public static @Nullable String safeErrorDetail(@Nullable String value) {
        return io.memoryos.FailureEvidence.safeErrorDetail(value);
    }

    public static OffsetDateTime sqlTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static String identifier(String value) {
        if (!IDENTIFIER.matcher(Objects.requireNonNull(value, "identifier")).matches()) {
            throw new IllegalArgumentException("not a plain SQL identifier: " + value);
        }
        return value;
    }
}
