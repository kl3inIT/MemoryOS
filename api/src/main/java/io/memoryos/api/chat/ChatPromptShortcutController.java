package io.memoryos.api.chat;

import io.memoryos.chat.ChatPromptShortcutService;
import io.memoryos.chat.ChatPromptShortcutService.Preferences;
import io.memoryos.chat.ChatPromptShortcutService.ShortcutInput;
import io.memoryos.chat.persistence.JdbcPromptShortcutRepository.Shortcut;
import io.memoryos.iam.identity.IdentityContext;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/chat/prompt-shortcuts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid chat request",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat resource not accessible",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Name is taken or revision has changed",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatPromptShortcutController {
    private final ChatPromptShortcutService shortcuts;
    ChatPromptShortcutController(ChatPromptShortcutService shortcuts) { this.shortcuts = shortcuts; }

    record HiddenRequest(boolean hidden) {}
    record PreferencesRequest(boolean enabled) {}

    @GetMapping
    @Operation(operationId = "listChatPromptShortcuts", summary = "List the actor's own and public prompt shortcuts")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<Shortcut> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "false") boolean includeHidden) {
        return shortcuts.list(identity.actorId(), includeHidden);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatPromptShortcut", summary = "Create a private prompt shortcut")
    @ApiResponse(responseCode = "201", description = "Successful chat operation", useReturnTypeSchema = true)
    Shortcut create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ShortcutInput request) {
        return shortcuts.create(identity.actorId(), request, false);
    }
    @PutMapping("/{shortcutId}")
    @Operation(operationId = "updateChatPromptShortcut", summary = "Update a private prompt shortcut with an expected revision")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    Shortcut update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID shortcutId,
            @RequestParam long revision, @RequestBody ShortcutInput request) {
        return shortcuts.update(identity.actorId(), shortcutId, revision, request, false);
    }
    @DeleteMapping("/{shortcutId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatPromptShortcut", summary = "Delete a private prompt shortcut")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID shortcutId) {
        shortcuts.delete(identity.actorId(), shortcutId, false);
    }
    @PutMapping("/{shortcutId}/hidden")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "hideChatPromptShortcut", summary = "Hide or show a public prompt shortcut for the actor")
    void hide(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID shortcutId,
            @RequestBody HiddenRequest request) {
        shortcuts.hide(identity.actorId(), shortcutId, request.hidden());
    }
    @GetMapping("/preferences")
    @Operation(operationId = "getChatPromptShortcutPreferences", summary = "Read whether the actor uses prompt shortcuts")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    Preferences preferences(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return shortcuts.preferences(identity.actorId());
    }
    @PutMapping("/preferences")
    @Operation(operationId = "setChatPromptShortcutPreferences", summary = "Enable or disable prompt shortcuts for the actor")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    Preferences setPreferences(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody PreferencesRequest request) {
        return shortcuts.preferences(identity.actorId(), request.enabled());
    }
    @GetMapping("/public")
    @Operation(operationId = "listPublicChatPromptShortcuts", summary = "List public prompt shortcuts; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<Shortcut> publicShortcuts(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return shortcuts.publicShortcuts(identity.actorId());
    }
    @PostMapping("/public")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createPublicChatPromptShortcut", summary = "Create a public prompt shortcut; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "201", description = "Successful chat operation", useReturnTypeSchema = true)
    Shortcut createPublic(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ShortcutInput request) {
        return shortcuts.create(identity.actorId(), request, true);
    }
    @PutMapping("/public/{shortcutId}")
    @Operation(operationId = "updatePublicChatPromptShortcut", summary = "Update a public prompt shortcut; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    Shortcut updatePublic(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID shortcutId,
            @RequestParam long revision, @RequestBody ShortcutInput request) {
        return shortcuts.update(identity.actorId(), shortcutId, revision, request, true);
    }
    @DeleteMapping("/public/{shortcutId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deletePublicChatPromptShortcut", summary = "Delete a public prompt shortcut; requires AGENTS_MANAGE")
    void deletePublic(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID shortcutId) {
        shortcuts.delete(identity.actorId(), shortcutId, true);
    }
}
