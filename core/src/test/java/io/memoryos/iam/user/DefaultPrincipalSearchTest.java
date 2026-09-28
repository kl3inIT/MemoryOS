package io.memoryos.iam.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupIdentityPage;
import io.memoryos.iam.GroupScopeService;
import io.memoryos.iam.IamException;
import io.memoryos.iam.PrincipalGroup;
import io.memoryos.iam.PrincipalPerson;
import io.memoryos.iam.PrincipalQuery;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantMembership;
import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.user.persistence.UserQueryRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultPrincipalSearchTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final ActorId SEARCHER = new ActorId(UUID.randomUUID());

    private final UserQueryRepository users = mock(UserQueryRepository.class);
    private final GroupScopeService groups = mock(GroupScopeService.class);
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final DefaultPrincipalSearch search = new DefaultPrincipalSearch(users, groups, tenants);

    @Test
    void anActiveMemberSearchesTheirTenantWithoutAnyCapability() {
        // The searcher's capabilities are never consulted: meetings share through this picker without Chat authority.
        when(tenants.findActiveMembership(SEARCHER))
                .thenReturn(Optional.of(new TenantMembership(TENANT, "Tasco", TenantMembershipRole.MEMBER)));
        var person = new PrincipalPerson(new ActorId(UUID.randomUUID()), "Lan", "lan@tasco.vn");
        var group = new GroupIdentity(new GroupId(UUID.randomUUID()), "Board", null);
        when(users.searchActiveMembers(TENANT, "la", 5)).thenReturn(List.of(person));
        when(groups.listGroupOptions(TENANT, "la", 0, 5)).thenReturn(new GroupIdentityPage(List.of(group), 0, 5, 1, 1));

        var matches = search.search(SEARCHER, new PrincipalQuery("la", 5));

        assertThat(matches.people()).containsExactly(person);
        assertThat(matches.groups()).containsExactly(new PrincipalGroup(group.id(), "Board"));
    }

    @Test
    void someoneWithoutAnActiveMembershipIsDeniedBeforeAnyRead() {
        // Inactive members, members of a disabled Tenant and actors of no Tenant have no active membership.
        when(tenants.findActiveMembership(SEARCHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> search.search(SEARCHER, new PrincipalQuery("la", 5)))
                .isInstanceOfSatisfying(IamException.class,
                        denied -> assertThat(denied.code()).isEqualTo("IAM_ACCESS_DENIED"));
        verifyNoInteractions(users, groups);
    }
}
