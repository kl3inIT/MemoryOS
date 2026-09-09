package io.memoryos.connector.application;

import io.memoryos.connector.SourceRunHistoryMaintenance;
import io.memoryos.connector.persistence.JdbcSourceRunRetentionRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultSourceRunHistoryMaintenance implements SourceRunHistoryMaintenance {
    private final JdbcSourceRunRetentionRepository history;
    private final Duration summaries;
    private final Duration details;

    public DefaultSourceRunHistoryMaintenance(JdbcSourceRunRetentionRepository history,
            @Value("${memoryos.connector.run-history.summary-retention:P90D}") Duration summaries,
            @Value("${memoryos.connector.run-history.detail-retention:P14D}") Duration details) {
        if (details.isNegative() || details.isZero() || summaries.compareTo(details) < 0)
            throw new IllegalArgumentException("Run retention must have positive details no longer than summaries");
        this.history = history;
        this.summaries = summaries;
        this.details = details;
    }

    @Override
    @Transactional
    public int pruneHistory(int limit) {
        Instant now = Instant.now();
        return history.prune(now.minus(summaries), now.minus(details), Math.clamp(limit, 1, 100));
    }
}
