package io.memoryos.mcp;

import io.memoryos.mcp.persistence.JdbcMcpEndpointCallRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MEM-209: keeps the MCP endpoint's activity log to {@link #RETENTION}; the Worker runs it. */
@Service
public class McpEndpointCallRetention {
    public static final Duration RETENTION = Duration.ofDays(90);
    private static final int BATCH = 5_000;
    private static final int MAX_BATCHES = 100;

    private final JdbcMcpEndpointCallRepository calls;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    public McpEndpointCallRetention(JdbcMcpEndpointCallRepository calls, PlatformTransactionManager transactions) {
        this.calls = calls;
        this.transactions = new TransactionTemplate(transactions);
    }

    /** Removes calls older than the retention, one bounded batch per transaction; returns how many went. */
    public int purge() {
        Instant before = clock.instant().minus(RETENTION);
        int removed = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Integer gone = transactions.execute(ignored -> calls.purge(before, BATCH));
            int count = gone == null ? 0 : gone;
            removed += count;
            if (count < BATCH) break;
        }
        return removed;
    }
}
