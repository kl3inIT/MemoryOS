package io.memoryos.chat;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId,
        @Nullable UUID latestChildMessageId, Role role, @Nullable String content, Status status,
        Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources, List<ChatFileDescriptor> files, List<ChatArtifact> artifacts,
        ChatActivity activity, ChatResearch research, @Nullable String failureCode, @Nullable String refusalReason) {
    /** MEM-195 refusal reasons: a completed answer that declined rather than answered. */
    public static final String NO_EVIDENCE = "no_evidence";
    public static final String UNCITED = "uncited";
    public static final String BLOCKED_TOPIC = "blocked_topic";

    /**
     * MEM-208: the questions among {@code messages} whose reply the guardrails stopped, found through each reply's parent,
     * since an edit or a branch reorders a history and a reply is not always next to its question.
     */
    public static Set<UUID> blockedQuestions(Collection<ChatMessage> messages) {
        var blocked = new HashSet<UUID>();
        for (var message : messages)
            if (message.role() == Role.ASSISTANT && BLOCKED_TOPIC.equals(message.refusalReason()) && message.parentMessageId() != null)
                blocked.add(message.parentMessageId());
        return blocked;
    }
    public ChatMessage {
        sources = List.copyOf(sources); files = List.copyOf(files); artifacts = List.copyOf(artifacts);
        if (activity == null) activity = ChatActivity.EMPTY;
        if (research == null) research = ChatResearch.EMPTY;
    }
    public enum Role { ROOT, USER, ASSISTANT }
    public enum Status { COMPLETED, RUNNING, CANCELED, FAILED }

    /** A message with what every message has; the builder adds what only some carry. */
    public static Builder builder(UUID id, UUID sessionId, Role role, Status status, Instant createdAt) {
        return new Builder(id, sessionId, role, status, createdAt);
    }

    public static final class Builder {
        private final UUID id;
        private final UUID sessionId;
        private final Role role;
        private final Status status;
        private final Instant createdAt;
        private @Nullable UUID parentMessageId;
        private @Nullable UUID latestChildMessageId;
        private @Nullable String content;
        private @Nullable Instant finishedAt;
        private List<ChatSource> sources = List.of();
        private List<ChatFileDescriptor> files = List.of();
        private List<ChatArtifact> artifacts = List.of();
        private ChatActivity activity = ChatActivity.EMPTY;
        private ChatResearch research = ChatResearch.EMPTY;
        private @Nullable String failureCode;
        private @Nullable String refusalReason;

        private Builder(UUID id, UUID sessionId, Role role, Status status, Instant createdAt) {
            this.id = id;
            this.sessionId = sessionId;
            this.role = role;
            this.status = status;
            this.createdAt = createdAt;
        }

        public Builder parentMessageId(@Nullable UUID value) { parentMessageId = value; return this; }
        public Builder latestChildMessageId(@Nullable UUID value) { latestChildMessageId = value; return this; }
        public Builder content(@Nullable String value) { content = value; return this; }
        public Builder finishedAt(@Nullable Instant value) { finishedAt = value; return this; }
        public Builder sources(List<ChatSource> value) { sources = value; return this; }
        public Builder files(List<ChatFileDescriptor> value) { files = value; return this; }
        public Builder artifacts(List<ChatArtifact> value) { artifacts = value; return this; }
        public Builder activity(ChatActivity value) { activity = value; return this; }
        public Builder research(ChatResearch value) { research = value; return this; }
        public Builder failureCode(@Nullable String value) { failureCode = value; return this; }
        public Builder refusalReason(@Nullable String value) { refusalReason = value; return this; }

        public ChatMessage build() {
            return new ChatMessage(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt,
                    finishedAt, sources, files, artifacts, activity, research, failureCode, refusalReason);
        }
    }
}
