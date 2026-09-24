package io.memoryos.api.source;

import io.memoryos.api.source.contract.GroupSourcesResponse;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.IdentityContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups/{groupId}/sources")
@Tag(name = "Groups")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class GroupSourceController {

    private final SourceManagementService sources;

    GroupSourceController(SourceManagementService sources) {
        this.sources = sources;
    }

    @Operation(operationId = "listGroupSources", summary = "List sources associated with one group")
    @GetMapping
    GroupSourcesResponse listGroupSources(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId
    ) {
        return GroupSourcesResponse.from(sources.listGroupSources(
                identityContext.actorId(),
                new GroupId(groupId)
        ));
    }

    @Operation(
            operationId = "removeGroupSource",
            summary = "Remove one source from one group",
            description = "Global source managers may remove any association. Otherwise only the source's responsible "
                    + "manager may remove it, from a group they manage; other managers of that group may not. "
                    + "The source's other groups keep their associations."
    )
    @PostMapping("/{sourceId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeGroupSource(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @PathVariable UUID sourceId
    ) {
        sources.removeGroupSource(
                identityContext.actorId(),
                new GroupId(groupId),
                new SourceId(sourceId)
        );
    }
}
