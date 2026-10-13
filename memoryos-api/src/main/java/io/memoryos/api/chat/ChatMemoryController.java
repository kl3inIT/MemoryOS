package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatMemoryEnabledRequest;
import io.memoryos.api.chat.contract.ChatMemoryRequest;
import io.memoryos.api.chat.contract.ChatMemoryResponse;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.chat.ChatMemoryService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What Chat remembers about the current member, which only that member reads and changes (MEM-250). */
@RestController
@RequestMapping(value = "/api/chat/memory", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Memory")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid memory")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "CSRF required")
@ApiResponse(responseCode = "404", description = "Membership unavailable")
class ChatMemoryController {
    private final ChatMemoryService memories;

    ChatMemoryController(ChatMemoryService memories) {
        this.memories = memories;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Your memory", useReturnTypeSchema = true)
    @Operation(operationId = "getChatMemory", summary = "Read what Chat remembers about you")
    ChatMemoryResponse get(@CurrentActor IdentityContext identity) {
        return ChatMemoryResponse.from(memories.get(identity.actorId()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "The saved memory", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "The memory changed since it was read")
    @Operation(operationId = "saveChatMemory", summary = "Replace what Chat remembers about you")
    ChatMemoryResponse save(@CurrentActor IdentityContext identity, @Valid @RequestBody ChatMemoryRequest request) {
        return ChatMemoryResponse.from(memories.replace(identity.actorId(), request.content(), request.revision()));
    }

    @DeleteMapping
    @ApiResponse(responseCode = "200", description = "The emptied memory", useReturnTypeSchema = true)
    @Operation(operationId = "clearChatMemory", summary = "Forget everything Chat remembers about you")
    ChatMemoryResponse clear(@CurrentActor IdentityContext identity) {
        return ChatMemoryResponse.from(memories.clear(identity.actorId()));
    }

    @PutMapping(value = "/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "The memory with its switch", useReturnTypeSchema = true)
    @Operation(operationId = "setChatMemoryEnabled", summary = "Turn Chat's memory of you on or off")
    ChatMemoryResponse enabled(@CurrentActor IdentityContext identity, @Valid @RequestBody ChatMemoryEnabledRequest request) {
        return ChatMemoryResponse.from(memories.enabled(identity.actorId(), request.enabled()));
    }
}
