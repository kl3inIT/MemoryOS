package io.memoryos.api.groups;

import io.memoryos.api.groups.contract.AddGroupMembersRequest;
import io.memoryos.api.groups.contract.CreateGroupRequest;
import io.memoryos.api.groups.contract.GroupCapabilitiesResponse;
import io.memoryos.api.groups.contract.GroupMemberPageResponse;
import io.memoryos.api.groups.contract.GroupSummaryPageResponse;
import io.memoryos.api.groups.contract.GroupSummaryResponse;
import io.memoryos.api.groups.contract.RenameGroupRequest;
import io.memoryos.api.groups.contract.ReplaceGroupCapabilitiesRequest;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.GroupService;
import io.memoryos.iam.IdentityContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups")
@Tag(name = "Groups")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class GroupsController {
    private static final String API_PROBLEM_SCHEMA = "#/components/schemas/ApiProblem";

    private final GroupService groups;

    GroupsController(GroupService groups) {
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
    }

    @Operation(operationId = "listGroups", summary = "List Groups visible to the current actor")
    @ApiResponse(
            responseCode = "200",
            description = "A bounded, server-authorized Group page",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GroupSummaryPageResponse.class)
            )
    )
    @ApiResponse(responseCode = "401", description = "No accepted authentication is present", content = @Content)
    @ApiResponse(
            responseCode = "403",
            description = "The actor has no global or managed-Group read authority",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @GetMapping
    GroupSummaryPageResponse listGroups(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Size(max = GroupQuery.MAX_SEARCH_LENGTH)
            @RequestParam(required = false) String search,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(GroupQuery.MAX_SIZE) @RequestParam(defaultValue = "20") int size
    ) {
        return GroupSummaryPageResponse.from(groups.list(
                identityContext.actorId(),
                new GroupQuery(search, page, size)
        ));
    }

    @Operation(operationId = "listGroupCapabilities", summary = "List the server-owned Group capability registry")
    @ApiResponse(
            responseCode = "200",
            description = "Capability metadata for implemented consumers",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GroupCapabilitiesResponse.class)
            )
    )
    @GetMapping("/capabilities")
    GroupCapabilitiesResponse listGroupCapabilities(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext
    ) {
        return GroupCapabilitiesResponse.from(groups.capabilities(identityContext.actorId()));
    }

    @Operation(operationId = "createGroup", summary = "Create an ordinary Group")
    @ApiResponse(
            responseCode = "201",
            description = "Group created",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GroupSummaryResponse.class)
            )
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    GroupSummaryResponse createGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Valid @RequestBody CreateGroupRequest request
    ) {
        return GroupSummaryResponse.from(groups.create(identityContext.actorId(), request.name()));
    }

    @Operation(operationId = "getGroup", summary = "Get one visible Group")
    @ApiResponse(
            responseCode = "200",
            description = "The server-authorized Group detail",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GroupSummaryResponse.class)
            )
    )
    @GetMapping("/{groupId}")
    GroupSummaryResponse getGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId
    ) {
        return GroupSummaryResponse.from(groups.get(identityContext.actorId(), new GroupId(groupId)));
    }

    @Operation(operationId = "renameGroup", summary = "Rename an ordinary Group")
    @ApiResponse(
            responseCode = "200",
            description = "Group renamed",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GroupSummaryResponse.class)
            )
    )
    @PostMapping("/{groupId}/rename")
    GroupSummaryResponse renameGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @Valid @RequestBody RenameGroupRequest request
    ) {
        return GroupSummaryResponse.from(groups.rename(
                identityContext.actorId(),
                new GroupId(groupId),
                request.name()
        ));
    }

    @Operation(operationId = "deleteGroup", summary = "Delete an ordinary Group")
    @ApiResponse(responseCode = "204", description = "Group links and grants deleted", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{groupId}/delete")
    void deleteGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId
    ) {
        groups.delete(identityContext.actorId(), new GroupId(groupId));
    }

    @Operation(operationId = "listGroupMembers", summary = "List members of one visible Group")
    @ApiResponse(
            responseCode = "200",
            description = "A bounded Group member page",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GroupMemberPageResponse.class)
            )
    )
    @GetMapping("/{groupId}/members")
    GroupMemberPageResponse listGroupMembers(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @Size(max = GroupQuery.MAX_SEARCH_LENGTH)
            @RequestParam(required = false) String search,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(GroupQuery.MAX_SIZE) @RequestParam(defaultValue = "20") int size
    ) {
        return GroupMemberPageResponse.from(groups.members(
                identityContext.actorId(),
                new GroupId(groupId),
                new GroupQuery(search, page, size)
        ));
    }

    @Operation(operationId = "listGroupCandidates", summary = "List Tenant members eligible to join a Group")
    @ApiResponse(
            responseCode = "200",
            description = "A bounded candidate page",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GroupMemberPageResponse.class)
            )
    )
    @GetMapping("/{groupId}/candidates")
    GroupMemberPageResponse listGroupCandidates(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @Size(max = GroupQuery.MAX_SEARCH_LENGTH)
            @RequestParam(required = false) String search,
            @Min(0) @RequestParam(defaultValue = "0") int page,
            @Min(1) @Max(GroupQuery.MAX_SIZE) @RequestParam(defaultValue = "20") int size
    ) {
        return GroupMemberPageResponse.from(groups.candidates(
                identityContext.actorId(),
                new GroupId(groupId),
                new GroupQuery(search, page, size)
        ));
    }

    @Operation(operationId = "addGroupMembers", summary = "Add Tenant members to a Group")
    @ApiResponse(responseCode = "204", description = "Members added idempotently", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{groupId}/members")
    void addGroupMembers(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @Valid @RequestBody AddGroupMembersRequest request
    ) {
        groups.addMembers(identityContext.actorId(), new GroupId(groupId), request.toActorIds());
    }

    @Operation(operationId = "removeGroupMember", summary = "Remove one member from a Group")
    @ApiResponse(responseCode = "204", description = "Member removed", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{groupId}/members/{actorId}/remove")
    void removeGroupMember(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @PathVariable UUID actorId
    ) {
        groups.removeMember(
                identityContext.actorId(),
                new GroupId(groupId),
                new ActorId(actorId)
        );
    }

    @Operation(operationId = "assignGroupManager", summary = "Assign a manager to an ordinary Group")
    @ApiResponse(responseCode = "204", description = "Manager assigned", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{groupId}/members/{actorId}/assign-manager")
    void assignGroupManager(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @PathVariable UUID actorId
    ) {
        groups.assignManager(
                identityContext.actorId(),
                new GroupId(groupId),
                new ActorId(actorId)
        );
    }

    @Operation(operationId = "removeGroupManager", summary = "Remove a manager from an ordinary Group")
    @ApiResponse(responseCode = "204", description = "Manager flag removed", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{groupId}/members/{actorId}/remove-manager")
    void removeGroupManager(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @PathVariable UUID actorId
    ) {
        groups.removeManager(
                identityContext.actorId(),
                new GroupId(groupId),
                new ActorId(actorId)
        );
    }

    @Operation(operationId = "replaceGroupCapabilities", summary = "Replace an ordinary Group's explicit capability grants")
    @ApiResponse(responseCode = "204", description = "Explicit grants replaced", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{groupId}/capabilities")
    void replaceGroupCapabilities(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID groupId,
            @Valid @RequestBody ReplaceGroupCapabilitiesRequest request
    ) {
        groups.replaceCapabilities(
                identityContext.actorId(),
                new GroupId(groupId),
                request.capabilities()
        );
    }
}
