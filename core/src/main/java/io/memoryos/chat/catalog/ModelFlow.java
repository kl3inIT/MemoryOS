package io.memoryos.chat.catalog;

/** A task that may use its own Tenant model instead of the conversation model (Onyx {@code LLMModelFlowType}). */
public enum ModelFlow {
    CHAT_NAMING,
    /** Summary, decisions and action items for a recorded meeting. */
    MEETING_MINUTES,
    /** Proposals for the stretches of a transcript the speech provider was unsure of. */
    MEETING_CORRECTION
}
