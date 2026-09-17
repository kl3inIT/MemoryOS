package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.FailureCategory;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ProviderCredentials;
import io.memoryos.chat.persistence.ImageConnectionRepository;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAccess;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImageConnectionServiceTest {
    private final ImageConnectionRepository connections = mock(ImageConnectionRepository.class);
    private final ProviderCredentials credentials = mock(ProviderCredentials.class);
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final ImageConnectionService service =
            new ImageConnectionService(connections, credentials, authorization, tenants);

    @Test
    void providersRequireModelManagement() {
        when(authorization.require(actor, IamCapability.MODELS_MANAGE, false))
                .thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "denied"));
        var denied = assertThrows(IamException.class, () -> service.providers(actor));
        assertEquals("IAM_ACCESS_DENIED", denied.code());
        verifyNoInteractions(connections);
    }

    @Test
    void providersListEveryInstalledProtocolForManagers() {
        when(authorization.require(actor, IamCapability.MODELS_MANAGE, false))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        assertEquals(List.of(ImageProvider.values()), service.providers(actor));
    }

    @Test
    void saveRejectsABlankEndpointWhenTheProviderRequiresOne() {
        when(authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        var input = new ImageConnectionService.Input("", "flux-1-schnell",
                new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, null), 0);
        var rejected = assertThrows(ChatException.class,
                () -> service.save(actor, ImageProvider.CLOUDFLARE_WORKERS_AI, input));
        assertEquals(FailureCategory.VALIDATION, rejected.category());
        verifyNoInteractions(connections);
    }

    @Test
    void saveAcceptsABlankEndpointWhenTheProviderDefaultsIt() {
        when(authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        when(connections.findByTenantIdAndProvider(tenant.value(), ImageProvider.OPENAI_IMAGE))
                .thenReturn(Optional.empty());
        when(connections.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var input = new ImageConnectionService.Input("", "gpt-image-1",
                new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, null), 0);
        var view = service.save(actor, ImageProvider.OPENAI_IMAGE, input);
        assertEquals("gpt-image-1", view.model());
    }
}
