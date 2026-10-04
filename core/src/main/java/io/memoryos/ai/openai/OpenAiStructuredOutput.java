package io.memoryos.ai.openai;

import com.embabel.agent.spi.loop.StructuredOutputRequest;
import com.embabel.agent.spi.support.springai.SpringAiNativeStructuredOutputConfigurer;
import com.embabel.common.ai.autoconfig.NativeSupport;
import com.embabel.common.ai.model.LlmMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Puts the JSON schema of a typed answer on the request, so the provider constrains the model to it instead of the
 * prompt asking for it. Both routes read the same options: Chat Completions sends them as {@code response_format}
 * through Spring AI, and {@link ResponsesRequestBuilder} sends them as {@code text.format}.
 */
final class OpenAiStructuredOutput implements SpringAiNativeStructuredOutputConfigurer {
    @Override
    public ChatOptions configure(ChatOptions options, StructuredOutputRequest request, NativeSupport support,
                                 LlmMetadata model) {
        if (!(options instanceof OpenAiChatOptions openAi)) return options;
        return openAi.mutate().responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(request.getSchema()).strict(request.getStrict()).build()).build();
    }
}
