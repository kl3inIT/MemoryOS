package io.memoryos.integration.microsoft;

import static io.memoryos.connector.SharePointProviderException.Failure.LIMIT_EXCEEDED;
import static io.memoryos.connector.SharePointProviderException.Failure.MALFORMED;

import io.memoryos.connector.SharePointGateway;
import io.memoryos.connector.SharePointGateway.PrincipalType;
import io.memoryos.connector.SharePointProviderException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * SharePoint REST answers as the records of {@link SharePointGateway}. What a role assignment means for a reader is
 * decided in the connector; here only the shape is read: which kind of principal, and whether every role bound is
 * Limited Access.
 */
final class SharePointRestModels {
    private static final int MAX_FIELD_CHARS = 16_384;
    // SP.RoleType 1 and 9: the roles of somebody who was only given a way through to something below.
    private static final int GUEST = 1;
    private static final int RESTRICTED_GUEST = 9;

    private SharePointRestModels() {}

    static SharePointGateway.RoleAssignment roleAssignment(JsonNode entry) {
        JsonNode bindings = entry.path("RoleDefinitionBindings");
        if (!bindings.isArray()) throw new SharePointProviderException(MALFORMED);
        boolean limited = true;
        for (JsonNode binding : bindings) {
            int kind = binding.path("RoleTypeKind").asInt(-1);
            if (kind != GUEST && kind != RESTRICTED_GUEST) limited = false;
        }
        return new SharePointGateway.RoleAssignment(principal(entry.path("Member")), limited);
    }

    static SharePointGateway.Principal principal(JsonNode member) {
        if (!member.isObject() || !member.path("Id").isIntegralNumber()) {
            throw new SharePointProviderException(MALFORMED);
        }
        // SP.PrincipalType: 1 a user, 4 a security group of the directory, 8 a group of the site.
        PrincipalType type = switch (member.path("PrincipalType").asInt(0)) {
            case 1 -> PrincipalType.USER;
            case 4 -> PrincipalType.ENTRA_GROUP;
            case 8 -> PrincipalType.SHAREPOINT_GROUP;
            default -> PrincipalType.OTHER;
        };
        String login = text(member, "LoginName");
        if (login == null) throw new SharePointProviderException(MALFORMED);
        return new SharePointGateway.Principal(type, member.path("Id").asLong(), login, text(member, "Title"),
                text(member, "UserPrincipalName"));
    }

    private static @Nullable String text(JsonNode node, String field) {
        String value = node.path(field).asString(null);
        if (value == null || value.isBlank()) return null;
        if (value.length() > MAX_FIELD_CHARS) throw new SharePointProviderException(LIMIT_EXCEEDED);
        return value;
    }
}
