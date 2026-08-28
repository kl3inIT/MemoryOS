package io.memoryos.connector;

import io.memoryos.identity.ActorId;

import java.util.List;

public interface SourceManagementService {

    SourceDetail createFileSource(ActorId actorId, String name);

    List<SourceSummary> listSources(ActorId actorId);

    SourceDetail getSource(ActorId actorId, SourceId sourceId);

    List<SourceOperationView> listIndexOperations(ActorId actorId, SourceId sourceId);

    SourceUploadResult upload(
            ActorId actorId,
            SourceId sourceId,
            String filename,
            byte[] content,
            String sha256
    );

    SourceOperationView reindex(ActorId actorId, SourceId sourceId, SourceItemId itemId);

    SourceOperationView removeItem(ActorId actorId, SourceId sourceId, SourceItemId itemId);

    SourceOperationView deleteSource(ActorId actorId, SourceId sourceId);

    SourceOperationView getOperation(ActorId actorId, SourceOperationId operationId);
}
