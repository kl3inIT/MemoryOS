package io.memoryos.api.channel;

import io.memoryos.api.channel.contract.AssistantChannelResponse;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.channel.ChannelConnectionService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Where an assistant answers (MEM-273), for its Kênh tab: read by anyone who may use the assistant. */
@RestController
@Tag(name = "Channels")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid assistant id")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Tenant membership required")
@ApiResponse(responseCode = "404", description = "Assistant unavailable")
final class AssistantChannelController {
    private final ChannelConnectionService channels;

    AssistantChannelController(ChannelConnectionService channels) {
        this.channels = channels;
    }

    @Operation(operationId = "listAssistantChannels", summary = "List the bots that answer with an assistant, by default or in their group chats")
    @ApiResponse(responseCode = "200", description = "Bots", useReturnTypeSchema = true)
    @GetMapping(value = "/api/chat/personas/{personaId}/channels", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<AssistantChannelResponse>> list(@CurrentActor IdentityContext identity, @PathVariable UUID personaId) {
        return ResponseEntity.ok().body(channels.boundTo(identity.actorId(), personaId).stream()
                .map(AssistantChannelResponse::from).toList());
    }
}
