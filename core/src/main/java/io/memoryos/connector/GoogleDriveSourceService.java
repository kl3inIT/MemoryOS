package io.memoryos.connector;

import io.memoryos.identity.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface GoogleDriveSourceService {
    SelectionReceipt create(ActorId actorId, UUID requestId, String name, CredentialId credentialId, ScopeMode scopeMode, List<String> links);
    Configuration configuration(ActorId actorId, SourceId sourceId);
    SelectionReceipt replaceRoots(ActorId actorId, UUID requestId, SourceId sourceId, long expectedRevision,
            long expectedDiscoveryRevision, long expectedCredentialRevision, ScopeMode scopeMode,
            List<String> links, List<String> linkedDocumentIds);
    SelectionPolicy selectionPolicy(ActorId actorId);
    int selectionRequestByteLimit();
    SelectionReceipt selectionRequest(ActorId actorId, UUID requestId);
    SelectionDraft selectionDraft(ActorId actorId, SourceId sourceId);
    SelectionPage selection(ActorId actorId, SourceId sourceId, @Nullable String search,
            @Nullable SelectionKind kind, @Nullable String cursor, int size);
    SelectionTreePage selectionTree(ActorId actorId, SourceId sourceId, @Nullable String parentId,
            @Nullable String cursor, int size);
    Configuration discoverLinkedDocuments(ActorId actorId, SourceId sourceId, long expectedRevision);
    Configuration updateSchedule(ActorId actorId, SourceId sourceId, long expectedRevision, int syncIntervalMinutes);
    SourceOperationView synchronize(ActorId actorId, SourceId sourceId);

    enum ScopeMode { GENERAL, SPECIFIC }
    record SelectionReceipt(SourceId sourceId, SourceOperationView operation) {}
    record SelectionPolicy(int maxExplicitRootsPerSource, int maxRequestBytes, int maxLinkedDocuments) {}
    record SelectionDraft(long revision, long discoveryRevision, long credentialRevision,
            List<String> links, List<String> linkedDocumentIds) {}
    enum SelectionKind { FOLDER, FILE, LINKED }
    record SelectionCounts(long folders, long files, long linkedDocuments, long approvedLinkedDocuments) {}
    record SelectionItem(String id, String name, String mimeType, SelectionKind kind, boolean selected,
            boolean coveredByRoots, LinkedDocumentStatus status, List<LinkOrigin> origins) {}
    record SelectionPage(long revision, long discoveryRevision, long credentialRevision,
            List<SelectionItem> items, @Nullable String nextCursor, SelectionCounts counts) {}
    record SelectionTreeItem(String id, String name, String mimeType, SelectionKind kind, boolean selected,
            boolean coveredByRoots, LinkedDocumentStatus status, List<LinkOrigin> origins, boolean expandable) {}
    record SelectionTreePage(long revision, long discoveryRevision, long credentialRevision,
            List<SelectionTreeItem> items, @Nullable String nextCursor, SelectionCounts counts) {}

    record Root(String id, String name, String mimeType) {}
    enum LinkedDocumentStatus { AVAILABLE, UNAVAILABLE, UNSUPPORTED }
    record LinkOrigin(String rootId, String parentId, String parentName, String location) {}
    record LinkedDocument(String id, String name, String mimeType, boolean selected, boolean coveredByRoots,
            LinkedDocumentStatus status, List<LinkOrigin> origins) {
        public LinkedDocument { origins = List.copyOf(origins); }
    }
    record DiscoveryError(String fileId, String fileName, String code) {}
    record Configuration(SourceId sourceId, CredentialId credentialId, String accountEmail, String credentialStatus,
            long credentialRevision, boolean oauthClientConfigured, long revision, int syncIntervalMinutes,
            long scheduleRevision, ScopeMode scopeMode, SelectionCounts counts, long discoveryRevision,
            @Nullable Instant discoveredAt, List<DiscoveryError> discoveryErrors,
            @Nullable Instant lastSyncedAt, boolean pendingWork, @Nullable String errorCode,
            @Nullable SourceOperationView pendingSelectionOperation) {
        public Configuration { discoveryErrors = List.copyOf(discoveryErrors); }
    }
}
