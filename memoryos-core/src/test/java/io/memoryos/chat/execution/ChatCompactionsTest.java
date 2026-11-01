package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.TurnFailure;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatModelSelector;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.session.ChatTurnPersistence.CompactionInput;
import io.memoryos.chat.session.ChatTurnPersistence.TurnContext;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.shared.Tokenizers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

class ChatCompactionsTest {
    private static final ModelRequestPolicy POLICY = ModelRequestPolicy.hosted(Tokenizers.o200k(), p -> p);
    private static final String FILLER = " lorem".repeat(100);
    private final ChatTurnPersistence persistence = mock(ChatTurnPersistence.class);
    private final ChatModelSelector models = mock(ChatModelSelector.class);
    private final ChatModelExecutor model = mock(ChatModelExecutor.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ChatCompactions compactions = new ChatCompactions(persistence, models, model, null, Runnable::run, 1,
            meters, ObservationRegistry.NOOP);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID session = UUID.randomUUID();
    private final List<ChatMessage> branch = conversation(10);
    private final UUID assistant = branch.getFirst().id();

    @Test
    void aWrittenSummaryIsSavedAfterTheNewestQuestionAndTheClaimEnds() {
        when(persistence.compactionInput(actor, session, assistant))
                .thenReturn(Optional.of(new CompactionInput(tenant, branch, null)));
        when(persistence.claimCompaction(tenant, session, ChatCompactions.HOLD)).thenReturn(true);
        var summaryModel = summaryModel();
        when(model.summarize(any(), any(), anyInt(), any(), any())).thenReturn("## Goal\nPlan the trip");

        compactions.after(actor, session, assistant, POLICY, 2000);

        verify(persistence).saveSummary(eq(tenant), eq(session), eq(branch.get(1).id()), eq(branch.get(4).id()),
                eq("## Goal\nPlan the trip"), anyInt(), eq(summaryModel));
        verify(persistence).releaseCompaction(session, null);
        assertEquals(1.0, meters.counter("memoryos.chat.compaction.runs", "outcome", "written").count());
    }

    @Test
    void aFailedSummaryWritesNothingKeepsTheWindowAndWaitsTenMinutes() {
        when(persistence.compactionInput(actor, session, assistant))
                .thenReturn(Optional.of(new CompactionInput(tenant, branch, null)));
        when(persistence.claimCompaction(tenant, session, ChatCompactions.HOLD)).thenReturn(true);
        summaryModel();
        when(model.summarize(any(), any(), anyInt(), any(), any())).thenThrow(TurnFailure.DEADLINE.exception());

        compactions.after(actor, session, assistant, POLICY, 2000);

        verify(persistence, never()).saveSummary(any(), any(), any(), any(), anyString(), anyInt(), any());
        verify(persistence).releaseCompaction(session, ChatCompactions.COOLDOWN);
        assertEquals(1.0, meters.counter("memoryos.chat.compaction.runs", "outcome", "failed").count());
    }

    @Test
    void twoTurnsCompactOnce() {
        when(persistence.compactionInput(actor, session, assistant))
                .thenReturn(Optional.of(new CompactionInput(tenant, branch, null)));
        // Another turn's compaction holds the session: this one leaves it.
        when(persistence.claimCompaction(tenant, session, ChatCompactions.HOLD)).thenReturn(false);
        compactions.after(actor, session, assistant, POLICY, 2000);
        verify(models, never()).resolveFlow(any(), any(), any());

        // It ended while this one waited for the claim: read again under the claim, the summary is already current.
        var cutoff = branch.get(4).id();
        when(persistence.claimCompaction(tenant, session, ChatCompactions.HOLD)).thenReturn(true);
        when(persistence.compactionInput(actor, session, assistant))
                .thenReturn(Optional.of(new CompactionInput(tenant, branch, null)))
                .thenReturn(Optional.of(new CompactionInput(tenant, branch, new TurnContext.Summary(cutoff, "## Goal"))));
        compactions.after(actor, session, assistant, POLICY, 2000);

        verify(models, never()).resolveFlow(any(), any(), any());
        verify(persistence, times(1)).releaseCompaction(session, null);
        assertEquals(1.0, meters.counter("memoryos.chat.compaction.runs", "outcome", "current").count());
    }

    @Test
    void aConversationWithinTheThresholdTakesNoClaim() {
        var brief = conversation(3);
        when(persistence.compactionInput(actor, session, brief.getFirst().id()))
                .thenReturn(Optional.of(new CompactionInput(tenant, brief, null)));

        compactions.after(actor, session, brief.getFirst().id(), POLICY, 2000);

        verify(persistence, never()).claimCompaction(any(), any(), any());
        verify(persistence, never()).releaseCompaction(any(), isNull());
    }

    /** The summary model; returns the model configuration id the summary records. */
    private UUID summaryModel() {
        var binding = ModelBinding.builder(new SpringAiLlmService("summary-model", "fixture", mock(ChatModel.class)),
                p -> p, POLICY, 32000, 4096, false, false).build();
        var lease = mock(ModelClients.Lease.class);
        when(lease.binding()).thenReturn(binding);
        UUID id = UUID.randomUUID();
        when(models.resolveFlow(actor, session, ModelFlow.CHAT_COMPACTION)).thenReturn(new ModelResolver.Resolved(id, null, lease));
        return id;
    }

    private static List<ChatMessage> conversation(int turns) {
        var newestFirst = new ArrayList<ChatMessage>();
        for (int i = turns - 1; i >= 0; i--) {
            newestFirst.add(message(ChatMessage.Role.ASSISTANT, "Answer " + i + FILLER));
            newestFirst.add(message(ChatMessage.Role.USER, "Question " + i + FILLER));
        }
        return newestFirst;
    }

    private static ChatMessage message(ChatMessage.Role role, String content) {
        return ChatMessage.builder(UUID.randomUUID(), UUID.randomUUID(), role, ChatMessage.Status.COMPLETED, Instant.now())
                .content(content).finishedAt(Instant.now()).build();
    }
}
