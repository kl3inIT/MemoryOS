package io.memoryos.ai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.TestDatabase;
import io.memoryos.ai.AiException;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.persistence.JpaOcrConnectionRepository;
import io.memoryos.ai.persistence.OcrConnectionEntity;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MEM-243: the OCR connection an administrator saves and chooses is the reader extraction asks for. */
class OcrConnectionServiceTest {
    private static final ProviderCredentials.Change KEEP = new ProviderCredentials.Change(ProviderCredentials.Action.KEEP, null);
    private static final String SERVICE = "http://ocr.internal:8080";

    private final JpaOcrConnectionRepository connections = mock(JpaOcrConnectionRepository.class);
    private final ProviderCredentials credentials = mock(ProviderCredentials.class);
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final OcrConnectionService service = new OcrConnectionService(connections,
            new ProviderConnections(credentials, TestDatabase.noAudit()), authorization);

    @BeforeEach void manager() {
        when(authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        when(authorization.require(actor, IamCapability.MODELS_MANAGE, false))
                .thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        when(connections.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(connections.findByTenantIdAndProvider(tenant.value(), OcrProvider.PADDLEOCR_VL)).thenReturn(Optional.empty());
    }

    private OcrConnectionEntity stored() {
        var entity = new OcrConnectionEntity(tenant.value(), OcrProvider.PADDLEOCR_VL);
        entity.configure(SERVICE, null, DataBoundary.INTERNAL);
        when(connections.findByTenantIdAndProvider(tenant.value(), OcrProvider.PADDLEOCR_VL)).thenReturn(Optional.of(entity));
        when(connections.findByTenantIdOrderByProvider(tenant.value())).thenReturn(List.of(entity));
        return entity;
    }

    @Test
    void aSavedConnectionKeepsItsServiceAndBoundaryWithoutBecomingTheReader() {
        var view = service.save(actor, OcrProvider.PADDLEOCR_VL,
                new OcrConnectionService.Input(" " + SERVICE + " ", KEEP, DataBoundary.INTERNAL, 0));

        assertEquals(SERVICE, view.endpoint());
        assertEquals(DataBoundary.INTERNAL, view.dataBoundary());
        assertFalse(view.active(), "saving does not choose the reader");
        assertFalse(view.credentialConfigured());
    }

    @Test
    void aConnectionNeedsAnHttpServiceAddress() {
        for (var endpoint : List.of("", "ftp://ocr:1", "http://user@ocr:1", "http://ocr:1/?q=1")) {
            assertThrows(AiException.class, () -> service.save(actor, OcrProvider.PADDLEOCR_VL,
                    new OcrConnectionService.Input(endpoint, KEEP, DataBoundary.INTERNAL, 0)), endpoint);
        }
        verify(connections, never()).saveAndFlush(any());
    }

    @Test
    void theChosenConnectionIsTheReaderUntilItIsCleared() {
        var entity = stored();
        service.select(actor, OcrProvider.PADDLEOCR_VL);
        assertTrue(entity.active());

        when(connections.findByTenantIdAndActiveTrue(tenant.value())).thenReturn(Optional.of(entity));
        var reader = service.reader(tenant);
        assertEquals(OcrProvider.PADDLEOCR_VL, reader.provider());
        assertEquals(SERVICE, reader.endpoint());
        assertEquals("", service.key(reader), "the service takes no key");

        service.select(actor, null);
        assertFalse(entity.active());
        when(connections.findByTenantIdAndActiveTrue(tenant.value())).thenReturn(Optional.empty());
        assertNull(service.reader(tenant), "no reader returns scans to Docling's own OCR");
    }

    @Test
    void aReaderMustBeASavedConnection() {
        when(connections.findByTenantIdOrderByProvider(tenant.value())).thenReturn(List.of());
        assertThrows(AiException.class, () -> service.select(actor, OcrProvider.PADDLEOCR_VL));
    }

    @Test
    void aTestOfUnsavedDetailsIsValidatedButNotSaved() {
        var probe = service.forTest(actor, OcrProvider.PADDLEOCR_VL, new OcrConnectionService.ProbeInput(SERVICE, null));
        assertEquals(SERVICE, probe.connection().endpoint());
        assertNull(probe.connection().id());
        assertThrows(AiException.class, () -> service.forTest(actor, OcrProvider.PADDLEOCR_VL,
                new OcrConnectionService.ProbeInput("not a url", null)));
        assertThrows(AiException.class, () -> service.forTest(actor, OcrProvider.PADDLEOCR_VL, null),
                "there is no saved connection to test");
        verify(connections, never()).saveAndFlush(any());
    }
}
