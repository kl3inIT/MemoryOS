package io.memoryos.ai.systemone;

import java.time.Duration;
import org.springaicommunity.typesafe.TypeSafeClient;

/**
 * One connection type: what it needs and how its {@link TypeSafeClient} is built. {@link SystemOneAdapterRegistry}
 * holds exactly one class per {@link SystemOneProvider}. Everything asked of a connection goes through that client,
 * so the library's components (ADR 0026) work with every type.
 */
public interface SystemOneAdapter {
    SystemOneProvider provider();
    SystemOneCapabilities capabilities();

    /**
     * The client of one connection. Its calls fail with a {@link RuntimeException} whose message may name the address
     * and is never logged or shown.
     *
     * @param key the connection's plaintext key, empty when it has none
     */
    TypeSafeClient client(SystemOneConnectionService.Connection connection, String key, Duration timeout);
}
