package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ImageAvailabilityResponse;
import io.memoryos.api.chat.contract.ImageConnectionRequest;
import io.memoryos.api.chat.contract.ImageConnectionResponse;
import io.memoryos.api.chat.contract.ImageSelectionRequest;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ProviderCredentials;
import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageProvider;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/images", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Image")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid image configuration", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Image connection unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Image connection changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Image provider unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class ImageConnectionController {
    private final ImageConnectionService connections;
    private final ImageProviderClient client;
    ImageConnectionController(ImageConnectionService connections, ImageProviderClient client) {
        this.connections = connections; this.client = client;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Configured image availability", useReturnTypeSchema = true)
    @Operation(operationId = "getChatImageAvailability", summary = "Read configured image-generation availability without credentials")
    ImageAvailabilityResponse availability(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        var access = connections.resolve(identity.actorId());
        var generate = access.generate();
        return new ImageAvailabilityResponse(generate != null,
                generate == null ? null : generate.provider(), generate == null ? null : generate.model());
    }
    @GetMapping("/connections")
    @ApiResponse(responseCode = "200", description = "Image connections", useReturnTypeSchema = true)
    @Operation(operationId = "listChatImageConnections", summary = "List image connections for model managers")
    List<ImageConnectionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return connections.list(identity.actorId()).stream().map(ImageConnectionResponse::from).toList();
    }
    @PutMapping("/connections/{provider}")
    @ApiResponse(responseCode = "200", description = "Saved image connection", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatImageConnection", summary = "Configure one image connection without automatically enabling it")
    ImageConnectionResponse save(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable ImageProvider provider, @Valid @RequestBody ImageConnectionRequest request) {
        return ImageConnectionResponse.from(connections.save(identity.actorId(), provider, new ImageConnectionService.Input(request.endpoint(), request.model(),
                new ProviderCredentials.Change(request.credentialAction(), request.credentialValue()), request.revision())));
    }
    @PutMapping("/selection")
    @ApiResponse(responseCode = "204", description = "Image selection saved", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "selectChatImageProvider", summary = "Select the image provider; null disables image generation")
    void select(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @Valid @RequestBody ImageSelectionRequest request) {
        connections.select(identity.actorId(), request.provider());
    }
    @PostMapping("/connections/{provider}/test")
    @ApiResponse(responseCode = "204", description = "Provider request succeeded", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "testChatImageConnection", summary = "Explicitly generate a test image; provider charges may apply")
    void test(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable ImageProvider provider) {
        var connection = connections.forTest(identity.actorId(), provider);
        try {
            if (client.generate(connection, "a small solid blue circle on a white background", null).bytes().length == 0)
                throw new IOException("No image produced");
        } catch (IOException | IllegalArgumentException failed) { throw ChatException.providerUnavailable(); }
    }
}
