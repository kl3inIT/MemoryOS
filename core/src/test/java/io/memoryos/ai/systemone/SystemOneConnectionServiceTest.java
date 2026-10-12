package io.memoryos.ai.systemone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.FailureCategory;
import io.memoryos.TestDatabase;
import io.memoryos.ai.AiException;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.FlowModelDefault;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.persistence.JpaSystemOneConnectionRepository;
import io.memoryos.ai.persistence.ModelCatalogRepository;
import io.memoryos.ai.persistence.SystemOneConnectionEntity;
import io.memoryos.ai.systemone.adapter.CloudflareSystemOneAdapter;
import io.memoryos.ai.systemone.adapter.CompatibleSystemOneAdapter;
import io.memoryos.ai.systemone.adapter.LayaSystemOneAdapter;
import io.memoryos.ai.systemone.adapter.NineRouterSystemOneAdapter;
import io.memoryos.ai.systemone.adapter.TypeSafeSystemOneAdapter;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemOneConnectionServiceTest {
    private static final ProviderCredentials.Change KEEP = new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, null);
    private static final ProviderCredentials.Change REPLACE =
            new ProviderCredentials.Change(ProviderCredentials.Action.REPLACE, "test-secret");

    private final JpaSystemOneConnectionRepository connections = mock(JpaSystemOneConnectionRepository.class);
    private final ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
    private final ProviderCredentials credentials = mock(ProviderCredentials.class);
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final SystemOneAdapterRegistry adapters = new SystemOneAdapterRegistry(List.of(new TypeSafeSystemOneAdapter(),
            new CloudflareSystemOneAdapter(), new NineRouterSystemOneAdapter(), new LayaSystemOneAdapter(),
            new CompatibleSystemOneAdapter()));
    private final SystemOneConnectionService service = new SystemOneConnectionService(connections, catalog,
            new ProviderConnections(credentials, TestDatabase.noAudit()), authorization, adapters, TestDatabase.noAudit());

    @BeforeEach void manager() {
        when(authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        when(authorization.require(actor, IamCapability.MODELS_MANAGE, false))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        when(connections.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(credentials.update(any(), any(), any(), eq(REPLACE))).thenReturn("v1:stored");
        when(credentials.configured("v1:stored")).thenReturn(true);
    }

    private static SystemOneConnectionService.Input input(String name, String endpoint, String model,
                                                           ProviderCredentials.Change credential) {
        return new SystemOneConnectionService.Input(name, endpoint, model, credential, DataBoundary.EXTERNAL, null, 0);
    }

    private SystemOneConnectionEntity stored(SystemOneProvider provider, String name, String credential) {
        var entity = new SystemOneConnectionEntity(tenant.value(), provider);
        entity.configure(name, "http://serving.internal:8000/v1", "auto", credential, DataBoundary.INTERNAL, 0.0);
        when(connections.findByTenantIdAndId(tenant.value(), entity.id())).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test void configurationRequiresModelManagement() {
        when(authorization.require(actor, IamCapability.MODELS_MANAGE, false))
                .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "denied"));
        assertEquals("IAM_ACCESS_DENIED", assertThrows(IamException.class, () -> service.list(actor)).code());
        verifyNoInteractions(connections);
    }

    @Test void everyTypeIsOfferedWithWhatItNeeds() {
        var types = service.types();
        assertEquals(List.of(SystemOneProvider.values()), types.stream().map(SystemOneConnectionService.Type::provider).toList());
        assertEquals(SystemOneCapabilities.Endpoint.ACCOUNT, types.get(1).capabilities().endpoint());
        assertEquals("clef-flash", types.get(1).capabilities().defaultModel());
    }

    @Test void aTenantHoldsSeveralConnectionsOfOneTypeEachWithItsOwnModel() {
        var first = service.create(actor, SystemOneProvider.NINEROUTER,
                input(" 9Router Jev ", "http://9router.internal:20128/v1", "openrouter/typesafe/jev-1.13", REPLACE));
        var second = service.create(actor, SystemOneProvider.NINEROUTER,
                input("9Router Clef", "http://9router.internal:20128/v1", "cloudflare/clef-flash", REPLACE));

        assertEquals("9Router Jev", first.name());
        assertEquals("openrouter/typesafe/jev-1.13", first.model());
        assertEquals("cloudflare/clef-flash", second.model());
        assertTrue(first.credentialConfigured());
    }

    @Test void aBlankModelTakesTheTypesDefaultAndATypeWithoutOneRequiresIt() {
        var laya = service.create(actor, SystemOneProvider.LAYA, input("Serving", "http://serving.internal:8000/v1", "", KEEP));
        assertEquals("auto", laya.model());

        var rejected = assertThrows(AiException.class, () -> service.create(actor, SystemOneProvider.SYSTEMONE_COMPATIBLE,
                input("Other", "http://10.0.0.5:8080/v1", " ", KEEP)));
        assertEquals(FailureCategory.VALIDATION, rejected.category());
    }

    @Test void theEndpointIsWhatTheTypeNeeds() {
        // The hosted service has one address.
        assertThrows(AiException.class, () -> service.create(actor, SystemOneProvider.TYPESAFE,
                input("TypeSafe", "https://elsewhere.example/v1", "", REPLACE)));
        assertEquals("", service.create(actor, SystemOneProvider.TYPESAFE, input("TypeSafe", "", "", REPLACE)).endpoint());
        // A gateway or a self-hosted server names its own.
        assertThrows(AiException.class, () -> service.create(actor, SystemOneProvider.NINEROUTER, input("9Router", "", "m", REPLACE)));
        // Cloudflare takes an account ID, not an address.
        assertThrows(AiException.class, () -> service.create(actor, SystemOneProvider.CLOUDFLARE,
                input("Cloudflare", "https://api.cloudflare.com/client/v4/accounts/x", "", REPLACE)));
        assertEquals("3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5", service.create(actor, SystemOneProvider.CLOUDFLARE,
                input("Cloudflare", "3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5", "", REPLACE)).endpoint());
    }

    @Test void aNameIsUniqueInTheTenantAndAStaleRevisionIsRefused() {
        when(connections.existsByTenantIdAndNameIgnoreCaseAndIdNot(eq(tenant.value()), eq("Serving"), any())).thenReturn(true);
        assertEquals(FailureCategory.VALIDATION, assertThrows(AiException.class, () -> service.create(actor,
                SystemOneProvider.LAYA, input("Serving", "http://serving.internal:8000/v1", "", KEEP))).category());

        var entity = stored(SystemOneProvider.LAYA, "Laya", null);
        var stale = new SystemOneConnectionService.Input("Laya", "http://serving.internal:8000/v1", "auto", KEEP,
                DataBoundary.INTERNAL, 0.0, 7);
        assertEquals(FailureCategory.CONFLICT,
                assertThrows(AiException.class, () -> service.update(actor, entity.id(), stale)).category());
        verify(connections, never()).saveAndFlush(any());
    }

    @Test void aConnectionATaskRunsOnIsNotDeletedAndKeepsARequiredKey() {
        var entity = stored(SystemOneProvider.NINEROUTER, "9Router", "v1:stored");
        when(catalog.flowUsesClassifier(tenant.value(), entity.id())).thenReturn(true);

        assertEquals(FailureCategory.CONFLICT,
                assertThrows(AiException.class, () -> service.delete(actor, entity.id())).category());
        verify(connections, never()).delete(any());

        var remove = new ProviderCredentials.Change(ProviderCredentials.Action.REMOVE, null);
        assertEquals(FailureCategory.VALIDATION, assertThrows(AiException.class, () -> service.update(actor, entity.id(),
                input("9Router", "http://9router.internal:20128/v1", "m", remove))).category());
    }

    @Test void onlyAClassifyingTaskRunsOnAConnectionAndOnlyAUsableOne() {
        var entity = stored(SystemOneProvider.NINEROUTER, "9Router", null);
        assertEquals(FailureCategory.VALIDATION, assertThrows(AiException.class,
                () -> service.assign(actor, ModelFlow.CHAT_NAMING, entity.id(), 1)).category());
        // Its type needs a key and none is stored.
        assertEquals(FailureCategory.SERVICE_UNAVAILABLE, assertThrows(AiException.class,
                () -> service.assign(actor, ModelFlow.CHAT_GUARDRAIL, entity.id(), 1)).category());
        verify(catalog, never()).setFlowClassifier(any(), any(), any(), anyLong());

        var laya = stored(SystemOneProvider.LAYA, "Serving", null);
        when(catalog.flowDefault(tenant.value(), ModelFlow.CHAT_GUARDRAIL))
                .thenReturn(new FlowModelDefault(ModelFlow.CHAT_GUARDRAIL, UUID.randomUUID(), null, null, 3));
        service.assign(actor, ModelFlow.CHAT_GUARDRAIL, laya.id(), 3);
        verify(catalog).setFlowClassifier(tenant.value(), ModelFlow.CHAT_GUARDRAIL, laya.id(), 3);
    }

    @Test void aTaskOnALanguageModelHasNoConnectionAndATaskOnAConnectionAlwaysNamesIt() {
        when(catalog.flowDefault(tenant.value(), ModelFlow.CHAT_GUARDRAIL))
                .thenReturn(new FlowModelDefault(ModelFlow.CHAT_GUARDRAIL, UUID.randomUUID(), null, null, 1));
        assertNull(service.forFlow(tenant, ModelFlow.CHAT_GUARDRAIL));
        assertNull(service.forFlow(tenant, ModelFlow.MEETING_MINUTES));

        var entity = stored(SystemOneProvider.NINEROUTER, "9Router", null);
        when(catalog.flowDefault(tenant.value(), ModelFlow.CHAT_GUARDRAIL))
                .thenReturn(new FlowModelDefault(ModelFlow.CHAT_GUARDRAIL, null, null, entity.id(), 2));
        // Without its key the connection is still the task's classifier; its call fails, not the lookup.
        assertEquals("9Router", service.forFlow(tenant, ModelFlow.CHAT_GUARDRAIL).name());

        var laya = stored(SystemOneProvider.LAYA, "Serving", null);
        when(catalog.flowDefault(tenant.value(), ModelFlow.CHAT_GUARDRAIL))
                .thenReturn(new FlowModelDefault(ModelFlow.CHAT_GUARDRAIL, null, null, laya.id(), 3));
        var connection = service.forFlow(tenant, ModelFlow.CHAT_GUARDRAIL);
        assertEquals("Serving", connection.name());
        assertEquals(0.0, connection.cost(180L));
    }
}
