package io.memoryos.api.users;

import io.memoryos.api.users.contract.UserPageResponse;
import io.memoryos.api.users.contract.ReplaceUserGroupsRequest;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupService;
import io.memoryos.iam.UserQueryService;
import io.memoryos.iam.UserQuery;
import io.memoryos.iam.UserSort;
import io.memoryos.iam.UserStatus;
import io.memoryos.iam.TenantMemberManagement;
import io.memoryos.iam.TenantMembershipRole;

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
@RequestMapping("/api/users")
@Tag(name = "Users")
final class UsersController {

    private static final String API_PROBLEM_SCHEMA = "#/components/schemas/ApiProblem";

    private final UserQueryService users;
    private final TenantMemberManagement memberManagement;
    private final GroupService groups;

    UsersController(
            UserQueryService users,
            TenantMemberManagement memberManagement,
            GroupService groups
    ) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.memberManagement = Objects.requireNonNull(memberManagement, "memberManagement must not be null");
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
    }

    @Operation(
            operationId = "listUsers",
            summary = "List users manageable by the current IAM administrator",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(
            responseCode = "200",
            description = "A bounded page of current memberships and eligible pending invitations",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = UserPageResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid user directory query",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(responseCode = "401", description = "No accepted authentication is present", content = @Content)
    @ApiResponse(
            responseCode = "403",
            description = "The actor lacks USERS_MANAGE authority",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @GetMapping
    UserPageResponse listUsers(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Size(max = UserQuery.MAX_SEARCH_LENGTH)
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) TenantMembershipRole role,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(defaultValue = "NAME_ASC") UserSort sort,
            @Parameter(schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0"))
            @Min(0)
            @RequestParam(defaultValue = "0") int page,
            @Parameter(
                    schema = @Schema(
                            type = "integer",
                            format = "int32",
                            defaultValue = "20",
                            minimum = "1",
                            maximum = "100"
                    )
            )
            @Min(1)
            @Max(UserQuery.MAX_SIZE)
            @RequestParam(defaultValue = "20") int size
    ) {
        return UserPageResponse.from(users.list(
                identityContext.actorId(),
                new UserQuery(
                        search,
                        status,
                        role,
                        groupId == null ? null : new GroupId(groupId),
                        sort,
                        page,
                        size
                )
        ));
    }

    @Operation(
            operationId = "activateUser",
            summary = "Activate an existing Tenant member",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(responseCode = "204", description = "The member is active", content = @Content)
    @ApiResponse(responseCode = "401", description = "No accepted authentication is present", content = @Content)
    @ApiResponse(
            responseCode = "403",
            description = "The actor lacks USERS_MANAGE authority, the target is protected, "
                    + "or the same-origin header is missing",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "The member does not exist in the current Tenant",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{actorId}/activate")
    void activateUser(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID actorId
    ) {
        memberManagement.activate(identityContext.actorId(), new ActorId(actorId));
    }

    @Operation(
            operationId = "deactivateUser",
            summary = "Deactivate an existing Tenant member",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(responseCode = "204", description = "The member is inactive", content = @Content)
    @ApiResponse(responseCode = "401", description = "No accepted authentication is present", content = @Content)
    @ApiResponse(
            responseCode = "403",
            description = "The actor lacks USERS_MANAGE authority, the target is protected, "
                    + "or the same-origin header is missing",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "The member does not exist in the current Tenant",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{actorId}/deactivate")
    void deactivateUser(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID actorId
    ) {
        memberManagement.deactivate(identityContext.actorId(), new ActorId(actorId));
    }

    @Operation(
            operationId = "replaceUserGroups",
            summary = "Replace a user's ordinary Group memberships",
            security = {
                    @SecurityRequirement(name = "browserSession"),
                    @SecurityRequirement(name = "bearerAuth")
            }
    )
    @ApiResponse(responseCode = "204", description = "The user's ordinary Group memberships were replaced", content = @Content)
    @ApiResponse(responseCode = "401", description = "No accepted authentication is present", content = @Content)
    @ApiResponse(
            responseCode = "403",
            description = "The actor lacks IAM_ADMIN authority or the same-origin header is missing",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "The user or a requested Group does not exist in the current Tenant",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = API_PROBLEM_SCHEMA)
            )
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{actorId}/groups")
    void replaceUserGroups(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID actorId,
            @Valid @RequestBody ReplaceUserGroupsRequest request
    ) {
        groups.replaceOrdinaryMemberships(
                identityContext.actorId(),
                new ActorId(actorId),
                request.groupIds().stream().map(GroupId::new).toList()
        );
    }
}
