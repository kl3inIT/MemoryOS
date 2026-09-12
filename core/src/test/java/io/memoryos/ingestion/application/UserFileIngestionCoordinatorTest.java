package io.memoryos.ingestion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.memoryos.chat.UserFileWork;
import io.memoryos.chat.UserFileWorkPort;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;
import io.memoryos.ingestion.*;
import io.memoryos.objectstorage.*;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserFileIngestionCoordinatorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lostOrFailedLeaseRenewalNeverStagesOrPublishesStaleExtraction(boolean throwsException) throws Exception {
        var files = mock(UserFileWorkPort.class);
        var extractor = mock(ChatFileExtractor.class);
        var storage = mock(ObjectStorage.class);
        var artifacts = mock(ExtractionArtifactPort.class);
        var scheduler = mock(ScheduledExecutorService.class);
        var future = mock(ScheduledFuture.class);
        var renewal = new AtomicReference<Runnable>();
        doAnswer(call -> { renewal.set(call.getArgument(0)); return future; })
                .when(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
        var object = mock(StoredObjectReference.class);
        var metadata = mock(ObjectMetadata.class);
        when(object.metadata()).thenReturn(metadata);
        var work = new UserFileWork(new TenantId(UUID.randomUUID()), new ActorId(UUID.randomUUID()), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UserFileWork.Action.PROCESS, 1, object);
        when(files.claim(any(), any(), any())).thenReturn(Optional.of(work));
        if (throwsException) when(files.renew(work)).thenThrow(new IllegalStateException("database unavailable"));
        else when(files.renew(work)).thenReturn(false);
        var content = mock(ObjectContent.class);
        when(content.metadata()).thenReturn(metadata); when(storage.open(any())).thenReturn(content);
        when(extractor.extract(any(), anyLong(), any())).thenAnswer(_ -> {
            renewal.get().run();
            return new DocumentContent("text/plain", "test.txt", "stale text", Map.of());
        });
        var coordinator = new UserFileIngestionCoordinator(files, extractor, storage, artifacts, scheduler);
        assertEquals(IngestionCoordinator.Outcome.SKIPPED, coordinator.process(new OperationDelivery(work.tenantId(),
                OperationWorkload.USER_FILE, new SourceOperationId(work.operationId()), UUID.randomUUID())));
        verifyNoInteractions(artifacts);
        verify(files, never()).complete(any(), any()); verify(files, never()).failed(any(), any());
        verify(content).close(); verify(future).cancel(false);
    }
}
