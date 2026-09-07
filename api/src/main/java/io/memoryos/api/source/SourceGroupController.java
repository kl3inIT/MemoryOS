package io.memoryos.api.source;

import io.memoryos.api.source.contract.SourceGroupListResponse;
import io.memoryos.api.source.contract.SourceGroupPageResponse;
import io.memoryos.api.source.contract.UpdateSourceGroupsRequest;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.IdentityContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class SourceGroupController {

    private final SourceManagementService sources;

    SourceGroupController(SourceManagementService sources) {
        this.sources = sources;
    }

    @Operation(operationId = "listSourceGroups", summary = "List groups associated with one source")
    @GetMapping("/{sourceId}/groups")
    SourceGroupListResponse listSourceGroups(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId
    ) {
        return SourceGroupListResponse.from(sources.listSourceGroups(
                identityContext.actorId(),
                new SourceId(sourceId)
        ));
    }

    @Operation(operationId = "updateSourceGroups", summary = "Replace groups associated with one source")
    @PostMapping(value = "/{sourceId}/groups", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateSourceGroups(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId,
            @Valid @RequestBody UpdateSourceGroupsRequest request
    ) {
        sources.replaceSourceGroups(
                identityContext.actorId(),
                new SourceId(sourceId),
                request.toGroupIds()
        );
    }

    @Operation(operationId = "listSourceGroupOptions", summary = "List groups available for source association")
    @GetMapping("/group-options")
    SourceGroupPageResponse listSourceGroupOptions(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Size(max = GroupQuery.MAX_SEARCH_LENGTH)
            @RequestParam(required = false) @Nullable String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(GroupQuery.MAX_SIZE) int size
    ) {
        return SourceGroupPageResponse.from(sources.listSourceGroupOptions(
                identityContext.actorId(),
                search,
                page,
                size
        ));
    }
}
