package io.memoryos.connector.sharepoint;

import io.memoryos.connector.SharePointGateway.EntraMember;
import io.memoryos.connector.SharePointGateway.Principal;
import io.memoryos.connector.SharePointGateway.PrincipalType;
import io.memoryos.connector.SharePointGateway.RoleAssignment;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What SharePoint's role assignments mean for readers (MEM-136), as Onyx reads them. An assignment that binds only
 * Limited Access lets nobody read. A person is known by the sign-in name without {@code .onmicrosoft}; a group by a
 * name both sides build the same way, here for a document and in the group membership of the Source. A group inside
 * a group is named too, because membership is kept one level deep: each group lists only its direct members.
 *
 * <p>One rule is stricter than Onyx: an Entra group whose identifier is not in its login name is left out rather than
 * looked up by its display name, which is not unique. That can only withhold a document.
 */
final class SharePointAccess {
    /** "Everyone except external users" and "Everyone": a document one of them may read is read by every reader. */
    private static final List<String> EVERYONE = List.of("c:0-.f|rolemanager|spo-grid-all-users/", "c:0(.s|true");
    private static final Pattern GUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    private static final int MAX_EMAIL = 320;
    /** Groups one document is followed through; past it the walk stops and what was not reached grants nothing. */
    private static final int MAX_GROUPS = 1_000;
    /** The longest group name kept; a group with a longer one is left out on both sides, which admits nobody. */
    private static final int MAX_GROUP_ID = 512;

    private SharePointAccess() {}

    /** What a group contains, as Microsoft lists it. A group that no longer exists contains nothing. */
    interface Groups {
        /** The direct members of a SharePoint group of the site. */
        List<Principal> siteGroup(long id);

        /** The groups directly inside an Entra group; its people may be left out, since only groups are followed. */
        List<EntraMember> entraGroup(String id);
    }

    /** Who reads a document: everyone, or these people and the members of these groups. */
    record Readers(boolean everyone, SortedSet<String> userEmails, SortedSet<String> groupIds) {}

    /**
     * Who the role assignments of one securable of the site at {@code webUrl} let read it. Groups are followed
     * breadth first and each is asked once, so groups that contain each other end.
     */
    static Readers readers(String webUrl, List<RoleAssignment> assignments, Groups groups) {
        var walk = new Walk(webUrl, groups);
        for (RoleAssignment assignment : assignments) {
            // A person named on the document reads it; a person inside a group reads through its membership.
            if (assignment.limitedAccessOnly()) continue;
            Principal member = assignment.member();
            String email = member.type() == PrincipalType.USER ? email(member.userPrincipalName()) : null;
            if (email != null) walk.emails.add(email);
            walk.group(member);
        }
        while (!walk.pending.isEmpty() && !walk.everyone) walk.pending.poll().run();
        return new Readers(walk.everyone, walk.emails, walk.groupIds);
    }

    /** The groups one securable is followed through. */
    private static final class Walk {
        private final String webUrl;
        private final Groups groups;
        private final TreeSet<String> emails = new TreeSet<>();
        private final TreeSet<String> groupIds = new TreeSet<>();
        /** The groups found and not yet asked for their members. */
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private boolean everyone;

        Walk(String webUrl, Groups groups) {
            this.webUrl = webUrl;
            this.groups = groups;
        }

        void group(Principal principal) {
            if (principal.type() == PrincipalType.SHAREPOINT_GROUP) {
                if (found(siteGroupId(webUrl, principal))) {
                    pending.add(() -> groups.siteGroup(principal.id()).forEach(this::group));
                }
            } else if (principal.type() == PrincipalType.ENTRA_GROUP) {
                if (everyone(principal.loginName())) {
                    everyone = true;
                    return;
                }
                String id = entraId(principal.loginName());
                if (id != null) entra(id, principal.title());
            }
        }

        private void entra(String id, @Nullable String displayName) {
            if (!found(entraGroupId(displayName, id))) return;
            pending.add(() -> {
                for (EntraMember member : groups.entraGroup(id)) {
                    String nested = member.group() ? entraId(member.id()) : null;
                    if (nested != null) entra(nested, member.displayName());
                }
            });
        }

        /** Whether the group has a name, is new to this walk and is within its bound. */
        private boolean found(@Nullable String groupId) {
            return groupId != null && groupIds.size() < MAX_GROUPS && groupIds.add(groupId);
        }
    }

    static boolean everyone(String loginName) {
        String login = loginName.toLowerCase(Locale.ROOT);
        return EVERYONE.stream().anyMatch(login::contains);
    }

    /**
     * The email a reader's verified login email is matched with (ADR 0011): the sign-in name, lower case, without
     * {@code .onmicrosoft}. Null for a name that is not an address.
     */
    static @Nullable String email(@Nullable String userPrincipalName) {
        if (userPrincipalName == null) return null;
        String email = userPrincipalName.strip().toLowerCase(Locale.ROOT).replace(".onmicrosoft", "");
        return email.length() <= MAX_EMAIL && EMAIL.matcher(email).matches() ? email : null;
    }

    /**
     * The identifier of the Entra group a login name stands for: the name itself, or the last part of a claim such
     * as {@code c:0t.c|tenant|<id>}. Null when that part is anything else, such as the owners of a group
     * ({@code <id>_o}), who are not its members.
     */
    static @Nullable String entraId(String loginName) {
        String last = loginName.substring(loginName.lastIndexOf('|') + 1).strip().toLowerCase(Locale.ROOT);
        return GUID.matcher(last).matches() ? last : null;
    }

    /** The name of an Entra group in membership, or null when it is too long to keep. */
    static @Nullable String entraGroupId(@Nullable String displayName, String id) {
        return bounded(((displayName == null ? "" : displayName.strip()) + "_" + id).toLowerCase(Locale.ROOT));
    }

    /** A SharePoint group belongs to its site, so two sites' groups of one name stay apart. */
    static @Nullable String siteGroupId(String webUrl, Principal group) {
        String site = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
        String title = group.title() == null ? group.loginName() : group.title();
        return bounded((site + "::" + title.strip()).toLowerCase(Locale.ROOT));
    }

    private static @Nullable String bounded(String groupId) {
        return groupId.length() > MAX_GROUP_ID ? null : groupId;
    }
}
