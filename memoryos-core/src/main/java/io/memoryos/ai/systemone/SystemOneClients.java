package io.memoryos.ai.systemone;

import java.time.Duration;
import java.util.Map;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Noul;
import org.springframework.stereotype.Component;

/**
 * The {@link TypeSafeClient} of a System One connection: what a caller hands to the library's components, such as
 * {@code JevGuardrail} (ADR 0026). Calls run outside any transaction; the caller decides what a failure means.
 */
@Component
public class SystemOneClients {
    /** One deadline for the whole exchange: a check that waits longer than a language model would is no gain. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String PROBE = "probe";

    private final SystemOneAdapterRegistry adapters;
    private final SystemOneConnectionService connections;

    public SystemOneClients(SystemOneAdapterRegistry adapters, SystemOneConnectionService connections) {
        this.adapters = adapters;
        this.connections = connections;
    }

    public TypeSafeClient client(SystemOneConnectionService.Connection connection) {
        return adapters.adapter(connection.provider()).client(connection, connections.key(connection), TIMEOUT);
    }

    /** One self-contained question, to show a connection answers; a reply without its answer is a failure. */
    public void test(SystemOneConnectionService.Connection connection) {
        client(connection).systemOne("The sky is blue.", Map.of(PROBE, Noul.of("Is the statement about the sky?")))
                .noulValue(PROBE);
    }
}
