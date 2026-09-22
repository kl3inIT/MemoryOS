package io.memoryos.usage;

/** The task an AI call served (Onyx {@code LLMFlow}); usage and costs are reported per flow. */
public enum AiUsageFlow {
    CHAT,
    CHAT_NAMING,
    DEEP_RESEARCH,
    EMBEDDING_QUERY,
    EMBEDDING_INDEXING,
    IMAGE_GENERATION,
    IMAGE_EDIT,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    MEETING_MINUTES,
    MEETING_CORRECTION
}
