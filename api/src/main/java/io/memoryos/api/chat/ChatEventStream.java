package io.memoryos.api.chat;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.api.chat.contract.ChatSourceResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
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

    record TextDeltaEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                          @Schema(requiredMode = REQUIRED) long sequence,
                          @Schema(requiredMode = REQUIRED) String text) {}

    record ReasoningEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                          @Schema(requiredMode = REQUIRED) long sequence,
                          @Schema(requiredMode = REQUIRED) String text,
                          @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String parentToolCallId) {}

    record ResearchPlanEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                             @Schema(requiredMode = REQUIRED) long sequence,
                             @Schema(requiredMode = REQUIRED) String text) {}

    record TopLevelBranchingEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                  @Schema(requiredMode = REQUIRED) long sequence,
                                  @Schema(requiredMode = REQUIRED) int branches) {}

    record ResearchAgentStartEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                   @Schema(requiredMode = REQUIRED) long sequence,
                                   @Schema(requiredMode = REQUIRED) String toolCallId,
                                   @Schema(requiredMode = REQUIRED) int tabIndex,
                                   @Schema(requiredMode = REQUIRED) String task) {}

    record IntermediateReportEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                   @Schema(requiredMode = REQUIRED) long sequence,
                                   @Schema(requiredMode = REQUIRED) String toolCallId,
                                   @Schema(requiredMode = REQUIRED) String text) {}

    record IntermediateReportCitationsEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                            @Schema(requiredMode = REQUIRED) long sequence,
                                            @Schema(requiredMode = REQUIRED) String toolCallId,
                                            @Schema(requiredMode = REQUIRED) List<ResearchCitation> citations) {}

    /** An intermediate report citation number and the merged turn source it refers to. */
    record ResearchCitation(@Schema(requiredMode = REQUIRED) int marker, @Schema(requiredMode = REQUIRED) int citationId) {}

    record OutcomeEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                        @Schema(requiredMode = REQUIRED) long sequence,
                        @Schema(requiredMode = REQUIRED, allowableValues = {"COMPLETED", "CANCELED", "FAILED"}) String status,
                        @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String failureCode,
                        @Schema(requiredMode = REQUIRED) boolean hasArtifacts) {}

    record ResetEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                      @Schema(requiredMode = REQUIRED, allowableValues = {"BUFFER_MISSING", "BUFFER_GAP", "BUFFER_EXPIRED"}) String reason) {}

    record ToolEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                     @Schema(requiredMode = REQUIRED) long sequence,
                     @Schema(requiredMode = REQUIRED) String toolCallId,
                     @Schema(requiredMode = REQUIRED) String toolName,
                     @Schema(requiredMode = REQUIRED) ChatToolEvent.Stage stage,
                     @Schema(requiredMode = REQUIRED, types = {"object", "null"}) @Nullable ChatSourceResponse source,
                     @Schema(requiredMode = REQUIRED, types = {"object", "null"}) ChatToolEvent.@Nullable QueryPlan search,
                     @Schema(requiredMode = REQUIRED) List<ChatToolEvent.ReadingDocument> documents,
                     @Schema(requiredMode = REQUIRED, types = {"integer", "null"}, format = "int64") @Nullable Long durationMs,
                     @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String parentToolCallId,
                     @Schema(requiredMode = REQUIRED, types = {"integer", "null"}, format = "int32") @Nullable Integer tabIndex,
                     @Schema(requiredMode = REQUIRED, types = {"string", "null"}, allowableValues = {"AUTHORIZATION_REQUIRED", "TIMEOUT", "UNAVAILABLE"},
                             description = "Why a FAILED step failed when the person can act on it; a category only.") ChatToolEvent.@Nullable Failure failure) {}

    record ImageEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                      @Schema(requiredMode = REQUIRED) long sequence,
                      @Schema(requiredMode = REQUIRED) String toolCallId,
                      @Schema(requiredMode = REQUIRED) ChatImageEvent.Stage stage,
                      @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable UUID id,
                      @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String mediaType,
                      @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String revisedPrompt) {}

    record CodeEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                     @Schema(requiredMode = REQUIRED) long sequence,
                     @Schema(requiredMode = REQUIRED) String toolCallId,
                     @Schema(requiredMode = REQUIRED) io.memoryos.chat.ChatCodeEvent.Stage stage,
                     @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String code,
                     @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String output,
                     @Schema(requiredMode = REQUIRED) List<io.memoryos.chat.ChatCodeEvent.GeneratedFile> files) {}

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
                        run.code(), run.output(), run.files());
            }
            default -> throw new IllegalArgumentException("Unknown Chat event type");
        };
    }

    private static ChatResearchEvent research(StreamBufferWriter.Event event) {
        return Objects.requireNonNull(event.research());
    }
}
