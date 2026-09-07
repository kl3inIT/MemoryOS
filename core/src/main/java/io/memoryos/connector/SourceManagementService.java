package io.memoryos.connector;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupIdentityPage;
import io.memoryos.objectstorage.ObjectUploadAuthorization;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadSpecification;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

public interface SourceManagementService {

    SourceDetail createFileSource(ActorId actorId, String name, Collection<GroupId> groupIds);

    List<SourceSummary> listSources(ActorId actorId);

    SourceDetail getSource(ActorId actorId, SourceId sourceId);

    List<GroupIdentity> listSourceGroups(ActorId actorId, SourceId sourceId);

    void replaceSourceGroups(ActorId actorId, SourceId sourceId, Collection<GroupId> groupIds);

    GroupIdentityPage listSourceGroupOptions(
            ActorId actorId,
            @Nullable String search,
            int page,
            int size
    );

    List<SourceSummary> listGroupSources(ActorId actorId, GroupId groupId);

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
