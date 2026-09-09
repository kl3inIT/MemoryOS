package io.memoryos.api.chat;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.memoryos.chat.streaming.StreamBufferWriter;
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

    record OutcomeEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                        @Schema(requiredMode = REQUIRED) long sequence,
                        @Schema(requiredMode = REQUIRED, allowableValues = {"COMPLETED", "CANCELED", "FAILED"}) String status,
                        @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String failureCode) {}

    record ResetEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                      @Schema(requiredMode = REQUIRED, allowableValues = {"BUFFER_MISSING", "BUFFER_GAP", "BUFFER_EXPIRED"}) String reason) {}

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
            case "outcome" -> new OutcomeEvent(event.assistantMessageId(), event.sequence(),
                    Objects.requireNonNull(event.status()).name(), event.failureCode());
            default -> throw new IllegalArgumentException("Unknown Chat event type");
        };
    }
}
