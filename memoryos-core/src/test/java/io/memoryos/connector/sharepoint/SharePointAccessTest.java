package io.memoryos.connector.sharepoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.connector.SharePointGateway.EntraMember;
import io.memoryos.connector.SharePointGateway.Principal;
import io.memoryos.connector.SharePointGateway.PrincipalType;
import io.memoryos.connector.SharePointGateway.RoleAssignment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The reader rules of MEM-136, after Onyx's {@code test_permission_utils.py}. */
class SharePointAccessTest {
    private static final String WEB = "https://contoso.sharepoint.com/sites/Finance";
    private static final String ACCOUNTING = "5f4dcc3b-5aa7-45d6-8cf3-99de8a2b4c1e";
    private static final String AUDIT = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private final Map<Long, List<Principal>> siteGroups = new HashMap<>();
    private final Map<String, List<EntraMember>> entraGroups = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final SharePointAccess.Groups groups = new SharePointAccess.Groups() {
        @Override public List<Principal> siteGroup(long id) {
            asked.add("site:" + id);
            return siteGroups.getOrDefault(id, List.of());
        }

        @Override public List<EntraMember> entraGroup(String id) {
            asked.add("entra:" + id);
            return entraGroups.getOrDefault(id, List.of());
        }
    };

    @Test
    void aPersonIsKnownByTheSignInNameWithoutOnmicrosoft() {
        var readers = SharePointAccess.readers(WEB, List.of(
                assignment(user("An@Contoso.onmicrosoft.com")), assignment(user("binh@contoso.com"))), groups);
        assertFalse(readers.everyone());
        assertEquals(Set.of("an@contoso.com", "binh@contoso.com"), readers.userEmails());
        assertTrue(readers.groupIds().isEmpty());
    }

    @Test
    void aNameThatIsNotAnAddressReadsNothing() {
        assertNull(SharePointAccess.email(null));
        assertNull(SharePointAccess.email("SHAREPOINT\\system"));
        assertNull(SharePointAccess.email("an @contoso.com"));
        assertNull(SharePointAccess.email("a".repeat(320) + "@contoso.com"));
        // A guest keeps the name Entra gave it, which no login email equals: it reads nothing rather than too much.
        assertEquals("an_gmail.com#ext#@contoso.com", SharePointAccess.email("an_gmail.com#EXT#@contoso.onmicrosoft.com"));
        assertTrue(SharePointAccess.readers(WEB, List.of(assignment(new Principal(PrincipalType.USER, 1,
                "i:0#.f|membership|system", "System", null))), groups).userEmails().isEmpty());
    }

    @Test
    void limitedAccessLetsNobodyRead() {
        var readers = SharePointAccess.readers(WEB, List.of(new RoleAssignment(user("an@contoso.com"), true),
                new RoleAssignment(siteGroup(9, "Finance Members"), true)), groups);
        assertTrue(readers.userEmails().isEmpty());
        assertTrue(readers.groupIds().isEmpty());
        assertTrue(asked.isEmpty(), "a group that reads nothing is not asked about");
    }

    @Test
    void aSharePointGroupIsNamedByItsSiteAndAnEntraGroupByItsNameAndIdentifier() {
        var readers = SharePointAccess.readers(WEB + "/", List.of(assignment(siteGroup(9, "Finance Members")),
                assignment(entraGroup("c:0t.c|tenant|" + ACCOUNTING.toUpperCase(), "Kế Toán"))), groups);
        assertEquals(Set.of("https://contoso.sharepoint.com/sites/finance::finance members",
                "kế toán_" + ACCOUNTING), readers.groupIds());
        assertTrue(readers.userEmails().isEmpty(), "people inside a group read through its membership");
    }

    @Test
    void groupsInsideGroupsAreNamedAndGroupsThatContainEachOtherEnd() {
        siteGroups.put(9L, List.of(user("an@contoso.com"), entraGroup("c:0t.c|tenant|" + ACCOUNTING, "Accounting")));
        entraGroups.put(ACCOUNTING, List.of(new EntraMember(false, "user-1", "Binh", "binh@contoso.com"),
                new EntraMember(true, AUDIT, "Audit", null)));
        entraGroups.put(AUDIT, List.of(new EntraMember(true, ACCOUNTING, "Accounting", null)));
        var readers = SharePointAccess.readers(WEB, List.of(assignment(siteGroup(9, "Finance Members"))), groups);
        assertEquals(Set.of(WEB.toLowerCase() + "::finance members", "accounting_" + ACCOUNTING, "audit_" + AUDIT),
                readers.groupIds());
        assertEquals(List.of("site:9", "entra:" + ACCOUNTING, "entra:" + AUDIT), asked, "each group is asked once");
        assertTrue(readers.userEmails().isEmpty());
    }

    @Test
    void everyoneAndEveryoneExceptExternalUsersMakeTheDocumentPublic() {
        for (String login : List.of("c:0-.f|rolemanager|spo-grid-all-users/11111111-2222-3333-4444-555555555555",
                "c:0(.s|true")) {
            var readers = SharePointAccess.readers(WEB, List.of(assignment(user("an@contoso.com")),
                    assignment(entraGroup(login, "Everyone"))), groups);
            assertTrue(readers.everyone(), login);
        }
        // Inside a SharePoint group it counts as well.
        siteGroups.put(9L, List.of(entraGroup("c:0(.s|true", "Everyone")));
        assertTrue(SharePointAccess.readers(WEB, List.of(assignment(siteGroup(9, "Visitors"))), groups).everyone());
        assertFalse(SharePointAccess.readers(WEB, List.of(new RoleAssignment(entraGroup("c:0(.s|true", "Everyone"), true)),
                groups).everyone(), "Limited Access for everyone opens nothing");
    }

    @Test
    void anEntraGroupWithoutItsIdentifierIsLeftOutRatherThanLookedUpByName() {
        assertEquals(ACCOUNTING, SharePointAccess.entraId(ACCOUNTING));
        assertEquals(ACCOUNTING, SharePointAccess.entraId("c:0o.c|federateddirectoryclaimprovider|" + ACCOUNTING));
        assertNull(SharePointAccess.entraId("c:0o.c|federateddirectoryclaimprovider|" + ACCOUNTING + "_o"),
                "the owners of a group are not its members");
        assertNull(SharePointAccess.entraId("c:0-.f|rolemanager|s-1-5-21-123"));
        var readers = SharePointAccess.readers(WEB, List.of(
                assignment(entraGroup("c:0-.f|rolemanager|s-1-5-21-123", "Accounting"))), groups);
        assertTrue(readers.groupIds().isEmpty());
        assertTrue(asked.isEmpty());
    }

    @Test
    void otherPrincipalsReadNothing() {
        var readers = SharePointAccess.readers(WEB, List.of(assignment(new Principal(PrincipalType.OTHER, 3,
                "list@contoso.com", "A distribution list", "list@contoso.com"))), groups);
        assertTrue(readers.userEmails().isEmpty());
        assertTrue(readers.groupIds().isEmpty());
    }

    private static RoleAssignment assignment(Principal principal) {
        return new RoleAssignment(principal, false);
    }

    private static Principal user(String userPrincipalName) {
        return new Principal(PrincipalType.USER, 11, "i:0#.f|membership|" + userPrincipalName, "A person",
                userPrincipalName);
    }

    private static Principal siteGroup(long id, String title) {
        return new Principal(PrincipalType.SHAREPOINT_GROUP, id, title, title, null);
    }

    private static Principal entraGroup(String loginName, String title) {
        return new Principal(PrincipalType.ENTRA_GROUP, 12, loginName, title, null);
    }
}
