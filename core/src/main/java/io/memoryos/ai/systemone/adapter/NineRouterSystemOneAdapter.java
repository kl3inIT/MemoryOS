package io.memoryos.ai.systemone.adapter;

import io.memoryos.ai.systemone.SystemOneAdapter;
import io.memoryos.ai.systemone.SystemOneCapabilities;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProtocol;
import io.memoryos.ai.systemone.SystemOneProvider;
import java.time.Duration;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.stereotype.Component;

/**
 * The 9Router gateway serves the protocol at its {@code /v1} and routes by the model, which names the upstream
 * provider ({@code openrouter/typesafe/jev-1.13}); the administrator names it, since the gateway decides what exists.
 */
@Component
public final class NineRouterSystemOneAdapter implements SystemOneAdapter {
    @Override public SystemOneProvider provider() { return SystemOneProvider.NINEROUTER; }
    @Override public SystemOneCapabilities capabilities() {
        return new SystemOneCapabilities(true, SystemOneCapabilities.Endpoint.URL, "", null);
    }

    @Override public TypeSafeClient client(SystemOneConnectionService.Connection connection, String key, Duration timeout) {
        return SystemOneProtocol.client(connection.endpoint(), key, connection.model(), timeout);
    }
}
