package io.memoryos.ai.systemone;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The call a System One connection answers: one choice among labels. It runs outside any transaction; the caller
 * decides what a failure means.
 */
@Component
public class SystemOneClient {
    /** One deadline for the whole exchange: a check that waits longer than a language model would is no gain. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Question PROBE = new Question("The sky is blue.", "Is the statement about the sky?",
            options("yes", "The statement is about the sky.", "no", "The statement is about something else."));

    private final SystemOneAdapterRegistry adapters;
    private final SystemOneConnectionService connections;

    public SystemOneClient(SystemOneAdapterRegistry adapters, SystemOneConnectionService connections) {
        this.adapters = adapters;
        this.connections = connections;
    }

    /**
     * A choice question: the content to judge, what to decide, and the labels to choose from, each with what it
     * means. The labels keep their order.
     */
    public record Question(String state, String instructions, Map<String, String> options) {
        public Question {
            if (state.isBlank() || instructions.isBlank() || options.size() < 2)
                throw new IllegalArgumentException("A choice needs content, instructions and two options");
            options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }
        @Override public @NonNull String toString() { return "SystemOneQuestion[redacted]"; }
    }

    /**
     * The answer: the chosen label, always one of the question's, and how sure the model is. Token counts are null
     * when the service reported none.
     */
    public record Decision(String label, double confidence, Map<String, Double> probabilities,
                           @Nullable Long inputTokens, @Nullable Long outputTokens) {
        public Decision {
            probabilities = Map.copyOf(probabilities);
        }
    }

    public Decision choose(SystemOneConnectionService.Connection connection, Question question) {
        var decision = adapters.adapter(connection.provider())
                .choose(connection, connections.key(connection), question, TIMEOUT);
        if (!question.options().containsKey(decision.label()))
            throw new IllegalStateException("The System One answer names no offered label");
        return decision;
    }

    /** One self-contained question, to show a connection answers. */
    public void test(SystemOneConnectionService.Connection connection) {
        choose(connection, PROBE);
    }

    private static Map<String, String> options(String first, String firstMeaning, String second, String secondMeaning) {
        var options = new LinkedHashMap<String, String>();
        options.put(first, firstMeaning);
        options.put(second, secondMeaning);
        return options;
    }
}
