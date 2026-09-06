package io.memoryos.connector;

import io.memoryos.identity.ActorId;
import io.memoryos.objectstorage.ObjectUploadAuthorization;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadSpecification;

import java.util.List;

public interface SourceManagementService {

    SourceDetail createFileSource(ActorId actorId, String name);

    List<SourceSummary> listSources(ActorId actorId);

    SourceDetail getSource(ActorId actorId, SourceId sourceId);

    List<SourceOperationView> listIndexAttempts(ActorId actorId, SourceId sourceId, int limit);

    ObjectUploadAuthorization initiateUpload(
            ActorId actorId,
            SourceId sourceId,
            ObjectUploadSpecification specification
    );

    SourceUploadReceipt finalizeUpload(ActorId actorId, SourceId sourceId, ObjectUploadId uploadId);

    SourceOperationView reindex(ActorId actorId, SourceId sourceId, SourceItemId itemId);

    SourceOperationView removeItem(ActorId actorId, SourceId sourceId, SourceItemId itemId);

    SourceOperationView deleteSource(ActorId actorId, SourceId sourceId);

    SourceOperationView getOperation(ActorId actorId, SourceOperationId operationId);
}
