package io.memoryos.ai;

/**
 * Why a model turn stopped. The code is stored on the failed answer and read by the web client, so it never changes.
 * The codes keep the {@code CHAT_} prefix they had while model calls lived in Chat. A failure that is not
 * {@linkplain #reported() reported} is stored as {@code CHAT_EXECUTION_FAILED}.
 */
public enum TurnFailure {
    OUTPUT_LIMIT("CHAT_OUTPUT_LIMIT", true),
    CYCLE_LIMIT("CHAT_CYCLE_LIMIT", true),
    BUDGET_EXCEEDED("CHAT_BUDGET_EXCEEDED", true),
    MODEL_UNAVAILABLE("CHAT_MODEL_UNAVAILABLE", true),
    INCOMPLETE_RESPONSE("CHAT_INCOMPLETE_RESPONSE", true),
    LAST_CYCLE_TOOL_CALL("CHAT_LAST_CYCLE_TOOL_CALL", true),
    UNSUPPORTED_OPTIONS("CHAT_UNSUPPORTED_OPTIONS", true),
    EMPTY_RESPONSE("CHAT_EMPTY_RESPONSE", true),
    CONTEXT_LIMIT("CHAT_CONTEXT_LIMIT", true),
    MODEL_OUTPUT_LIMIT("CHAT_MODEL_OUTPUT_LIMIT", true),
    DEADLINE("CHAT_DEADLINE", false),
    PROVIDER_UNAVAILABLE("CHAT_PROVIDER_UNAVAILABLE", false);

    private final String code;
    private final boolean reported;

    TurnFailure(String code, boolean reported) {
        this.code = code;
        this.reported = reported;
    }

    public String code() {
        return code;
    }

    /** Whether the stored answer carries this code rather than {@code CHAT_EXECUTION_FAILED}. */
    public boolean reported() {
        return reported;
    }

    public TurnFailureException exception() {
        return new TurnFailureException(this);
    }
}
