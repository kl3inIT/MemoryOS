package io.memoryos.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.channel.ChannelAdapters;
import io.memoryos.channel.ChannelInboundService;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelSupervisor;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.ChatTurnService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The process starts from its own composition with no bot switched on: everything a Chat turn reaches is there, the
 * supervisor runs, and it reports ready. A bean a turn needs and the composition left out fails this test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1",
                "spring.data.redis.connect-timeout=100ms",
                "spring.data.redis.timeout=100ms"
        }
)
@AutoConfigureTestRestTemplate
class ChannelsApplicationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        ChannelsPostgresDatabase.configure(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate http;

    @Test
    void startsWithWhatATurnNeedsAndReportsReady() {
        assertTrue(context.getBean(ChannelSupervisor.class).isRunning());
        context.getBean(ChannelInboundService.class);
        context.getBean(ChatTurnService.class);
        context.getBean(ChatSessionService.class);

        var response = http.getForEntity("/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        // MEM-263: the address a platform pushes to is served, without a session; no bot receives over it here.
        context.getBean(ChannelAdapters.class).of(ChannelProvider.ZALO_BOT);
        var pushed = http.postForEntity("/hooks/channels/" + UUID.randomUUID(), "{}", String.class);
        assertEquals(HttpStatus.NOT_FOUND, pushed.getStatusCode());
    }
}
