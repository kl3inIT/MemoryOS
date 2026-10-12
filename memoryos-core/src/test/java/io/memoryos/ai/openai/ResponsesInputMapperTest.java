package io.memoryos.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

class ResponsesInputMapperTest {
    @Test
    void systemAndUserMessagesBecomeMessageItemsWithImagesAsDataUrls() {
        assertEquals(List.of(Map.of("type", "message", "role", "system", "content", "Rules")),
                ResponsesInputMapper.input(new SystemMessage("Rules")));

        var user = UserMessage.builder().text("What is this?")
                .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] {1, 2, 3})))).build();
        assertEquals(List.of(Map.of("type", "message", "role", "user", "content", List.of(
                        Map.of("type", "input_text", "text", "What is this?"),
                        Map.of("type", "input_image", "detail", "auto", "image_url", "data:image/png;base64,AQID")))),
                ResponsesInputMapper.input(user));
    }

    @Test
    void anAssistantMessageReplaysItsEchoedOutputItemsExactly() {
        var outputItems = List.of(
                Map.of("type", "reasoning", "id", "rs_1", "encrypted_content", "opaque"),
                Map.of("type", "function_call", "call_id", "call_1", "name", "search_knowledge", "arguments", "{}"));
        var assistant = AssistantMessage.builder().content("ignored once echoed")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "search_knowledge", "{}")))
                .properties(Map.of(ResponsesInputMapper.OUTPUT_ITEMS, ResponsesInputMapper.echo(outputItems))).build();

        assertEquals(outputItems, ResponsesInputMapper.input(assistant));
    }

    @Test
    void anAssistantMessageWithoutAnEchoBecomesItsTextAndItsFunctionCalls() {
        var assistant = AssistantMessage.builder().content("Let me check.")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "search_knowledge", "{\"q\":1}"))).build();

        assertEquals(List.of(Map.of("type", "message", "role", "assistant", "content", "Let me check."),
                        Map.of("type", "function_call", "call_id", "call_1", "name", "search_knowledge", "arguments", "{\"q\":1}")),
                ResponsesInputMapper.input(assistant));
    }

    @Test
    void toolResponsesBecomeFunctionCallOutputsAndOtherMessagesAreRefused() {
        var tool = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_1", "search_knowledge", "Twelve days"))).build();

        assertEquals(List.of(Map.of("type", "function_call_output", "call_id", "call_1", "output", "Twelve days")),
                ResponsesInputMapper.input(tool));
        assertThrows(IllegalArgumentException.class, () -> ResponsesInputMapper.input(mock(Message.class)));
    }
}
