package io.memoryos.api.chat;

import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.iam.identity.IdentityContext;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/image-artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Image not accessible", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatImageArtifactController {
    private final ImageArtifactService images;
    ChatImageArtifactController(ImageArtifactService images) { this.images = images; }

    @GetMapping(value = "/{artifactId}/content", produces = {"image/png", "image/jpeg", "image/webp"})
    @Operation(operationId = "getChatImageArtifact", summary = "Read an owner-private generated image")
    @ApiResponse(responseCode = "200", description = "Generated image bytes",
            content = @Content(schema = @Schema(type = "string", format = "binary")))
    void content(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID artifactId, HttpServletResponse response) throws IOException {
        var served = images.open(identity.actorId(), artifactId);
        try (var content = served.content()) {
            response.setContentType(served.mediaType());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", "inline");
            response.setContentLengthLong(content.metadata().sizeBytes());
            content.inputStream().transferTo(response.getOutputStream());
        }
    }
}
