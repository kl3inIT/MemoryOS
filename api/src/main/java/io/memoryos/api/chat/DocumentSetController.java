package io.memoryos.api.chat;

import io.memoryos.chat.DocumentSetService;
import io.memoryos.chat.DocumentSetService.Input;
import io.memoryos.chat.DocumentSetService.DocumentSetSharingInput;
import io.memoryos.chat.DocumentSetService.View;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/document-sets")
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid chat request",
        content = @Content(mediaType = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met",
        content = @Content(mediaType = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat resource not accessible",
        content = @Content(mediaType = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class DocumentSetController {
    private final DocumentSetService sets;
    DocumentSetController(DocumentSetService sets) { this.sets = sets; }

    @GetMapping
    @Operation(operationId = "listDocumentSets", summary = "List Document Sets the actor can use")
    @ApiResponse(responseCode = "200", description = "Usable Document Sets", useReturnTypeSchema = true)
    List<View> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return sets.list(identity.actorId(), offset, limit);
    }

    @GetMapping("/{documentSetId}")
    @Operation(operationId = "getDocumentSet", summary = "Read a usable Document Set")
    @ApiResponse(responseCode = "200", description = "Document Set", useReturnTypeSchema = true)
    View get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID documentSetId) {
        return sets.get(identity.actorId(), documentSetId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createDocumentSet", summary = "Create a private Document Set; requires AGENTS_CREATE")
    @ApiResponse(responseCode = "201", description = "Created Document Set", useReturnTypeSchema = true)
    View create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody Input input) {
        return sets.create(identity.actorId(), input);
    }

    @PutMapping("/{documentSetId}")
    @Operation(operationId = "updateDocumentSet", summary = "Update a Document Set with an expected revision")
    @ApiResponse(responseCode = "200", description = "Updated Document Set", useReturnTypeSchema = true)
    View update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID documentSetId,
            @RequestParam long revision, @RequestBody Input input) {
        return sets.update(identity.actorId(), documentSetId, revision, input);
    }

    @DeleteMapping("/{documentSetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteDocumentSet", summary = "Soft-delete a Document Set and detach it from agents")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID documentSetId,
            @RequestParam long revision) {
        sets.delete(identity.actorId(), documentSetId, revision);
    }

    @PutMapping("/{documentSetId}/sharing")
    @Operation(operationId = "shareDocumentSet", summary = "Replace direct people and ordinary Group viewers")
    @ApiResponse(responseCode = "200", description = "Updated Document Set", useReturnTypeSchema = true)
    View share(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID documentSetId,
            @RequestParam long revision, @RequestBody DocumentSetSharingInput input) {
        return sets.share(identity.actorId(), documentSetId, revision, input);
    }
}
