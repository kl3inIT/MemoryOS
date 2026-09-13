package io.memoryos.connector;

import io.memoryos.iam.ActorId;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface GoogleDriveAclService {
    Page list(ActorId actor, SourceId source, Query query);
    File get(ActorId actor, SourceId source, String fileId);

    record Query(@Nullable String cursor, int size, @Nullable String query) {}
    record Page(List<Item> items, @Nullable String nextCursor, long totalItems) {
        public Page { items = List.copyOf(items); }
    }
    record Item(String fileId, String name, GoogleDriveAclSnapshot.@Nullable Status status,
            GoogleDriveAclSnapshot.@Nullable ContextStatus contextStatus, @Nullable Long revision,
            @Nullable Integer permissionCount, @Nullable Instant lastSuccessAt, @Nullable Instant lastAttemptAt) {}
    record File(String fileId, String name, @Nullable GoogleDriveAclSnapshot snapshot) {}
}
