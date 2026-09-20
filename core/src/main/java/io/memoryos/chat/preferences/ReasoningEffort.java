package io.memoryos.chat.preferences;

/**
 * How much a reasoning model should think, as a member may choose it. Onyx offers the same levels plus {@code xhigh},
 * which only OpenAI and Anthropic adaptive models tell apart; MemoryOS leaves it out until levels are filtered per
 * provider. The provider name of each level is what the model options already accept.
 */
public enum ReasoningEffort {
    OFF("none"), LOW("low"), MEDIUM("medium"), HIGH("high");

    private final String providerValue;

    ReasoningEffort(String providerValue) {
        this.providerValue = providerValue;
    }

    /** The value sent to the provider as {@code reasoning_effort}. */
    public String providerValue() {
        return providerValue;
    }
}
