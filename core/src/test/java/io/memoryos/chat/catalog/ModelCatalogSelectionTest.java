package io.memoryos.chat.catalog;

import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantMembership;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCatalogSelectionTest {
    @Test void marksTenantDefaultWhenPersonaInherits() {
        var fixture = new Fixture();
        assertEquals(List.of(fixture.defaultId), fixture.defaults());
    }

    @Test void marksAuthorizedPersonaDefaultInsteadOfTenantDefault() {
        var fixture = new Fixture();
        fixture.preferPersona();
        assertEquals(List.of(fixture.otherId), fixture.defaults());
    }

    @Test void revokedPersonaProviderFallsBackWithoutLeakingIt() {
        var fixture = new Fixture();
        fixture.preferPersona();
        var restricted = new ModelCatalogRepository.Provider(fixture.otherProvider.id(), fixture.tenant,
                "Private", "test", "http://model.invalid", true, false, null, 1, Set.of(UUID.randomUUID()), Set.of());
        when(fixture.catalog.provider(fixture.tenant, restricted.id())).thenReturn(Optional.of(restricted));
        when(fixture.catalog.providers(fixture.tenant)).thenReturn(List.of(fixture.provider, restricted));
        assertEquals(List.of(fixture.defaultId), fixture.defaults());
        assertEquals(1, fixture.service.availableModelsForPersona(fixture.actor, fixture.persona).size());
    }

    @Test void hiddenInheritedModelDoesNotFalselyMarkTenantDefault() {
        var fixture = new Fixture();
        fixture.preferPersona();
        var hidden = new ModelCatalogRepository.Model(fixture.otherId, fixture.tenant, fixture.otherProvider.id(),
                "other", "Other", false, fixture.settings, 1);
        when(fixture.catalog.model(fixture.tenant, fixture.otherId)).thenReturn(Optional.of(hidden));
        when(fixture.catalog.models(fixture.tenant)).thenReturn(List.of(fixture.defaultModel, hidden));
        assertTrue(fixture.defaults().isEmpty());
    }

    private static final class Fixture {
        final UUID tenant = UUID.randomUUID(), persona = UUID.randomUUID(), defaultId = UUID.randomUUID(), otherId = UUID.randomUUID();
        final ActorId actor = new ActorId(UUID.randomUUID());
        final ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
        final ModelSettings settings = new ModelSettings(36096, 4096,
                new ModelSettings.Capabilities(true, true, true, true), Map.of(), null, "openai-o200k-v1");
        final ModelCatalogRepository.Provider provider = provider();
        final ModelCatalogRepository.Provider otherProvider = provider();
        final ModelCatalogRepository.Model defaultModel = new ModelCatalogRepository.Model(defaultId, tenant, provider.id(), "luna", "Luna", true, settings, 1);
        final ModelCatalogRepository.Model otherModel = new ModelCatalogRepository.Model(otherId, tenant, otherProvider.id(), "other", "Other", true, settings, 1);
        final ModelCatalogService service;

        Fixture() {
            var chats = mock(JdbcChatRepository.class);
            var tenants = mock(TenantAccessResolver.class);
            var membership = mock(TenantMembership.class);
            when(membership.tenantId()).thenReturn(new TenantId(tenant));
            when(tenants.lockActiveMembership(actor)).thenReturn(Optional.of(membership));
            when(chats.usablePersona(new TenantId(tenant), actor, persona)).thenReturn(true);
            var authorization = mock(IamAuthorization.class);
            when(authorization.effectiveCapabilities(actor)).thenReturn(Set.of());
            var adapters = mock(ChatProviderAdapters.class);
            var adapter = mock(ChatProviderAdapter.class);
            when(adapters.supports("test")).thenReturn(true);
            when(adapters.require("test")).thenReturn(adapter);
            when(adapter.credentialRequirement()).thenReturn(ChatProviderAdapter.CredentialRequirement.NONE);
            when(catalog.actorGroups(tenant, actor.value())).thenReturn(Set.of());
            when(catalog.providers(tenant)).thenReturn(List.of(provider, otherProvider));
            when(catalog.models(tenant)).thenReturn(List.of(defaultModel, otherModel));
            when(catalog.model(tenant, otherId)).thenReturn(Optional.of(otherModel));
            when(catalog.provider(tenant, otherProvider.id())).thenReturn(Optional.of(otherProvider));
            when(catalog.defaultModel(tenant)).thenReturn(new ModelCatalogRepository.Default(defaultId, 1));
            when(catalog.personaModel(tenant, actor.value(), persona)).thenReturn(new ModelCatalogRepository.PersonaModel(persona, null, 1));
            service = new ModelCatalogService(catalog, chats, tenants, authorization, adapters,
                    mock(ProviderCredentials.class), new PersonaProperties(), null);
        }
        ModelCatalogRepository.Provider provider() {
            return new ModelCatalogRepository.Provider(UUID.randomUUID(), tenant, "Connection", "test", "http://model.invalid",
                    true, true, null, 1, Set.of(), Set.of());
        }
        void preferPersona() {
            when(catalog.personaModel(tenant, actor.value(), persona)).thenReturn(new ModelCatalogRepository.PersonaModel(persona, otherId, 1));
        }
        List<UUID> defaults() {
            return service.availableModelsForPersona(actor, persona).stream().filter(ModelCatalogService.AvailableModel::isDefault)
                    .map(ModelCatalogService.AvailableModel::id).toList();
        }
    }
}
