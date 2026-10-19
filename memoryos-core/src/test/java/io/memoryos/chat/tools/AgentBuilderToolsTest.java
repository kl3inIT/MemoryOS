package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.ai.ReasoningEffort;
import io.memoryos.chat.AssistantPatch;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatPersonaService.BuilderChoices;
import io.memoryos.chat.ChatPersonaService.BuilderModel;
import io.memoryos.chat.ChatPersonaService.BuilderOption;
import io.memoryos.chat.ChatPersonaService.PersonaInput;
import io.memoryos.chat.ChatToolActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentBuilderToolsTest {
    private final UUID readable = UUID.randomUUID();
    private final UUID model = UUID.randomUUID();
    private final List<AssistantPatch> changes = new ArrayList<>();
    private final List<PersonaInput> checked = new ArrayList<>();

    @Test
    void anAcceptedChangeIsSentAndTheNextCallBuildsOnIt() {
        var tools = tools();

        String first = tools.updateAssistant("  Trợ lý thuế ", null, null, null, null, null, null, null, null, null, null, null);
        String second = tools.writeInstructions("Trả lời câu hỏi thuế, trích dẫn tài liệu.");

        assertTrue(first.startsWith("Changed in the unsaved form: name."), first);
        assertTrue(second.contains("instructions"), second);
        assertEquals("Trợ lý thuế", changes.get(0).name());
        assertNull(changes.get(0).instructions(), "only the changed fields are sent");
        assertEquals("Trả lời câu hỏi thuế, trích dẫn tài liệu.", changes.get(1).instructions());
        assertEquals("Trợ lý thuế", checked.getLast().name(), "the second check sees the first change");
        assertEquals(List.of("search"), List.copyOf(checked.getLast().tools()));
    }

    @Test
    void aRefusedChangeSendsNothingAndTellsTheModelWhy() {
        var tools = tools();

        String refused = tools.updateAssistant("Mới", null, null, null, null, null,
                List.of(UUID.randomUUID().toString()), null, null, null, null, null);
        String malformed = tools.updateAssistant(null, null, null, null, null, null, null, null, null, "gpt", null, null);
        String empty = tools.updateAssistant(null, null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(refused.contains("A selected source is unavailable."), refused);
        assertTrue(malformed.contains("is not an option ID"), malformed);
        assertTrue(empty.startsWith("Nothing to change"), empty);
        assertEquals(List.of(), changes);
        // The refused name is not kept: the next change builds on the form as it was.
        tools.updateAssistant(null, "Kế toán", null, null, null, null, null, null, null, null, null, null);
        assertEquals("Kế toán thuế", checked.getLast().name());
    }

    @Test
    void clearingAndLevelsMergeAsTheFormHoldsThem() {
        var form = form();
        var merged = AgentBuilderTools.merged(form, new AssistantPatch(null, "", null, null, null, "", null, null, null,
                null, null, model, "HIGH", true));
        assertNull(merged.title());
        assertNull(merged.greeting());
        assertEquals(model, merged.modelConfigurationId());
        assertEquals(ReasoningEffort.HIGH, merged.reasoningEffort());
        assertEquals(Boolean.TRUE, merged.grounded());
        assertNull(AgentBuilderTools.merged(merged, new AssistantPatch(null, null, null, null, null, null, null, null,
                null, null, null, null, "", null)).reasoningEffort());
    }

    @Test
    void findOptionsSearchesEveryChoiceByName() {
        var tools = tools();
        assertEquals(readable + " Sổ tay nhân sự", tools.findOptions("sources", "NHÂN"));
        assertEquals("No option matches.", tools.findOptions("sources", "kế toán"));
        assertTrue(tools.findOptions("tenants", "x").startsWith("Unknown kind"));
    }

    private AgentBuilderTools tools() {
        var choices = new BuilderChoices(List.of(new BuilderModel(model, "Luna", true, true)),
                List.of(new BuilderOption(readable, "Sổ tay nhân sự")), List.of(), List.of());
        var turn = new AgentBuilderTools.Turn(form(), choices, input -> {
            checked.add(input);
            if (!input.sourceIds().stream().allMatch(readable::equals)) throw ChatException.invalid("A selected source is unavailable.");
        }, (call, patch) -> changes.add(patch));
        return new AgentBuilderTools(turn, () -> {}, new ChatToolActivity(event -> {}));
    }

    private static PersonaInput form() {
        return new PersonaInput("Kế toán thuế", "Thuế", "", "", "Xin chào", List.of(), List.of(), null, Set.of("search"),
                null, null, null, null, null, null, null, null, null);
    }
}
