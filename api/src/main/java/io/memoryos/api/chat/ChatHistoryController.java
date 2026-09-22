package io.memoryos.api.chat;

import io.memoryos.chat.history.ChatHistoryService;
import io.memoryos.chat.history.persistence.JdbcChatHistoryRepository;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Tenant's conversations, read by an administrator who holds CHAT_HISTORY_READ (MEM-125). */
@RestController
@RequestMapping(value = "/api/chat/history", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat history")
@ApiResponse(responseCode = "400", description = "Invalid filter, cursor or page size", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Conversation history requirement not met, or history is turned off for the Tenant", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "No such conversation in this Tenant", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatHistoryController {
    /** User-controlled text only; the audit and usage exports guard the same prefixes. */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private final ChatHistoryService history;

    ChatHistoryController(ChatHistoryService history) { this.history = history; }

    @Schema(name = "ChatHistoryEntry", description = "One conversation. The person is absent when the Tenant hides who asked")
    record Entry(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                 @Nullable UUID actorId, @Nullable String person, @Nullable String email,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
                 @Nullable String question, @Nullable String answer, @Nullable String modelName,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long messages,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) JdbcChatHistoryRepository.Feedback feedback,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The owner deleted this conversation; it is kept until retention removes it")
                 boolean deleted,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
        static Entry from(ChatHistoryService.Conversation value) {
            return new Entry(value.id(), value.actorId(), value.person(), value.email(), value.title(),
                    value.question(), value.answer(), value.modelName(), value.messages(), value.feedback(),
                    value.deleted(), value.updatedAt());
        }
    }

    @Schema(name = "ChatHistoryPage")
    record Page(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Entry> items, @Nullable String nextCursor,
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long conversations,
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long positive,
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long negative) {}

    @Schema(name = "ChatHistoryMessage", description = "One message; citations are named, and opening one uses the reader's own Source authority")
    record Message(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
                   @Nullable String modelName,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                   @Nullable Boolean positive, @Nullable String comment,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> citations) {
        static Message from(JdbcChatHistoryRepository.Message value) {
            return new Message(value.id(), value.role(), value.content(), value.modelName(), value.createdAt(),
                    value.positive(), value.comment(), value.citations());
        }
    }

    @Schema(name = "ChatHistoryTranscript")
    record Transcript(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Entry conversation,
                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Message> messages) {}

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping
    @Operation(operationId = "listChatHistory", summary = "The Tenant's conversations, newest first; requires conversation history access")
    Page list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to,
              @RequestParam(required = false) @Nullable String q,
              @RequestParam(required = false) @Nullable UUID actorId,
              @RequestParam(required = false) JdbcChatHistoryRepository.@Nullable Feedback feedback,
              @RequestParam(required = false) @Nullable String cursor,
              @RequestParam(defaultValue = "30") int size) {
        var page = history.page(identity.actorId(), query(from, to, q, actorId, feedback), cursor, size);
        return new Page(page.items().stream().map(Entry::from).toList(), page.nextCursor(),
                page.totals().conversations(), page.totals().positive(), page.totals().negative());
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/{sessionId}")
    @Operation(operationId = "getChatHistoryTranscript", summary = "One conversation's transcript; the read is itself recorded in the audit log")
    Transcript transcript(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                          @PathVariable UUID sessionId) {
        var transcript = history.transcript(identity.actorId(), sessionId);
        return new Transcript(Entry.from(transcript.conversation()),
                transcript.messages().stream().map(Message::from).toList());
    }

    @ApiResponse(responseCode = "200", description = "CSV", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(operationId = "exportChatHistory", summary = "The conversations the filters select as CSV, at most 50,000 rows; the export is itself recorded")
    void export(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to,
                @RequestParam(required = false) @Nullable String q,
                @RequestParam(required = false) @Nullable UUID actorId,
                @RequestParam(required = false) JdbcChatHistoryRepository.@Nullable Feedback feedback,
                HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename("chat-history_" + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + ".csv",
                        StandardCharsets.UTF_8)
                .build().toString());
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        // A byte-order mark, so a spreadsheet opens Vietnamese questions as UTF-8.
        writer.write('﻿');
        var csv = new CSVPrinter(writer, CSVFormat.DEFAULT);
        csv.printRecord("session_id", "updated_at", "person", "email", "title", "first_question", "first_answer",
                "model", "messages", "feedback", "deleted");
        history.export(identity.actorId(), query(from, to, q, actorId, feedback), conversation -> {
            try {
                csv.printRecord(conversation.id(), conversation.updatedAt(), guard(conversation.person()),
                        guard(conversation.email()), guard(conversation.title()), guard(conversation.question()),
                        guard(conversation.answer()), conversation.modelName(), conversation.messages(),
                        conversation.feedback(), conversation.deleted());
            } catch (IOException broken) {
                throw new UncheckedIOException(broken);
            }
        });
        csv.flush();
    }

    private static JdbcChatHistoryRepository.Query query(@Nullable Instant from, @Nullable Instant to,
                                                         @Nullable String q, @Nullable UUID actorId,
                                                         JdbcChatHistoryRepository.@Nullable Feedback feedback) {
        return new JdbcChatHistoryRepository.Query(from, to, q == null || q.isBlank() ? null : q.trim(), actorId,
                feedback);
    }

    private static @Nullable String guard(@Nullable String value) {
        if (value == null || value.isEmpty()) return value;
        return FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}
