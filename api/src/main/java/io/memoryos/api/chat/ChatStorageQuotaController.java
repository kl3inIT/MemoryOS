package io.memoryos.api.chat;

import io.memoryos.chat.ChatStorageQuotaService;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Storage limits for file libraries: model managers read and set them, as they do every Tenant AI limit. */
@RestController
@RequestMapping(value = "/api/chat/storage-quota", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid storage limit", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Model management or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat is unavailable or the person is not a member", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatStorageQuotaController {
    private final ChatStorageQuotaService quotas;

    ChatStorageQuotaController(ChatStorageQuotaService quotas) { this.quotas = quotas; }

    @Schema(name = "ChatStorageQuota")
    record QuotaResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, format = "int64",
            description = "The Tenant's limit per person; null means no limit") @Nullable Long tenantLimitBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "People with their own limit") List<PersonQuotaResponse> people) {

        static QuotaResponse from(ChatStorageQuotaService.Administration administration) {
            return new QuotaResponse(administration.tenantLimitBytes(), administration.people().stream()
                    .map(person -> new PersonQuotaResponse(person.actorId(), person.name(), person.maxBytes())).toList());
        }
    }

    @Schema(name = "ChatStoragePersonQuota")
    record PersonQuotaResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID actorId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long maxBytes) {}

    @Schema(name = "ChatStorageQuotaInput")
    record QuotaRequest(@Schema(types = {"integer", "null"}, format = "int64",
            description = "Bytes, 1 to 1 TiB; null removes the limit") @Nullable Long maxBytes) {}

    @GetMapping
    @Operation(operationId = "getChatStorageQuota", summary = "Read the Tenant storage limit and its exceptions")
    @ApiResponse(responseCode = "200", description = "The Tenant limit and the people with their own", useReturnTypeSchema = true)
    ResponseEntity<QuotaResponse> read(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(QuotaResponse.from(quotas.read(identity.actorId())));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setChatStorageQuota", summary = "Set or remove the Tenant storage limit per person")
    @ApiResponse(responseCode = "200", description = "The limits after the change", useReturnTypeSchema = true)
    ResponseEntity<QuotaResponse> setTenantLimit(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestBody QuotaRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(QuotaResponse.from(quotas.setTenantLimit(identity.actorId(), request.maxBytes())));
    }

    @PutMapping(value = "/{actorId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setChatStoragePersonQuota", summary = "Set or remove one person's own storage limit")
    @ApiResponse(responseCode = "200", description = "The limits after the change", useReturnTypeSchema = true)
    ResponseEntity<QuotaResponse> setPersonLimit(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID actorId, @RequestBody QuotaRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(QuotaResponse.from(quotas.setPersonLimit(identity.actorId(), actorId, request.maxBytes())));
    }
}
