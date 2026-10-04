package io.memoryos.ai.systemone;

import java.time.Duration;

/**
 * One connection type's protocol. {@link SystemOneAdapterRegistry} holds exactly one class per
 * {@link SystemOneProvider}.
 */
public interface SystemOneAdapter {
    SystemOneProvider provider();
    SystemOneCapabilities capabilities();

    /**
     * Asks one choice question. A failure of any kind (an unreachable service, a status other than 2xx, an answer
     * that names no offered label) is a {@link RuntimeException}; its message may name the address and is never
     * logged or shown.
     *
     * @param key the connection's plaintext key, empty when it has none
     */
    SystemOneClient.Decision choose(SystemOneConnectionService.Connection connection, String key,
                                    SystemOneClient.Question question, Duration timeout);
}
