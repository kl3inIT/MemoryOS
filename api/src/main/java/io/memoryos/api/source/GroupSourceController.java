package io.memoryos.api.source;

import io.memoryos.api.source.contract.GroupSourcesResponse;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.IdentityContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
