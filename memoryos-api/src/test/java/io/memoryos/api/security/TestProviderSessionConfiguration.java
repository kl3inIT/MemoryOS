package io.memoryos.api.security;

import io.memoryos.iam.ProviderSessionTerminator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Records provider sessions that sign-out asks to end, and whether the fake provider ends them. */
@TestConfiguration(proxyBeanMethods = false)
class TestProviderSessionConfiguration {

    private static final AtomicBoolean ENDS_SESSIONS = new AtomicBoolean(true);
    private static final List<String> ENDED_SESSIONS = new CopyOnWriteArrayList<>();

    static void reset(boolean endsSessions) {
        ENDED_SESSIONS.clear();
        ENDS_SESSIONS.set(endsSessions);
    }

    static List<String> endedSessions() {
        return List.copyOf(ENDED_SESSIONS);
    }

    @Bean
    @Primary
    ProviderSessionTerminator testProviderSessionTerminator() {
        return providerSessionId -> {
            ENDED_SESSIONS.add(providerSessionId);
            return ENDS_SESSIONS.get();
        };
    }
}
