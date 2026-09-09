package io.memoryos.api.chat;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

@RestController
@RequestMapping("/api/chat/sessions/{sessionId}/messages/{assistantMessageId}/events")
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid request or cursor",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Conversation or message not accessible",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Chat capacity exhausted or provider unavailable",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatStreamController {
    private final ChatTurnService turns;
    private final ChatStreamProperties limits;
    private final Scheduler scheduler;

    ChatStreamController(ChatTurnService turns, ChatStreamProperties limits,
            @Qualifier("chatStreamScheduler") Scheduler scheduler) {
        this.turns = turns;
        this.limits = limits;
        this.scheduler = scheduler;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "streamChatMessage", summary = "Read or resume reply events; reset requires persisted history",
            description = "Events: text-delta, outcome, reset. Content event id is assistantMessageId:sequence. "
                    + "Only outcome confirms a committed terminal state. Heartbeats are comments. A reset has no event id.")
    @ApiResponse(responseCode = "200", description = "SSE frames; the schema describes each data payload",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(oneOf = {ChatEventStream.TextDeltaEvent.class, ChatEventStream.OutcomeEvent.class,
                            ChatEventStream.ResetEvent.class})))
    ResponseEntity<Flux<ServerSentEvent<Object>>> events(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @PathVariable UUID assistantMessageId,
            @RequestHeader(value = "Last-Event-ID", required = false) @Nullable String lastEvent,
            @RequestParam(required = false) @Nullable String after) {
        long sequence = cursor(assistantMessageId, lastEvent, after);
        var readerFactory = turns.subscribe(identity.actorId(), sessionId, assistantMessageId, sequence);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(ChatEventStream.encode(readerFactory, assistantMessageId, scheduler, limits.connectionTimeout()));
    }

    static long cursor(UUID assistant, @Nullable String header, @Nullable String query) {
        if (header != null && query != null && !header.equals(query)) throw ChatException.invalid("Conflicting stream cursors.");
        String value = header != null ? header : query;
        if (value == null) return 0;
        if (value.length() > 57 || !value.startsWith(assistant + ":")) throw ChatException.invalid("Invalid stream cursor.");
        String number = value.substring(37);
        if (!number.matches("[0-9]{1,19}")) throw ChatException.invalid("Invalid stream cursor.");
        try { return Long.parseLong(number); }
        catch (NumberFormatException invalid) { throw ChatException.invalid("Invalid stream cursor."); }
    }
}
