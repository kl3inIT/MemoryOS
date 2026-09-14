package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ChatModelClients;
import io.memoryos.chat.catalog.ChatProviderAdapter;
import io.memoryos.chat.catalog.ModelSettings;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import static org.mockito.Mockito.mock;

class ChatTokenizerProfilesTest {
    private static final String VIETNAMESE = "Hãy giải thích cách hệ thống lưu trữ và tìm kiếm tài liệu tiếng Việt.";

    @Test
    void pinnedUnicodeCountsAreUntruncatedAndConcurrent() throws Exception {
        try (var profiles = new ChatTokenizerProfiles(); var lease = profiles.acquire(ChatTokenizerProfiles.SMOL);
             var executor = Executors.newFixedThreadPool(4)) {
            var tokens = lease.tokens();
            assertEquals(52, tokens.estimate(VIETNAMESE));
            String repeated = String.join(" ", java.util.Collections.nCopies(20, VIETNAMESE));
            assertEquals(1040, tokens.estimate(repeated));
            var tasks = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(ignored -> executor.submit(() -> tokens.estimate(repeated))).toList();
            for (var result : tasks) assertEquals(1040, result.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void retiredCacheAndClosedCompositionRetainTokenizerUntilLastRealLease() {
        var profiles = new ChatTokenizerProfiles();
        var clients = new ChatModelClients(2);
        var settings = new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false),
                Map.of(), null, ChatTokenizerProfiles.SMOL);
        var id = UUID.randomUUID();
        try (var first = clients.acquire(id, "1", () -> client(profiles, settings));
             var second = clients.acquire(id, "2", () -> client(profiles, settings))) {
            clients.close();
            profiles.close();
            assertEquals(52, first.binding().policy().tokens().estimate(VIETNAMESE));
            assertEquals(52, second.binding().policy().tokens().estimate(VIETNAMESE));
            assertThrows(ChatException.class, () -> profiles.acquire(ChatTokenizerProfiles.SMOL));
        } finally { clients.close(); profiles.close(); }
    }

    @Test
    void rawFitDoesNotAdmitFramingOverflowAndExactFitUsesTheSamePolicy() {
        try (var profiles = new ChatTokenizerProfiles(); var lease = profiles.acquire(ChatTokenizerProfiles.SMOL)) {
            var settings = new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false),
                    Map.of(), null, ChatTokenizerProfiles.SMOL);
            var policy = OpenAiChatRequestPolicy.create(settings, lease.tokens());
            List<org.springframework.ai.chat.messages.Message> messages = List.of(new SystemMessage("Answer briefly."), new UserMessage(VIETNAMESE));
            int exact = policy.framing().applyAsInt(new Prompt(messages));
            int raw = lease.tokens().estimate("Answer briefly.") + lease.tokens().estimate(VIETNAMESE);
            assertTrue(raw < exact);
            assertThrows(ChatException.class, () -> policy.validateQuestion("Answer briefly.", VIETNAMESE, raw));
            assertThrows(ChatException.class, () -> policy.validateQuestion("Answer briefly.", VIETNAMESE, exact - 1));
            policy.validateQuestion("Answer briefly.", VIETNAMESE, exact);
            var binding = OpenAiChatProviderAdapter.binding("local", settings, mock(ChatModel.class), lease.tokens());
            var options = binding.service().convertOptions(new com.embabel.common.ai.model.LlmOptions().withMaxTokens(128));
            assertEquals(messages, policy.request(new Prompt(messages, options), exact).getInstructions());
        }
    }

    private static ChatProviderAdapter.Client client(ChatTokenizerProfiles profiles, ModelSettings settings) {
        var tokenLease = profiles.acquire(ChatTokenizerProfiles.SMOL);
        return new ChatProviderAdapter.Client(OpenAiChatProviderAdapter.binding("local", settings,
                mock(ChatModel.class), tokenLease.tokens()), tokenLease::close);
    }
}
