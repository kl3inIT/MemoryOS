package io.memoryos.usage;

/** The task an AI call served (Onyx {@code LLMFlow}); usage and costs are reported per flow. */
public enum AiUsageFlow {
    CHAT,
    CHAT_NAMING,
    /** MEM-208: the guardrail check before an answer, recorded apart so Chat counts each turn once. */
    CHAT_GUARDRAIL,
    /** MEM-268: the summary of a long conversation's earlier turns, recorded apart so Chat counts each turn once. */
    CHAT_COMPACTION,
    /** MEM-280: an Agent Builder turn, which edits an assistant's unsaved settings rather than answering. */
    AGENT_BUILDER,
    DEEP_RESEARCH,
    EMBEDDING_QUERY,
    EMBEDDING_INDEXING,
    IMAGE_GENERATION,
    IMAGE_EDIT,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    MEETING_MINUTES,
    MEETING_CORRECTION,
    /** MEM-243: a page of a scan or an image read by a language model chosen as the OCR reader. */
    OCR
}
