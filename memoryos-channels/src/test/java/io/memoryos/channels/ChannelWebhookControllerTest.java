package io.memoryos.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.channel.ChannelSupervisor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MEM-263: the route a platform pushes to asks the bot's webhook about the headers before it reads a body, and answers
 * as the webhook judged the request, after the claim.
 */
class ChannelWebhookControllerTest {
    private static final UUID BOT = UUID.fromString("0af099b0-b70f-455d-af56-a05a53bbc7c5");
    private final ChannelSupervisor supervisor = mock(ChannelSupervisor.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ChannelWebhookController(supervisor)).build();
    }

    @Test
    void aRequestTheWebhookAcceptsIsAnsweredOnceItsMessagesAreClaimed() throws Exception {
        when(supervisor.authorize(eq(BOT), any())).thenReturn(ChannelSupervisor.Delivery.ACCEPTED);
        when(supervisor.deliver(eq(BOT), any(), any())).thenReturn(ChannelSupervisor.Delivery.ACCEPTED);

        mvc.perform(post("/hooks/channels/" + BOT).header("X-Bot-Api-Secret-Token", "s3cret")
                        .content("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        verify(supervisor).authorize(eq(BOT), argThat(headers -> "s3cret".equals(headers.get("x-bot-api-secret-token"))));
        verify(supervisor).deliver(eq(BOT), argThat(headers -> "s3cret".equals(headers.get("x-bot-api-secret-token"))),
                argThat(body -> new String(body, StandardCharsets.UTF_8).equals("{\"ok\":true}")));
    }

    @Test
    void aRequestWithoutThePlatformsSecretIsRefusedUnreadAndAnUnknownBotIsNotFound() throws Exception {
        when(supervisor.authorize(eq(BOT), any())).thenReturn(ChannelSupervisor.Delivery.REFUSED);
        mvc.perform(post("/hooks/channels/" + BOT).content("{}")).andExpect(status().isForbidden());

        UUID other = UUID.randomUUID();
        when(supervisor.authorize(eq(other), any())).thenReturn(ChannelSupervisor.Delivery.UNKNOWN);
        when(supervisor.holderAddress(other)).thenReturn(Optional.empty());
        // No live process holds it, as between two holders: the platform retries.
        mvc.perform(post("/hooks/channels/" + other).content("{}")).andExpect(status().isServiceUnavailable());

        mvc.perform(post("/hooks/channels/not-a-bot").content("{}")).andExpect(status().isBadRequest());
        verify(supervisor, never()).deliver(any(), any(), any());
    }

    /** MEM-283: a request for a bot another process holds is passed to it once and answered as it answered. */
    @Test
    void aRequestForABotHeldElsewhereIsPassedToItsHolderOnce() throws Exception {
        var seen = new ConcurrentHashMap<String, String>();
        var holder = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        holder.createContext("/hooks/channels/" + BOT, exchange -> {
            seen.put("secret", String.valueOf(exchange.getRequestHeaders().getFirst("X-Bot-Api-Secret-Token")));
            seen.put("passed", String.valueOf(exchange.getRequestHeaders().getFirst("X-MemoryOS-Passed")));
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        holder.start();
        try {
            when(supervisor.authorize(eq(BOT), any())).thenReturn(ChannelSupervisor.Delivery.UNKNOWN);
            when(supervisor.holderAddress(BOT)).thenReturn(Optional.of("127.0.0.1:" + holder.getAddress().getPort()));

            mvc.perform(post("/hooks/channels/" + BOT).header("X-Bot-Api-Secret-Token", "s3cret").content("{\"ok\":1}"))
                    .andExpect(status().isForbidden());
            assertEquals(Map.of("secret", "s3cret", "passed", "1", "body", "{\"ok\":1}"), Map.copyOf(seen));

            seen.clear();
            mvc.perform(post("/hooks/channels/" + BOT).header("X-MemoryOS-Passed", "1").content("{}"))
                    .andExpect(status().isServiceUnavailable());
            assertNull(seen.get("body"), "a request passed once is not passed again");
        } finally {
            holder.stop(0);
        }
        verify(supervisor, never()).deliver(any(), any(), any());
    }

    @Test
    void aBodyLargerThanAMessageIsNotRead() throws Exception {
        when(supervisor.authorize(eq(BOT), any())).thenReturn(ChannelSupervisor.Delivery.ACCEPTED);
        byte[] large = new byte[ChannelWebhookController.MAX_BODY + 1];
        Arrays.fill(large, (byte) 'a');

        mvc.perform(post("/hooks/channels/" + BOT).content(large)).andExpect(status().is(413));

        verify(supervisor, never()).deliver(any(), any(), any());
    }
}
