package io.memoryos.iam.identity;

import io.memoryos.iam.identity.persistence.ActorEntity;
import io.memoryos.iam.identity.persistence.JpaActorRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantMembership;
import io.memoryos.iam.IamException;

class ActorLanguageServiceTest {
    private final JpaActorRepository languages = mock(JpaActorRepository.class);
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final ActorLanguageService service = new ActorLanguageService(languages, tenants);
    private final ActorId actor = new ActorId(UUID.randomUUID());

    @Test
    void activeMembershipIsLockedBeforeWritingOwnPreferenceWithoutAdministrativeAuthority() {
        when(tenants.lockActiveMembership(actor)).thenReturn(Optional.of(mock(TenantMembership.class)));
        var entity = new ActorEntity(actor.value());
        when(languages.refreshForUpdate(actor.value())).thenReturn(entity);
        assertEquals("en", service.save(actor, "en"));
        var order = inOrder(tenants, languages);
        order.verify(tenants).lockActiveMembership(actor);
        order.verify(languages).refreshForUpdate(actor.value());
        assertEquals("en", entity.getUiLanguage());
        verifyNoMoreInteractions(tenants, languages);
    }

    @Test
    void readsPreferenceThroughSpringDataWithoutMembershipRequirement() {
        var entity = new ActorEntity(actor.value());
        entity.setUiLanguage("en");
        when(languages.findById(actor.value())).thenReturn(Optional.of(entity));
        assertEquals("en", service.read(actor));
        verifyNoInteractions(tenants);
    }

    @Test
    void rejectsInactiveMembershipWithoutWriting() {
        when(tenants.lockActiveMembership(actor)).thenReturn(Optional.empty());
        assertThrows(IamException.class, () -> service.save(actor, "vi"));
        verifyNoInteractions(languages);
    }

    @Test
    void rejectsUnsupportedLanguageBeforePersistence() {
        for (String value : new String[]{null, "", "VI", "vi-VN", "fr"}) {
            assertThrows(IamException.class, () -> service.save(actor, value));
        }
        verifyNoInteractions(tenants, languages);
    }
}
