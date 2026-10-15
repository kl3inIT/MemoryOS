package io.memoryos.api.channel;

import io.memoryos.api.channel.contract.ChannelLinkCodeResponse;
import io.memoryos.api.channel.contract.ChannelLinkResponse;
import io.memoryos.api.channel.contract.CreateChannelLinkCodeRequest;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.channel.ChannelLinkService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A member's own accounts on platforms that name no e-mail, such as Zalo, linked to their MemoryOS account (MEM-263). */
@RestController
@RequestMapping(value = "/api/channel-links", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Channels")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid request")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Chat access or CSRF required")
@ApiResponse(responseCode = "404", description = "No bot of the Tenant that takes a linked account")
final class ChannelLinkController {
    private final ChannelLinkService links;

    ChannelLinkController(ChannelLinkService links) {
        this.links = links;
    }

    @Operation(operationId = "listChannelLinks",
            summary = "List the Tenant's bots that take a linked account, with the member's own link on each")
    @ApiResponse(responseCode = "200", description = "Bots and links", useReturnTypeSchema = true)
    @GetMapping
    ResponseEntity<List<ChannelLinkResponse>> list(@CurrentActor IdentityContext identity) {
        return ResponseEntity.ok().body(links.links(identity.actorId()).stream().map(ChannelLinkResponse::from).toList());
    }

    @Operation(operationId = "createChannelLinkCode",
            summary = "Make a code, valid ten minutes, that links the platform account sending it to the bot to the member")
    @ApiResponse(responseCode = "201", description = "Linking code", useReturnTypeSchema = true)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChannelLinkCodeResponse> code(@CurrentActor IdentityContext identity,
            @Valid @RequestBody CreateChannelLinkCodeRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ChannelLinkCodeResponse.from(links.code(identity.actorId(), body.connectionId())));
    }

    @Operation(operationId = "deleteChannelLink", summary = "Remove the member's link on a bot, and any code they made for it")
    @ApiResponse(responseCode = "204", description = "Link removed", content = @Content)
    @DeleteMapping("/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> unlink(@CurrentActor IdentityContext identity, @PathVariable UUID connectionId) {
        links.unlink(identity.actorId(), connectionId);
        return ResponseEntity.noContent().build();
    }
}
