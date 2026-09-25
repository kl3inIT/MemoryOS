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
import io.memoryos.api.chat.contract.TextDeltaEvent;
import io.memoryos.api.chat.contract.ToolEvent;
import io.memoryos.api.chat.contract.TopLevelBranchingEvent;

import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.api.chat.contract.ChatSourceResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
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
        return batch.events().stream().map(event -> ServerSentEvent.builder(payload(event))
                .event(event.type()).id(event.id()).build()).toList();
    }

    private static Object payload(StreamBufferWriter.Event event) {
        return switch (event.type()) {
            case "text-delta" -> new TextDeltaEvent(event.assistantMessageId(), event.sequence(), Objects.requireNonNull(event.text()));
            case "reasoning" -> new ReasoningEvent(event.assistantMessageId(), event.sequence(), Objects.requireNonNull(event.text()), event.parentToolCallId());
            case "research-plan" -> new ResearchPlanEvent(event.assistantMessageId(), event.sequence(), Objects.requireNonNull(research(event).text()));
            case "top-level-branching" -> new TopLevelBranchingEvent(event.assistantMessageId(), event.sequence(), Objects.requireNonNull(research(event).branches()));
            case "research-agent-start" -> new ResearchAgentStartEvent(event.assistantMessageId(), event.sequence(),
                    Objects.requireNonNull(research(event).toolCallId()), Objects.requireNonNull(research(event).tabIndex()), Objects.requireNonNull(research(event).text()));
            case "intermediate-report" -> new IntermediateReportEvent(event.assistantMessageId(), event.sequence(),
                    Objects.requireNonNull(research(event).toolCallId()), Objects.requireNonNull(research(event).text()));
            case "intermediate-report-citations" -> new IntermediateReportCitationsEvent(event.assistantMessageId(), event.sequence(),
                    Objects.requireNonNull(research(event).toolCallId()),
                    research(event).citations().stream().map(citation -> new ResearchCitation(citation.marker(), citation.citationId())).toList());
            case "outcome" -> new OutcomeEvent(event.assistantMessageId(), event.sequence(),
                    Objects.requireNonNull(event.status()).name(), event.failureCode(), event.hasArtifacts());
            case "tool" -> {
                var tool = Objects.requireNonNull(event.tool());
                yield new ToolEvent(event.assistantMessageId(), event.sequence(), tool.toolCallId(), tool.toolName(), tool.stage(),
                        tool.source() == null ? null : ChatSourceResponse.from(tool.source()), tool.search(), tool.documents(), tool.durationMs(),
                        tool.parentToolCallId(), tool.tabIndex(), tool.failure());
            }
            case "image" -> {
                var image = Objects.requireNonNull(event.image());
                yield new ImageEvent(event.assistantMessageId(), event.sequence(), image.toolCallId(), image.stage(),
                        image.artifactId(), image.mediaType(), image.revisedPrompt());
            }
            case "code" -> {
                var run = Objects.requireNonNull(event.code());
                yield new CodeEvent(event.assistantMessageId(), event.sequence(), run.toolCallId(), run.stage(),
                        run.code(), run.output(), run.files(), run.stream());
            }
            default -> throw new IllegalArgumentException("Unknown Chat event type");
        };
    }

    private static ChatResearchEvent research(StreamBufferWriter.Event event) {
        return Objects.requireNonNull(event.research());
    }
}
