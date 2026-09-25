package io.memoryos.api.chat;

import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/image-artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid image request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Image not accessible", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatImageArtifactController {
    private final ImageArtifactService images;
    ChatImageArtifactController(ImageArtifactService images) { this.images = images; }

    @DeleteMapping("/{artifactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatImageArtifact",
            summary = "Delete an owner-private generated image; the answer keeps a deleted card and a sweep releases the bytes")
    @ApiResponse(responseCode = "204", description = "Image deleted")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID artifactId) {
        images.delete(identity.actorId(), artifactId);
    }

    @GetMapping(value = "/{artifactId}/content", produces = {"image/png", "image/jpeg", "image/webp"})
    @Operation(operationId = "getChatImageArtifact", summary = "Read an owner-private generated image")
    @ApiResponse(responseCode = "200", description = "Generated image bytes",
            content = @Content(schema = @Schema(type = "string", format = "binary")))
    void content(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID artifactId,
            @Parameter(description = "Which rendering to read; a thumbnail is what the file library shows")
            @RequestParam(defaultValue = "ORIGINAL") ImageArtifactService.Variant variant,
            HttpServletResponse response) throws IOException {
        try (var served = images.open(identity.actorId(), artifactId, variant)) {
            response.setContentType(served.mediaType());
            // An artifact's bytes never change under its id, and the route authorizes every read, so the
            // owner's own browser may keep them. A shared cache must not: the response is owner-private.
            response.setHeader("Cache-Control", "private, max-age=31536000, immutable");
            response.setHeader("Content-Disposition", "inline");
            response.setContentLengthLong(served.sizeBytes());
            served.inputStream().transferTo(response.getOutputStream());
        }
    }
}
