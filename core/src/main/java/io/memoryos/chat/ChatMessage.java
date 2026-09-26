package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId,
        @Nullable UUID latestChildMessageId, Role role, @Nullable String content, Status status,
        Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources, List<ChatFileDescriptor> files, List<ChatArtifact> artifacts,
        ChatActivity activity, ChatResearch research, @Nullable String failureCode, @Nullable String refusalReason) {
    /** MEM-195 refusal reasons: a completed answer that declined rather than answered. */
    public static final String NO_EVIDENCE = "no_evidence";
    public static final String UNCITED = "uncited";
    public static final String BLOCKED_TOPIC = "blocked_topic";
    public ChatMessage {
        sources = List.copyOf(sources); files = List.copyOf(files); artifacts = List.copyOf(artifacts);
        if (activity == null) activity = ChatActivity.EMPTY;
        if (research == null) research = ChatResearch.EMPTY;
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources,
                       List<ChatFileDescriptor> files, List<ChatArtifact> artifacts, ChatActivity activity, ChatResearch research,
                       @Nullable String failureCode) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, sources, files, artifacts, activity, research, failureCode, null);
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources,
                       List<ChatFileDescriptor> files, List<ChatArtifact> artifacts, ChatActivity activity, ChatResearch research) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, sources, files, artifacts, activity, research, null, null);
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources,
                       List<ChatFileDescriptor> files, List<ChatArtifact> artifacts, ChatActivity activity) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, sources, files, artifacts, activity, ChatResearch.EMPTY);
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources,
                       List<ChatFileDescriptor> files, List<ChatArtifact> artifacts) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, sources, files, artifacts, ChatActivity.EMPTY);
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources, List<ChatFileDescriptor> files) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, sources, files, List.of());
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, sources, List.of());
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, List.of());
    }
    public enum Role { ROOT, USER, ASSISTANT }
    public enum Status { COMPLETED, RUNNING, CANCELED, FAILED }
}
