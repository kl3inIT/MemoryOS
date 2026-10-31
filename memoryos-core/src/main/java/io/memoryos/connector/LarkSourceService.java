package io.memoryos.connector;

import io.memoryos.iam.GroupId;
import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Creating and reconfiguring a Lark Suite Source. Creation and every scope change are accepted for asynchronous
 * verification against Lark and become effective only when that verification succeeds, so a space or a folder is
 * never trusted without the app having read it first.
 */
public interface LarkSourceService {
    /** The most roots one Source names. */
    int MAX_ROOTS = 100;

    SelectionReceipt create(ActorId actorId, UUID requestId, String name, CredentialId credentialId,
            Scope scope, SourceAccess access, List<GroupId> groupIds);

    SelectionReceipt replaceScope(ActorId actorId, UUID requestId, SourceId sourceId, long expectedScopeRevision,
            long expectedCredentialRevision, Scope scope);

    Configuration configuration(ActorId actorId, SourceId sourceId);

    List<RootView> roots(ActorId actorId, SourceId sourceId);

    /** The Wiki spaces the credential's app is a member of now, read from Lark: what a Source may name. */
    List<SpaceOption> spaces(ActorId actorId, CredentialId credentialId);

    SelectionReceipt selectionRequest(ActorId actorId, UUID requestId);

    Configuration updateSchedule(ActorId actorId, SourceId sourceId, long expectedScheduleRevision,
            int syncIntervalMinutes);

    Configuration setPaused(ActorId actorId, SourceId sourceId, long expectedScheduleRevision, boolean paused);

    SourceOperationView synchronize(ActorId actorId, SourceId sourceId);

    enum RootKind { WIKI_SPACE, DRIVE_FOLDER }

    /**
     * One thing to read: a Wiki space by its ID, or a Drive folder by its link or token. A folder has to be named,
     * because Lark does not list the folders shared with an app.
     */
    record Root(RootKind kind, String value) {}

    record Scope(List<Root> roots, int syncIntervalMinutes) {
        public Scope { roots = List.copyOf(roots); }
    }

    record SelectionReceipt(SourceId sourceId, SourceOperationView operation) {}

    record RootView(RootKind kind, String token, @Nullable String displayName) {}

    record SpaceOption(String spaceId, String name) {}

    record Configuration(SourceId sourceId, CredentialId credentialId, String credentialName, String credentialStatus,
                         long credentialRevision, long scopeRevision, long rootCount, int syncIntervalMinutes,
                         long scheduleRevision, boolean syncPaused, @Nullable Instant lastSyncedAt,
                         boolean pendingWork, @Nullable String errorCode,
                         @Nullable SourceOperationView pendingSelectionOperation) {}
}
