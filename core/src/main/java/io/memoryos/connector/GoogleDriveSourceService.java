package io.memoryos.connector;

import io.memoryos.identity.ActorId;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface GoogleDriveSourceService {
    SourceId create(ActorId actorId, String name, CredentialId credentialId, ScopeMode scopeMode, List<String> links);
    Configuration configuration(ActorId actorId, SourceId sourceId);
    Configuration replaceRoots(ActorId actorId, SourceId sourceId, long expectedRevision, ScopeMode scopeMode, List<String> links);
    Configuration updateSchedule(ActorId actorId, SourceId sourceId, long expectedRevision, int syncIntervalMinutes);
    SourceOperationView synchronize(ActorId actorId, SourceId sourceId);

    enum ScopeMode { GENERAL, SPECIFIC }

    record Root(String id, String name, String mimeType) {}
    record Configuration(SourceId sourceId, CredentialId credentialId, String accountEmail, String credentialStatus,
            long credentialRevision, boolean oauthClientConfigured, long revision, int syncIntervalMinutes,
            long scheduleRevision, ScopeMode scopeMode, List<Root> roots,
            @Nullable Instant lastSyncedAt, boolean pendingWork, @Nullable String errorCode) {
        public Configuration { roots = List.copyOf(roots); }
    }
}
