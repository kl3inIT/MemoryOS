package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import io.memoryos.chat.ChatActivityEvent;
import io.memoryos.chat.ChatReasoningDelta;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.chat.catalog.ChatProviderAdapter;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.execution.ChatModelGuard;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.research.ResearchExecutor;
import io.memoryos.chat.research.ResearchProperties;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.retrieval.SearchTasks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

/**
 * Live deep research turn against OpenAI through the product executor. Opt-in (MEMORYOS_DR_LIVE=true) with
 * SPRING_AI_OPENAI_API_KEY; the agents search a small in-test knowledge tool so citations and merging are observable.
 */
@EnabledIfEnvironmentVariable(named = "MEMORYOS_DR_LIVE", matches = "true")
class ResearchExecutorLiveTest {
    private static final Map<String, String> DOCUMENTS = Map.of(
            "vinfast", "VinFast sold 72,000 electric scooters in Vietnam in 2025, about 40% of the electric two-wheeler market.",
            "yadea", "Yadea held roughly 25% of Vietnam's electric two-wheeler market in 2025 and opened a second plant in Bac Giang.",
            "policy", "Hanoi plans to restrict petrol motorbikes inside Ring Road 1 from July 2026; registration fees for electric vehicles are 0%.");

    @Test
    void researchTurnRunsPlanAgentsMergedCitationsAndFinalReport() throws Exception {
        String key = System.getenv("SPRING_AI_OPENAI_API_KEY");
        assertTrue(key != null && !key.isBlank(), "SPRING_AI_OPENAI_API_KEY is required");
        String model = System.getenv().getOrDefault("MEMORYOS_DR_LIVE_MODEL", "gpt-5-mini");
        var meters = new SimpleMeterRegistry();
        var settings = new ModelSettings(128000, 16000, new ModelSettings.Capabilities(true, true, false, true),
                Map.of("maxCompletionTokens", true, "reasoningEffort", "low", "helperReasoningEffort", "minimal"), null, ChatTokenizerProfiles.HOSTED);
        var process = mock(AgentProcess.class);
        var budget = mock(Budget.class, RETURNS_DEEP_STUBS);
        when(budget.earlyTerminationPolicy().shouldTerminate(process)).thenReturn(null);
        when(budget.getTokens()).thenReturn(2_000_000);
        when(budget.getCost()).thenReturn(100.0);
        var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
        try (var client = adapter.create(new ChatProviderAdapter.Connection("https://api.openai.com/v1", key), model, settings, Duration.ofSeconds(60));
             var work = new SearchTasks.Scope(Duration.ofSeconds(5))) {
            var setup = new ChatTurnSetup(UUID.randomUUID(), UUID.randomUUID(), new ActorId(UUID.randomUUID()), new TenantId(UUID.randomUUID()),
                    model, List.of(new SystemMessage("Persona instructions are not used by research."),
                    new UserMessage("Research the electric motorbike market in Vietnam in 2025: the market leaders and their shares, and the policy "
                            + "changes that affect it. Use only the organization knowledge base; keep the report short.")), client.binding())
                    .withResearch(new ChatTurnSetup.Research(true, true, "vi", List.of()));
            var events = new CopyOnWriteArrayList<ChatActivityEvent>();
            var guards = new CopyOnWriteArrayList<ChatModelGuard>();
            var searches = new AtomicInteger();
            setup.evidence().publishTo(events::add);
            var output = new StringBuilder();
            long started = System.nanoTime();
            new ResearchExecutor(new ResearchProperties(4, 3, 3, Duration.ofMinutes(10), 1024, 3, Duration.ofMinutes(4), Duration.ofMinutes(8),
                    1000, 3000, 6000, 50000, 5)).run(new ResearchExecutor.Turn(setup, setup.messages(), client.binding().service().getChatModel(),
                    process, budget, () -> {}, Mono.never(), work, 4096, text -> { synchronized (output) { output.append(text); } }, events::add,
                    agent -> new ResearchExecutor.AgentTools(List.of(knowledge(agent, searches)), () -> {}, CompletableFuture.completedFuture(null)),
                    guards::add, ignored -> {}));
            long seconds = (System.nanoTime() - started) / 1_000_000_000;

            var research = events.stream().filter(ChatResearchEvent.class::isInstance).map(ChatResearchEvent.class::cast).toList();
            String plan = research.stream().filter(e -> e.kind() == ChatResearchEvent.Kind.PLAN_DELTA).map(ChatResearchEvent::text).reduce("", String::concat);
            var agents = research.stream().filter(e -> e.kind() == ChatResearchEvent.Kind.AGENT_START).toList();
            var sources = events.stream().filter(ChatToolEvent.class::isInstance).map(ChatToolEvent.class::cast)
                    .filter(e -> e.stage() == ChatToolEvent.Stage.SOURCE).toList();
            System.out.println("LIVE DR seconds=" + seconds + " guards=" + guards.size() + " searches=" + searches.get());
            System.out.println("LIVE DR plan=\n" + plan);
            agents.forEach(a -> System.out.println("LIVE DR agent tab=" + a.tabIndex() + " task=" + a.text()));
            research.stream().filter(e -> e.kind() == ChatResearchEvent.Kind.REPORT_CITATIONS)
                    .forEach(e -> System.out.println("LIVE DR citations " + e.toolCallId() + " " + e.citations()));
            System.out.println("LIVE DR reasoning=" + events.stream().filter(ChatReasoningDelta.class::isInstance).count()
                    + " turn sources=" + sources.stream().map(s -> s.source().citationId() + ":" + s.source().title()).toList());
            System.out.println("LIVE DR report=\n" + output);

            assertFalse(plan.isBlank(), "The plan streams before research");
            assertFalse(agents.isEmpty(), "The orchestrator delegates research agents");
            assertTrue(searches.get() > 0, "Agents call their tools");
            assertFalse(output.isEmpty(), "The final report is the answer");
            assertEquals(sources.stream().map(s -> s.source().citationId()).toList(),
                    java.util.stream.IntStream.rangeClosed(1, sources.size()).boxed().toList(), "Merged sources keep one turn sequence");
            assertTrue(guards.stream().allMatch(ChatModelGuard::usageKnown), "Every research inference reports usage");
        } finally { meters.close(); }
    }

    private static Tool knowledge(ResearchExecutor.AgentScope agent, AtomicInteger searches) {
        return Tool.Companion.of("search_knowledge", "Search authorized organization documents. Returns evidence with citation numbers.",
                Tool.InputSchema.of(Tool.Parameter.string("query", "Focused search query")), Tool.Metadata.DEFAULT, input -> {
                    searches.incrementAndGet();
                    var call = Objects.requireNonNull(agent.activity().current());
                    var text = new StringBuilder();
                    for (var document : DOCUMENTS.entrySet()) {
                        var source = agent.evidence().register("doc:" + document.getKey(), id -> new ChatSource(id, uuid(document.getKey()),
                                uuid(document.getKey() + ":g"), document.getKey() + " report", 0, 0, List.of(new ChatSource.Provenance(0, "[]"))), call);
                        if (source != null) text.append("\n[").append(source.citationId()).append("] ").append(document.getValue());
                    }
                    return Tool.Result.text(text.toString());
                });
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
