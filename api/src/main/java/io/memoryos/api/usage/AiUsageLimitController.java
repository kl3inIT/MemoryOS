package io.memoryos.api.usage;

import io.memoryos.iam.IdentityContext;
import io.memoryos.usage.AiUsageLimit;
import io.memoryos.usage.AiUsageLimitScope;
import io.memoryos.usage.AiUsageLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
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

    @Schema(name = "AiUsageLimit", description = "A cap on AI spending. A model without a price adds tokens but no cost, so only a token budget binds it")
    record Limit(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiUsageLimitScope scope,
                 @Nullable UUID groupId, @Nullable String groupName,
                 @Nullable Long tokenBudget, @Nullable BigDecimal costBudgetUsd,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int periodDays,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Spent against this limit in its own window; for a per-person limit, the busiest person's spend")
                 long tokensUsed,
                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal costUsed) {
        static Limit from(AiUsageLimit value) {
            return from(new AiUsageLimitService.Configured(value, 0, BigDecimal.ZERO));
        }

        static Limit from(AiUsageLimitService.Configured value) {
            AiUsageLimit limit = value.limit();
            return new Limit(limit.id(), limit.scope(), limit.groupId(), limit.groupName(), limit.tokenBudget(),
                    limit.costBudgetUsd(), limit.periodDays(), limit.enabled(), value.tokensUsed(), value.costUsed());
        }
    }

    @Schema(name = "AiUsageLimitRequest")
    record Request(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiUsageLimitScope scope,
                   @Nullable UUID groupId, @Nullable Long tokenBudget, @Nullable BigDecimal costBudgetUsd,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int periodDays,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {
        AiUsageLimit toLimit(UUID id) {
            return new AiUsageLimit(id, scope, groupId, null, tokenBudget, costBudgetUsd, periodDays, enabled);
        }
    }

    @Schema(name = "AiUsageStanding", description = "The budget that binds the caller, and what they have spent against it")
    record Standing(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiUsageLimitScope scope,
                    @Nullable String groupName, @Nullable Long tokenBudget,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long tokensUsed,
                    @Nullable BigDecimal costBudgetUsd,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal costUsed,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int periodDays,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant resetsAt) {
        static Standing from(AiUsageLimitService.Standing value) {
            return new Standing(value.scope(), value.groupName(), value.tokenBudget(), value.tokensUsed(),
                    value.costBudgetUsd(), value.costUsed(), value.periodDays(), value.resetsAt());
        }
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping
    @Operation(operationId = "listAiUsageLimits", summary = "The Tenant's AI spending limits; requires model management")
    List<Limit> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return limits.list(identity.actorId()).stream().map(Limit::from).toList();
    }

    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(operationId = "createAiUsageLimit", summary = "Sets a spending limit for the Tenant, a Group or each person; requires model management")
    Limit create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                 @RequestBody Request request) {
        return Limit.from(limits.create(identity.actorId(), request.toLimit(UUID.randomUUID())));
    }

    @ApiResponse(responseCode = "200", description = "Updated", useReturnTypeSchema = true)
    @PutMapping(value = "/{limitId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateAiUsageLimit", summary = "Changes a limit's budgets, period or switch; who it applies to is fixed. Requires model management")
    Limit update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                 @PathVariable UUID limitId, @RequestBody Request request) {
        return Limit.from(limits.update(identity.actorId(), limitId, request.toLimit(limitId)));
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
    @Nullable Standing mine(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return limits.standing(identity.actorId()).map(Standing::from).orElse(null);
    }
}
