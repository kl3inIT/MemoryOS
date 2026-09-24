package io.memoryos.chat.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import io.memoryos.iam.group.GroupScopeService;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.TenantMembership;
import java.util.*;
import org.junit.jupiter.api.Test;

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
        var restricted = new LlmProvider(fixture.otherProvider.id(), fixture.tenant,
                "Private", "test", "http://model.invalid", true, false, null, 1, Set.of(UUID.randomUUID()), Set.of(), DataBoundary.EXTERNAL);
        when(fixture.catalog.provider(fixture.tenant, restricted.id())).thenReturn(Optional.of(restricted));
        when(fixture.catalog.providers(fixture.tenant)).thenReturn(List.of(fixture.provider, restricted));
        assertEquals(List.of(fixture.defaultId), fixture.defaults());
        assertEquals(1, fixture.service.availableModelsForPersona(fixture.actor, fixture.persona).size());
    }

    @Test void agentRestrictedProviderWithoutGroupsIsUsableThroughItsAgentsOnly() {
        var fixture = new Fixture();
        var agentOnly = new LlmProvider(fixture.otherProvider.id(), fixture.tenant,
                "Agent provider", "test", "http://model.invalid", true, false, null, 1, Set.of(), Set.of(fixture.persona), DataBoundary.EXTERNAL);
        when(fixture.catalog.providers(fixture.tenant)).thenReturn(List.of(fixture.provider, agentOnly));
        // Onyx can_user_access_llm_provider: no Groups, listed agent, non-public provider.
        assertEquals(2, fixture.service.availableModelsForPersona(fixture.actor, fixture.persona).size());
        var grouped = new LlmProvider(fixture.otherProvider.id(), fixture.tenant,
                "Grouped agent provider", "test", "http://model.invalid", true, false, null, 1, Set.of(UUID.randomUUID()), Set.of(fixture.persona), DataBoundary.EXTERNAL);
        when(fixture.catalog.providers(fixture.tenant)).thenReturn(List.of(fixture.provider, grouped));
        assertEquals(1, fixture.service.availableModelsForPersona(fixture.actor, fixture.persona).size());
    }

    @Test void hiddenInheritedModelDoesNotFalselyMarkTenantDefault() {
        var fixture = new Fixture();
        fixture.preferPersona();
        var hidden = new ModelConfiguration(fixture.otherId, fixture.tenant, fixture.otherProvider.id(),
                "other", "Other", false, fixture.settings, 1);
        when(fixture.catalog.model(fixture.tenant, fixture.otherId)).thenReturn(Optional.of(hidden));
        when(fixture.catalog.models(fixture.tenant)).thenReturn(List.of(fixture.defaultModel, hidden));
        assertTrue(fixture.defaults().isEmpty());
    }

    @Test void namingFlowUsesItsEligibleModelAndOtherwiseTheConversationModel() {
        var fixture = new Fixture();
        assertEquals(fixture.defaultId, fixture.service.resolveFlow(fixture.actor, fixture.session, ModelFlow.CHAT_NAMING).model().id());
        fixture.naming(fixture.otherId);
        var selected = fixture.service.resolveFlow(fixture.actor, fixture.session, ModelFlow.CHAT_NAMING);
        assertEquals(fixture.otherId, selected.model().id());
        assertNull(selected.fallbackReason());
        // Hidden, disabled or restricted: the task keeps working on the conversation model and never reports a fallback.
        var restricted = new LlmProvider(fixture.otherProvider.id(), fixture.tenant,
                "Private", "test", "http://model.invalid", true, false, null, 2, Set.of(UUID.randomUUID()), Set.of(), DataBoundary.INTERNAL);
        when(fixture.catalog.provider(fixture.tenant, restricted.id())).thenReturn(Optional.of(restricted));
        selected = fixture.service.resolveFlow(fixture.actor, fixture.session, ModelFlow.CHAT_NAMING);
        assertEquals(fixture.defaultId, selected.model().id());
        assertNull(selected.fallbackReason());
    }

    @Test void flowModelMustBeTenantWideAndCanBeCleared() {
        var fixture = new Fixture();
        fixture.manage();
        var restricted = new LlmProvider(fixture.otherProvider.id(), fixture.tenant,
                "Private", "test", "http://model.invalid", true, true, null, 2, Set.of(), Set.of(fixture.persona), DataBoundary.EXTERNAL);
        when(fixture.catalog.provider(fixture.tenant, restricted.id())).thenReturn(Optional.of(restricted));
        assertThrows(io.memoryos.chat.ChatException.class,
                () -> fixture.service.setFlowDefault(fixture.actor, ModelFlow.CHAT_NAMING, fixture.otherId, 1));
        verify(fixture.catalog, never()).setFlowDefault(any(), any(), any(), anyLong());
        fixture.service.setFlowDefault(fixture.actor, ModelFlow.CHAT_NAMING, null, 1);
        verify(fixture.catalog).setFlowDefault(fixture.tenant, ModelFlow.CHAT_NAMING, null, 1);
    }

    @Test void flowViewReportsASetModelThatIsNoLongerEligible() {
        var fixture = new Fixture();
        fixture.manage();
        fixture.naming(fixture.otherId);
        var hidden = new ModelConfiguration(fixture.otherId, fixture.tenant, fixture.otherProvider.id(),
                "other", "Other", false, fixture.settings, 2);
        assertTrue(fixture.service.flowDefaults(fixture.actor).getFirst().available());
        when(fixture.catalog.model(fixture.tenant, fixture.otherId)).thenReturn(Optional.of(hidden));
        var view = fixture.service.flowDefaults(fixture.actor).getFirst();
        assertEquals(fixture.otherId, view.modelConfigurationId());
        assertFalse(view.available());
    }

    private static final class Fixture {
        final UUID tenant = UUID.randomUUID(), persona = UUID.randomUUID(), defaultId = UUID.randomUUID(), otherId = UUID.randomUUID(),
                session = UUID.randomUUID();
        final IamAuthorization authorization = mock(IamAuthorization.class);
        final ActorId actor = new ActorId(UUID.randomUUID());
        final ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
        final ModelSettings settings = new ModelSettings(36096, 4096,
                new ModelSettings.Capabilities(true, true, true, true), Map.of(), null, "openai-o200k-v1");
        final LlmProvider provider = provider();
        final LlmProvider otherProvider = provider();
        final ModelConfiguration defaultModel = new ModelConfiguration(defaultId, tenant, provider.id(), "luna", "Luna", true, settings, 1);
        final ModelConfiguration otherModel = new ModelConfiguration(otherId, tenant, otherProvider.id(), "other", "Other", true, settings, 1);
        final ModelCatalogService service;

        Fixture() {
            var chats = mock(JdbcChatRepository.class);
            var tenants = mock(TenantAccessResolver.class);
            var membership = mock(TenantMembership.class);
            when(membership.tenantId()).thenReturn(new TenantId(tenant));
            when(tenants.lockActiveMembership(actor)).thenReturn(Optional.of(membership));
            when(tenants.findActiveMembership(actor)).thenReturn(Optional.of(membership));
            when(chats.usablePersona(new TenantId(tenant), actor, persona, false)).thenReturn(true);
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
            when(catalog.defaultModel(tenant)).thenReturn(new ModelDefault(defaultId, 1));
            when(catalog.model(tenant, defaultId)).thenReturn(Optional.of(defaultModel));
            when(catalog.provider(tenant, provider.id())).thenReturn(Optional.of(provider));
            naming(null);
            when(chats.findOwned(new TenantId(tenant), actor, session, false)).thenReturn(Optional.of(new io.memoryos.chat.ChatSession(
                    session, persona, UUID.randomUUID(), "Chat", java.time.Instant.now(), java.time.Instant.now(), null)));
            when(chats.persona(session, true, false)).thenReturn(new JdbcChatRepository.Persona("", "luna",
                    io.memoryos.chat.ChatTurnOptions.DEFAULT, "7", null, List.of(), Set.of(), null, false));
            when(catalog.personaModel(tenant, actor.value(), false, persona)).thenReturn(new PersonaModelDefault(persona, null, 1));
            service = new ModelCatalogService(catalog, chats, tenants, authorization, adapters,
                    mock(ProviderCredentials.class), mock(GroupScopeService.class), mock(io.memoryos.iam.audit.AuditTrail.class));
        }
        LlmProvider provider() {
            return new LlmProvider(UUID.randomUUID(), tenant, "Connection", "test", "http://model.invalid",
                    true, true, null, 1, Set.of(), Set.of(), DataBoundary.EXTERNAL);
        }
        void naming(UUID model) {
            var value = new FlowModelDefault(ModelFlow.CHAT_NAMING, model, 1);
            when(catalog.flowDefault(tenant, ModelFlow.CHAT_NAMING)).thenReturn(value);
            when(catalog.flowDefaults(tenant)).thenReturn(List.of(value));
        }
        void manage() {
            var access = new io.memoryos.iam.group.IamAccess(new TenantId(tenant), io.memoryos.iam.group.Authority.GLOBAL);
            when(authorization.require(actor, io.memoryos.iam.group.IamCapability.MODELS_MANAGE, false)).thenReturn(access);
            when(authorization.lockAndRequireExclusive(actor, io.memoryos.iam.group.IamCapability.MODELS_MANAGE)).thenReturn(access);
        }
        void preferPersona() {
            when(catalog.personaModel(tenant, actor.value(), false, persona)).thenReturn(new PersonaModelDefault(persona, otherId, 1));
        }
        List<UUID> defaults() {
            return service.availableModelsForPersona(actor, persona).stream().filter(ModelCatalogService.AvailableModel::isDefault)
                    .map(ModelCatalogService.AvailableModel::id).toList();
        }
    }
}
