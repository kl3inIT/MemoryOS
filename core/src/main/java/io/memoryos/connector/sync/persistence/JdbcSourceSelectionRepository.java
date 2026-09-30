package io.memoryos.connector.sync.persistence;

import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceType;
import io.memoryos.shared.TenantId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What the engine asks of selection requests without knowing their provider. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceSelectionRepository {
    private final JdbcClient jdbc;

    public JdbcSourceSelectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The provider whose adapter verifies the request, or empty when the request no longer exists. */
    public Optional<SourceType> sourceType(TenantId tenant, SourceOperationId operation) {
        return SelectionOperations.sourceType(jdbc, tenant, operation);
    }

    /** Cancels the request still pending for a Source that is being deleted. */
    public void cancelForSource(TenantId tenant, SourceId source) {
        jdbc.sql("UPDATE source_selection_operations SET status = 'CANCELLED', error_code = 'SOURCE_DELETING', "
                        + "completed_at = CURRENT_TIMESTAMP, " + WorkLeases.RELEASE + """

                WHERE tenant_id = :tenant AND source_id = :source AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }
}
