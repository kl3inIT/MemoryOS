package io.memoryos.channels;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.memoryos.channel.ChannelSupervisor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** MEM-263: the route a platform pushes to answers as the bot's webhook judged the request, after the claim. */
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
        when(supervisor.deliver(eq(BOT), any(), any())).thenReturn(ChannelSupervisor.Delivery.ACCEPTED);

        mvc.perform(post("/hooks/channels/" + BOT).header("X-Bot-Api-Secret-Token", "s3cret")
                        .content("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        verify(supervisor).deliver(eq(BOT), argThat(headers -> "s3cret".equals(headers.get("x-bot-api-secret-token"))),
                argThat(body -> new String(body, StandardCharsets.UTF_8).equals("{\"ok\":true}")));
    }

    @Test
    void aRequestWithoutThePlatformsSecretIsRefusedAndAnUnknownBotIsNotFound() throws Exception {
        when(supervisor.deliver(eq(BOT), any(), any())).thenReturn(ChannelSupervisor.Delivery.REFUSED);
        mvc.perform(post("/hooks/channels/" + BOT).content("{}")).andExpect(status().isForbidden());

        UUID other = UUID.randomUUID();
        when(supervisor.deliver(eq(other), any(), any())).thenReturn(ChannelSupervisor.Delivery.UNKNOWN);
        mvc.perform(post("/hooks/channels/" + other).content("{}")).andExpect(status().isNotFound());

        mvc.perform(post("/hooks/channels/not-a-bot").content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void aBodyLargerThanAMessageIsNotRead() throws Exception {
        byte[] large = new byte[ChannelWebhookController.MAX_BODY + 1];
        Arrays.fill(large, (byte) 'a');

        mvc.perform(post("/hooks/channels/" + BOT).content(large)).andExpect(status().is(413));

        verify(supervisor, never()).deliver(any(), any(), any());
    }
}
