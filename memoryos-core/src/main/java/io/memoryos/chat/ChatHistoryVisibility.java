package io.memoryos.chat;

/**
 * How much of the Tenant's conversations an administrative reader may see, as Onyx's {@code query_history_type}.
 *
 * <p>{@link #ANONYMIZED} hides who asked and nothing else: a question often names its author anyway, so the screen
 * says as much rather than letting the word stand for more than it does.
 */
public enum ChatHistoryVisibility {
    /** Conversations are readable and name the person who asked. */
    NORMAL,
    /** Conversations are readable; the asker's name and e-mail are dropped before they leave the server. */
    ANONYMIZED,
    /** Nobody reads another person's conversations, whatever they hold. Conversations are still recorded. */
    DISABLED
}
