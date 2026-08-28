package io.memoryos.api.source;

import io.memoryos.api.source.contract.SourceOperationResponse;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.identity.IdentityContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/source-operations")
@Tag(name = "Sources")
final class SourceOperationController {

    private final SourceManagementService sources;

    SourceOperationController(SourceManagementService sources) {
        this.sources = sources;
    }

    @Operation(operationId = "getSourceOperation", summary = "Get a durable source operation")
    @GetMapping("/{operationId}")
    SourceOperationResponse getOperation(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID operationId
    ) {
        return SourceOperationResponse.from(sources.getOperation(
                identityContext.actorId(),
                new SourceOperationId(operationId)
        ));
    }
}
