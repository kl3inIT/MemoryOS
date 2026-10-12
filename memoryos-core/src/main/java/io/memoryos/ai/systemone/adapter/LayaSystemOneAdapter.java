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
 * A self-hosted {@code laya.serve}: the protocol without a key, and {@code auto} lets its router pick the checkpoint
 * (the multilingual one for Vietnamese).
 */
@Component
public final class LayaSystemOneAdapter implements SystemOneAdapter {
    @Override public SystemOneProvider provider() { return SystemOneProvider.LAYA; }
    @Override public SystemOneCapabilities capabilities() {
        return new SystemOneCapabilities(false, SystemOneCapabilities.Endpoint.URL, "auto", 0.0);
    }

    @Override public TypeSafeClient client(SystemOneConnectionService.Connection connection, String key, Duration timeout) {
        return SystemOneProtocol.client(connection.endpoint(), key, connection.model(), timeout);
    }
}
