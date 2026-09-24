package io.memoryos.ingestion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentContentException;
import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.persistence.JdbcSearchWorkRepository;
import io.memoryos.retrieval.SearchIndex;
import io.memoryos.retrieval.SearchUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class SearchIngestionCoordinatorTest {
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final DocumentId document = new DocumentId(UUID.randomUUID());
    private final UUID generation = UUID.randomUUID();
    private final JdbcSearchWorkRepository work = mock(JdbcSearchWorkRepository.class);
    private final DocumentChunkPort documents = mock(DocumentChunkPort.class);
    private final SearchIndex index = mock(SearchIndex.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private SearchIngestionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(index.identity()).thenReturn("test-index");
        when(index.identities()).thenReturn(List.of("test-index"));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), any()))
                .thenAnswer(_ -> mock(ScheduledFuture.class));
        var claim = new JdbcSearchWorkRepository.Claim(tenant, UUID.randomUUID(), UUID.randomUUID(),
                document, generation, "INDEX", 1, "test-index");
        when(work.claim(any())).thenReturn(Optional.of(claim));
        when(documents.prepare(tenant, document, generation)).thenReturn(Optional.of(
                new DocumentChunkSet(tenant, document, generation, "t", "text/plain", Instant.EPOCH,
                        List.of(new DocumentChunk(0, "passage", List.of(), 0, 0, "[]", "a".repeat(64), 1)))));
        coordinator = new SearchIngestionCoordinator(work, documents, index,
                new TransactionTemplate(manager), scheduler, new SimpleMeterRegistry());
    }

    @Test
    void contentRejectionFailsOnceWithTheSpecificCode() {
        doThrow(new DocumentContentException("SEARCH_INDEX_CONTENT_LIMIT", "document exceeds chunk limit"))
                .when(index).index(any(), any());
        when(work.finish(any(), eq("FAILED"), eq("SEARCH_INDEX_CONTENT_LIMIT"))).thenReturn(true);

        assertEquals(io.memoryos.ingestion.IngestionCoordinator.Outcome.FAILED,
                coordinator.process(mock(OperationDelivery.class)));

        verify(work).finish(any(), eq("FAILED"), eq("SEARCH_INDEX_CONTENT_LIMIT"));
        verify(documents).markSearchFailed(tenant, document, generation, "SEARCH_INDEX_CONTENT_LIMIT", "test-index");
    }

    @Test
    void transientFailureKeepsTheGenericCodeAndRetries() {
        doThrow(new SearchUnavailableException()).when(index).index(any(), any());
        when(work.finish(any(), eq("NOT_STARTED"), eq("SEARCH_INDEX_FAILED"))).thenReturn(true);

        assertEquals(io.memoryos.ingestion.IngestionCoordinator.Outcome.FAILED,
                coordinator.process(mock(OperationDelivery.class)));

        verify(work).finish(any(), eq("NOT_STARTED"), eq("SEARCH_INDEX_FAILED"));
        verify(documents).markSearchFailed(tenant, document, generation, "SEARCH_INDEX_FAILED", "test-index");
        verify(documents, never()).markSearchReady(any(), any(), any(), any());
    }
}
