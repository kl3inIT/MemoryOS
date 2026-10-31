package io.memoryos.api.source;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointSourceService;
import io.memoryos.connector.SharePointSourceService.Configuration;
import io.memoryos.connector.SharePointSourceService.RootPage;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SharePointSourceService.SelectionPolicy;
import io.memoryos.connector.SharePointSourceService.SelectionReceipt;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The SharePoint Source endpoints: what they route, what they require, and what they answer. Authority
 * lives in the application service and is tested there.
 */
class SharePointSourceApiTest extends SourceProviderApiTest {
    private static final UUID SOURCE = UUID.fromString("2b4c5d6e-7f80-4912-a345-6789abcdef01");

    @BeforeEach
    void allowTheDefaultSelectionBudget() {
        when(sharePointSources.selectionRequestByteLimit()).thenReturn(3_145_728);
    }

    @Test
    void aSelectionAboveTheByteBudgetIsRefusedBeforeItReachesTheService() throws Exception {
        when(sharePointSources.selectionRequestByteLimit()).thenReturn(64);

        mockMvc.perform(post("/api/sources/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_INVALID_REQUEST"));
        mockMvc.perform(put("/api/sources/{id}/sharepoint/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_INVALID_REQUEST"));
        verify(sharePointSources, never()).create(any(), any(), any(), any(), any(), any(), any());
        verify(sharePointSources, never()).replaceScope(any(), any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void creatingASourceAcceptsTheScopeForVerification() throws Exception {
        when(sharePointSources.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(receipt());

        mockMvc.perform(post("/api/sources/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()))
                .andExpect(jsonPath("$.operation.type").value("VALIDATE_SHAREPOINT_SELECTION"));
    }

    @Test
    void replacingTheScopeNeedsTheRevision() throws Exception {
        when(sharePointSources.replaceScope(any(), any(), any(), anyLong(), anyLong(), any())).thenReturn(receipt());

        mockMvc.perform(put("/api/sources/{id}/sharepoint/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/sources/{id}/sharepoint/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));
        verify(sharePointSources).replaceScope(any(), any(), any(), eq(3L), eq(2L), any());
    }

    @Test
    void configurationAndRootsAreReadable() throws Exception {
        when(sharePointSources.configuration(any(), any())).thenReturn(configuration());
        when(sharePointSources.roots(any(), any(), any(), anyInt())).thenReturn(new RootPage(4L, List.of(
                new SharePointSourceService.RootView("https://contoso.sharepoint.com/sites/Finance",
                        SharePointSourceService.RootKind.SITE, "Finance", true)), "1", 2L));

        mockMvc.perform(get("/api/sources/{id}/sharepoint", SOURCE).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"))
                .andExpect(jsonPath("$.pruneIntervalHours").value(168))
                .andExpect(jsonPath("$.tenantHost").value("contoso.sharepoint.com"));

        mockMvc.perform(get("/api/sources/{id}/sharepoint/roots", SOURCE).with(authentication(owner))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roots[0].kind").value("SITE"))
                .andExpect(jsonPath("$.nextCursor").value("1"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void scheduleAndPauseCarryTheScheduleRevision() throws Exception {
        when(sharePointSources.updateSchedule(any(), any(), anyLong(), anyInt(), anyInt())).thenReturn(configuration());
        when(sharePointSources.setPaused(any(), any(), anyLong(), anyBoolean())).thenReturn(configuration());

        mockMvc.perform(put("/api/sources/{id}/sharepoint/schedule", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncIntervalMinutes\":30,\"pruneIntervalHours\":0}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""));
        verify(sharePointSources).updateSchedule(any(), any(), eq(7L), eq(30), eq(0));

        mockMvc.perform(post("/api/sources/{id}/sharepoint/pause", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7,\"paused\":true}"))
                .andExpect(status().isOk());
        verify(sharePointSources).setPaused(any(), any(), eq(7L), eq(true));
    }

    @Test
    void synchronizeSchedulesARun() throws Exception {
        when(sharePointSources.synchronize(any(), any())).thenReturn(new SourceOperationView(
                new SourceOperationId(UUID.randomUUID()), SourceOperationType.SYNC_SOURCE,
                SourceOperationStatus.NOT_STARTED, Instant.now(), null, null));

        mockMvc.perform(post("/api/sources/{id}/sharepoint/sync", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("SYNC_SOURCE"));
    }

    @Test
    void selectionPolicyAndReceiptAreReadableWhenAuthenticated() throws Exception {
        reset(sharePointSources);
        when(sharePointSources.selectionPolicy(any())).thenReturn(new SelectionPolicy(1000, 100, 3_145_728));
        when(sharePointSources.selectionRequest(any(), any())).thenReturn(receipt());

        mockMvc.perform(get("/api/sources/sharepoint/selection-policy").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRootsPerSource").value(1000));

        mockMvc.perform(get("/api/sources/sharepoint/selection-requests/{id}", UUID.randomUUID())
                        .with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));

        mockMvc.perform(get("/api/sources/sharepoint/selection-policy")).andExpect(status().isUnauthorized());
    }

    private static SelectionReceipt receipt() {
        return new SelectionReceipt(new SourceId(SOURCE), new SourceOperationView(
                new SourceOperationId(UUID.randomUUID()), SourceOperationType.VALIDATE_SHAREPOINT_SELECTION,
                SourceOperationStatus.NOT_STARTED, Instant.now(), null, null));
    }

    private static Configuration configuration() {
        return new Configuration(new SourceId(SOURCE), new CredentialId(UUID.randomUUID()), "Entra app", "ACTIVE",
                2L, 4L, ScopeMode.SPECIFIC, 2L, List.of(), List.of("*/Archive/*"), true, false, 30, 168, 7L,
                false, "contoso.sharepoint.com", null, null, false, null, null);
    }

    private static String createBody() {
        return """
                {"requestId":"%s","name":"Finance","credentialId":"%s","access":"PUBLIC",
                 "scope":{"scopeMode":"SPECIFIC","siteUrls":["https://contoso.sharepoint.com/sites/Finance"],
                 "includeDocuments":true,"includePages":false,"syncIntervalMinutes":30,"pruneIntervalHours":168}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
    }

    private static String scopeBody() {
        return """
                {"requestId":"%s","expectedCredentialRevision":2,
                 "scope":{"scopeMode":"ALL_SITES","excludedSites":["*/personal/*"],
                 "includeDocuments":true,"includePages":false,"syncIntervalMinutes":60,"pruneIntervalHours":24}}
                """.formatted(UUID.randomUUID());
    }
}
