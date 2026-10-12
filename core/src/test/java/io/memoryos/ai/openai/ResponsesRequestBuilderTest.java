package io.memoryos.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseIncludable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ResponsesRequestBuilderTest {
    @Test
    void aReasoningModelAsksForMediumEffortSummariesAndItsEncryptedReasoning() {
        var params = new ResponsesRequestBuilder(true, true).build(new Prompt("Question"), options(false).build(), false, false);

        assertEquals(Optional.of(false), params.store());
        assertEquals(Optional.of(100L), params.maxOutputTokens());
        assertEquals(JsonValue.from(Map.of("effort", "medium", "summary", "auto")),
                params._additionalBodyProperties().get("reasoning"));
        assertEquals(Optional.of(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)), params.include());
        assertTrue(params.tools().isEmpty(), "the final cycle offers no tool, hosted search included");
        assertFalse(params._additionalBodyProperties().containsKey("tool_choice"));
    }

    @Test
    void theTurnsOwnEffortWinsAndNoEffortAsksForNoSummary() {
        var builder = new ResponsesRequestBuilder(true, true);

        assertEquals(JsonValue.from(Map.of("effort", "high", "summary", "auto")),
                builder.build(new Prompt("Question"), options(false).reasoningEffort("high").build(), false, false)
                        ._additionalBodyProperties().get("reasoning"));
        assertEquals(JsonValue.from(Map.of("effort", "none")),
                builder.build(new Prompt("Question"), options(false).reasoningEffort("none").build(), false, false)
                        ._additionalBodyProperties().get("reasoning"));
    }

    @Test
    void aResponseFormatIsSentAsTheTextFormatOfTheResponsesApi() {
        String schema = """
                {"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"],"additionalProperties":false}""";
        var builder = new ResponsesRequestBuilder(false, false);
        var params = builder.build(new Prompt("Question"), options(false).outputSchema(schema).build(), false, false);
        assertEquals(JsonValue.from(Map.of("format", Map.of("type", "json_schema", "name", "answer", "strict", true,
                        "schema", Map.of("type", "object", "properties", Map.of("summary", Map.of("type", "string")),
                                "required", List.of("summary"), "additionalProperties", false)))),
                params._additionalBodyProperties().get("text"));
        assertFalse(builder.build(new Prompt("Question"), options(false).build(), false, false)
                ._additionalBodyProperties().containsKey("text"));
    }

    @Test
    void aModelThatDoesNotReasonSendsNoReasoningOptionsEvenWhenSummariesAreConfigured() {
        var builder = new ResponsesRequestBuilder(false, true);
        var params = builder.build(new Prompt("Question"), options(false).build(), false, false);

        assertFalse(builder.summaries());
        assertFalse(params._additionalBodyProperties().containsKey("reasoning"));
        assertTrue(params.include().isEmpty());
    }

    @Test
    void functionToolsHostedSearchAndTheToolChoiceAreDeclaredTogether() {
        var options = options(true).toolChoice("required").parallelToolCalls(false).build();
        var params = new ResponsesRequestBuilder(false, false).build(new Prompt("Question"), options, true, true);

        var tools = params.tools().orElseThrow();
        assertEquals(2, tools.size());
        assertEquals("search_knowledge", tools.getFirst().function().orElseThrow().name());
        assertTrue(tools.getLast().webSearch().isPresent());
        assertEquals(JsonValue.from("required"), params._additionalBodyProperties().get("tool_choice"));
        assertEquals(Optional.of(false), params.parallelToolCalls());

        var withoutWeb = new ResponsesRequestBuilder(false, false).build(new Prompt("Question"), options, true, false);
        assertEquals(1, withoutWeb.tools().orElseThrow().size());
    }

    private static OpenAiChatOptions.Builder options(boolean tools) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("search_knowledge")
                .description("Search documents").inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        return OpenAiChatOptions.builder().model("configured-model").maxCompletionTokens(100)
                .toolCallbacks(tools ? List.of(callback) : List.of());
    }
}
