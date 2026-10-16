package io.memoryos.connector;

import io.memoryos.iam.GroupId;
import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Creating and reconfiguring a SharePoint Source. Creation and every scope change are accepted for
 * asynchronous verification against Microsoft and become effective only when that verification succeeds,
 * so a pasted address is never trusted without being resolved first.
 */
public interface SharePointSourceService {
    SelectionReceipt create(ActorId actorId, UUID requestId, String name, CredentialId credentialId,
            Scope scope, SourceAccess access, List<GroupId> groupIds);

    SelectionReceipt replaceScope(ActorId actorId, UUID requestId, SourceId sourceId, long expectedScopeRevision,
            long expectedCredentialRevision, Scope scope);

    Configuration configuration(ActorId actorId, SourceId sourceId);

    RootPage roots(ActorId actorId, SourceId sourceId, @Nullable String cursor, int size);

    SelectionPolicy selectionPolicy(ActorId actorId);

    int selectionRequestByteLimit();

    SelectionReceipt selectionRequest(ActorId actorId, UUID requestId);

    Configuration updateSchedule(ActorId actorId, SourceId sourceId, long expectedScheduleRevision,
            int syncIntervalMinutes, int pruneIntervalHours);

    Configuration setPaused(ActorId actorId, SourceId sourceId, long expectedScheduleRevision, boolean paused);

    SourceOperationView synchronize(ActorId actorId, SourceId sourceId);

    enum ScopeMode { ALL_SITES, SPECIFIC }

    enum RootKind { SITE, LIBRARY, FOLDER }

    /**
     * What an administrator asked to synchronize. {@code siteUrls} is empty for {@link ScopeMode#ALL_SITES},
     * where every site the application can read is in scope except the excluded ones.
     */
    record Scope(ScopeMode scopeMode, List<String> siteUrls, List<String> excludedSites, List<String> excludedPaths,
                 boolean includeDocuments, boolean includePages, int syncIntervalMinutes, int pruneIntervalHours) {
        public Scope {
            siteUrls = List.copyOf(siteUrls);
            excludedSites = List.copyOf(excludedSites);
            excludedPaths = List.copyOf(excludedPaths);
        }
    }

    record SelectionReceipt(SourceId sourceId, SourceOperationView operation) {}

    record SelectionPolicy(int maxRootsPerSource, int maxExclusionsPerKind, int maxRequestBytes) {}

    record RootView(String url, RootKind kind, @Nullable String displayName, boolean verified) {}

    record RootPage(long scopeRevision, List<RootView> roots, @Nullable String nextCursor, long total) {
        public RootPage { roots = List.copyOf(roots); }
    }

    record Configuration(SourceId sourceId, CredentialId credentialId, String credentialName, String credentialStatus,
                         long credentialRevision, long scopeRevision, ScopeMode scopeMode, long rootCount,
                         List<String> excludedSites, List<String> excludedPaths, boolean includeDocuments,
                         boolean includePages, int syncIntervalMinutes, int pruneIntervalHours, long scheduleRevision,
                         boolean syncPaused, @Nullable String tenantHost, @Nullable Instant lastSyncedAt,
                         @Nullable Instant lastPrunedAt, boolean pendingWork, @Nullable String errorCode,
                         @Nullable SourceOperationView pendingSelectionOperation) {
        public Configuration {
            excludedSites = List.copyOf(excludedSites);
            excludedPaths = List.copyOf(excludedPaths);
        }
    }
}
