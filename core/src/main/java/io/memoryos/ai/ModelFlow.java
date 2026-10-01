package io.memoryos.ai;

/** A task that may use its own Tenant model instead of the conversation model (Onyx {@code LLMModelFlowType}). */
public enum ModelFlow {
    CHAT_NAMING,
    /**
     * The guardrail check before a grounded or guarded answer: small talk, a question or a blocked topic. It must
     * return a structured verdict, so it may use a model that does so reliably while the conversation model answers.
     */
    CHAT_GUARDRAIL,
    /** Summary, decisions and action items for a recorded meeting. */
    MEETING_MINUTES,
    /** Proposals for the stretches of a transcript the speech provider was unsure of. */
    MEETING_CORRECTION
}
