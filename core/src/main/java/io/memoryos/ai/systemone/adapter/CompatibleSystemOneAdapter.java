package io.memoryos.ai.systemone.adapter;

import io.memoryos.ai.systemone.SystemOneAdapter;
import io.memoryos.ai.systemone.SystemOneCapabilities;
import io.memoryos.ai.systemone.SystemOneClient;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProtocol;
import io.memoryos.ai.systemone.SystemOneProvider;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Any server that speaks the protocol; nothing about it is assumed. */
@Component
public final class CompatibleSystemOneAdapter implements SystemOneAdapter {
    @Override public SystemOneProvider provider() { return SystemOneProvider.SYSTEMONE_COMPATIBLE; }
    @Override public SystemOneCapabilities capabilities() {
        return new SystemOneCapabilities(false, SystemOneCapabilities.Endpoint.URL, "", null);
    }

    @Override public SystemOneClient.Decision choose(SystemOneConnectionService.Connection connection, String key,
                                                     SystemOneClient.Question question, Duration timeout) {
        return SystemOneProtocol.choose(connection.endpoint(), key, connection.model(), question, timeout);
    }
}
