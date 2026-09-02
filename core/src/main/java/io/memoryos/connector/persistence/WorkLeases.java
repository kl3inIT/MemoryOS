package io.memoryos.connector.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared lease mechanics for the worker-claimed attempt tables.
 */
final class WorkLeases {

    static final int MAX_BATCH = 32;

    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private WorkLeases() {
    }

    /**
     * Claims up to {@code batchSize} rows of {@code table}. {@code candidateSql} must select claimable
     * ids with {@code :now} and {@code :limit} parameters and lock them with {@code FOR UPDATE SKIP LOCKED};
     * each candidate is then leased with a fresh claim token and loaded through {@code load}.
     */
    static <W> List<W> claim(
            JdbcClient jdbcClient,
            String table,
            String candidateSql,
            int batchSize,
            BiFunction<UUID, UUID, W> load
    ) {
        int limit = Math.clamp(batchSize, 1, MAX_BATCH);
        Instant now = Instant.now();
        List<UUID> candidates = jdbcClient.sql(candidateSql)
                .param("now", sqlTime(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
        List<W> claimed = new ArrayList<>(candidates.size());
        for (UUID candidate : candidates) {
            UUID token = UUID.randomUUID();
            int updated = jdbcClient.sql("UPDATE " + table + """

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
                    .param("leaseExpiresAt", sqlTime(now.plus(LEASE)))
                    .param("id", candidate)
                    .param("now", sqlTime(now))
                    .update();
            if (updated == 1) {
                claimed.add(load.apply(candidate, token));
            }
        }
        return List.copyOf(claimed);
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
}
