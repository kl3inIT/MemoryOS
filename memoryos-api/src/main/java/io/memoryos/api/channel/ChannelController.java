package io.memoryos.api.channel;

import io.memoryos.api.channel.contract.ChannelAppCheckResponse;
import io.memoryos.api.channel.contract.ChannelAppRequest;
import io.memoryos.api.channel.contract.ChannelResponse;
import io.memoryos.api.channel.contract.CreateChannelRequest;
import io.memoryos.api.channel.contract.ReplaceChannelSecretRequest;
import io.memoryos.api.channel.contract.UpdateChannelEnabledRequest;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.channel.ChannelConnectionDraft;
import io.memoryos.channel.ChannelConnectionService;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelRegion;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The Tenant's chat bots (MEM-246), under the capability to manage them. */
@RestController
@RequestMapping(value = "/api/channels", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Channels")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid bot, or an app the platform rejected")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "Bot unavailable")
@ApiResponse(responseCode = "409", description = "Bot changed, or its app already serves a bot")
@ApiResponse(responseCode = "503", description = "The platform did not answer, or bots are not configured")
final class ChannelController {
    private final ChannelConnectionService channels;

    ChannelController(ChannelConnectionService channels) {
        this.channels = channels;
    }

    @Operation(operationId = "listChannels", summary = "List the Tenant's bots with the state of their connections")
    @ApiResponse(responseCode = "200", description = "Bots", useReturnTypeSchema = true)
    @GetMapping
    ResponseEntity<List<ChannelResponse>> list(@CurrentActor IdentityContext identity) {
        return ResponseEntity.ok().body(channels.listWithState(identity.actorId()).stream().map(ChannelResponse::from).toList());
    }

    @Operation(operationId = "testLarkChannelApp", summary = "Check a Lark app with Lark without storing anything")
    @ApiResponse(responseCode = "200", description = "Lark accepts the app", useReturnTypeSchema = true)
    @PostMapping(value = "/lark/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChannelAppCheckResponse> test(@CurrentActor IdentityContext identity,
            @Valid @RequestBody ChannelAppRequest body) {
        return ResponseEntity.ok().body(ChannelAppCheckResponse.from(channels.test(identity.actorId(), ChannelProvider.LARK,
                lark(body.region(), body.appId(), body.appSecret()))));
    }

    @Operation(operationId = "createLarkChannel",
            summary = "Check a Lark app with Lark and add its bot, switched off; nothing is stored when Lark rejects it")
    @ApiResponse(responseCode = "201", description = "Added bot", useReturnTypeSchema = true)
    @PostMapping(value = "/lark", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChannelResponse> create(@CurrentActor IdentityContext identity,
            @Valid @RequestBody CreateChannelRequest body) {
        var created = channels.create(identity.actorId(), new ChannelConnectionDraft(ChannelProvider.LARK, body.name(),
                lark(body.region(), body.appId(), body.appSecret()), body.noticeLanguage()));
        return ResponseEntity.status(HttpStatus.CREATED).body(find(identity, created.id()));
    }

    @Operation(operationId = "replaceChannelSecret",
            summary = "Replace the App Secret of a bot's app once the platform accepts it, with a revision precondition")
    @ApiResponse(responseCode = "200", description = "Updated bot", useReturnTypeSchema = true)
    @PutMapping(value = "/{channelId}/secret", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChannelResponse> replaceSecret(@CurrentActor IdentityContext identity, @PathVariable UUID channelId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody ReplaceChannelSecretRequest body) {
        channels.replaceSecret(identity.actorId(), channelId, revision(ifMatch), body.appSecret());
        return ResponseEntity.ok().body(find(identity, channelId));
    }

    @Operation(operationId = "updateChannelEnabled", summary = "Switch a bot on or off with a revision precondition")
    @ApiResponse(responseCode = "200", description = "Updated bot", useReturnTypeSchema = true)
    @PutMapping(value = "/{channelId}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChannelResponse> setEnabled(@CurrentActor IdentityContext identity, @PathVariable UUID channelId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody UpdateChannelEnabledRequest body) {
        channels.setEnabled(identity.actorId(), channelId, revision(ifMatch), body.enabled());
        return ResponseEntity.ok().body(find(identity, channelId));
    }

    @Operation(operationId = "deleteChannel",
            summary = "Remove a bot and retire its account, with a revision precondition")
    @ApiResponse(responseCode = "204", description = "Bot removed", content = @Content)
    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@CurrentActor IdentityContext identity, @PathVariable UUID channelId,
            @RequestHeader("If-Match") String ifMatch) {
        channels.delete(identity.actorId(), channelId, revision(ifMatch));
        return ResponseEntity.noContent().build();
    }

    private ChannelResponse find(IdentityContext identity, UUID id) {
        return channels.listWithState(identity.actorId()).stream()
                .filter(listed -> listed.connection().id().equals(id)).findFirst()
                .map(ChannelResponse::from)
                .orElseThrow(ChannelException::notFound);
    }

    static long revision(String value) {
        if (value == null || !value.matches("\"[0-9]{1,19}\"")) throw invalidRevision();
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException exception) { throw invalidRevision(); }
    }

    /** A Lark app's credentials: its region is one of the provider's settings. */
    private static ChannelCredentials lark(ChannelRegion region, String appId, String appSecret) {
        return new ChannelCredentials(appId, appSecret, Map.of(ChannelRegion.SETTING, region.name()));
    }

    private static ChannelException invalidRevision() {
        return ChannelException.invalid("If-Match must contain one quoted numeric revision.");
    }
}
