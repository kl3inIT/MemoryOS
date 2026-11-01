package io.memoryos.ai.systemone.adapter;

import io.memoryos.ai.systemone.SystemOneAdapter;
import io.memoryos.ai.systemone.SystemOneCapabilities;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProtocol;
import io.memoryos.ai.systemone.SystemOneProvider;
import java.time.Duration;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.stereotype.Component;

/** Any server that speaks the protocol; nothing about it is assumed. */
@Component
public final class CompatibleSystemOneAdapter implements SystemOneAdapter {
    @Override public SystemOneProvider provider() { return SystemOneProvider.SYSTEMONE_COMPATIBLE; }
    @Override public SystemOneCapabilities capabilities() {
        return new SystemOneCapabilities(false, SystemOneCapabilities.Endpoint.URL, "", null);
    }

    @Override public TypeSafeClient client(SystemOneConnectionService.Connection connection, String key, Duration timeout) {
        return SystemOneProtocol.client(connection.endpoint(), key, connection.model(), timeout);
    }
}
