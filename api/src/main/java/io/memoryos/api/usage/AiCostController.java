package io.memoryos.api.usage;

import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.usage.AiCostService;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.persistence.AiCostQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
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

    @Schema(name = "AiCostSummary", description = "Known costs in USD; calls without a price or reported usage are counted in unknownCostCalls, never as zero")
    record Summary(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal externalCost,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unknownCostCalls,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long cacheReadTokens,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long imageCount,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal audioSeconds,
                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long activePeople) {
        static Summary from(AiCostQueries.Totals value) {
            return new Summary(value.cost(), value.externalCost(), value.calls(), value.unknownCostCalls(), value.inputTokens(),
                    value.outputTokens(), value.cacheReadTokens(), value.imageCount(), value.audioSeconds(), value.activePeople());
        }
    }

    @Schema(name = "AiCostDay", description = "One UTC day of one series: a data boundary (INTERNAL, EXTERNAL, NONE), a model name or ALL")
    record Day(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate day,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String series,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long cacheReadTokens) {
        static Day from(AiCostQueries.Day value) {
            return new Day(value.day(), value.key(), value.cost(), value.calls(), value.inputTokens(), value.outputTokens(),
                    value.cacheReadTokens());
        }
    }

    @Schema(name = "AiCostRow", description = "A ranked row: a person (key SYSTEM for work without a person), Group, model, task or provider")
    record Row(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String detail,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unknownCostCalls,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost) {
        static Row from(AiCostQueries.Row value) {
            return new Row(value.key(), value.label(), value.detail(), value.calls(), value.unknownCostCalls(), value.inputTokens(),
                    value.outputTokens(), value.cost());
        }
    }

    @Schema(name = "AiCostDetail")
    record Detail(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Summary summary,
                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Day> daily,
                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Row> models,
                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Row> flows,
                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Row> providers) {}

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/summary")
    @Operation(operationId = "getAiCostSummary", summary = "Tenant AI usage and known costs for a UTC day range; requires model management")
    Summary summary(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    @RequestParam(required = false) @Nullable String model, @RequestParam(required = false) @Nullable AiUsageFlow flow) {
        return Summary.from(costs.summary(identity.actorId(), new AiCostService.Range(from, to, null, false, model, flow)));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/daily")
    @Operation(operationId = "listAiCostDays", summary = "Daily AI costs split by data boundary, by model or not at all; requires model management")
    List<Day> daily(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    @RequestParam(defaultValue = "BOUNDARY") AiCostQueries.Split split,
                    @RequestParam(required = false) @Nullable String model, @RequestParam(required = false) @Nullable AiUsageFlow flow) {
        return costs.daily(identity.actorId(), new AiCostService.Range(from, to, null, false, model, flow), split)
                .stream().map(Day::from).toList();
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/breakdown")
    @Operation(operationId = "listAiCostBreakdown", summary = "AI costs ranked by person, Group, model, task or provider; requires model management")
    List<Row> breakdown(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam AiCostQueries.Dimension by,
                        @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "200", defaultValue = "50"))
                        @RequestParam(defaultValue = "50") int limit,
                        @RequestParam(required = false) @Nullable String model, @RequestParam(required = false) @Nullable AiUsageFlow flow) {
        return costs.breakdown(identity.actorId(), new AiCostService.Range(from, to, null, false, model, flow), by, limit)
                .stream().map(Row::from).toList();
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/detail")
    @Operation(operationId = "getAiCostDetail", summary = "One person's, or system work's, AI costs by day, model, task and provider; requires model management")
    Detail detail(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                  @RequestParam(required = false) @Nullable UUID actorId, @RequestParam(defaultValue = "false") boolean system) {
        var detail = costs.detail(identity.actorId(), new AiCostService.Range(from, to, actorId, system, null, null));
        return new Detail(Summary.from(detail.totals()), detail.daily().stream().map(Day::from).toList(),
                detail.models().stream().map(Row::from).toList(), detail.flows().stream().map(Row::from).toList(),
                detail.providers().stream().map(Row::from).toList());
    }
}
