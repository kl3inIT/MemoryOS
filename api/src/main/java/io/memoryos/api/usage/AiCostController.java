package io.memoryos.api.usage;

import io.memoryos.api.usage.contract.AiCostDayResponse;
import io.memoryos.api.usage.contract.AiCostDetailResponse;
import io.memoryos.api.usage.contract.AiCostRowResponse;
import io.memoryos.api.usage.contract.AiCostSummaryResponse;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.usage.AiCostDimension;
import io.memoryos.usage.AiCostService;
import io.memoryos.usage.AiCostSplit;
import io.memoryos.usage.AiUsageFlow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/ai-costs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI costs")
@ApiResponse(responseCode = "400", description = "Invalid period or filter", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Model management or Tenant membership requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class AiCostController {
    private final AiCostService costs;

    AiCostController(AiCostService costs) { this.costs = costs; }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/summary")
    @Operation(operationId = "getAiCostSummary", summary = "Tenant AI usage and known costs for a UTC day range; requires model management")
    AiCostSummaryResponse summary(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    @RequestParam(required = false) @Nullable String model, @RequestParam(required = false) @Nullable AiUsageFlow flow) {
        return AiCostSummaryResponse.from(costs.summary(identity.actorId(), new AiCostService.Range(from, to, null, false, model, flow)));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/daily")
    @Operation(operationId = "listAiCostDays", summary = "Daily AI costs split by data boundary, by model or not at all; requires model management")
    List<AiCostDayResponse> daily(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    @RequestParam(defaultValue = "BOUNDARY") AiCostSplit split,
                    @RequestParam(required = false) @Nullable String model, @RequestParam(required = false) @Nullable AiUsageFlow flow) {
        return costs.daily(identity.actorId(), new AiCostService.Range(from, to, null, false, model, flow), split)
                .stream().map(AiCostDayResponse::from).toList();
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/breakdown")
    @Operation(operationId = "listAiCostBreakdown", summary = "AI costs ranked by person, Group, model, task or provider; requires model management")
    List<AiCostRowResponse> breakdown(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam AiCostDimension by,
                        @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "200", defaultValue = "50"))
                        @RequestParam(defaultValue = "50") int limit,
                        @RequestParam(required = false) @Nullable String model, @RequestParam(required = false) @Nullable AiUsageFlow flow) {
        return costs.breakdown(identity.actorId(), new AiCostService.Range(from, to, null, false, model, flow), by, limit)
                .stream().map(AiCostRowResponse::from).toList();
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/mine")
    @Operation(operationId = "getMyAiCosts", summary = "The caller's own AI costs by day, model, task and provider; any Chat reader")
    AiCostDetailResponse mine(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return AiCostDetailResponse.from(costs.mine(identity.actorId(), from, to));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/detail")
    @Operation(operationId = "getAiCostDetail", summary = "One person's, or system work's, AI costs by day, model, task and provider; requires model management")
    AiCostDetailResponse detail(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                  @RequestParam(required = false) @Nullable UUID actorId, @RequestParam(defaultValue = "false") boolean system) {
        return AiCostDetailResponse.from(costs.detail(identity.actorId(), new AiCostService.Range(from, to, actorId, system, null, null)));
    }
}
