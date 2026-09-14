package io.memoryos.api.chat;

import io.memoryos.api.search.contract.SearchDocumentResponse;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.retrieval.DocumentSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Opens organization document passages cited in a conversation; needs Tenant membership and document eligibility, not Search authority. */
@RestController
@RequestMapping(value = "/api/chat/documents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid passage window", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Document generation not readable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatDocumentController {
    private final DocumentSearchService documents;

    ChatDocumentController(DocumentSearchService documents) { this.documents = documents; }

    @GetMapping("/{documentId}")
    @Operation(operationId = "readChatDocumentPassages", summary = "Read current document passages around a Chat citation")
    @ApiResponse(responseCode = "200", description = "Authorized document passages", useReturnTypeSchema = true)
    ResponseEntity<SearchDocumentResponse> passages(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID documentId, @RequestParam UUID generation, @RequestParam(defaultValue = "0") int from) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(SearchDocumentResponse.from(documents.citation(identity.actorId(), documentId, generation, from)));
    }
}
