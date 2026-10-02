package io.memoryos.usage;

/** The task an AI call served (Onyx {@code LLMFlow}); usage and costs are reported per flow. */
public enum AiUsageFlow {
    CHAT,
    CHAT_NAMING,
    /** MEM-208: the guardrail check before an answer, recorded apart so Chat counts each turn once. */
    CHAT_GUARDRAIL,
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
