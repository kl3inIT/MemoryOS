package io.memoryos.connector;

import io.memoryos.identity.ActorId;

import java.util.List;

public interface SourceManagementService {

    SourceDetail createFileSource(ActorId actorId, String name);

    List<SourceSummary> listSources(ActorId actorId);

    SourceDetail getSource(ActorId actorId, SourceId sourceId);

    List<SourceOperationView> listIndexAttempts(ActorId actorId, SourceId sourceId, int limit);

    SourceUploadResult upload(ActorId actorId, SourceId sourceId, String filename, byte[] content);

    SourceOperationView reindex(ActorId actorId, SourceId sourceId, SourceItemId itemId);

    SourceOperationView removeItem(ActorId actorId, SourceId sourceId, SourceItemId itemId);

    SourceOperationView deleteSource(ActorId actorId, SourceId sourceId);

    SourceOperationView getOperation(ActorId actorId, SourceOperationId operationId);
}
