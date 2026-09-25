package io.memoryos.connector.source.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.memoryos.connector.SourceOperationStatus;
import org.junit.jupiter.api.Test;

class OperationStatusTest {
    @Test
    void aCancelledAttemptIsPublishedCancelledNotSuperseded() {
        assertThat(JdbcSourceRepository.operationStatus("CANCELLED")).isEqualTo(SourceOperationStatus.CANCELLED);
        assertThat(JdbcSourceRepository.operationStatus("SUPERSEDED")).isEqualTo(SourceOperationStatus.SUPERSEDED);
        assertThat(JdbcSourceRepository.operationStatus("COMPLETED_WITH_ERRORS")).isEqualTo(SourceOperationStatus.SUCCEEDED);
    }
}
