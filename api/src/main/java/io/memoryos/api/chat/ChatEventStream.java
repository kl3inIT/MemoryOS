package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.CodeEvent;
import io.memoryos.api.chat.contract.ImageEvent;
import io.memoryos.api.chat.contract.IntermediateReportCitationsEvent;
import io.memoryos.api.chat.contract.IntermediateReportEvent;
import io.memoryos.api.chat.contract.OutcomeEvent;
import io.memoryos.api.chat.contract.ReasoningEvent;
import io.memoryos.api.chat.contract.ResearchAgentStartEvent;
import io.memoryos.api.chat.contract.ResearchCitation;
import io.memoryos.api.chat.contract.ResearchPlanEvent;
import io.memoryos.api.chat.contract.ResetEvent;
import io.memoryos.api.chat.contract.TextEvent;
import io.memoryos.api.chat.contract.ToolEvent;
import io.memoryos.api.chat.contract.TopLevelBranchingEvent;

import io.memoryos.api.chat.contract.ChatSourceResponse;
import io.memoryos.chat.streaming.ChatStreamEvent;
import io.memoryos.chat.streaming.CodeRun;
import io.memoryos.chat.streaming.ImageProgress;
import io.memoryos.chat.streaming.IntermediateReport;
import io.memoryos.chat.streaming.IntermediateReportCitations;
import io.memoryos.chat.streaming.Outcome;
import io.memoryos.chat.streaming.Reasoning;
import io.memoryos.chat.streaming.ResearchAgentStart;
import io.memoryos.chat.streaming.ResearchPlan;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.streaming.TextDelta;
import io.memoryos.chat.streaming.ToolProgress;
import io.memoryos.chat.streaming.TopLevelBranching;
import io.memoryos.chat.streaming.UnknownEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/** HTTP owns only a buffer reader. Canceling this publisher never cancels model execution. */
final class ChatEventStream {
    private ChatEventStream() {}

    static Flux<ServerSentEvent<Object>> encode(Supplier<StreamBufferWriter.Reader> reader, UUID assistant,
            Scheduler scheduler, Duration timeout) {
        return Flux.using(reader::get, resource -> Flux.<StreamBufferWriter.Batch>generate(sink -> {
                    try {
                        var batch = resource.read();
                        sink.next(batch);
                        if (batch.done()) sink.complete();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        sink.complete();
                    }
                }).subscribeOn(scheduler)
                .concatMapIterable(batch -> frames(batch, assistant), 1), StreamBufferWriter.Reader::close)
                .take(timeout)
                .onErrorMap(failure -> new IllegalStateException("Chat stream closed", failure));
    }

    private static List<ServerSentEvent<Object>> frames(StreamBufferWriter.Batch batch, UUID assistant) {
        if (batch.reset() != null) return List.of(ServerSentEvent.builder((Object)
                new ResetEvent(assistant, batch.reset())).event("reset").build());
        if (batch.events().isEmpty()) return batch.done() ? List.of()
                : List.of(ServerSentEvent.builder().comment("heartbeat").build());
        // An event this version does not know has no frame; the browser's cursor passes it with the next event.
        return batch.events().stream().filter(event -> !(event instanceof UnknownEvent))
                .map(event -> ServerSentEvent.builder(payload(event)).event(event.type()).id(event.id()).build()).toList();
    }

    private static Object payload(ChatStreamEvent event) {
        UUID assistant = event.assistantMessageId();
        long sequence = event.sequence();
        return switch (event) {
            case TextDelta text -> new TextEvent(assistant, sequence, text.text());
            case Reasoning reasoning -> new ReasoningEvent(assistant, sequence, reasoning.text(), reasoning.parentToolCallId());
            case ResearchPlan plan -> new ResearchPlanEvent(assistant, sequence, plan.text());
            case TopLevelBranching branching -> new TopLevelBranchingEvent(assistant, sequence, branching.branches());
            case ResearchAgentStart start -> new ResearchAgentStartEvent(assistant, sequence, start.toolCallId(),
                    start.tabIndex(), start.task());
            case IntermediateReport report -> new IntermediateReportEvent(assistant, sequence, report.toolCallId(), report.text());
            case IntermediateReportCitations cited -> new IntermediateReportCitationsEvent(assistant, sequence, cited.toolCallId(),
                    cited.citations().stream().map(citation -> new ResearchCitation(citation.marker(), citation.citationId())).toList());
            case Outcome outcome -> new OutcomeEvent(assistant, sequence, outcome.status().name(), outcome.failureCode(),
                    outcome.hasArtifacts());
            case ToolProgress progress -> {
                var tool = progress.tool();
                yield new ToolEvent(assistant, sequence, tool.toolCallId(), tool.toolName(), tool.stage(),
                        tool.source() == null ? null : ChatSourceResponse.from(tool.source()), tool.search(), tool.documents(),
                        tool.durationMs(), tool.parentToolCallId(), tool.tabIndex(), tool.failure());
            }
            case ImageProgress progress -> {
                var image = progress.image();
                yield new ImageEvent(assistant, sequence, image.toolCallId(), image.stage(), image.artifactId(),
                        image.mediaType(), image.revisedPrompt());
            }
            case CodeRun progress -> {
                var run = progress.code();
                yield new CodeEvent(assistant, sequence, run.toolCallId(), run.stage(), run.code(), run.output(), run.files(),
                        run.stream());
            }
            case UnknownEvent _ -> throw new IllegalArgumentException("Unknown Chat event type");
        };
    }
}
