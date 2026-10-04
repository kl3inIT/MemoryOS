package io.memoryos.ai.systemone.adapter;

import io.memoryos.ai.systemone.SystemOneAdapter;
import io.memoryos.ai.systemone.SystemOneCapabilities;
import io.memoryos.ai.systemone.SystemOneClient;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProtocol;
import io.memoryos.ai.systemone.SystemOneProvider;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** TypeSafe's hosted Jev: one address, a key, and a published price of 0.042 USD per million input tokens. */
@Component
public final class TypeSafeSystemOneAdapter implements SystemOneAdapter {
    static final String BASE = "https://api.typesafe.ai/v1";

    @Override public SystemOneProvider provider() { return SystemOneProvider.TYPESAFE; }
    @Override public SystemOneCapabilities capabilities() {
        return new SystemOneCapabilities(true, SystemOneCapabilities.Endpoint.FIXED, "jev-latest", 0.042);
    }

    @Override public SystemOneClient.Decision choose(SystemOneConnectionService.Connection connection, String key,
                                                     SystemOneClient.Question question, Duration timeout) {
        return SystemOneProtocol.choose(BASE, key, connection.model(), question, timeout);
    }
}
