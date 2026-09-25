package io.memoryos.api.usage;

import io.memoryos.api.usage.contract.AiUsageLimitRequest;
import io.memoryos.api.usage.contract.AiUsageLimitResponse;
import io.memoryos.api.usage.contract.AiUsageStandingResponse;
import io.memoryos.iam.IdentityContext;
import io.memoryos.usage.AiUsageLimitService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** What a Tenant, a Group and a person may spend on AI (MEM-123). */
@RestController
@RequestMapping(value = "/api/ai-costs/limits", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI costs")
@ApiResponse(responseCode = "400", description = "Invalid budget or period", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Model management requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class AiUsageLimitController {
    private final AiUsageLimitService limits;

    AiUsageLimitController(AiUsageLimitService limits) { this.limits = limits; }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping
    @Operation(operationId = "listAiUsageLimits", summary = "The Tenant's AI spending limits; requires model management")
    List<AiUsageLimitResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return limits.list(identity.actorId()).stream().map(AiUsageLimitResponse::from).toList();
    }

    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(operationId = "createAiUsageLimit", summary = "Sets a spending limit for the Tenant, a Group or each person; requires model management")
    AiUsageLimitResponse create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                 @RequestBody AiUsageLimitRequest request) {
        return AiUsageLimitResponse.from(limits.create(identity.actorId(), request.toLimit(UUID.randomUUID())));
    }

    @ApiResponse(responseCode = "200", description = "Updated", useReturnTypeSchema = true)
    @PutMapping(value = "/{limitId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateAiUsageLimit", summary = "Changes a limit's budgets, period or switch; who it applies to is fixed. Requires model management")
    AiUsageLimitResponse update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                 @PathVariable UUID limitId, @RequestBody AiUsageLimitRequest request) {
        return AiUsageLimitResponse.from(limits.update(identity.actorId(), limitId, request.toLimit(limitId)));
    }

    @ApiResponse(responseCode = "204", description = "Removed")
    @DeleteMapping("/{limitId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteAiUsageLimit", summary = "Removes a limit; requires model management")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID limitId) {
        limits.delete(identity.actorId(), limitId);
    }

    @ApiResponse(responseCode = "200", description = "The binding budget, or nothing when the Tenant sets no limit", useReturnTypeSchema = true)
    @GetMapping("/mine")
    @Operation(operationId = "getMyAiUsageStanding", summary = "The budget that binds the caller and what they have spent against it; any member")
    @Nullable AiUsageStandingResponse mine(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return limits.standing(identity.actorId()).map(AiUsageStandingResponse::from).orElse(null);
    }
}
